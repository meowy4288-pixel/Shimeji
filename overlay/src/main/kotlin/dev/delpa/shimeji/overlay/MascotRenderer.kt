package dev.delpa.shimeji.overlay

import android.graphics.Canvas

/**
 * Draws one mascot frame for the current FSM state.
 *
 * Implementations must be stateless per call: the View owns animation timing
 * and passes [frameIndex] (already resolved against the FSM state's frame
 * count). [frameCount] is included so renderers can make phase decisions.
 *
 * Current implementations:
 *  - [ProceduralMascotRenderer]: zero-asset Canvas fallback.
 *  - [SpriteMascotRenderer]: Bundled classic Shimeji PNG frames with feet
 *    anchor + horizontal flip.
 */
interface MascotRenderer {
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: PhysicsStatePublic,
        animationName: String,
        frameIndex: Int,
        frameCount: Int,
        nowMs: Long,
    )
}