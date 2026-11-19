package com.bijnor.game

import kotlin.math.max
import kotlin.math.min

/**
 * v36 WALL PENETRATION: a bullet no longer dies on the first thin wall / wooden door / furniture it meets.
 *
 * The solid it hits is measured ALONG THE PATH OF THE BULLET (touching boxes = one wall, so a corner where two wall boxes overlap is not counted twice;
 * a slanted shot is longer in the wall than a straight one, as in real life). If that thickness is not more than the gun can punch through, the
 * bullet leaves on the other side with less damage and less speed. Thick solids (concrete blocks, big buildings, a hill) always stop it.
 * Only numbers of this file need to be changed to tune the feel.
 */
object Penetration {
    /** the most wall (m, measured along the bullet path) this gun can punch through; 0 = never (buckshot) */
    fun limit(g: Gun): Float = when {
        g.pistol -> 0f              // v63: a PISTOL never goes through a wall (only big guns do)
        g.cls == "shotgun" -> 0f
        g.cls == "amr" -> 1.60f     // v63: big guns go through more wall
        g.cls == "sniper" -> 0.90f
        g.cls == "dmr" -> 0.75f
        g.id == "p90" -> 0.48f      // armour piercing 5.7x28
        else -> 0.40f               // SMG: thin plaster / wood walls (0.25 - 0.3 m in the houses) and doors, not a thick wall
    }

    /** how many separate walls one bullet can go through */
    fun maxWalls(g: Gun): Int = when {
        g.pistol || g.cls == "shotgun" -> 0
        g.cls == "amr" -> 5
        g.cls == "sniper" || g.cls == "dmr" -> 3
        else -> 2
    }

    /** share of the damage that is left after a wall of this thickness (thicker wall = weaker bullet) */
    fun damageKeep(g: Gun, thick: Float): Float {
        val lim = max(limit(g), 0.01f)
        val base = when {
            g.cls == "amr" -> 0.85f
            g.cls == "sniper" -> 0.80f
            g.cls == "dmr" -> 0.72f
            g.id == "p90" -> 0.65f
            else -> 0.55f
        }
        val f = base * (1f - 0.35f * min(1f, thick / lim))
        return f
    }

    /** share of the speed that is left after a wall */
    fun speedKeep(g: Gun): Float = when {
        g.cls == "amr" -> 0.92f
        g.cls == "sniper" || g.cls == "dmr" -> 0.85f
        else -> 0.75f
    }

    // entry / exit distance of the last rayBoxSpan call (no allocation in the game loop)
    private var spanIn = 0f
    private var spanOut = 0f

    /** slab test that also gives the exit distance. true = the ray crosses the box before maxD (spanIn..spanOut, measured from the origin, spanIn >= 0) */
    private fun rayBoxSpan(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                           x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, maxD: Float): Boolean {
        var t0 = 0f; var t1 = maxD
        if (kotlin.math.abs(dx) < 1e-8f) { if (ox < x0 || ox > x1) return false }
        else { var a = (x0 - ox) / dx; var b = (x1 - ox) / dx; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return false }
        if (kotlin.math.abs(dy) < 1e-8f) { if (oy < y0 || oy > y1) return false }
        else { var a = (y0 - oy) / dy; var b = (y1 - oy) / dy; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return false }
        if (kotlin.math.abs(dz) < 1e-8f) { if (oz < z0 || oz > z1) return false }
        else { var a = (z0 - oz) / dz; var b = (z1 - oz) / dz; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return false }
        spanIn = t0; spanOut = t1
        return true
    }

    /**
     * Thickness (m) of the wall the bullet enters tIn metres from (ox,oy,oz) along (dx,dy,dz), or -1 when it is more than cap (or any part of it is a big solid).
     * cell = the collision boxes near the entry point (MapData.boxesAt). Boxes that touch or overlap the wall (gap < 6 cm) are added to it.
     */
    fun thickness(cell: List<MapData.Box>?, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, tIn: Float, cap: Float): Float {
        if (cell == null || cap <= 0f) return -1f
        var end = tIn
        val reach = tIn + cap + 1.5f
        var grew = true; var guard = 0
        while (grew && guard++ < 8) {
            grew = false
            for (i in 0 until cell.size) {
                val b = cell[i]
                // a big solid (concrete block, building mass: smallest side more than the gun can punch) is never a "thin wall", even where a slanted shot clips only its corner
                if (min(min(b.maxX - b.minX, b.maxY - b.minY), b.maxZ - b.minZ) > cap) continue
                if (!rayBoxSpan(ox, oy, oz, dx, dy, dz, b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ, reach)) continue
                if (spanIn <= end + 0.06f && spanOut > end + 1e-4f) { end = spanOut; grew = true }
            }
            if (end - tIn > cap) return -1f
        }
        if (end <= tIn + 1e-4f) return -1f   // the thing that stopped the bullet is not a thin wall (big solid) -> no penetration
        val t = end - tIn
        return if (t > cap) -1f else t
    }
}
