package com.bijnor.game

import kotlin.math.sqrt

/**
 * v44 COMBAT REALISM: the numbers behind four new features. The game code (GameRenderer / Bots / GameScreen) only calls these helpers.
 *
 *  13  LEAN / PEEK          - [Lean]
 *  14  RECOIL PATTERN       - [RecoilPattern]   every gun climbs and drifts the SAME way each spray (learnable, no dice)
 *  15  BULLET WHIZ + SUPPRESSION - [Suppression]
 *  16  WEIGHT / DURABILITY / JAM - [Burden] (carried kg -> speed) and [GunCondition] (wear -> jam chance)
 */

/** 13: how far the head goes sideways and how much the view rolls while leaning. */
object Lean {
    /** sideways travel of the head / gun (m) at full lean */
    const val DIST = 0.42f
    /** view roll (rad) at full lean, about 11 degrees */
    const val ROLL = 0.19f
    /** body roll (rad) of the 3rd-person character at full lean */
    const val BODY_ROLL = 0.30f
    /** how fast the lean follows the button (1 / s) */
    const val RATE = 11f
    /** gap (m) kept between the head and a wall while peeking */
    const val WALL_GAP = 0.22f
}

/**
 * 14: fixed recoil pattern. Shot number n of a spray (n = 0 for the first bullet; the spray restarts after a short pause) gives
 * a vertical kick that grows over the first ~10 shots and then stays, and a sideways kick taken from a fixed table that is shifted
 * per gun. Same gun + same shot number = same kick, so a player can learn to pull down against it.
 */
object RecoilPattern {
    /** sideways kick per shot (-1..1), the table is nearly zero-sum so the muzzle swings left / right around the aim line */
    private val LAT = floatArrayOf(
        0.20f, 0.50f, 0.70f, 0.40f, -0.10f, -0.50f, -0.80f, -0.60f, 0.00f, 0.60f,
        0.90f, 0.50f, -0.20f, -0.60f, -0.70f, -0.40f, 0.10f, 0.40f
    )

    /** seconds without a shot after which the next shot is shot 0 again */
    fun resetGap(g: Gun): Float = g.delay * 2.5f + 0.30f

    private fun phase(g: Gun): Int { var h = 7; for (ch in g.id) h = h * 31 + ch.code; return (h and 0x7fffffff) % LAT.size }
    private fun flip(g: Gun): Float { var h = 3; for (ch in g.id) h = h * 17 + ch.code; return if ((h and 1) == 0) 1f else -1f }

    /** upward kick (rad) of shot n */
    fun pitch(g: Gun, n: Int): Float {
        val m = if (g.semi) 1f else (0.85f + 0.06f * n.coerceAtMost(9))   // auto guns: first bullet 0.85, from the 10th on 1.39
        return g.recoil * 2.2f * m
    }

    /** sideways kick (rad, + = right) of shot n */
    fun yaw(g: Gun, n: Int): Float = LAT[(n + phase(g)) % LAT.size] * flip(g) * g.recoil * 1.8f
}

/** 15: a bullet that flies past the head. */
object Suppression {
    /** closest distance (m) at which a passing bullet still counts */
    const val NEAR = 2.4f
    /** speed of sound (m/s): faster bullets crack, slower ones (VSS / VSK subsonic) only thud */
    const val SOUND = 343f
    /** how much the screen is shaken / darkened by a miss of this distance and speed (0..1) */
    fun amount(dist: Float, speed: Float): Float {
        val near = (1f - dist / NEAR).coerceIn(0f, 1f)
        return near * (if (speed >= SOUND) 1f else 0.5f)
    }
    /** per second the fear goes down */
    const val DECAY = 0.45f
    /** aim spread gets this much bigger at full suppression */
    const val SPREAD_K = 0.6f
    /** closest point of a straight bullet piece to a point: returns the distance, writes the nearest point to out[0..2] (a piece starts at sx,sy,sz and runs len metres along d) */
    fun closest(sx: Float, sy: Float, sz: Float, dx: Float, dy: Float, dz: Float, len: Float, px: Float, py: Float, pz: Float, out: FloatArray): Float {
        var t = (px - sx) * dx + (py - sy) * dy + (pz - sz) * dz
        t = t.coerceIn(0f, len)
        val cx = sx + dx * t; val cy = sy + dy * t; val cz = sz + dz * t
        out[0] = cx; out[1] = cy; out[2] = cz
        val ex = cx - px; val ey = cy - py; val ez = cz - pz
        return sqrt(ex * ex + ey * ey + ez * ez)
    }
}

