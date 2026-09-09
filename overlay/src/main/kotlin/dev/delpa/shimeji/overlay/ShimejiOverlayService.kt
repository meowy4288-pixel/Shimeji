package dev.delpa.shimeji.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import dev.delpa.shimeji.core.event.VisualCommand
import dev.delpa.shimeji.core.fsm.FsmConfig
import dev.delpa.shimeji.core.fsm.FsmEvent
import dev.delpa.shimeji.core.fsm.InterruptPriority
import dev.delpa.shimeji.core.fsm.ShimejiFSM
import dev.delpa.shimeji.core.harness.HarnessEngine
import dev.delpa.shimeji.core.physics.PhysicsBounds
import dev.delpa.shimeji.core.physics.PhysicsEngine
import dev.delpa.shimeji.overlay.accessibility.ShimejiAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.coroutines.resume

/**
 * User-started foreground service managing the mascot overlay window.
 *
 * Window strategy:
 *  - TYPE_APPLICATION_OVERLAY, a tightly bounded, mascot-sized rectangle.
 *  - FLAG_NOT_FOCUSABLE: the window never takes keyboard focus -> key events
 *    stay with the app below; taps outside the window keep working normally.
 *  - FLAG_NOT_TOUCH_MODAL: touch events outside this window's rectangle are
 *    passed to whatever is underneath, so the background app is not blocked.
 *    Combined with NOT_FOCUSABLE this gives a "floating sticker" semantics.
 *  - We do NOT claim transparent pixels inside the rectangle pass through:
 *    Android delivers touches to the window that covers the point, and there
 *    is no supported way to define nonrectangular touch regions without
 *    hidden APIs. A bounded window is the correct approach for this milestone.
 *  - FLAG_NOT_TOUCHABLE is never set while dragging. Untrusted-touch
 *    restrictions prevent transparent-pixel passthrough and arbitrary hit
 *    regions; a whole-window passthrough mode is deliberately NOT introduced.
 */
class ShimejiOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "mascot_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "dev.delpa.shimeji.overlay.action.START"
        const val ACTION_PAUSE = "dev.delpa.shimeji.overlay.action.PAUSE"
        const val ACTION_RESUME = "dev.delpa.shimeji.overlay.action.RESUME"
        const val ACTION_STOP = "dev.delpa.shimeji.overlay.action.STOP"
        private const val TAG = "ShimejiOverlay"
        private const val TAP_MAX_MS = 400L
        private const val DEFAULT_MASCOT_SCALE = 0.86f

        /** Explicit intent used by MainActivity and notification actions. */
        fun intent(context: Context) = Intent(context, ShimejiOverlayService::class.java)
    }

    private var wm: WindowManager? = null
    private var mascotView: MascotView? = null
    private var params: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null
    private var frameJob: Job? = null
    private var collectorJob: Job? = null
    private var paused = false
    private var attached = false
    private var bounds: PhysicsBounds? = null
    private var smallIconRes: Int = R.drawable.ic_mascot

    // Touch state
    private var downRawX = 0f
    private var downRawY = 0f
    private var downEventTime = 0L
    private var startStateX = 0f
    private var startStateY = 0f
    private var lastMoveRawX = 0f
    private var lastMoveRawY = 0f
    private var lastMoveTime = 0L
    private var pendingVx = 0f
    private var pendingVy = 0f
    private var dragging = false
    private var touchSlop = 0

    // Metrics
    private var lastMetricsWidth = 0
    private var lastMetricsHeight = 0
    private var lastInsets: Rect? = null
    private var frameCounter = 0L

    private val engine: HarnessEngine?
        get() = (application as? ShimejiAppHost)?.harnessEngine()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                ensureAttached()
            }
            ACTION_PAUSE -> pauseLoop()
            ACTION_RESUME -> resumeLoop()
            ACTION_STOP -> {
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // User-started via visible interaction; do not silently resurrect.
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------
    // Foreground notification + actions
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_desc) }
            nm.createNotificationChannel(channel)
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun buildNotification(): Notification {
        val resume = PendingIntent.getService(
            this,
            2,
            intent(this).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this,
            1,
            intent(this).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            3,
            intent(this).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openApp = (application as? ShimejiAppHost)?.mainActivityIntent()?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(smallIconRes)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_pause), pause)
            .addAction(0, getString(R.string.action_resume), resume)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    // ------------------------------------------------------------------
    // Window attach / cleanup
    // ------------------------------------------------------------------

    private val mascotSizePx: Int
        get() {
            val prefs = MascotPrefs(this)
            return (112 * resources.displayMetrics.density * prefs.mascotScale).roundToInt()
        }

    private fun ensureAttached() {
        if (attached && mascotView != null) return
        val overlayWm = wm ?: return
        val engineHost = engine ?: return

        val fsmConfig = loadFsmConfig()
        val renderer = loadMascotRenderer()
        val view = MascotView(
            context = this,
            fsmConfig = fsmConfig,
            renderer = renderer,
            physics = PhysicsEngine(),
        )
        mascotView = view

        val size = mascotSizePx
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
        }
        params = lp

        recomputeBounds()

        // Initial position: centered horizontally, standing on the usable ground.
        val b = bounds ?: return
        view.state.x = (b.left + b.right) / 2f
        view.state.y = b.bottom
        view.state.grounded = true

        try {
            overlayWm.addView(view, lp)
            attached = true
        } catch (t: Throwable) {
            // Failed window attachment: shut down cleanly rather than crash-loop.
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        view.setOnTouchListener(::onMascotTouch)

        // Wire the accessibility service context to the mascot's reaction engine.
        ShimejiAccessibilityService.onContextChanged = { ctx ->
            mascotView?.screenContext = ctx
        }
        // Feed the current context immediately if the service is already running.
        if (ShimejiAccessibilityService.isRunning) {
            mascotView?.screenContext = ShimejiAccessibilityService.currentContext
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.scope = scope
        frameJob = scope.launch { frameLoop() }
        collectorJob = scope.launch { collectVisualEvents(engineHost) }
    }

    /**
     * Renderer selection: custom character from internal storage first, then
     * bundled sprite character, then zero-asset procedural fallback.
     */
    private fun loadMascotRenderer(): MascotRenderer {
        val prefs = MascotPrefs(this)
        val selected = prefs.selectedCharacter

        // Try custom character from internal storage.
        if (selected.isNotEmpty()) {
            val charManager = CharacterManager(this)
            val anims = charManager.loadCharacter(selected)
            if (anims != null) {
                val scale = DEFAULT_MASCOT_SCALE * prefs.mascotScale
                val sprite = SpriteMascotRenderer.fromAnimations(anims, scale)
                if (sprite != null) {
                    Log.i(TAG, "using custom character: $selected")
                    return sprite
                }
            }
            Log.w(TAG, "custom character '$selected' failed to load; trying bundled")
        }

        // Fall back to bundled classic Shimeji.
        val sprite = SpriteMascotRenderer.load(this)
        if (sprite != null) {
            Log.i(TAG, "using SpriteMascotRenderer (bundled classic Shimeji)")
            return sprite
        }
        Log.w(TAG, "sprite mascot unavailable; using procedural fallback")
        return ProceduralMascotRenderer()
    }

    private fun loadFsmConfig(): FsmConfig {
        val raw = runCatching {
            assets.open("shimeji_fsm.json").bufferedReader().use { it.readText() }
        }.getOrElse { err ->
            // Should never happen (asset is bundled); fall back to a minimal config.
            android.util.Log.e("ShimejiOverlay", "Failed to load shimeji_fsm.json", err)
            DEFAULT_FSM_JSON
        }
        return ShimejiFSM.validOrNull(raw) ?: defaultFsmConfig()
    }

    private fun defaultFsmConfig(): FsmConfig =
        ShimejiFSM.validOrNull(DEFAULT_FSM_JSON)!!

    private fun cleanup() {
        frameJob?.cancel()
        collectorJob?.cancel()
        frameJob = null
        collectorJob = null
        scope?.cancel()
        scope = null
        mascotView?.setOnTouchListener(null)
        val view = mascotView
        val overlayWm = wm
        if (view != null && overlayWm != null && attached) {
            runCatching { overlayWm.removeView(view) }
        }
        attached = false
        mascotView = null
        params = null
        bounds = null
        // Force a full recompute on the next attach (metrics/insets may be
        // cached from an identical prior run).
        lastInsets = null
        lastMetricsWidth = 0
        lastMetricsHeight = 0
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Frame loop (display-synchronized, paused when screen off)
    // ------------------------------------------------------------------

    private suspend fun frameLoop() {
        var lastNanos = System.nanoTime()
        var animMs = 0L
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        while (scope?.isActive == true && attached) {
            val frameNanos = awaitNextFrame()
            val dtMs = ((frameNanos - lastNanos) / 1_000_000L).coerceIn(0L, 50L).toFloat()
            lastNanos = frameNanos

            // Pause all animation work when the screen is off.
            if (!powerManager.isInteractive) {
                delay(500)
                continue
            }
            if (paused) {
                delay(33)
                continue
            }

            frameCounter++
            if (frameCounter % 30L == 0L) recomputeBounds()

            val view = mascotView ?: break
            animMs += dtMs.toLong()
            view.update(dtMs, animMs)
            updateWindowPosition()
            view.invalidate()
        }
    }

    private suspend fun awaitNextFrame(): Long = suspendCancellableCoroutine { cont ->
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (cont.isActive) cont.resume(frameTimeNanos)
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
        cont.invokeOnCancellation {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }

    private fun pauseLoop() {
        // Keep the window; stop animation work.
        paused = true
    }

    private fun resumeLoop() {
        paused = false
    }

    // ------------------------------------------------------------------
    // Visual event collection
    // ------------------------------------------------------------------

    private suspend fun collectVisualEvents(engineHost: HarnessEngine) {
        engineHost.events.collect { event ->
            when (event) {
                is VisualCommand.PlayAnimation -> triggerAnimation(event.animation)
                else -> Unit
            }
        }
    }

    /**
     * Fire-and-forget visual feedback. Never blocks tool execution; failure or
     * absence is harmless.
     */
    private fun triggerAnimation(name: String) {
        val view = mascotView ?: return
        val now = System.currentTimeMillis()
        if (view.fsm.canAcceptVisualCommand(InterruptPriority.COSMETIC_FEEDBACK)) {
            when (name) {
                "magic_cast", "tool_feedback" -> view.fsm.onEvent(FsmEvent.TOOL_FEEDBACK, now)
                else -> view.forceState(name, now)
            }
        }
    }

    // ------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------

    private fun onMascotTouch(v: View, event: MotionEvent): Boolean {
        val view = mascotView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downEventTime = event.eventTime
                startStateX = view.state.x
                startStateY = view.state.y
                lastMoveRawX = event.rawX
                lastMoveRawY = event.rawY
                lastMoveTime = event.eventTime
                pendingVx = 0f
                pendingVy = 0f
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val rawX = event.rawX
                val rawY = event.rawY
                if (!dragging && hypot(rawX - downRawX, rawY - downRawY) > touchSlop) {
                    dragging = true
                    view.dragging = true // physics pins position; no gravity while held
                    view.fsm.onEvent(FsmEvent.DRAG_STARTED, System.currentTimeMillis())
                }
                if (dragging) {
                    val b = bounds ?: return true
                    val targetX = (startStateX + (rawX - downRawX)).coerceIn(b.left, b.right)
                    val targetY = (startStateY + (rawY - downRawY)).coerceIn(b.top, b.bottom)
                    view.state.x = targetX
                    view.state.y = targetY
                    // Release velocity estimate from the last move delta.
                    val dt = ((event.eventTime - lastMoveTime) / 1000f).coerceAtLeast(1e-3f)
                    pendingVx = (rawX - lastMoveRawX) / dt
                    pendingVy = (rawY - lastMoveRawY) / dt
                    lastMoveRawX = rawX
                    lastMoveRawY = rawY
                    lastMoveTime = event.eventTime
                    updateWindowPosition()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    view.dragging = false
                    view.physics.releaseFromDrag(view.state, pendingVx, pendingVy)
                    view.fsm.onEvent(FsmEvent.DRAG_ENDED, System.currentTimeMillis())
                } else if (event.eventTime - downEventTime < TAP_MAX_MS) {
                    // Quick tap: face the tap direction, then poke.
                    val size = mascotSizePx
                    val tapLocalX = event.x
                    val halfW = size / 2f
                    val prefs = MascotPrefs(view.context)
                    if (prefs.tapFacing) {
                        view.state.facingLeft = tapLocalX < halfW
                    }
                    if (prefs.tapPoke) {
                        view.poke(System.currentTimeMillis())
                    }
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // Treat as release with no velocity (do not fling after cancel).
                if (dragging) {
                    view.dragging = false
                    view.physics.releaseFromDrag(view.state, 0f, 0f)
                    view.fsm.onEvent(FsmEvent.DRAG_ENDED, System.currentTimeMillis())
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Single-pointer design: dropping the primary pointer ends the drag.
                if (event.actionIndex == 0) {
                    if (dragging) {
                        view.dragging = false
                        view.physics.releaseFromDrag(view.state, pendingVx, pendingVy)
                        view.fsm.onEvent(FsmEvent.DRAG_ENDED, System.currentTimeMillis())
                    }
                    dragging = false
                    return true
                }
                return true
            }
            else -> return true
        }
    }

    private fun updateWindowPosition() {
        val view = mascotView
        val lp = params
        val overlayWm = wm
        if (view == null || lp == null || overlayWm == null || !attached) return
        val nx = view.state.x.roundToInt()
        val ny = view.state.y.roundToInt()
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            runCatching { overlayWm.updateViewLayout(view, lp) }
        }
    }

    // ------------------------------------------------------------------
    // Bounds / insets
    // ------------------------------------------------------------------

    /**
     * Recompute the usable collision box from current display metrics and
     * insets. Insets (system bars, display cutout, gesture areas, optionally
     * IME when reported) are subtracted exactly once here; downstream code
     * never subtracts insets again — positions are window top-lefts in screen
     * coordinates in the same space as raw touch coordinates.
     *
     * Keyboard policy: if the overlay View reports IME insets we exclude them
     * (mascot walks above the keyboard). Overlay windows commonly do not report
     * IME insets; in that case we reserve nothing for the keyboard and document
     * that the mascot may be visually overlapped by the IME while typing.
     * Navigation gesture areas are excluded from the usable box.
     */
    private fun recomputeBounds() {
        val view = mascotView ?: return
        val metrics = DisplayMetrics().also { wm?.defaultDisplay?.getRealMetrics(it) }
        val insets = currentInsets(view)
        // Early-out: nothing relevant changed since the last pass (same metrics
        // and insets) — avoids allocating a PhysicsBounds and re-setting bounds
        // every 30 frames.
        if (metrics.widthPixels == lastMetricsWidth &&
            metrics.heightPixels == lastMetricsHeight &&
            insets.left == lastInsets?.left &&
            insets.top == lastInsets?.top &&
            insets.right == lastInsets?.right &&
            insets.bottom == lastInsets?.bottom
        ) {
            return
        }
        lastInsets = insets
        lastMetricsWidth = metrics.widthPixels
        lastMetricsHeight = metrics.heightPixels

        val size = mascotSizePx

        val left = insets.left.toFloat()
        val top = insets.top.toFloat()
        val right = (metrics.widthPixels - insets.right - size).toFloat()
        val bottom = (metrics.heightPixels - insets.bottom - size).toFloat()

        val newBounds = PhysicsBounds(
            left = left,
            top = top,
            right = right.coerceAtLeast(left),
            bottom = bottom.coerceAtLeast(top),
            displayWidth = metrics.widthPixels.toFloat(),
            displayHeight = metrics.heightPixels.toFloat(),
        )

        val old = bounds
        bounds = newBounds
        if (old == null || old != newBounds) {
            view.setBounds(newBounds, resetCompletion = true)
        }
    }

    private fun currentInsets(view: View): Rect {
        val ri = view.rootWindowInsets
        if (ri != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                // NOTE: IME insets are deliberately NOT subtracted. Overlay
                // windows on some OEM builds report persistent, bogus IME
                // insets even with the keyboard closed, which would push the
                // mascot far above the real bottom edge. The mascot may be
                // visually overlapped by the keyboard while typing instead.
                val typeMask =
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout() or
                        android.view.WindowInsets.Type.systemGestures()
                val list = ri.getInsets(typeMask)
                return Rect(list.left, list.top, list.right, list.bottom)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val cutout = ri.displayCutout
                return Rect(
                    ri.systemWindowInsetLeft + (cutout?.safeInsetLeft ?: 0),
                    ri.systemWindowInsetTop + (cutout?.safeInsetTop ?: 0),
                    ri.systemWindowInsetRight + (cutout?.safeInsetRight ?: 0),
                    ri.systemWindowInsetBottom + (cutout?.safeInsetBottom ?: 0),
                )
            }
            @Suppress("DEPRECATION")
            return Rect(ri.systemWindowInsetLeft, ri.systemWindowInsetTop, ri.systemWindowInsetRight, ri.systemWindowInsetBottom)
        }
        return Rect(0, 0, 0, 0)
    }
}

