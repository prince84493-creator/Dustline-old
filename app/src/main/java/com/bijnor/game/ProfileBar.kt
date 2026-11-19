package com.bijnor.game

import android.graphics.*
import androidx.core.graphics.PathParser

/**
 * Top-left profile bar: ONE dark strip stuck to the screen corner.
 *   [avatar] name / ID / xp line  Lv.N | (S) coins  <diamond> diamonds  [+]
 * Everything is the real data of the player (Account + Profile): name, uid, level, xp progress, coins, diamonds,
 * and the avatar shows the character / hair the player picked.
 */
class ProfileBar {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val plusRect = RectF()
    val cardRect = RectF()
    /** the coin icon + coin number: tapping it opens the COINS -> DIAMONDS panel */
    val coinRect = RectF()

    /**
     * @param x,y   top-left of the bar (pass 0,0 to stick it to the corner)
     * @param pbh   bar height
     * @param maxW  width of the screen (the bar is scaled down if it would be wider than ~80 % of it)
     * @param prof  the player's profile (character + hair) used for the avatar
     */
    fun draw(c: Canvas, a: Account?, x: Float, y: Float, pbh: Float, maxW: Float, d: Float, prof: Profile? = null) {
        val h = pbh
        val coinTxt = (a?.coins ?: 0L).toString()
        val diaTxt = (a?.diamonds ?: 0L).toString()

        // ---- layout in local units (px at scale 1): [wing][profile plate] [coin / diamond plate] ----
        val t = h * 0.16f; val b = h * 0.99f; val cy = (t + b) / 2f
        val pl = h * 0.30f; val pr = pl + h * 4.40f
        val slant = h * 0.20f
        val cl = pr + h * 0.10f
        val coinR = h * 0.25f
        val coinCx = cl + h * 0.62f
        val coinTx = coinCx + coinR + h * 0.14f
        val ts = h * 0.36f
        val coinW = Glow.measure(coinTxt, ts); val diaW = Glow.measure(diaTxt, ts)
        val diaS = h * 0.52f
        val diaX = coinTx + coinW + h * 0.50f
        val diaTx = diaX + diaS + h * 0.14f
        val pw = h * 0.50f
        val cr = maxOf(cl + h * 4.30f, diaTx + diaW + h * 0.28f + pw + h * 0.42f)
        val plusL = cr - pw - h * 0.40f
        val barW = cr + h * 0.10f

        val sc = minOf(1f, (maxW * 0.80f) / barW)
        val sv = c.save()
        c.translate(x, y); c.scale(sc, sc)
        paint.style = Paint.Style.FILL; paint.shader = null

        // ---- profile plate (wing, crown, avatar, name / ID / level / xp) ----
        val pp = Ui.polygon(pl + slant * 0.5f, t, pr, t, pr - slant, b, pl, b, pl, t + slant * 0.5f)
        Glow.wing(c, pl + h * 0.02f, t + h * 0.04f, h * 0.70f, -1, d)
        Glow.plate(c, pp, pl, t, pr, b, d)
        val av = RectF(pl + h * 0.07f, t + h * 0.05f, pl + h * 0.07f + (b - t) - h * 0.10f, b - h * 0.05f)
        drawAvatar(c, av, prof)
        Glow.crown(c, av.left + av.width() * 0.30f, t + h * 0.12f, h * 0.40f, d)

        val tx = av.right + h * 0.14f; val lvR = pr - slant - h * 0.20f
        val nm = a?.name ?: "PLAYER"
        paint.typeface = Ui.FONT_BOLD; paint.textSize = h * 0.31f
        Glow.text(c, TextUtilsEllipsize.fit(nm, paint, lvR - tx), tx, t + (b - t) * 0.40f, h * 0.31f, Color.WHITE, d, skew = 0f, ow = 2f)
        if (a != null && a.uid.isNotEmpty()) Glow.text(c, "ID: ${a.uid}", tx, t + (b - t) * 0.68f, h * 0.20f, 0xFFFFC42E.toInt(), d, skew = 0f, ow = 1.6f)
        Glow.text(c, "Lv.${a?.level ?: 1}", lvR, t + (b - t) * 0.68f, h * 0.21f, Color.WHITE, d, Paint.Align.RIGHT, skew = -0.15f, ow = 1.6f)
        // real xp progress: gold hazard stripes = earned, blue stripes = still to earn
        val bt = t + (b - t) * 0.78f; val bb = t + (b - t) * 0.92f
        val fillTo = tx + (lvR - tx) * (a?.progress ?: 0f).coerceIn(0f, 1f)
        paint.color = 0xFF050A24.toInt(); c.drawRect(tx, bt, lvR, bb, paint)
        Ui.hatch(c, fillTo, bt, lvR, bb, 0x331A4BD8, 0x882E7BFF.toInt(), h * 0.07f)
        if (fillTo > tx) Ui.hatch(c, tx, bt, fillTo, bb, 0xFF16120A.toInt(), Glow.GOLD_MID, h * 0.07f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, h * 0.02f); paint.color = 0xCCFFC42E.toInt(); c.drawRect(tx, bt, lvR, bb, paint)
        paint.style = Paint.Style.FILL

        // ---- coin / diamond plate ----
        val cp = Ui.polygon(cl + slant, t, cr, t, cr - slant, b, cl, b)
        Glow.plate(c, cp, cl, t, cr, b, d)
        Glow.coin(c, coinCx, cy, coinR, d)
        Glow.text(c, coinTxt, coinTx, cy + ts * 0.34f, ts, Color.WHITE, d, skew = 0f, ow = 2f)
        // slanted blue divider
        paint.color = 0x802E7BFF.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, h * 0.025f)
        val dvx = diaX - h * 0.26f; c.drawLine(dvx + h * 0.06f, t + h * 0.14f, dvx - h * 0.06f, b - h * 0.14f, paint); paint.style = Paint.Style.FILL
        // diamond (7 real facets)
        Glow.gemGlow(c, diaX + diaS / 2f, cy, diaS * 0.95f)
        val s = diaS / 32f; val sv2 = c.save(); c.translate(diaX, cy - diaS / 2f); c.scale(s, s)
        for (i in DIAMOND_PATHS.indices) { paint.color = DIAMOND_COLS[i]; c.drawPath(DIA_PATHS[i], paint) }
        c.restoreToCount(sv2)
        Glow.sparkle(c, diaX + diaS * 0.22f, cy - diaS * 0.38f, diaS * 0.16f)
        Glow.text(c, diaTxt, diaTx, cy + ts * 0.34f, ts, Color.WHITE, d, skew = 0f, ow = 2f)
        val pbR = RectF(plusL, cy - pw / 2f, plusL + pw, cy + pw / 2f)
        Glow.plusButton(c, pbR, d)
        c.restoreToCount(sv)

