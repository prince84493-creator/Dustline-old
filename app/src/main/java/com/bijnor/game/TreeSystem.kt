package com.bijnor.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.opengl.GLES30 as GL
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ====================================================================================================================
//  TREES (ported from ped-3d.html: procedural branches + leaf cards) + WEATHER (sun / rain / storm) + RAIN lines.
//  - 7 tree types x 2 shapes, built once, drawn INSTANCED (one draw call per shape), wind sway done in the vertex shader (uTreeOn).
//  - Placed: never on a road, never inside a town, not in water / on the coast; groves + lone trees + a moderate number beside roads.
// ====================================================================================================================

private class V3(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun norm(): V3 { val l = len(); return if (l < 1e-6f) V3(0f, 1f, 0f) else V3(x / l, y / l, z / l) }
    /** rotate around a UNIT axis (Rodrigues) */
    fun rot(k: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        val kc = k.cross(this); val kd = k.dot(this) * (1f - c)
        return V3(x * c + kc.x * s + k.x * kd, y * c + kc.y * s + k.y * kd, z * c + kc.z * s + k.z * kd)
    }
}

/** One tree type (TY of the HTML). h0/h1 = hue range, l0/l1 = lightness range. */
private class TC(
    val key: String, val bark: Int, val trunk: Float, val r: Float, val d: Int, val k: Int, val ratio: Float, val spread: Float,
    val up: Float, val twist: Float, val L: Int, val ls: Float, val cy: Float, val cr: Float,
    val h0: Float, val h1: Float, val sat: Float, val l0: Float, val l1: Float, val fall: Boolean = false, val pine: Boolean = false,
    val kind: Int = 0   // v64: 0 = tree, 1 = ROCK (solid boulder), 2 = BUSH (solid shrub core)
)

private const val TS = 12   // tree vertex: pos3 nor3 uv2 col3 flex1

private class MB {
    var v = FloatArray(TS * 2048); var nv = 0
    var ix = IntArray(6144); var ni = 0
    fun vert(p: V3, n: V3, u: Float, w: Float, r: Float, g: Float, b: Float, flex: Float): Int {
        if ((nv + 1) * TS > v.size) v = v.copyOf(v.size * 2)
        val o = nv * TS
        v[o] = p.x; v[o + 1] = p.y; v[o + 2] = p.z; v[o + 3] = n.x; v[o + 4] = n.y; v[o + 5] = n.z
        v[o + 6] = u; v[o + 7] = w; v[o + 8] = r; v[o + 9] = g; v[o + 10] = b; v[o + 11] = flex
        return nv++
    }
    fun tri(a: Int, b: Int, c: Int) {
        if (ni + 3 > ix.size) ix = ix.copyOf(ix.size * 2)
        ix[ni++] = a; ix[ni++] = b; ix[ni++] = c
    }
    fun verts(): FloatArray = v.copyOf(nv * TS)
    fun idx(): IntArray = ix.copyOf(ni)
}

private class Variant(val type: Int, val pine: Boolean, val bark: FloatArray, val barkIdx: IntArray, val leaf: FloatArray, val leafIdx: IntArray)

private class GenCtx(seed: Int, val c: TC) {
    val rnd = Random(seed)
    val B = MB(); val L = MB()
    val crown = V3(0f, c.cy, 0f)
    var sides = 5
    fun R() = rnd.nextFloat()
    fun rv(a: Float) = V3(R() - .5f, R() - .5f, R() - .5f) * (2f * a)
}

private fun hsl(h: Float, s: Float, l: Float): FloatArray {
    val hh = ((h % 1f) + 1f) % 1f
    val q = if (l < .5f) l * (1f + s) else l + s - l * s; val p = 2f * l - q
    fun f(tt: Float): Float {
        var t = tt; if (t < 0f) t += 1f; if (t > 1f) t -= 1f
        return if (t < 1f / 6f) p + (q - p) * 6f * t else if (t < .5f) q else if (t < 2f / 3f) p + (q - p) * (2f / 3f - t) * 6f else p
    }
    return floatArrayOf(f(hh + 1f / 3f), f(hh), f(hh - 1f / 3f))
}

