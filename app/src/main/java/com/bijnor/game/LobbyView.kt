package com.bijnor.game

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.sin

/** Native port of #lobby: profile bar, settings, STORE / CHARACTER / VAULT, START with pulse + shine. */
class LobbyView(ctx: Context) : View(ctx) {
    var account: Account? = null
    var profile: Profile = Profile()
    var onStart: (() -> Unit)? = null
    var onStore: (() -> Unit)? = null
    var onCharacter: (() -> Unit)? = null
    var onVault: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onProfile: (() -> Unit)? = null
    var onPlus: (() -> Unit)? = null
    /** tap on the coin counter of the profile bar -> COINS -> DIAMONDS panel */
    var onCoin: (() -> Unit)? = null
    /** true = the 3D avatar view (LobbyAvatarView) sits behind this view, so no flat portrait / opaque backdrop is drawn */
    var avatar3d = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = ProfileBar()
    private val btnRects = Array(3) { RectF() }
    private val startRect = RectF()
    private val gearRect = RectF()
    private var pressed = -1
    private var msg = ""
    private val handler = Handler(Looper.getMainLooper())
    private val t0 = System.nanoTime()
    private var startEnabled = true


    private val labels = arrayOf("STORE", "CHARACTER", "VAULT")
    private val gearPath = "M19.4 13c.1-.3.1-.7.1-1s0-.7-.1-1l2.1-1.6c.2-.2.3-.4.1-.6l-2-3.5c-.1-.2-.4-.3-.6-.2l-2.5 1c-.5-.4-1.1-.7-1.7-1l-.4-2.6c0-.2-.3-.4-.5-.4h-4c-.2 0-.5.2-.5.4l-.4 2.6c-.6.3-1.2.6-1.7 1l-2.5-1c-.2-.1-.5 0-.6.2l-2 3.5c-.1.2-.1.5.1.6L4.6 11c-.1.3-.1.7-.1 1s0 .7.1 1l-2.1 1.6c-.2.2-.3.4-.1.6l2 3.5c.1.2.4.3.6.2l2.5-1c.5.4 1.1.7 1.7 1l.4 2.6c0 .2.3.4.5.4h4c.2 0 .5-.2.5-.4l.4-2.6c.6-.3 1.2-.6 1.7-1l2.5 1c.2.1.5 0 .6-.2l2-3.5c.1-.2.1-.5-.1-.6zM12 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"
    private val iconPaths = arrayOf(
        "M5 8h14l1 12H4zM8 8a4 4 0 0 1 8 0",
        "M12 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4 21c0-4.4 3.6-7 8-7s8 2.6 8 7z",
        "M8 3 3 6l2 5 3-1v11h8V10l3 1 2-5-5-3c-.5 1.7-2 2.5-4 2.5S8.5 4.7 8 3z"
    )

    fun setMessage(t: String) {
        msg = t; invalidate()
        handler.postDelayed({ if (msg == t) { msg = ""; invalidate() } }, 3000)
    }

    fun setStartEnabled(e: Boolean) { startEnabled = e; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        drawBackdrop(c, w, h)

        val ins = 12f * d
        val pbh = (h * 0.12f).coerceIn(44f * d, 60f * d)
        bar.draw(c, account, 0f, 0f, pbh, w, d, profile)

        // settings button: gold gear + small blue label plate (top right)
        val gw = 62f * d
        gearRect.set(w - ins - gw, 6f * d, w - ins, 6f * d + 70f * d)
        val gOn = pressed == 10
        val gi = Ui.svg(gearPath, 24f, 40f * d)
        val gcx = gearRect.centerX(); val gcy = gearRect.top + 24f * d
        val s2 = c.save(); c.translate(gcx - 20f * d, gcy - 20f * d)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * d; paint.strokeJoin = Paint.Join.ROUND; paint.color = 0xFF3A2200.toInt(); c.drawPath(gi, paint)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, 40f * d, if (gOn) 0xFFFFFFFF.toInt() else Glow.GOLD_HI, Glow.GOLD_LO, Shader.TileMode.CLAMP)
        c.drawPath(gi, paint); paint.shader = null
        c.restoreToCount(s2)
        val lp = Path(); val lr = RectF(gearRect.left, gearRect.top + 47f * d, gearRect.right, gearRect.top + 65f * d)
        lp.addRoundRect(lr, 7f * d, 7f * d, Path.Direction.CW)
        Glow.plate(c, lp, lr.left, lr.top, lr.right, lr.bottom, d, gOn)
        Glow.text(c, "Settings", lr.centerX(), lr.centerY() + 4f * d, 11.5f * d, Color.WHITE, d, Paint.Align.CENTER, skew = 0f, ow = 1.6f)

