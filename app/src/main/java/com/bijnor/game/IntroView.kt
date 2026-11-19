package com.bijnor.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * Start-up intro (VK Developer logo animation). Plays the moment the game opens, BEFORE the loading screen:
 * the animation pops up from the centre (scale + fade, overshoot), plays its 9 frames (cross-faded so it looks smooth),
 * holds on the last frame, then fades out and calls onFinished -> MainActivity starts the loading screen.
 * Frames: assets/ui/intro/f0.png .. f8.png (taken from the supplied GIF, 180 ms per frame).
 */
class IntroView(ctx: Context) : View(ctx) {
    var onFinished: (() -> Unit)? = null

    private val frames: List<Bitmap> = (0 until 9).mapNotNull { i ->
        try { ctx.assets.open("ui/intro/f$i.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pop = OvershootInterpolator(1.4f)

    private val popMs = 450L          // pop-up
    private val frameMs = 180L        // original GIF speed
    private val holdMs = 700L         // stay on the finished logo
    private val fadeMs = 400L         // fade out
    private var t0 = 0L
    private var done = false

    var isPlaying = true
        private set

    private var started = false   // the clock starts at the FIRST drawn frame (onCreate is busy before that, so the pop-up is never skipped)

    private fun finish() {
        if (done) return
        done = true; isPlaying = false
        visibility = GONE
        onFinished?.invoke()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.BLACK)
        if (frames.isEmpty()) { finish(); return }
        val now = System.nanoTime() / 1_000_000L
        if (!started) { started = true; t0 = now }
        val t = now - t0
        val animEnd = popMs + frames.size * frameMs          // all frames shown
        val total = animEnd + holdMs + fadeMs

        // which frame (+ cross-fade to the next)
        val ft = (t.toFloat() / frameMs).coerceAtLeast(0f)
        val idx = ft.toInt().coerceAtMost(frames.size - 1)
        val frac = if (ft >= frames.size - 1) 0f else ft - idx
        val cur = frames[idx]

        // pop-up (first popMs): scale 0.55 -> 1 with overshoot, alpha 0 -> 1
        val pp = (t.toFloat() / popMs).coerceIn(0f, 1f)
        val scalePop = 0.55f + 0.45f * pop.getInterpolation(pp)
        var alpha = pp.coerceIn(0f, 1f)
        // fade out at the end
        val fadeStart = animEnd + holdMs
        if (t > fadeStart) alpha *= (1f - (t - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)

        // fit the WHOLE animation on screen (nothing cut off: crown, VK and "Developer" stay fully visible), centred on black
        val s = min(w / cur.width, h / cur.height)
        val dw = cur.width * s * scalePop; val dh = cur.height * s * scalePop
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)

        paint.alpha = (alpha * 255).toInt()
        c.drawBitmap(cur, null, dst, paint)
        if (frac > 0.01f && idx + 1 < frames.size) {
            paint.alpha = (alpha * frac * 255).toInt()
            c.drawBitmap(frames[idx + 1], null, dst, paint)
        }
        paint.alpha = 255
        // soft black fade on the 4 edges of the picture (the smoke reaches the frame border)
        val ef = (dst.width() * 0.06f).coerceAtLeast(8f)
        paint.shader = LinearGradient(dst.left, 0f, dst.left + ef, 0f, Color.BLACK, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(dst.left, dst.top, dst.left + ef, dst.bottom, paint)
        paint.shader = LinearGradient(dst.right - ef, 0f, dst.right, 0f, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(dst.right - ef, dst.top, dst.right, dst.bottom, paint)
        val ev = (dst.height() * 0.06f).coerceAtLeast(8f)
        paint.shader = LinearGradient(0f, dst.top, 0f, dst.top + ev, Color.BLACK, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(dst.left, dst.top, dst.right, dst.top + ev, paint)
        paint.shader = LinearGradient(0f, dst.bottom - ev, 0f, dst.bottom, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(dst.left, dst.bottom - ev, dst.right, dst.bottom, paint)
        paint.shader = null

        if (t >= total) { finish(); return }
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = true   // swallow touches while the intro is on screen
}