/** Implemented by the app's Application so the overlay can reach the shared engine. */
interface ShimejiAppHost {
    fun harnessEngine(): HarnessEngine

    /** Intent that opens the main settings screen (for the notification content intent). */
    fun mainActivityIntent(): android.content.Intent
}

private val DEFAULT_FSM_JSON = """
{"initial":"idle","fallbackState":"idle","gravity":1500,"maxFallSpeed":900,
 "states":[
   {"id":"idle","animation":"idle","frameDurationMs":400,"frames":["idle-a","idle-b"],"looping":true,
    "transitions":[
      {"to":"walking","guard":"RANDOM_ANYTIME","cooldownMs":4000,"weight":0.6},
      {"to":"sit","guard":"GROUNDED","cooldownMs":5000,"weight":0.7},
      {"to":"dangle","guard":"GROUNDED","cooldownMs":8000,"weight":0.5},
      {"to":"lie","guard":"GROUNDED","cooldownMs":14000,"weight":0.35},
      {"to":"look_up","guard":"GROUNDED","cooldownMs":4000,"weight":0.6},
      {"to":"falling","guard":"AIRBORNE","cooldownMs":0,"weight":1.0}]},
   {"id":"walking","animation":"walk","frameDurationMs":160,"frames":["walk-a","walk-b"],"looping":true,
    "transitions":[
      {"to":"idle","guard":"RANDOM_ANYTIME","cooldownMs":3000,"weight":1.0},
      {"to":"climbing","guard":"NEAR_LEFT_CLIMB","cooldownMs":0,"weight":1.0},
      {"to":"climbing","guard":"NEAR_RIGHT_CLIMB","cooldownMs":0,"weight":1.0},
      {"to":"falling","guard":"AIRBORNE","cooldownMs":0,"weight":1.0}]},
   {"id":"falling","animation":"fall","frameDurationMs":120,"frames":["fall"],"looping":true,
    "transitions":[{"to":"idle","guard":"GROUNDED","cooldownMs":150,"weight":1.0}]},
   {"id":"climbing","animation":"climb","frameDurationMs":200,"frames":["climb-a","climb-b"],"looping":true,"durationMs":900,
    "transitions":[
      {"to":"falling","guard":"RANDOM_ANYTIME","cooldownMs":800,"weight":1.0},
      {"to":"idle","guard":"RANDOM_ANYTIME","cooldownMs":1200,"weight":0.4}]},
   {"id":"dragging","animation":"drag","frameDurationMs":120,"frames":["drag-a","drag-b"],"looping":true,"transitions":[]},
   {"id":"tool_feedback","animation":"magic_cast","frameDurationMs":90,"frames":["cast-a","cast-b","cast-c"],"looping":false,"durationMs":600,
    "transitions":[{"to":"idle","guard":"RANDOM_ANYTIME","cooldownMs":0,"weight":1.0}]},
   {"id":"sit","animation":"sit","frameDurationMs":300,"frames":["sit"],"looping":true,
    "transitions":[
      {"to":"idle","guard":"GROUNDED","cooldownMs":2500,"weight":1.0},
      {"to":"dangle","guard":"GROUNDED","cooldownMs":3500,"weight":0.6},
      {"to":"look_up","guard":"GROUNDED","cooldownMs":3500,"weight":0.4}]},
   {"id":"dangle","animation":"dangle","frameDurationMs":220,"frames":["dangle-a","dangle-b"],"looping":true,
    "transitions":[{"to":"idle","guard":"GROUNDED","cooldownMs":3000,"weight":1.0}]},
   {"id":"lie","animation":"lie","frameDurationMs":400,"frames":["lie"],"looping":true,
    "transitions":[{"to":"idle","guard":"GROUNDED","cooldownMs":4500,"weight":1.0}]},
   {"id":"look_up","animation":"look_up","frameDurationMs":300,"frames":["look-a","look-b"],"looping":true,
    "transitions":[{"to":"idle","guard":"GROUNDED","cooldownMs":1800,"weight":1.0}]},
   {"id":"poke","animation":"poke","frameDurationMs":110,"frames":["poke-a","poke-b"],"looping":false,"durationMs":550,
    "transitions":[{"to":"idle","guard":"RANDOM_ANYTIME","cooldownMs":350,"weight":1.0}]},
   {"id":"jump","animation":"jump","frameDurationMs":130,"frames":["jump"],"looping":false,"durationMs":500,
    "transitions":[{"to":"idle","guard":"RANDOM_ANYTIME","cooldownMs":300,"weight":1.0}]},
   {"id":"bounce","animation":"bounce","frameDurationMs":120,"frames":["bounce-a","bounce-b"],"looping":false,"durationMs":480,
    "transitions":[{"to":"idle","guard":"RANDOM_ANYTIME","cooldownMs":300,"weight":1.0}]},
   {"id":"trip","animation":"trip","frameDurationMs":130,"frames":["trip-a","trip-b"],"looping":false,"durationMs":650,
    "transitions":[{"to":"idle","guard":"RANDOM_ANYTIME","cooldownMs":400,"weight":1.0}]}
 ]}
""".trimIndent()