private fun addCyl(g: GenCtx, a: V3, b: V3, r0: Float, r1: Float, shade: Float) {
    val S = g.sides
    val dv = b - a; val len = dv.len(); val d = dv.norm()
    val tmp = if (abs(d.y) < .9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
    val u = tmp.cross(d).norm(); val v = d.cross(u)
    val bc = g.c.bark
    val cr = ((bc shr 16) and 255) / 255f * shade; val cg = ((bc shr 8) and 255) / 255f * shade; val cb = (bc and 255) / 255f * shade
    val o = g.B.nv
    for (i in 0..S) {
        val ang = i.toFloat() / S * 6.2832f
        val n = u * cos(ang) + v * sin(ang)
        g.B.vert(a + n * r0, n, i.toFloat() / S * 2f, 0f, cr, cg, cb, 0f)
        g.B.vert(b + n * r1, n, i.toFloat() / S * 2f, len * .5f, cr, cg, cb, 0f)
    }
    for (i in 0 until S) { val a0 = o + i * 2; g.B.tri(a0, a0 + 2, a0 + 1); g.B.tri(a0 + 1, a0 + 2, a0 + 3) }
}

private fun leafCol(g: GenCtx, cn: V3): FloatArray {
    val c = g.c
    val ao = min(1f, max(.35f, (cn - g.crown).len() / c.cr))
    val hue = c.h0 + g.R() * (c.h1 - c.h0) - (if (!c.fall && c.L > 1 && g.R() < .07f) .05f else 0f)
    val lig = (c.l0 + g.R() * (c.l1 - c.l0)) * (.55f + .45f * ao) * 1.3f
    return hsl(hue, c.sat, min(.7f, lig))
}

private fun card(g: GenCtx, cn: V3, axIn: V3, w: Float, h: Float) {
    val ax = axIn.norm()
    val s = ax.cross(g.rv(1f)).norm()
    var n0 = s.cross(ax)
    val out = (cn - g.crown).norm()
    if (n0.dot(out) < 0f) n0 = n0 * -1f
    val nn = (n0 * .4f + out * .6f).norm()
    val cl = leafCol(g, cn)
    val o = g.L.nv
    val sxs = floatArrayOf(-1f, 1f, -1f, 1f); val ts = floatArrayOf(0f, 0f, 1f, 1f)
    for (i in 0 until 4) {
        val q = cn + s * (sxs[i] * w / 2f) + ax * (ts[i] * h)
        g.L.vert(q, nn, (sxs[i] + 1f) / 2f, ts[i], cl[0], cl[1], cl[2], if (ts[i] > .5f) 1f else .3f)
    }
    g.L.tri(o, o + 1, o + 2); g.L.tri(o + 2, o + 1, o + 3)
}

private const val LEAF_SCALE = 1.7f   // fewer branches than the HTML tree (phone), so each leaf card is bigger

private fun leaves(g: GenCtx, p: V3, n: Int) {
    for (i in 0 until n) {
        val s = g.c.ls * (.7f + g.R() * .6f) * LEAF_SCALE
        card(g, p + g.rv(.6f), g.rv(1f) + V3(0f, .3f, 0f), s, s * 1.3f)
    }
}

private fun grow(g: GenCtx, p: V3, dir: V3, len: Float, rad: Float, d: Int) {
    val c = g.c
    var cur = p; var dd = dir
    for (s in 0..1) {
        dd = (dd + g.rv(if (c.twist > 0f) c.twist else .12f)).norm()
        val nx = cur + dd * (len / 2f)
        addCyl(g, cur, nx, rad * (1f - .2f * s), rad * (1f - .2f * (s + 1)), .78f + g.R() * .4f)
        cur = nx
    }
    val lc = min(c.L, 10)
    if (d == 0) { if (lc > 0) leaves(g, cur, lc); return }
    if (d == 1 && lc > 0) leaves(g, cur, lc shr 1)
    val kids = c.k + (if (g.R() < .3f) 1 else 0) - (if (g.R() < .15f) 1 else 0)
    for (i in 0 until kids) {
        val perp = g.rv(1f).cross(dd).norm()
        var nd = dd.rot(perp, c.spread * (.5f + g.R() * .7f))
        nd = V3(nd.x, nd.y + c.up, nd.z).norm()
        grow(g, cur, nd, len * c.ratio * (.8f + g.R() * .4f), rad * .62f, d - 1)
    }
}

private fun genPine(g: GenCtx) {
    val H = 9.5f; var pr = V3(0f, 0f, 0f)
    for (k in 0 until 4) {
        val nx = V3((g.R() - .5f) * .15f, (k + 1) * H / 4f, (g.R() - .5f) * .15f)
        addCyl(g, pr, nx, .34f * (1f - k * .22f), .34f * (1f - (k + 1) * .22f), .85f)
        pr = nx
    }
    val K = 16; val J = 5
    for (k in 0 until K) {
        val t = k.toFloat() / K; val y = 1.6f + t * 7.9f; val len = 3.1f * (1f - t) + .35f
        for (j in 0 until J) {
            val a = k * 2.4f + j * (6.2832f / J) + g.R() * .3f
            val dir = V3(cos(a), -.18f + .25f * t, sin(a)).norm()
            val b = V3(0f, y, 0f); val tip = b + dir * len
            addCyl(g, b, tip, .05f + .05f * (1f - t), .012f, .85f)
            for (m in 0 until 9) {
                val ps = b + dir * (len * (.12f + m * .1f))
                val ax = dir + g.rv(.5f)
                card(g, ps, V3(ax.x, ax.y - .3f, ax.z), .55f * (1f - t * .3f) * 1.25f, (1.1f * (1f - t * .5f) + .3f) * 1.25f)
            }
        }
    }
}

/** v64: a faceted boulder: a fat stack of cones + 3-4 smaller lumps leaning out of it (closed tops, no hollow tube). About 1.7 m across at scale 1. */
private fun genRock(g: GenCtx) {
    g.sides = 6
    val sh = 0.78f + g.R() * 0.3f
    addCyl(g, V3(0f, -.2f, 0f), V3(0f, .5f, 0f), .9f, .68f, sh)
    addCyl(g, V3(0f, .5f, 0f), V3(g.R() * .1f - .05f, .92f, g.R() * .1f - .05f), .68f, .1f, sh * 1.08f)
    for (k in 0 until 4) {
        val a = k * 1.57f + g.R() * .8f; val d = .5f + g.R() * .35f
        val bx = cos(a) * d; val bz = sin(a) * d; val h = .35f + g.R() * .4f
        addCyl(g, V3(bx, -.2f, bz), V3(bx * .85f, h, bz * .85f), .5f + g.R() * .2f, .08f, sh * (.85f + g.R() * .3f))
    }
}

/** v64: a dense shrub (jhaadi): a few twigs and about 70 leaf cards in a low dome, about 1.8 m across at scale 1. */
private fun genBush(g: GenCtx) {
    g.sides = 4
    for (k in 0 until 5) {
        val a = k * 1.26f + g.R() * .5f
        addCyl(g, V3(0f, 0f, 0f), V3(cos(a) * .35f, .55f + g.R() * .3f, sin(a) * .35f), .05f, .02f, .8f)
    }
    for (i in 0 until 70) {
        val a = g.R() * 6.2832f; val e = g.R() * 1.35f; val d = .25f + g.R() * .7f
        val p = V3(cos(a) * cos(e) * d, .12f + sin(e) * d * .85f, sin(a) * cos(e) * d)
        card(g, p, g.rv(1f) + V3(0f, .35f, 0f), .55f, .72f)
    }
}

private fun buildVariant(type: Int, c: TC, seed: Int): Variant {
    val g = GenCtx(seed, c)
    if (c.kind == 1) { genRock(g); return Variant(type, false, g.B.verts(), g.B.idx(), g.L.verts(), g.L.idx()) }
    if (c.kind == 2) { genBush(g); return Variant(type, false, g.B.verts(), g.B.idx(), g.L.verts(), g.L.idx()) }
    if (c.pine) { g.sides = 4; genPine(g) }
    else {
        g.sides = 5
        grow(g, V3(0f, 0f, 0f), V3(0f, 1f, 0f), c.trunk, c.r, if (c.k >= 3) 4 else 5)
    }
    // root flare
    for (i in 0 until 6) {
        val a = i * .9f + g.R() * .4f; val q = if (c.r > 0f) c.r else .34f
        addCyl(g, V3(cos(a) * q * .5f, .9f, sin(a) * q * .5f), V3(cos(a) * q * 3.2f, -.05f, sin(a) * q * 3.2f), q * .55f, q * .12f, .7f)
    }
    return Variant(type, c.pine, g.B.verts(), g.B.idx(), g.L.verts(), g.L.idx())
}

/** One shape on the GPU: bark + leaf VAO (same instance buffer). */
private class GlVar(@JvmField var barkVao: Int, @JvmField var barkCnt: Int, @JvmField var leafVao: Int, @JvmField var leafCnt: Int, @JvmField var inst: Int)

class TreeSystem(private val map: MapData) {
    private val TYPES = listOf(
        TC("broad", 0x5b4630, 2.6f, .3f, 6, 3, .74f, .75f, .22f, .18f, 7, .34f, 5.2f, 3.8f, .24f, .32f, .55f, .2f, .34f),
        TC("oak", 0x3d3024, 2.1f, .55f, 6, 3, .74f, 1f, .08f, .25f, 7, .32f, 4.6f, 4.4f, .22f, .3f, .5f, .16f, .28f),
        TC("acacia", 0x4a3a2e, 2.6f, .26f, 7, 2, .8f, .9f, .04f, .28f, 16, .3f, 5.5f, 4.6f, .2f, .28f, .55f, .22f, .34f),
        TC("dense", 0xa98a62, 2.4f, .4f, 6, 3, .78f, .55f, .32f, .1f, 9, .27f, 6f, 4.3f, .22f, .3f, .62f, .25f, .42f),
        TC("autumn", 0x4a3a30, 3.4f, .17f, 6, 3, .72f, .7f, .22f, .14f, 6, .36f, 5.4f, 3.3f, 0f, .09f, .85f, .35f, .5f, fall = true),
        TC("pine", 0x6a3f2a, 0f, 0f, 0, 0, 0f, 0f, 0f, 0f, 1, 0f, 5f, 3.2f, .33f, .4f, .38f, .1f, .2f, pine = true),
        TC("dead", 0x8a7a68, 2.4f, .62f, 6, 2, .74f, 1.2f, .12f, .5f, 0, 0f, 5f, 4f, 0f, 0f, 0f, 0f, 0f),
        // v64: boulders and shrubs (placed by placeRocks(), never picked by pickType)
        TC("rock", 0x8c887e, 0f, 0f, 0, 0, 0f, 0f, 0f, 0f, 0, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, kind = 1),
        TC("bush", 0x4a3a28, 0f, 0f, 0, 0, 0f, 0f, 0f, 0f, 0, .34f, .6f, 1f, .22f, .32f, .5f, .16f, .30f, kind = 2)
    )
    private val variants = ArrayList<Variant>()
    private val gl = ArrayList<GlVar>()
    private val MAXI = 640
    private var instBuf: ByteBuffer? = null
    private var instData = Array(0) { FloatArray(0) }
    private var instCnt = IntArray(0)
    private var leafTex = 0; private var needleTex = 0
    private var glReady = false

    // placed trees (parallel arrays)
    private var tx = FloatArray(0); private var ty = FloatArray(0); private var tz = FloatArray(0)
    private var tyaw = FloatArray(0); private var tscl = FloatArray(0); private var tph = FloatArray(0); private var tvar = IntArray(0)
    private val CELL = 64f
    private val GN = 80
    private var cells: Array<IntArray?> = arrayOfNulls(0)
    var treeCount = 0; private set
    /** v57: x, z of every placed tree (for the satellite map). */
    fun positionsXZ(): FloatArray {   // v64: trees only (rocks / bushes are not drawn on the satellite map)
        val n = min(treeCount, min(tx.size, tz.size)); val o = ArrayList<Float>(n * 2)
        for (i in 0 until n) { if (TYPES[(tvar[i] / 2).coerceIn(0, TYPES.size - 1)].kind != 0) continue; o.add(tx[i]); o.add(tz[i]) }
        return o.toFloatArray()
    }

    init {
        for ((ti, c) in TYPES.withIndex()) for (k in 0 until 2) variants.add(buildVariant(ti, c, 1000 + ti * 97 + k * 31))
        place()
    }

    // ------------------------------------------------------------------ placement
    private fun cityR(i: Int): Float = when (i) {
        0 -> 450f                                   // Bijnor (biggest)
        in 1..5, 14, 16, 17 -> 260f             // big towns
        19, 28, 13 -> 130f                          // small / quiet ones
        else -> 190f
    }

    private fun inTown(x: Float, z: Float): Boolean {
        for ((i, l) in BijnorLabels.ALL.withIndex()) {
            val dx = x - l.x; val dz = z - l.z; val r = cityR(i)
            if (dx * dx + dz * dz < r * r) return true
        }
        return false
    }

    /** a tree may stand here: land, dry, not in a town, and NO road under the trunk or the first metres of the crown */
    private fun spotOk(x: Float, z: Float): Boolean {
        if (abs(x) > MapData.LAND_HALF_X - 20f || abs(z) > MapData.LAND_HALF_Z - 20f) return false
        if (inTown(x, z)) return false
        if (map.terrainBlocked(x, z, 2f)) return false
        if (!map.onMainland(x, z)) return false
        if (map.waterDepth(x, z) > 0.02f) return false
        if (map.ground(x, z) < 1.3f) return false   // v53: was 2.0 - with the new low fields (min 1.4 m) every valley would have stayed treeless; 1.3 still keeps trees off the beach step
        if (map.roadAt(x, z)) return false
        for (h in map.houses) if (x > h.minX - 60f && x < h.maxX + 60f && z > h.minZ - 60f && z < h.maxZ + 60f) return false   // no trees near any house
        for (k in 0 until 8) {
            val a = k * 0.7853982f
            if (map.roadAt(x + cos(a) * 3.5f, z + sin(a) * 3.5f)) return false
            if (map.roadAt(x + cos(a) * 6.5f, z + sin(a) * 6.5f)) return false
        }
        val sp = map.spawn
        if ((x - sp[0]) * (x - sp[0]) + (z - sp[2]) * (z - sp[2]) < 14f * 14f) return false
        return true
    }

    private fun place() {
        val rnd = Random(20260611)
        val xs = ArrayList<Float>(); val zs = ArrayList<Float>(); val yw = ArrayList<Float>(); val sc = ArrayList<Float>()
        val ph = ArrayList<Float>(); val vr = ArrayList<Int>()
        val grid = HashMap<Int, ArrayList<Int>>()
        fun key(ix: Int, iz: Int) = (ix + 500) * 1000 + (iz + 500)
        fun free(x: Float, z: Float, d: Float): Boolean {
            val cx = floor(x / 8f).toInt(); val cz = floor(z / 8f).toInt()
            val rr = (d / 8f).toInt() + 1
            for (a in -rr..rr) for (b in -rr..rr) {
                val l = grid[key(cx + a, cz + b)] ?: continue
                for (i in l) { val dx = xs[i] - x; val dz = zs[i] - z; if (dx * dx + dz * dz < d * d) return false }
            }
            return true
        }
        fun add(x: Float, z: Float, type: Int) {
            val i = xs.size
            xs.add(x); zs.add(z); yw.add(rnd.nextFloat() * 6.2832f); sc.add(0.85f + rnd.nextFloat() * 0.55f)
            ph.add(rnd.nextFloat() * 6.2832f); vr.add(type * 2 + rnd.nextInt(2))
            grid.getOrPut(key(floor(x / 8f).toInt(), floor(z / 8f).toInt())) { ArrayList() }.add(i)
        }
        fun pickType(main: Int): Int {
            if (main >= 0 && rnd.nextFloat() < .7f) return main
            val r = rnd.nextFloat()
            return when {
                r < .27f -> 0
                r < .47f -> 3
                r < .65f -> 2
                r < .77f -> 1
                r < .85f -> 5
                r < .93f -> 4
                else -> 6
            }
        }
        // 1) groves / small woods
        var groves = 0; var tries = 0
        while (groves < 48 && tries < 4000) {
            tries++
            val cx = (rnd.nextFloat() * 2f - 1f) * (MapData.LAND_HALF_X - 60f); val cz = (rnd.nextFloat() * 2f - 1f) * (MapData.LAND_HALF_Z - 60f)
            if (!spotOk(cx, cz)) continue
            val rad = 35f + rnd.nextFloat() * 75f; val n = 14 + rnd.nextInt(30)
            val main = rnd.nextInt(7)
            var placed = 0; var t2 = 0
            while (placed < n && t2 < n * 14) {
                t2++
                val a = rnd.nextFloat() * 6.2832f; val d = sqrt(rnd.nextFloat()) * rad
                val x = cx + cos(a) * d; val z = cz + sin(a) * d
                if (!spotOk(x, z) || !free(x, z, 5.5f)) continue
                add(x, z, pickType(main)); placed++
            }
            groves++
        }
        // 2) lone trees in the fields
        var lone = 0; tries = 0
        while (lone < 330 && tries < 20000) {
            tries++
            val x = (rnd.nextFloat() * 2f - 1f) * MapData.LAND_HALF_X; val z = (rnd.nextFloat() * 2f - 1f) * MapData.LAND_HALF_Z
            if (!spotOk(x, z) || !free(x, z, 14f)) continue
            add(x, z, pickType(-1)); lone++
        }
        // 3) beside the roads: a random point ON a road, walk away from it until the asphalt ends, then 7-13 m further
        //    (even spread along every road, moderate number, never on the asphalt, never in a town)
        val tri = ArrayList<FloatArray>()
        for (ly in map.roadLayers) {
            var q = 0
            while (q + 2 < ly.idx.size) {
                tri.add(floatArrayOf(ly.xz[ly.idx[q] * 2], ly.xz[ly.idx[q] * 2 + 1], ly.xz[ly.idx[q + 1] * 2], ly.xz[ly.idx[q + 1] * 2 + 1], ly.xz[ly.idx[q + 2] * 2], ly.xz[ly.idx[q + 2] * 2 + 1]))
                q += 3
            }
        }
        var side = 0; tries = 0
        if (tri.isNotEmpty()) while (side < 650 && tries < 60000) {
            tries++
            val t = tri[rnd.nextInt(tri.size)]
            val u = sqrt(rnd.nextFloat()); val v = rnd.nextFloat()
            val bx = t[0] * (1f - u) + t[2] * u * (1f - v) + t[4] * u * v
            val bz = t[1] * (1f - u) + t[3] * u * (1f - v) + t[5] * u * v
            val ang = rnd.nextFloat() * 6.2832f; val dx = cos(ang); val dz = sin(ang)
            var st = 0f
            while (st < 24f && map.roadAt(bx + dx * st, bz + dz * st)) st += 1f
            val dd = st + 7f + rnd.nextFloat() * 6f
            val x = bx + dx * dd; val z = bz + dz * dd
            if (!spotOk(x, z) || !free(x, z, 12f)) continue
            val r = rnd.nextFloat()
            add(x, z, if (r < .4f) 0 else if (r < .65f) 3 else if (r < .85f) 2 else if (r < .92f) 1 else 4)
            side++
        }
        // 4) v64: BOULDERS (type 7, solid) and SHRUBS (type 8, solid core). Own random stream, so the trees above stay exactly where they were.
        run {
            val rr = Random(640064)
            var rocks = 0; var rt = 0
            while (rocks < 520 && rt < 30000) {   // single boulders and small piles of 2-4 beside each other
                rt++
                val x = (rr.nextFloat() * 2f - 1f) * MapData.LAND_HALF_X; val z = (rr.nextFloat() * 2f - 1f) * MapData.LAND_HALF_Z
                if (!spotOk(x, z) || !free(x, z, 6f)) continue
                add(x, z, 7); rocks++
                val extra = rr.nextInt(4)
                for (e in 0 until extra) {
                    val a = rr.nextFloat() * 6.2832f; val d = 1.9f + rr.nextFloat() * 1.6f
                    val x2 = x + cos(a) * d; val z2 = z + sin(a) * d
                    if (!spotOk(x2, z2) || !free(x2, z2, 1.8f)) continue
                    add(x2, z2, 7); rocks++
                }
            }
            var bushes = 0; rt = 0
            while (bushes < 800 && rt < 40000) {   // shrubs: lone ones and hedgerow-like clumps
                rt++
                val x = (rr.nextFloat() * 2f - 1f) * MapData.LAND_HALF_X; val z = (rr.nextFloat() * 2f - 1f) * MapData.LAND_HALF_Z
                if (!spotOk(x, z) || !free(x, z, 4f)) continue
                add(x, z, 8); bushes++
                val extra = rr.nextInt(4)
                for (e in 0 until extra) {
                    val a = rr.nextFloat() * 6.2832f; val d = 1.5f + rr.nextFloat() * 1.5f
                    val x2 = x + cos(a) * d; val z2 = z + sin(a) * d
                    if (!spotOk(x2, z2) || !free(x2, z2, 1.4f)) continue
                    add(x2, z2, 8); bushes++
                }
            }
        }
        val n = xs.size
        treeCount = n
        tx = FloatArray(n); ty = FloatArray(n); tz = FloatArray(n); tyaw = FloatArray(n); tscl = FloatArray(n); tph = FloatArray(n); tvar = IntArray(n)
        for (i in 0 until n) {
            tx[i] = xs[i]; tz[i] = zs[i]; ty[i] = map.meshY(xs[i], zs[i]) - 0.06f
            tyaw[i] = yw[i]; tscl[i] = sc[i]; tph[i] = ph[i]; tvar[i] = vr[i]
        }
        // spatial cells for the per-frame lookup
        val lists = Array(GN * GN) { ArrayList<Int>() }
        for (i in 0 until n) {
            val cx = ((tx[i] + 2560f) / CELL).toInt().coerceIn(0, GN - 1); val cz = ((tz[i] + 2560f) / CELL).toInt().coerceIn(0, GN - 1)
            lists[cz * GN + cx].add(i)
        }
        cells = Array(GN * GN) { k -> if (lists[k].isEmpty()) null else lists[k].toIntArray() }
        // v63: tree trunks are SOLID (before, you walked straight through every tree). Radius = the drawn trunk radius of the type x tree scale (pine: 0.25 m), at least 0.22 m.
        val cr = FloatArray(n) { i ->
            val tc = TYPES[(tvar[i] / 2).coerceIn(0, TYPES.size - 1)]
            when (tc.kind) {
                1 -> 0.72f * tscl[i]   // v64 boulder: solid, the cone base is 0.9 m, the visible mass about 0.75 m
                2 -> 0.42f * tscl[i]   // v64 shrub: the thick core stops you, the outer leaves you brush through
                else -> max(0.22f, (if (tc.pine) 0.25f else tc.r) * tscl[i])
            }
        }
        map.setCircleColliders(tx.copyOf(), tz.copyOf(), cr)
    }

    // ------------------------------------------------------------------ GL
    private fun makeTex(pine: Boolean): Int {
        val bmp = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (!pine) {
            p.shader = LinearGradient(0f, 95f, 0f, 0f, Color.rgb(189, 189, 189), Color.WHITE, Shader.TileMode.CLAMP)
            val path = Path(); path.moveTo(32f, 95f); path.cubicTo(0f, 70f, 2f, 25f, 32f, 2f); path.cubicTo(62f, 25f, 64f, 70f, 32f, 95f); path.close()
            c.drawPath(path, p)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.rgb(196, 196, 196)
            c.drawLine(32f, 95f, 32f, 8f, p)
            for (i in 0 until 5) { val y = 80f - i * 14f; c.drawLine(32f, y, 14f, y - 14f, p); c.drawLine(32f, y, 50f, y - 14f, p) }
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = Color.WHITE; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 12) {
                val a = (i / 11f - .5f) * 1.1f
                c.drawLine(32f, 94f, 32f + sin(a) * 58f, 94f - cos(a) * 90f, p)
            }
        }
        val id = IntArray(1); GL.glGenTextures(1, id, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun meshVao(verts: FloatArray, idx: IntArray, inst: Int): Int {
        val vao = IntArray(1); GL.glGenVertexArrays(1, vao, 0); GL.glBindVertexArray(vao[0])
        val b = IntArray(2); GL.glGenBuffers(2, b, 0)
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()); vb.asFloatBuffer().put(verts)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, b[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, verts.size * 4, vb, GL.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()); ib.asIntBuffer().put(idx)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, b[1]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GL.GL_STATIC_DRAW)
        val st = TS * 4
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 3, GL.GL_FLOAT, false, st, 32)
        GL.glEnableVertexAttribArray(4); GL.glVertexAttribPointer(4, 1, GL.GL_FLOAT, false, st, 44)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, inst)
        GL.glEnableVertexAttribArray(5); GL.glVertexAttribPointer(5, 4, GL.GL_FLOAT, false, 32, 0); GL.glVertexAttribDivisor(5, 1)
        GL.glEnableVertexAttribArray(6); GL.glVertexAttribPointer(6, 4, GL.GL_FLOAT, false, 32, 16); GL.glVertexAttribDivisor(6, 1)
        GL.glBindVertexArray(0)
        return vao[0]
    }

    /** call from onSurfaceCreated (GL thread); safe to call again after the GL context was lost */
    fun initGl() {
        gl.clear()
        leafTex = makeTex(false); needleTex = makeTex(true)
        instBuf = ByteBuffer.allocateDirect(MAXI * 32).order(ByteOrder.nativeOrder())
        instData = Array(variants.size) { FloatArray(MAXI * 8) }
        instCnt = IntArray(variants.size)
        for (v in variants) {
            val ib = IntArray(1); GL.glGenBuffers(1, ib, 0)
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER, ib[0])
            GL.glBufferData(GL.GL_ARRAY_BUFFER, MAXI * 32, ByteBuffer.allocateDirect(MAXI * 32).order(ByteOrder.nativeOrder()), GL.GL_DYNAMIC_DRAW)
            val bv = meshVao(v.bark, v.barkIdx, ib[0])
            val lv = if (v.leafIdx.isNotEmpty()) meshVao(v.leaf, v.leafIdx, ib[0]) else 0
            gl.add(GlVar(bv, v.barkIdx.size, lv, v.leafIdx.size, ib[0]))
        }
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
        glReady = true
    }

    /**
     * Draw every tree within [range] m of the camera (cx, cz) that is not behind it (fx, fz = horizontal view direction).
     * The caller sets uTreeOn = 1 and the wind uniforms before; texture unit 0 is used for the leaf cards.
     */
    fun draw(uMode: Int, cx: Float, cz: Float, fx: Float, fz: Float, range: Float) {
        if (!glReady || treeCount == 0) return
        java.util.Arrays.fill(instCnt, 0)
        val gx0 = ((cx - range + 2560f) / CELL).toInt().coerceIn(0, GN - 1); val gx1 = ((cx + range + 2560f) / CELL).toInt().coerceIn(0, GN - 1)
        val gz0 = ((cz - range + 2560f) / CELL).toInt().coerceIn(0, GN - 1); val gz1 = ((cz + range + 2560f) / CELL).toInt().coerceIn(0, GN - 1)
        val r2 = range * range
        val fl = sqrt(fx * fx + fz * fz); val useDir = fl > 0.35f
        val ux = if (useDir) fx / fl else 0f; val uz = if (useDir) fz / fl else 0f
        for (gz in gz0..gz1) for (gx in gx0..gx1) {
            val l = cells[gz * GN + gx] ?: continue
            for (i in l) {
                val dx = tx[i] - cx; val dz = tz[i] - cz
                val d2 = dx * dx + dz * dz
                if (d2 > r2) continue
                if (useDir && d2 > 400f && dx * ux + dz * uz < -14f) continue
                val v = tvar[i]; val c = instCnt[v]
                if (c >= MAXI) continue
                val a = instData[v]; val o = c * 8
                a[o] = tx[i]; a[o + 1] = ty[i]; a[o + 2] = tz[i]; a[o + 3] = tyaw[i]; a[o + 4] = tscl[i]; a[o + 5] = tph[i]; a[o + 6] = 0f; a[o + 7] = 0f
                instCnt[v] = c + 1
            }
        }
        val bb = instBuf ?: return
        for (v in variants.indices) {
            val c = instCnt[v]; if (c == 0) continue
            bb.clear(); bb.asFloatBuffer().put(instData[v], 0, c * 8)
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER, gl[v].inst)
            GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, c * 32, bb)
        }
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
        // bark + branches (vertex colour)
        GL.glUniform1i(uMode, 8)
        for (v in variants.indices) {
            val c = instCnt[v]; if (c == 0) continue
            GL.glBindVertexArray(gl[v].barkVao); GL.glDrawElementsInstanced(GL.GL_TRIANGLES, gl[v].barkCnt, GL.GL_UNSIGNED_INT, 0, c)
        }
        // leaves: alpha-tested cards
        GL.glUniform1i(uMode, 7); GL.glActiveTexture(GL.GL_TEXTURE0)
        for (pass in 0..1) {
            GL.glBindTexture(GL.GL_TEXTURE_2D, if (pass == 0) leafTex else needleTex)
            for (v in variants.indices) {
                if (variants[v].pine != (pass == 1)) continue
                val c = instCnt[v]; if (c == 0 || gl[v].leafVao == 0) continue
                GL.glBindVertexArray(gl[v].leafVao); GL.glDrawElementsInstanced(GL.GL_TRIANGLES, gl[v].leafCnt, GL.GL_UNSIGNED_INT, 0, c)
            }
        }
        GL.glBindVertexArray(0)
    }
}