/** 16a: what you carry slows you down. */
object Burden {
    /** no penalty up to this load (kg) */
    const val FREE_KG = 14f
    /** the full penalty is reached here (kg); more is still possible but gives no further penalty beyond [MIN_SPEED] */
    const val HEAVY_KG = 32f
    /** speed factor at HEAVY_KG */
    private const val MAX_PENALTY = 0.36f
    const val MIN_SPEED = 0.55f

    const val MEDKIT = 0.7f
    const val BANDAGE = 0.06f
    const val SHROOM = 0.12f
    const val GRENADE = 0.45f
    const val SCOPE = 0.35f

    fun gunKg(g: Gun): Float = when (g.id) {
        "mp5" -> 2.5f; "ump" -> 2.5f; "mp40" -> 4.0f; "vector" -> 2.7f; "bizon" -> 2.7f; "p90" -> 2.6f; "vss" -> 2.6f
        "m1911" -> 1.1f; "g18" -> 0.7f; "deagle" -> 2.0f
        "m590" -> 3.2f; "vsk94" -> 3.3f; "kar98k" -> 4.1f; "m24" -> 5.5f; "awm" -> 6.5f; "m82" -> 12.9f
        else -> 3f
    }
    /** one round: type 0 smg, 1 sniper / dmr, 2 shotgun (AmmoInfo.typeOf) */
    fun ammoKg(type: Int): Float = when (type) { 0 -> 0.010f; 1 -> 0.032f; else -> 0.040f }
    fun vestKg(lvl: Int): Float = when (lvl) { 0 -> 0f; 1 -> 1.6f; 2 -> 2.6f; 3 -> 3.8f; else -> 5.2f }
    fun helmKg(lvl: Int): Float = if (lvl <= 0) 0f else 0.7f + 0.25f * lvl

    private fun t(kg: Float): Float = ((kg - FREE_KG) / (HEAVY_KG - FREE_KG)).coerceIn(0f, 1.5f)
    /** walking / running speed factor */
    fun speedK(kg: Float): Float = (1f - MAX_PENALTY * t(kg)).coerceAtLeast(MIN_SPEED)
    /** jump speed factor */
    fun jumpK(kg: Float): Float = (1f - 0.18f * t(kg)).coerceAtLeast(0.75f)
    fun heavy(kg: Float): Boolean = kg > FREE_KG
}

/** 16b: every gun wears out while you shoot with it; a worn gun jams and has to be cleared by hand (RELOAD button). */
object GunCondition {
    const val MAX = 100f
    /** below this the gun may jam */
    const val JAM_BELOW = 55f
    /** seconds the hand needs to clear a jam, durability that the cleaning gives back */
    const val CLEAR_TIME = 1.4f
    const val CLEAR_RESTORE = 6f
    /** a gun found on the ground is worn a little: random between these */
    const val FOUND_MIN = 60f
    const val FOUND_MAX = 100f

    fun wearPerShot(g: Gun): Float = when (g.cls) {
        "smg" -> 0.12f; "shotgun" -> 0.40f; "dmr" -> 0.32f; "sniper" -> 0.55f; "amr" -> 0.90f; else -> 0.15f
    }
    /** chance that THIS trigger pull jams: 0 when the gun is in good shape, up to about 7 % at 0 durability */
    fun jamChance(d: Float): Float {
        if (d >= JAM_BELOW) return 0f
        val x = (JAM_BELOW - d) / JAM_BELOW
        return 0.004f + 0.07f * x * x
    }
    /** a badly worn barrel is less accurate */
    fun spreadK(d: Float): Float = if (d >= 35f) 1f else 1f + (35f - d) / 35f * 0.5f
    fun grade(d: Float): String = when { d >= 70f -> "GOOD"; d >= JAM_BELOW -> "USED"; d >= 25f -> "WORN"; else -> "BAD" }
}
