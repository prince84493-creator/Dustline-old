package com.bijnor.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Glowing blue / gold look of the lobby (plates, shield badges, crown, wings, sparkles, outlined text).
 * Drawing helpers only - every number / name shown with them is still the player's real data.
 * No BlurMaskFilter anywhere (it is not usable on hardware canvases below API 28): glows are a few translucent strokes.
 */
object Glow {
    const val BLUE_GLOW = 0xFF2E7BFF.toInt()
    const val CYAN = 0xFF4FD2FF.toInt()
    const val GOLD_HI = 0xFFFFE680.toInt()
    const val GOLD_MID = 0xFFFFC107.toInt()
    const val GOLD_LO = 0xFFB87400.toInt()
    const val NAVY = 0xFF050A24.toInt()
    const val DARK = 0xFF16120A.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goldPos = floatArrayOf(0f, 0.5f, 1f)

    private fun goldShader(t: Float, b: Float): Shader =
        LinearGradient(0f, t, 0f, b, intArrayOf(GOLD_HI, GOLD_MID, GOLD_LO), goldPos, Shader.TileMode.CLAMP)

    /** soft outer glow: a few wide translucent strokes, drawn BEFORE the fill so only the outside shows */
    fun glow(c: Canvas, path: Path, color: Int, d: Float, strength: Float = 1f) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        for (i in 4 downTo 1) {
            p.strokeWidth = (2f + i * 2.6f) * d
            p.color = Color.argb((42f * strength / i).toInt().coerceIn(0, 255), r, g, b)
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
    }

