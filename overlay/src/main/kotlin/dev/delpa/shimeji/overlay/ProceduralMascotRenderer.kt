package dev.delpa.shimeji.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Procedural mascot renderer. No bitmap decoding; paints are allocated once.
 * The look is a replaceable demo asset — swap [MascotLook] or this whole class
 * for real art later.
 */
class ProceduralMascotRenderer(
    private val look: MascotLook = MascotLook(),
) : MascotRenderer {
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.bodyColor }
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FF8A8A.toInt()
        style = Paint.Style.FILL
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFE066.toInt()
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Reusable paths: reset() before each use — avoids per-frame allocation.
    private val legPath = Path()
    private val mouthPath = Path()

    data class MascotLook(
        val bodyColor: Int = 0xFF7E9BF2.toInt(),
        val accentColor: Int = 0xFFFFD166.toInt(),
    )

    override fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: PhysicsStatePublic,
        animationName: String,
        frameIndex: Int,
        frameCount: Int,
        nowMs: Long,
    ) {
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) * 0.86f

        // Gentle bob for idle/walk.
        val bob = when (animationName) {
            "idle" -> sin(nowMs / 380.0) * 2.0
            "walk" -> sin(nowMs / 180.0) * 3.0
            "fall" -> 0.0
            else -> 0.0
        }
        val bodyCy = (cy + bob).toFloat()

        // ---- legs (frame-dependent step for walk) ----
        if (animationName == "walk" || animationName == "climb") {
            val step = if (frameIndex % 2 == 0) 1f else -1f
            val lift = if (animationName == "walk") 4f else 10f
            drawLeg(canvas, cx - r * 0.45f, bodyCy + r * 0.5f, step * 3f, -lift)
            drawLeg(canvas, cx + r * 0.45f, bodyCy + r * 0.5f, -step * 3f, -lift)
        } else {
            drawLeg(canvas, cx - r * 0.45f, bodyCy + r * 0.5f, 0f, -2f)
            drawLeg(canvas, cx + r * 0.45f, bodyCy + r * 0.5f, 0f, -2f)
        }

        // ---- body ----
        canvas.drawCircle(cx, bodyCy, r, bodyPaint)

        // ---- face ----
        val facing = if (state.facingLeft) -1f else 1f
        val eyeOffset = r * 0.28f
        val eyeY = bodyCy - r * 0.08f
        val pupilShift = when (animationName) {
            "drag" -> 0f
            else -> facing * r * 0.08f
        }

        drawEye(canvas, cx - eyeOffset * facing, eyeY, r * 0.22f, pupilShift, animationName == "tool_feedback")
        drawEye(canvas, cx + eyeOffset * facing, eyeY, r * 0.22f, pupilShift, animationName == "tool_feedback")

        // ---- mouth / expression ----
        when (animationName) {
            "tool_feedback" -> { // happy open mouth + sparkles
                mouthPaint.style = Paint.Style.FILL
                mouthPaint.color = 0xFF444444.toInt()
                canvas.drawOval(RectF(cx - r * 0.22f, bodyCy + r * 0.18f, cx + r * 0.22f, bodyCy + r * 0.42f), mouthPaint)
                mouthPaint.style = Paint.Style.STROKE
                mouthPaint.color = 0xFF333333.toInt()
                val t = nowMs % 400L
                val s = 1f + ((t / 400f) * 12f).toFloat()
                canvas.drawLine(cx - r * 0.55f, bodyCy - r * 0.5f, cx - r * 0.55f - 20f, bodyCy - r * 0.5f - 20f * s, sparklePaint)
                canvas.drawLine(cx + r * 0.60f, bodyCy - r * 0.55f, cx + r * 0.60f + 24f, bodyCy - r * 0.55f - 24f * s, sparklePaint)
            }
            "drag" -> { // determined flat mouth
                canvas.drawLine(cx - r * 0.22f, bodyCy + r * 0.32f, cx + r * 0.22f, bodyCy + r * 0.32f, mouthPaint)
            }
            "fall" -> { // small "o"
                mouthPaint.style = Paint.Style.FILL
                mouthPaint.color = 0xFF444444.toInt()
                val er = r * 0.13f
                canvas.drawOval(RectF(cx - er, bodyCy + r * 0.2f, cx + er, bodyCy + r * 0.44f), mouthPaint)
                mouthPaint.style = Paint.Style.STROKE
                mouthPaint.color = 0xFF333333.toInt()
            }
            "climb" -> { // determined
                canvas.drawLine(cx - r * 0.25f, bodyCy + r * 0.34f, cx + r * 0.25f, bodyCy + r * 0.30f, mouthPaint)
            }
            "walk" -> { // slight smile
                mouthPath.reset()
                mouthPath.moveTo(cx - r * 0.22f, bodyCy + r * 0.30f)
                mouthPath.quadTo(cx, bodyCy + r * 0.42f, cx + r * 0.22f, bodyCy + r * 0.28f)
                canvas.drawPath(mouthPath, mouthPaint)
            }
            else -> { // idle smile
                mouthPath.reset()
                mouthPath.moveTo(cx - r * 0.20f, bodyCy + r * 0.28f)
                mouthPath.quadTo(cx, bodyCy + r * 0.38f, cx + r * 0.20f, bodyCy + r * 0.28f)
                canvas.drawPath(mouthPath, mouthPaint)
            }
        }

        // ---- randomized blush accent ----
        canvas.drawCircle(cx - r * 0.68f, bodyCy + r * 0.22f, r * 0.12f, blushPaint)
        canvas.drawCircle(cx + r * 0.68f, bodyCy + r * 0.22f, r * 0.12f, blushPaint)
    }

    private fun drawLeg(canvas: Canvas, footX: Float, hipY: Float, dx: Float, lift: Float) {
        legPath.reset()
        legPath.moveTo(footX, hipY)
        legPath.quadTo(footX + dx * 0.4f, hipY + 12f, footX + dx, hipY + 12f + lift)
        canvas.drawPath(legPath, mouthPaint)
    }

    private fun drawEye(canvas: Canvas, ex: Float, ey: Float, er: Float, pupilShift: Float, sparkle: Boolean) {
        canvas.drawCircle(ex, ey, er, eyeWhitePaint)
        canvas.drawCircle(ex + pupilShift, ey, er * 0.45f, pupilPaint)
        if (sparkle) {
            canvas.drawLine(ex - er, ey - er * 1.4f, ex - er - 6f, ey - er * 1.4f - 8f, sparklePaint)
        }
    }
}

/** Minimal public view of physics state for the renderer (no mutable core class leakage). */
data class PhysicsStatePublic(
    val facingLeft: Boolean,
) {
    companion object {
        fun of(facingLeft: Boolean) = PhysicsStatePublic(facingLeft)
    }
}