// ====================================================================================================================
//  WEATHER: sunny -> rain -> storm -> rain -> sunny ... (automatic). Wind / rain / cloud follow the targets smoothly.
// ====================================================================================================================
class WeatherSystem {
    /** 0.4 = light breeze, 0.8 rain, 1.7 storm */
    var wind = 0.4f; private set
    /** 0 = none, 1 = rain, 1.7 = storm rain */
    var rain = 0f; private set
    /** 0 = clear sun, 1 = black storm sky */
    var cloud = 0f; private set
    var wet = 0f; private set
    /** v57: 0..1 water standing in puddles / hollows. Fills while it rains (a storm faster), dries out slowly in the sun. */
    var pud = 0f; private set
    var flash = 0f; private set
    var time = 0f; private set
    var skyR = 0.74f; private set
    var skyG = 0.84f; private set
    var skyB = 0.93f; private set

    // duration (s), wind, rain, cloud
    private val PH = arrayOf(
        floatArrayOf(170f, 0.4f, 0f, 0f),     // sunshine
        floatArrayOf(120f, 0.8f, 1f, 0.7f),   // rain
        floatArrayOf(95f, 1.7f, 1.7f, 1f),    // storm (strong wind + lightning)
        floatArrayOf(45f, 0.8f, 1f, 0.7f),    // rain easing
        floatArrayOf(150f, 0.5f, 0f, 0.1f)    // sun again
    )
    private var phase = 0; private var phaseT = 0f
    private var nextFlash = 5f; private var pulse2 = -1f
    private val rnd = Random(91)