        // ---- hit areas in screen coordinates ----
        val pad = h * 0.14f
        plusRect.set(x + (pbR.left - pad) * sc, y, x + (pbR.right + pad) * sc, y + h * sc)
        cardRect.set(x, y, x + pr * sc, y + h * sc)
        coinRect.set(x + (coinCx - coinR - h * 0.14f) * sc, y, x + (plusL - h * 0.12f) * sc, y + h * sc)
    }

    companion object {
        private val DIAMOND_PATHS = arrayOf("M11 4h10l3 8H8z", "M8 4h3L8 12H2z", "M21 4h3l6 8h-6z", "M2 12h6l8 17z", "M8 12h8v17z", "M16 12h8l-8 17z", "M24 12h6L16 29z")
        private val DIAMOND_COLS = intArrayOf(0xFFC4F0FF.toInt(), 0xFF7FD0FF.toInt(), 0xFF4AAAF0.toInt(), 0xFF3A95E6.toInt(), 0xFF62BDFA.toInt(), 0xFF2F86DD.toInt(), 0xFF1F6FC4.toInt())
        private val DIA_PATHS: Array<Path> by lazy { Array(DIAMOND_PATHS.size) { PathParser.createPathFromPathData(DIAMOND_PATHS[it]) } }
        private val avPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        /**
         * The player's profile picture: his own photo (DP) or, for every new player, the default picture (assets/ui/default_dp.jpg), in a thin gold frame. Used by the lobby bar and the PROFILE screen.
         */
        fun drawAvatar(c: Canvas, r: RectF, prof: Profile?) {
            val p = avPaint
            val dp = DpStore.bitmap ?: DpStore.defaultBitmap   // v46: no own photo = the default picture (whole image), never the character head
            if (dp != null && !dp.isRecycled) {
                // the player's own photo (DP) replaces the character avatar
                val svd = c.save(); c.clipRect(r)
                p.style = Paint.Style.FILL; p.shader = null; p.isFilterBitmap = true
                c.drawBitmap(dp, null, r, p)
                c.restoreToCount(svd)
                p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1.5f, r.width() * 0.03f); p.color = 0xFFFFC55A.toInt()
                val id = p.strokeWidth / 2f
                c.drawRect(r.left + id, r.top + id, r.right - id, r.bottom - id, p)
                p.style = Paint.Style.FILL
                return
            }
            // v46: the old character-head avatar is gone for good; only if the default picture file is missing, a plain dark plate with a gold frame
            val sv = c.save(); c.clipRect(r)
            p.style = Paint.Style.FILL; p.shader = null; p.color = 0xFF14182B.toInt(); c.drawRect(r, p)
            c.restoreToCount(sv)
            p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1.5f, r.width() * 0.03f); p.color = 0xFFFFC55A.toInt()
            val i = p.strokeWidth / 2f
            c.drawRect(r.left + i, r.top + i, r.right - i, r.bottom - i, p)
            p.style = Paint.Style.FILL
        }
    }
}

object TextUtilsEllipsize {
    fun fit(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }
}