        // left buttons: blue glass plates with a gold shield badge, chevrons on the right (STORE carries the crown)
        val small = h < 420f * d
        val bh = if (small) 44f * d else 54f * d
        val bw = (if (small) 190f else 232f) * d
        var by = h * (if (small) 0.27f else 0.29f)
        for (i in 0 until 3) {
            val on = pressed == i
            val off = if (on) 6f * d else 0f
            val x0 = 5f * d
            btnRects[i].set(x0, by - 6f * d, x0 + bw + 10f * d, by + bh + 6f * d)
            val bs = bh * 1.10f
            val bcx = x0 + bs * 0.50f + off
            val pl = bcx; val pr = x0 + bw + off; val cut = bh * 0.30f
            val poly = Ui.polygon(pl, by, pr - cut, by, pr, by + cut * 0.9f, pr, by + bh - cut * 0.9f, pr - cut, by + bh, pl, by + bh)
            Glow.plate(c, poly, pl, by, pr, by + bh, d, on)
            // chevrons >>
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            val cxv = pr - cut - 24f * d; val my = by + bh / 2f
            for (k in 0..1) {
                val xx = cxv + k * 8f * d
                val ch = Path(); ch.moveTo(xx, my - 7f * d); ch.lineTo(xx + 6f * d, my); ch.lineTo(xx, my + 7f * d)
                paint.strokeWidth = 6f * d; paint.color = 0x332E7BFF; c.drawPath(ch, paint)
                paint.strokeWidth = 2.8f * d; paint.color = if (k == 0) Glow.CYAN else 0xFF8FE6FF.toInt(); c.drawPath(ch, paint)
            }
            paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
            // badge + icon
            val bcy = by + bh / 2f
            Glow.badge(c, bcx, bcy, bs, d, on)
            val isz = bs * 0.50f
            val ip = Ui.svg(iconPaths[i], 24f, isz)
            val sv2 = c.save(); c.translate(bcx - isz / 2f, bcy - isz / 2f - bs * 0.02f)
            paint.color = Glow.DARK; paint.style = Paint.Style.FILL
            if (i == 0) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f * isz / 24f; paint.strokeJoin = Paint.Join.ROUND }
            c.drawPath(ip, paint); paint.style = Paint.Style.FILL
            c.restoreToCount(sv2)
            if (i == 0) Glow.crown(c, bcx, by - bh * 0.02f, bs * 0.78f, d)
            // label (shrinks if the plate is narrow)
            val tx = bcx + bs * 0.58f
            var ls = bh * 0.42f
            val room = cxv - 6f * d - tx
            while (ls > 9f * d && Glow.measure(labels[i], ls) > room) ls -= d * 0.5f
            Glow.text(c, labels[i], tx, by + bh / 2f + ls * 0.34f, ls, Color.WHITE, d, skew = -0.2f, ow = 2f)
            by += bh + 8f * d
        }

        // lobby message
        if (msg.isNotEmpty()) {
            paint.typeface = Ui.FONT_BOLD; paint.textSize = 13f * d; paint.letterSpacing = 0.15f
            val tw = paint.measureText(msg); val mh = 26f * d
            val mx = w / 2 - tw / 2 - 16f * d; val my = h - 12f * d - 64f * d - mh
            paint.color = Ui.GOLD
            c.drawPath(Ui.polygon(mx + 8f * d, my, mx + tw + 32f * d, my, mx + tw + 24f * d, my + mh, mx, my + mh), paint)
            paint.color = Ui.INK; c.drawText(msg, mx + 16f * d, my + mh * 0.68f, paint); paint.letterSpacing = 0f
        }

        drawStart(c, w, h, d)
    }

    /** BIJNOR GAME plaque + START: gold slanted button with blue neon edge, hazard stripe, soft pulse and a sweeping shine. */
    private fun drawStart(c: Canvas, w: Float, h: Float, d: Float) {
        val bw = (w * 0.30f).coerceIn(170f * d, 300f * d)
        val bh = (h * 0.16f).coerceIn(44f * d, 74f * d)
        val r = w - 15f * d; val b = h - 12f * d
        val l = r - bw; val t = b - bh
        startRect.set(l, t, r, b)
        val tt = (System.nanoTime() - t0) / 1e9f
        val on = pressed == 20
        val pulse = if (startEnabled && !on) 1f + 0.02f * sin(tt * 4.5f) else 1f
        val sc = pulse * (if (on) 0.96f else 1f)

        // ---- title plaque above the button (decoration only) ----
        val ph = bh * 1.05f; val pt = t - ph - 6f * d; val pw = bw * 0.90f; val pl = r - pw - 4f * d
        if (pt > 70f * d) {
            val plq = Ui.polygon(pl, pt, pl + pw, pt, pl + pw, pt + ph, pl, pt + ph)
            Glow.glow(c, plq, Glow.BLUE_GLOW, d)
            val sv0 = c.save(); c.clipPath(plq)
            paint.shader = LinearGradient(0f, pt, 0f, pt + ph, intArrayOf(0xFF2A1B7A.toInt(), 0xFF8A2BA8.toInt(), 0xFFFF7A3C.toInt(), 0xFF1B1030.toInt()), floatArrayOf(0f, 0.45f, 0.78f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(pl, pt, pl + pw, pt + ph, paint); paint.shader = null
            paint.color = 0xFF0B0A18.toInt()
            val hills = Path(); hills.moveTo(pl, pt + ph); hills.lineTo(pl, pt + ph * 0.78f); hills.lineTo(pl + pw * 0.22f, pt + ph * 0.66f); hills.lineTo(pl + pw * 0.45f, pt + ph * 0.80f)
            hills.lineTo(pl + pw * 0.75f, pt + ph * 0.70f); hills.lineTo(pl + pw, pt + ph * 0.82f); hills.lineTo(pl + pw, pt + ph); hills.close()
            c.drawPath(hills, paint)
            c.restoreToCount(sv0)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f * d; paint.strokeJoin = Paint.Join.ROUND
            paint.shader = LinearGradient(0f, pt, 0f, pt + ph, intArrayOf(Glow.GOLD_HI, Glow.GOLD_MID, Glow.GOLD_LO), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(plq, paint); paint.shader = null; paint.style = Paint.Style.FILL
            Glow.wing(c, pl + 2f * d, pt + ph * 0.12f, ph * 0.9f, -1, d); Glow.wing(c, pl + pw - 2f * d, pt + ph * 0.12f, ph * 0.9f, 1, d)
            Glow.crown(c, pl + pw / 2f, pt + 4f * d, ph * 0.62f, d)
            Glow.text(c, "BIJNOR", pl + pw / 2f, pt + ph * 0.58f, ph * 0.46f, 0xFFEAF0FF.toInt(), d, Paint.Align.CENTER, skew = -0.22f, ow = 3.2f, spacing = 0.02f)
            Glow.text(c, "GAME", pl + pw / 2f, pt + ph * 0.86f, ph * 0.30f, Glow.GOLD_MID, d, Paint.Align.CENTER, skew = -0.22f, ow = 2.6f, spacing = 0.06f)
        }

        val sv = c.save(); c.scale(sc, sc, startRect.centerX(), startRect.centerY())
        val skew = bh * 0.32f; val cut = bh * 0.32f
        // blue neon plate peeking out on the left
        val back = Ui.polygon(l - bh * 0.22f, t + bh * 0.10f, l + skew + bh * 0.05f, t + bh * 0.10f, l + bh * 0.10f, b - bh * 0.02f, l - bh * 0.34f, b - bh * 0.02f)
        Glow.plate(c, back, l - bh * 0.34f, t, l + skew, b, d)
        val poly = Ui.polygon(l + skew, t, r, t, r, b - cut, r - cut, b, l, b)
        Glow.glow(c, poly, 0xFFFFB300.toInt(), d, if (startEnabled) 0.9f else 0f)
        paint.style = Paint.Style.FILL
        paint.shader = if (!startEnabled) LinearGradient(0f, t, 0f, b, 0xFF6B6B6B.toInt(), 0xFF454545.toInt(), Shader.TileMode.CLAMP)
        else LinearGradient(0f, t, 0f, b, intArrayOf(if (on) Ui.GOLD else 0xFFFFEE7A.toInt(), if (on) Ui.GOLD_DARK else 0xFFFFC107.toInt(), 0xFFE38E00.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(poly, paint); paint.shader = null

        val sv2 = c.save(); c.clipPath(poly)
        if (startEnabled) {
            paint.shader = LinearGradient(0f, t, 0f, t + bh * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(l, t, r, t + bh * 0.45f, paint); paint.shader = null
        }
        Ui.hatch(c, l, b - bh * 0.13f, r, b, 0xFF111111.toInt(), Ui.GOLD, 6f * d)
        if (startEnabled) {
            // shine sweep every ~3s
            val ph2 = (tt % 3f) / 1.1f
            if (ph2 in 0f..1f) {
                val sx = l - bh + (bw + bh * 2) * ph2
                val shine = Path().apply {
                    moveTo(sx, t); lineTo(sx + bh * 0.5f, t); lineTo(sx + bh * 0.5f - bh * 0.4f, b); lineTo(sx - bh * 0.4f, b); close()
                }
                paint.color = 0x59FFFFFF; c.drawPath(shine, paint)
            }
        }
        c.restoreToCount(sv2)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f * d; paint.strokeJoin = Paint.Join.ROUND
        paint.shader = LinearGradient(0f, t, 0f, b, 0xFFFFF3B0.toInt(), 0xFFB87400.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(poly, paint); paint.shader = null; paint.style = Paint.Style.FILL

        val bodyCy = (t + b - bh * 0.13f) / 2
        val ts = bh * 0.58f
        Glow.text(c, "START", (l + skew + r) / 2, bodyCy + ts * 0.34f, ts, if (startEnabled) 0xFF1A1405.toInt() else 0xFFBBBBBB.toInt(), d, Paint.Align.CENTER, skew = -0.22f, outline = 0x00000000, ow = 0f, spacing = 0.05f)
        c.restoreToCount(sv)

        if (startEnabled) postInvalidateOnAnimation()
    }

    private fun hit(x: Float, y: Float): Int {
        if (gearRect.contains(x, y)) return 10
        if (bar.plusRect.contains(x, y)) return 22
        if (bar.coinRect.contains(x, y)) return 24
        if (bar.cardRect.contains(x, y)) return 23
        if (startRect.contains(x, y)) return 20
        for (i in 0 until 3) if (btnRects[i].contains(x, y)) return i
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val p = hit(e.x, e.y)
                // nothing hit -> let the 3D avatar underneath receive the drag-to-rotate
                if (p < 0 || (p == 20 && !startEnabled)) return false
                pressed = p; invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = -1; invalidate()
                if (p >= 0 && hit(e.x, e.y) == p) {
                    when (p) {
                        0 -> onStore?.invoke()
                        1 -> onCharacter?.invoke()
                        2 -> onVault?.invoke()
                        10 -> onSettings?.invoke()
                        20 -> onStart?.invoke()
                        22 -> onPlus?.invoke()
                        23 -> onProfile?.invoke()
                        24 -> onCoin?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    private fun drawBackdrop(c: Canvas, w: Float, h: Float) {
        // stand-in for the 3D lobby scene (phase 2: OpenGL ES avatar + camera)
        if (!avatar3d) {
            paint.shader = LinearGradient(0f, 0f, w, h, 0xFF1B1426.toInt(), 0xFF0B0911.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
        }
        if (!avatar3d) {
            paint.shader = RadialGradient(w * 0.8f, h * 0.1f, w * 0.5f, 0x59AA5014, 0x00AA5014, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w * 0.1f, h * 0.95f, w * 0.55f, 0x66642D7D, 0x00642D7D, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        if (!avatar3d) {
            val ch = Characters.ALL.firstOrNull { it.id == profile.char } ?: Characters.ALL[0]
            PortraitRenderer.draw(c, ch, RectF(w * 0.30f, h * 0.14f, w * 0.70f, h * 0.98f), profile.hairStyle.ifEmpty { null }, profile.hair.ifEmpty { null })
        }
        if (avatar3d) {
            // background image is shown as-is; only a light top fade so the profile bar text stays readable
            paint.shader = LinearGradient(0f, 0f, 0f, h * 0.20f, 0x80090710.toInt(), 0x00090710, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h * 0.20f, paint)
            paint.shader = null
            return
        }
        // lb-vig
        paint.shader = RadialGradient(w * 0.55f, h * 0.58f, w * 0.65f, intArrayOf(0x00090710, 0x00090710, 0x99090710.toInt()), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, w * 0.4f, 0f, 0xD10B0912.toInt(), 0x000B0912, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w * 0.4f, h, paint)
        paint.shader = LinearGradient(0f, h, 0f, h * 0.68f, 0xC70B0912.toInt(), 0x000B0912, Shader.TileMode.CLAMP); c.drawRect(0f, h * 0.68f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.24f, 0xC70B0912.toInt(), 0x000B0912, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h * 0.24f, paint)
        paint.shader = null
    }

}