    val sunDim: Float get() = 0.82f * cloud
    val hemiDim: Float get() = 0.35f * cloud
    /** multiplier of the fog distance on the ground (rain / storm shorten the view) */
    val fogMul: Float get() = 1f - 0.55f * cloud

    fun update(dt: Float) {
        time += dt; phaseT += dt
        if (phaseT > PH[phase][0]) { phaseT = 0f; phase = (phase + 1) % PH.size }
        val p = PH[phase]; val k = min(1f, dt * 0.35f)
        wind += (p[1] - wind) * k; rain += (p[2] - rain) * k; cloud += (p[3] - cloud) * k
        wet += dt * (0.18f * min(rain, 1.2f) - (if (rain < 0.1f) 0.025f * (1f - cloud) else 0f) - 0.004f)
        wet = wet.coerceIn(0f, 1f)
        pud += dt * (0.012f * min(rain, 1.7f) - (if (rain < 0.1f) 0.0035f * (1f - 0.5f * cloud) else 0f))
        pud = pud.coerceIn(0f, 1f)
        if (rain > 1.3f) {
            nextFlash -= dt
            if (nextFlash <= 0f) { flash = 1f; nextFlash = 3f + rnd.nextFloat() * 9f; pulse2 = 0.16f }
        }
        if (pulse2 > 0f) { pulse2 -= dt; if (pulse2 <= 0f) flash = max(flash, 0.8f) }
        flash *= exp(-dt * 9f)
        val c = cloud * 0.85f
        var r = 0.74f + (0.27f - 0.74f) * c; var g = 0.84f + (0.30f - 0.84f) * c; var b = 0.93f + (0.35f - 0.93f) * c
        val f = min(1f, flash) * 0.8f
        r += (0.87f - r) * f; g += (0.91f - g) * f; b += (1.0f - b) * f
        skyR = r; skyG = g; skyB = b
    }
}

