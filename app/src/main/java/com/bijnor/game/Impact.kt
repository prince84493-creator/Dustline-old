package com.bijnor.game

import kotlin.math.abs
import kotlin.math.min

/**
 * v36 MATERIAL-WISE IMPACT: what a bullet hit is made of decides the mark and the particles.
 * concrete (walls, floors, roads) = grey dust + stone chips, wood (doors, furniture) = splinters, water = splash, dirt (ground) = brown dust + clods.
 */
object Impact {
    const val CONCRETE = 0
    const val WOOD = 1
    const val WATER = 2
    const val DIRT = 3

    /**
     * The collision boxes carry no material tag, so it is read from their shape (the houses are plaster / brick walls with wooden doors and furniture):
     * a door leaf = wood; anything 2 m or taller (wall, pillar), any floor / roof slab, any big block = concrete; the rest (tables, beds, cupboards, rails) = wood.
     */
    fun boxMaterial(b: MapData.Box, isDoor: Boolean): Int {
        if (isDoor) return WOOD
        val dx = b.maxX - b.minX; val dy = b.maxY - b.minY; val dz = b.maxZ - b.minZ
        val w = min(dx, dz); val l = if (dx > dz) dx else dz
        if (dy >= 2.0f) return CONCRETE
        if (dy <= 0.40f && l >= 1.5f) return CONCRETE       // floor / roof slab
        if (w >= 0.9f) return CONCRETE                       // big block
        return WOOD
    }

    /** Outward normal of the box face the ray (dx,dy,dz) entered at (hx,hy,hz); written to out[0..2]. */
    fun faceNormal(b: MapData.Box, hx: Float, hy: Float, hz: Float, dx: Float, dy: Float, dz: Float, out: FloatArray) {
        var bestD = 1e9f; var ax = 0; var sg = 1f
        // candidate faces: only those that look against the ray
        if (dx > 0f) { val d = abs(hx - b.minX); if (d < bestD) { bestD = d; ax = 0; sg = -1f } }
        if (dx < 0f) { val d = abs(hx - b.maxX); if (d < bestD) { bestD = d; ax = 0; sg = 1f } }
        if (dy > 0f) { val d = abs(hy - b.minY); if (d < bestD) { bestD = d; ax = 1; sg = -1f } }
        if (dy < 0f) { val d = abs(hy - b.maxY); if (d < bestD) { bestD = d; ax = 1; sg = 1f } }
        if (dz > 0f) { val d = abs(hz - b.minZ); if (d < bestD) { bestD = d; ax = 2; sg = -1f } }
        if (dz < 0f) { val d = abs(hz - b.maxZ); if (d < bestD) { bestD = d; ax = 2; sg = 1f } }
        out[0] = 0f; out[1] = 0f; out[2] = 0f; out[ax] = sg
    }
}
