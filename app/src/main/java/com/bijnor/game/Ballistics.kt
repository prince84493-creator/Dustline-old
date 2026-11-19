package com.bijnor.game

/**
 * v32 BALLISTICS: bullets are no longer instant (hitscan). Every shot is a small projectile that flies with a real muzzle speed,
 * is pulled down by gravity and slowed by air drag, so far targets need lead and hold-over.
 *
 * Muzzle speed (m/s) is the real-world value of the calibre. Zero range = distance where the sights are "zeroed": the barrel is tilted up a
 * tiny bit so the bullet crosses the crosshair line there (shorter than that it flies a hair high, further it drops below the crosshair).
 * Drag is quadratic (a = k * v^2): light pistol bullets and shotgun pellets lose speed fast, heavy rifle bullets keep it.
 */
object Ballistics {
    const val G = 9.81f
    /** a bullet that flew this long (s) or this far (m) is gone */
    const val MAX_LIFE = 6f
    const val MAX_RANGE = 1000f

    fun speed(g: Gun): Float = when (g.id) {
        "mp5" -> 400f; "ump" -> 330f; "mp40" -> 380f; "vector" -> 380f; "bizon" -> 360f
        "p90" -> 715f                       // 5.7x28
        "vss" -> 290f; "vsk94" -> 290f      // 9x39 subsonic
        "m590" -> 400f                      // buckshot
        "kar98k" -> 760f; "m24" -> 800f; "awm" -> 900f; "m82" -> 853f
        else -> if (g.cls == "smg") 380f else 600f
    }

    /** k of a = k * v^2 (1/m) */
    fun drag(g: Gun): Float = when {
        g.cls == "shotgun" -> 0.0065f
        g.id == "p90" -> 0.0011f
        g.cls == "smg" -> 0.0016f
        g.cls == "dmr" -> 0.0010f
        g.cls == "sniper" -> 0.0005f
        g.cls == "amr" -> 0.0003f
        else -> 0.001f
    }

    /** zero distance of the sights (m) */
    fun zero(g: Gun): Float = when {
        g.cls == "shotgun" -> 20f
        g.id == "vss" -> 60f
        g.cls == "smg" -> 40f
        g.cls == "dmr" -> 100f
        g.cls == "sniper" -> 250f
        g.cls == "amr" -> 350f
        else -> 80f
    }

    /** extra upward slope (rise per metre) that makes the bullet cross the aim line at the zero distance */
    fun lift(g: Gun): Float { val v = speed(g); return G * zero(g) / (2f * v * v) }

    /** speed of a bot's bullet = the speed of its gun */
    fun botSpeed(g: Gun): Float = speed(g)
}