// ====================================================================================================================
//  RAIN: thin lines in a box around the camera.
// ====================================================================================================================
class RainFx {
    private val N = 1500
    private val p = FloatArray(N * 3)
    private val ln = FloatArray(N * 6)
    private var vbo = 0
    var vao = 0; private set
    var vertexCount = 0; private set
    private var bb: ByteBuffer? = null
    private val rnd = Random(5)
    private var seeded = false

    fun init() {
        val v = IntArray(1); GL.glGenVertexArrays(1, v, 0); vao = v[0]; GL.glBindVertexArray(vao)
        val b = IntArray(1); GL.glGenBuffers(1, b, 0); vbo = b[0]
        bb = ByteBuffer.allocateDirect(N * 24).order(ByteOrder.nativeOrder())
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo); GL.glBufferData(GL.GL_ARRAY_BUFFER, N * 24, bb, GL.GL_DYNAMIC_DRAW)
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 12, 0)
        GL.glBindVertexArray(0)
    }

    /** [map] (optional): drops that are under a roof are not drawn, so it never rains inside a house (v53). */
    fun update(cx: Float, cy: Float, cz: Float, dt: Float, wind: Float, rain: Float, map: MapData? = null) {
        val half = 22f; val hgt = 26f
        if (!seeded) { for (i in 0 until N) { p[i * 3] = cx + (rnd.nextFloat() * 2f - 1f) * half; p[i * 3 + 1] = cy + rnd.nextFloat() * hgt - 6f; p[i * 3 + 2] = cz + (rnd.nextFloat() * 2f - 1f) * half }; seeded = true }
        val n = (N * min(1f, rain)).toInt()
        val vx = 2f + wind * 3.5f; val vy = -24f - rain * 5f
        map?.rainPrepare(cx, cz, half + 4f)
        var m = 0   // drops actually drawn
        for (i in 0 until n) {
            var x = p[i * 3] + vx * dt; var y = p[i * 3 + 1] + vy * dt; var z = p[i * 3 + 2]
            var dx = x - cx; dx -= 2f * half * floor((dx + half) / (2f * half)); x = cx + dx
            var dz = z - cz; dz -= 2f * half * floor((dz + half) / (2f * half)); z = cz + dz
            var dy = y - (cy - 6f); dy -= hgt * floor(dy / hgt); y = cy - 6f + dy
            p[i * 3] = x; p[i * 3 + 1] = y; p[i * 3 + 2] = z
            if (map != null && map.roofOver(x, y, z)) continue   // under a roof: no rain here
            val o = m * 6; m++
            ln[o] = x; ln[o + 1] = y; ln[o + 2] = z
            ln[o + 3] = x - vx * 0.03f; ln[o + 4] = y - vy * 0.03f; ln[o + 5] = z
        }
        vertexCount = m * 2
        val buf = bb ?: return
        if (m > 0) {
            buf.clear(); buf.asFloatBuffer().put(ln, 0, m * 6)
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo); GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, m * 24, buf); GL.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
        }
    }
}