    /** blue glass plate with a gold edge: the base of the profile bar, the coin bar and the lobby buttons */
    fun plate(c: Canvas, path: Path, l: Float, t: Float, r: Float, b: Float, d: Float, pressed: Boolean = false) {
        glow(c, path, BLUE_GLOW, d)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, t, 0f, b,
            intArrayOf(0xFF2A62E8.toInt(), 0xFF123AB0.toInt(), 0xFF07154F.toInt(), 0xFF040A2C.toInt()),
            floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        val sv = c.save(); c.clipPath(path)
        p.shader = LinearGradient(l, t, r, t,
            intArrayOf(0x00FFFFFF, 0x38A8D8FF, 0x00FFFFFF, 0x2468B8FF, 0x00FFFFFF),
            floatArrayOf(0f, 0.22f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(l, t, r, t + (b - t) * 0.5f, p)
        p.shader = LinearGradient(0f, b - (b - t) * 0.40f, 0f, b, 0x0039C8FF, 0x6039C8FF, Shader.TileMode.CLAMP)
        c.drawRect(l, b - (b - t) * 0.40f, r, b, p)
        p.shader = null
        if (pressed) { p.color = 0x45FFFFFF; c.drawRect(l, t, r, b, p) }
        c.restoreToCount(sv)
        p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeWidth = 2.4f * d
        p.shader = goldShader(t, b)
        c.drawPath(path, p)
        p.shader = null; p.style = Paint.Style.FILL
    }

    /** gold shield badge (icon holder) centred on cx, cy, height s */
    fun badge(c: Canvas, cx: Float, cy: Float, s: Float, d: Float, pressed: Boolean = false) {
        val w = s * 0.92f
        val path = Path()
        path.moveTo(cx - w / 2, cy - s * 0.28f); path.lineTo(cx - w * 0.22f, cy - s / 2)
        path.lineTo(cx + w * 0.22f, cy - s / 2); path.lineTo(cx + w / 2, cy - s * 0.28f)
        path.lineTo(cx + w / 2, cy + s * 0.18f); path.lineTo(cx, cy + s / 2)
        path.lineTo(cx - w / 2, cy + s * 0.18f); path.close()
        glow(c, path, 0xFFFFB300.toInt(), d, 0.55f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cy - s / 2, 0f, cy + s / 2,
            if (pressed) intArrayOf(0xFFFFF3B0.toInt(), 0xFFFFD84A.toInt(), 0xFFE59A00.toInt()) else intArrayOf(GOLD_HI, GOLD_MID, 0xFFD98200.toInt()),
            goldPos, Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeWidth = 1.6f * d; p.color = 0xFF6B3E00.toInt()
        c.drawPath(path, p)
        val sv = c.save(); c.scale(0.80f, 0.80f, cx, cy)
        p.strokeWidth = 1.1f * d; p.color = 0x8CFFFFFF.toInt(); c.drawPath(path, p)
        c.restoreToCount(sv)
        p.style = Paint.Style.FILL
    }

    /** gold crown whose bottom edge sits on baseY, width w */
    fun crown(c: Canvas, cx: Float, baseY: Float, w: Float, d: Float) {
        val h = w * 0.55f
        val path = Path()
        path.moveTo(cx - w * 0.5f, baseY); path.lineTo(cx - w * 0.5f, baseY - h * 0.75f); path.lineTo(cx - w * 0.25f, baseY - h * 0.40f)
        path.lineTo(cx, baseY - h); path.lineTo(cx + w * 0.25f, baseY - h * 0.40f); path.lineTo(cx + w * 0.5f, baseY - h * 0.75f)
        path.lineTo(cx + w * 0.5f, baseY); path.close()
        glow(c, path, 0xFFFFB300.toInt(), d, 0.5f)
        p.style = Paint.Style.FILL; p.shader = goldShader(baseY - h, baseY)
        c.drawPath(path, p); p.shader = null
        p.color = 0x66B87400; c.drawRect(cx - w * 0.5f, baseY - h * 0.22f, cx + w * 0.5f, baseY, p)
        p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeWidth = 1.3f * d; p.color = 0xFF6B3E00.toInt()
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF3AD0FF.toInt(); c.drawCircle(cx, baseY - h * 0.50f, w * 0.055f, p)
        p.color = 0xFFFF4A5A.toInt(); c.drawCircle(cx - w * 0.30f, baseY - h * 0.40f, w * 0.04f, p); c.drawCircle(cx + w * 0.30f, baseY - h * 0.40f, w * 0.04f, p)
    }

    /** golden feather wing hanging off x; dir = -1 grows to the left, +1 to the right */
    fun wing(c: Canvas, x: Float, y: Float, h: Float, dir: Int, d: Float) {
        val step = h * 0.26f
        for (i in 0..2) {
            val len = h * (0.55f - i * 0.12f) * dir
            val yy = y + i * step
            val path = Path()
            path.moveTo(x, yy); path.lineTo(x + len, yy - h * 0.10f); path.lineTo(x + len * 0.86f, yy + step * 0.55f); path.lineTo(x, yy + step * 1.0f); path.close()
            p.style = Paint.Style.FILL; p.shader = goldShader(yy - h * 0.1f, yy + step)
            c.drawPath(path, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0xFF7A4A00.toInt(); c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
    }

    /** small 4-point sparkle */
    fun sparkle(c: Canvas, cx: Float, cy: Float, r: Float, color: Int = Color.WHITE) {
        val path = Path()
        path.moveTo(cx, cy - r); path.lineTo(cx + r * 0.22f, cy - r * 0.22f); path.lineTo(cx + r, cy); path.lineTo(cx + r * 0.22f, cy + r * 0.22f)
        path.lineTo(cx, cy + r); path.lineTo(cx - r * 0.22f, cy + r * 0.22f); path.lineTo(cx - r, cy); path.lineTo(cx - r * 0.22f, cy - r * 0.22f); path.close()
        p.style = Paint.Style.FILL; p.shader = null; p.color = color; c.drawPath(path, p)
    }

    /** the game's gold coin (with the S mark) */
    fun coin(c: Canvas, cx: Float, cy: Float, r: Float, d: Float) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r * 2.2f, 0x40FFC21A, 0x00FFC21A, Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r * 2.2f, p)
        p.shader = LinearGradient(cx, cy - r, cx, cy + r, 0xFFFFE680.toInt(), 0xFFB36B00.toInt(), Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r, p)
        p.shader = RadialGradient(cx - r * 0.25f, cy - r * 0.3f, r * 1.1f, intArrayOf(0xFFFFF2A0.toInt(), 0xFFFFC21A.toInt(), 0xFFD58C00.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.86f, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.08f; p.color = 0xFFB87400.toInt(); c.drawCircle(cx, cy, r * 0.68f, p)
        p.style = Paint.Style.FILL; p.color = 0xFF8A5200.toInt(); p.textAlign = Paint.Align.CENTER; p.typeface = Ui.FONT_BOLD; p.textSize = r * 1.25f
        val fm = p.fontMetrics
        c.drawText("S", cx, cy - (fm.ascent + fm.descent) / 2, p)
        p.textAlign = Paint.Align.LEFT
        sparkle(c, cx - r * 0.55f, cy - r * 0.6f, r * 0.30f)
    }

    /** soft glow behind a gem (the 7-facet diamond itself is drawn by ProfileBar) */
    fun gemGlow(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, 0x663AD0FF, 0x003AD0FF, Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r, p); p.shader = null
    }

    /** gold square button with a dark plus */
    fun plusButton(c: Canvas, r: RectF, d: Float, pressed: Boolean = false) {
        val path = Path(); path.addRoundRect(r, r.width() * 0.16f, r.width() * 0.16f, Path.Direction.CW)
        glow(c, path, 0xFFFFB300.toInt(), d, 0.5f)
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, r.top, 0f, r.bottom, if (pressed) 0xFFFFF3B0.toInt() else GOLD_HI, 0xFFE59A00.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.color = 0xFF6B3E00.toInt(); c.drawPath(path, p)
        p.style = Paint.Style.FILL; p.color = DARK
        val t = r.width() * 0.13f; val l = r.width() * 0.29f
        c.drawRect(r.centerX() - l, r.centerY() - t, r.centerX() + l, r.centerY() + t, p)
        c.drawRect(r.centerX() - t, r.centerY() - l, r.centerX() + t, r.centerY() + l, p)
    }

    /** outlined (and optionally slanted) text: dark outline first, fill on top */
    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, fill: Int, d: Float, align: Paint.Align = Paint.Align.LEFT,
             skew: Float = -0.2f, outline: Int = NAVY, ow: Float = 2.4f, spacing: Float = 0f) {
        p.shader = null; p.typeface = Ui.FONT_BOLD; p.textSize = size; p.textAlign = align; p.textSkewX = skew; p.letterSpacing = spacing
        if (ow > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = ow * d; p.strokeJoin = Paint.Join.ROUND; p.color = outline; c.drawText(s, x, y, p) }
        p.style = Paint.Style.FILL; p.color = fill; c.drawText(s, x, y, p)
        p.textSkewX = 0f; p.letterSpacing = 0f; p.textAlign = Paint.Align.LEFT
    }

    fun measure(s: String, size: Float, spacing: Float = 0f): Float {
        p.typeface = Ui.FONT_BOLD; p.textSize = size; p.letterSpacing = spacing; p.textSkewX = 0f
        val w = p.measureText(s); p.letterSpacing = 0f
        return w
    }
}
