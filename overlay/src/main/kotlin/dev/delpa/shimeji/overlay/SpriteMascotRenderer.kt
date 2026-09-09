package dev.delpa.shimeji.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import org.json.JSONObject

/**
 * Sprite-strip mascot renderer for the bundled Classic Shimeji character
 * (Yuki Yamada's original, 128x128 RGBA PNG frames).
 *
 * Pose metadata lives in `assets/mascot/poses.json`:
 *   animations: { "<fsm animation name>": [ { file, (durationMs) }, ... ] }
 *
 * The frames are feet-anchored like the original (ImageAnchor 64,128 =
 * bottom-center); the sprite is scaled to ~86% of the smallest window
 * dimension and bottom-aligned, so physics (which operates on the window
 * rectangle) lines up with the character's feet. Frames flip horizontally
 * when the mascot faces left.
 *
 * If the pose set fails to load, callers fall back to the procedural renderer.
 */
class SpriteMascotRenderer private constructor(
    private val animations: Map<String, List<Bitmap>>,
    private val frameScale: Float,
) : MascotRenderer {

    companion object {
        private const val TAG = "SpriteMascotRenderer"
        private const val ORIGINAL_FRAME = 128
        private const val DEFAULT_SCALE = 0.86f

        /** Build from pre-loaded bitmaps (custom character). */
        fun fromAnimations(
            animations: Map<String, List<Bitmap>>,
            scale: Float = DEFAULT_SCALE,
        ): SpriteMascotRenderer? {
            if (animations.isEmpty()) return null
            val total = animations.values.sumOf { it.size }
            Log.i(TAG, "loaded ${animations.size} animations, $total frames (custom)")
            return SpriteMascotRenderer(animations, scale)
        }

        /** Build from bundled assets; returns null if poses.json or frames are unusable. */
        fun load(context: Context): SpriteMascotRenderer? {
            return runCatching {
                val raw = context.assets.open("mascot/poses.json")
                    .bufferedReader().use { it.readText() }
                val root = JSONObject(raw)
                val animsJson = root.getJSONObject("animations")
                val anims = linkedMapOf<String, List<Bitmap>>()
                val names = animsJson.keys()
                var total = 0
                for (name in names) {
                    val list = animsJson.getJSONArray(name)
                    val frames = ArrayList<Bitmap>(list.length())
                    for (i in 0 until list.length()) {
                        val file = list.getJSONObject(i).getString("file")
                        val bmp = BitmapFactory.decodeStream(
                            context.assets.open("mascot/$file"),
                        ) ?: throw IllegalStateException("undecodable frame $file")
                        frames.add(bmp)
                    }
                    anims[name] = frames
                    total += frames.size
                }
                if (anims.isEmpty()) return null
                Log.i(TAG, "loaded ${anims.size} animations, $total frames")
                SpriteMascotRenderer(anims, DEFAULT_SCALE)
            }.getOrElse { err ->
                Log.e(TAG, "failed to load mascot poses", err)
                null
            }
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

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
        val frames = animations[animationName] ?: animations.values.firstOrNull()
            ?: return
        if (frames.isEmpty()) return
        val idx = if (frames.size == 1) 0 else frameIndex % frames.size
        val bmp = frames[idx]

        val dstSize = (minOf(width, height) * frameScale).toFloat()
        val dstLeft = (width - dstSize) / 2f
        val dstTop = height - dstSize // bottom-center anchor = feet at window bottom

        canvas.save()
        if (state.facingLeft) {
            // Flip around the vertical center axis.
            canvas.scale(-1f, 1f, width / 2f, 0f)
        }
        canvas.drawBitmap(
            bmp,
            null,
            android.graphics.RectF(dstLeft, dstTop, dstLeft + dstSize, dstTop + dstSize),
            paint,
        )
        canvas.restore()
    }
}