package com.bijnor.game

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BIJNOR MAP (4561 m wide x 3925 m high, one flat green plain with sea all around and every road of the Bijnor map file).
 *
 * Source: Bijnor_Map_With_Roads_v24.html (land outline "ring", the 3 road layers, the road connectors and the 32 place names).
 * Nothing else was added to the map: no houses, no trees, no rocks, no bridges (the map file has none either).
 * The numbers are baked by tools/make_bijnor.py into assets/maps/bijnor.bin (height grid 801 x 801, cell 6.4 m, ring, roads).
 *
 * The map ENGINE that the rest of the game talks to is unchanged: ground / walkAt / terrainBlocked, boxesAt / resolve / rayWorld,
 * waterDepth / swimSpeed / swimFloor, houseSpots / randomLand / onMainland / spawn. World x / z are in metres, north = -z, the centre of
 * the map is (0, 0).
 */
class MapData(ctx: Context, progress: (Float) -> Unit) {
    class Group(val mat: String, val verts: FloatArray, val indices: IntArray) {
        /** First vertex index that was added later (moved geometry); Int.MAX_VALUE = none. */
        @JvmField var movedFrom = Int.MAX_VALUE
    }
    class Box(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float, val minZ: Float, val maxZ: Float) { @JvmField var stamp = 0; @JvmField var dyn: Dyn? = null }
    /** v30 moving parts of the houses (houses.bin "BJD1"): a DOOR (leaf hinged at hx,hz; c = direction when shut, o = direction when open) or a glass PANE that a bullet breaks */
    class Dyn(@JvmField val door: Boolean, @JvmField val box: Box, @JvmField val hx: Float, @JvmField val hz: Float, @JvmField val cx: Float, @JvmField val cz: Float,
              @JvmField val ox: Float, @JvmField val oz: Float, @JvmField val r: Float, @JvmField val g: Float, @JvmField val b: Float) {
        @JvmField var a = 0f; @JvmField var tgt = 0f; @JvmField var solid = true; @JvmField var broken = false; @JvmField var len = 0f
        /** v37 who controls the door: 0 = bots (auto), 1 = the player opened it with the DOOR button, 2 = the player shut / barred it from inside */
        @JvmField var pm = 0
    }
    /** One road layer of the map file: vertices x,z in metres and the triangle list. lvl = drawing order (1 = lowest). */
    class RoadLayer(val lvl: Int, val xz: FloatArray, val idx: IntArray)
    private class Raw(val n: Int, val cell: Float, val x0: Float, val spawn: FloatArray, val ring: FloatArray,
                      val layers: List<RoadLayer>, val conn: FloatArray, val gh: FloatArray)

    companion object {
        const val MAPK = 4f / 3f
        const val STRIDE = 11 // pos3 nor3 uv2 col3
        /**
         * v48 PLAYER SIZE = the size of the houses. Measured in houses.bin: doors 2.14 m (7 ft), window sill 0.7 m (hip), glass 0.7 - 2.05 m, ceiling 2.95 m.
         * A person of 1.78 m (5 ft 10, the 5.5 - 6 ft of a Free Fire character) is 83 % of the door and a ceiling is 1.65 x his height. The old 0.75 made him 1.29 m (tiny).
         */
        const val PLAYER_K = 1.0f
        /** Height of a person (m): model, bots and the player. */
        const val PERSON_H = 1.78f
        /** Head clearance of a standing body (m) used for ceilings / door lintels: below the 2.14 m doors, above the head. */
        const val BODY_H = 1.85f * PLAYER_K
        /** Eye height of a standing player (m): 12 cm under the top of the head. */
        const val EYE_H = 1.66f * PLAYER_K
        /** Water depth (m) of the open sea: deep enough to swim. */
        const val OPEN_SEA_DEPTH = 7.7f
        /** Land outline: width 4561 m (x -2280.5 .. 2280.5), height 3925 m (z -1962.5 .. 1962.5). */
        const val LAND_HALF_X = 2280f
        const val LAND_HALF_Z = 1962f
        /** Farthest land point from the centre (m). */
        const val ISLAND_R = 2383f
        /** A player landing farther than this from the centre is moved back to land. */
        const val LAND_LIMIT = ISLAND_R + 150f
        /** Only limit of the open sea: a safety stop so nobody swims off to infinity. */
        const val FAR_LIMIT = ISLAND_R + 900f
        /** Width of the land (m) for the loading text / credits. */
        const val MAP_WIDTH_M = 4561

        /** Slab test, no allocations. Returns entry distance (>=0) or -1. */
        fun rayBox(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                   x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, maxD: Float): Float {
            var t0 = 0f; var t1 = maxD
            // X
            if (kotlin.math.abs(dx) < 1e-8f) { if (ox < x0 || ox > x1) return -1f }
            else { var a = (x0 - ox) / dx; var b = (x1 - ox) / dx; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return -1f }
            // Y
            if (kotlin.math.abs(dy) < 1e-8f) { if (oy < y0 || oy > y1) return -1f }
            else { var a = (y0 - oy) / dy; var b = (y1 - oy) / dy; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return -1f }
            // Z
            if (kotlin.math.abs(dz) < 1e-8f) { if (oz < z0 || oz > z1) return -1f }
            else { var a = (z0 - oz) / dz; var b = (z1 - oz) / dz; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return -1f }
            return t0
        }

        /** v63: ray vs a person: an upright ROUND (elliptic) cylinder, centre (cx,cz), radii rx / rz, from y0 to y1 - a body is round, not a square box (corner shots no longer hit empty air). Returns entry distance (>=0) or -1. */
        fun rayBody(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                    cx: Float, cz: Float, rx: Float, rz: Float, y0: Float, y1: Float, maxD: Float): Float {
            var lo = 0f; var hi = maxD
            if (kotlin.math.abs(dy) < 1e-8f) { if (oy < y0 || oy > y1) return -1f }
            else { var a = (y0 - oy) / dy; var b = (y1 - oy) / dy; if (a > b) { val t = a; a = b; b = t }; if (a > lo) lo = a; if (b < hi) hi = b; if (lo > hi) return -1f }
            val px = (ox - cx) / rx; val pz = (oz - cz) / rz; val vx = dx / rx; val vz = dz / rz
            val qa = vx * vx + vz * vz; val qc = px * px + pz * pz - 1f
            if (qa < 1e-10f) return if (qc <= 0f) lo else -1f
            val qb = 2f * (px * vx + pz * vz)
            val disc = qb * qb - 4f * qa * qc
            if (disc < 0f) return -1f
            val sq = kotlin.math.sqrt(disc)
            val ta = (-qb - sq) / (2f * qa); val tb = (-qb + sq) / (2f * qa)
            if (ta > lo) lo = ta; if (tb < hi) hi = tb
            return if (lo > hi) -1f else lo
        }

        private fun sst(a: Float, b: Float, x: Float): Float { var t = (x - a) / (b - a); t = if (t < 0f) 0f else if (t > 1f) 1f else t; return t * t * (3f - 2f * t) }
        private fun hash(i: Int, j: Int, s: Int): Float {
            var h = i * 374761393 + j * 668265263 + s * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h ushr 1).toFloat() / 2147483648f
        }
        private fun vnoise(x: Float, z: Float, s: Int): Float {
            val i = kotlin.math.floor(x).toInt(); val j = kotlin.math.floor(z).toInt(); val fx = x - i; val fz = z - j
            val u = fx * fx * (3f - 2f * fx); val v = fz * fz * (3f - 2f * fz)
            return (hash(i, j, s) * (1f - u) + hash(i + 1, j, s) * u) * (1f - v) + (hash(i, j + 1, s) * (1f - u) + hash(i + 1, j + 1, s) * u) * v
        }
        private fun fbm(x: Float, z: Float, s: Int): Float = vnoise(x, z, s) * .55f + vnoise(x * 2.1f, z * 2.1f, s + 1) * .28f + vnoise(x * 4.3f, z * 4.3f, s + 2) * .17f

        /** assets/maps/bijnor.bin (little endian): "BJN1", n, cell, x0, spawn xyz, islandR, ring, road layers, connectors, height grid (cm, int16). */
        private fun readBin(ctx: Context): Raw {
            val bytes = ctx.assets.open("maps/bijnor.bin").use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); bb.get(magic)
            if (String(magic, Charsets.US_ASCII) != "BJN1") throw RuntimeException("maps/bijnor.bin is not a Bijnor map file")
            val n = bb.getInt(); val cell = bb.getFloat(); val x0 = bb.getFloat()
            val sx = bb.getFloat(); val sy = bb.getFloat(); val sz = bb.getFloat(); bb.getFloat()   // spawn, island radius (constant ISLAND_R)
            val nr = bb.getInt(); val ring = FloatArray(nr * 2); for (q in ring.indices) ring[q] = bb.getFloat()
            val nl = bb.getInt(); val layers = ArrayList<RoadLayer>()
            for (l in 0 until nl) {
                val lvl = bb.getInt(); val nv = bb.getInt(); val ni = bb.getInt()
                val xz = FloatArray(nv * 2); for (q in xz.indices) xz[q] = bb.getShort() / 10f
                val idx = IntArray(ni); for (q in idx.indices) idx[q] = bb.getShort().toInt() and 0xFFFF
                layers.add(RoadLayer(lvl, xz, idx))
            }
            val nc = bb.getInt(); val conn = FloatArray(nc * 5); for (q in conn.indices) conn[q] = bb.getFloat()
            val gh = FloatArray(n * n); for (q in gh.indices) gh[q] = bb.getShort() / 100f
            return Raw(n, cell, x0, floatArrayOf(sx, sy, sz), ring, layers, conn, gh)
        }
    }

    private val raw: Raw = readBin(ctx)

    /** Render meshes of the map (material name + vertices + indices): "terrain" (the land) and "road". The sea is drawn by the renderer. */
    val groups = ArrayList<Group>()
    /** Collision boxes (the Bijnor map has none). */
    val blockers = ArrayList<Box>()
    /** Colour of the open sea. */
    val seaColor: Int = android.graphics.Color.parseColor("#1664B2")
    /** Height grid: n x n cells, [cell] metres apart, the first cell sits at [x0] (both x and z). */
    val n: Int = raw.n
    val cell: Float = raw.cell
    val x0: Float = raw.x0
    private val gh: FloatArray = raw.gh
    private val walk: ByteArray = ByteArray(n * n)
    val spawn = floatArrayOf(raw.spawn[0], raw.spawn[1], raw.spawn[2])
    /** Land outline x,z pairs (m) and the road data, for the minimap / big map picture. */
    val ringXZ: FloatArray = raw.ring
    val roadLayers: List<RoadLayer> = raw.layers
    /** Road connectors: x1 z1 x2 z2 width (5 floats each). */
    val connXZ: FloatArray = raw.conn
    /** Collision boxes in a 24 m grid: every box sits in all cells within 4 m of it. */
    private val BC = 24f
    private var bgN = 0
    private var bgrid: Array<ArrayList<Box>?> = arrayOfNulls(0)
    private var stampNow = 0
    /** Water level grid: -999 = dry, else the surface height. */
    private var wl = FloatArray(0)
    /** 1 = walkable cell that belongs to the main land (empty = everything counts as main land). */
    private var mainland = ByteArray(0)
    /** Road raster (2.5 m cells over the whole grid): 1 = asphalt. Loot is never placed on it. */
    private val ROAD_CELL = 2.5f
    private var roadN = 0
    private var roadMask = ByteArray(0)
    /** Enterable houses of the map: x, z, width, depth, ground y. The Bijnor map has none. */
    val extraHouses = ArrayList<FloatArray>()
    /** Footprints of every house a player can walk into: cx, cz, width, depth, 1 = inside reachable. The Bijnor map has none, so ground loot lies in the open. */
    val houseSpots = ArrayList<FloatArray>()
    /** The 7 enterable Bijnor houses (assets/models/houses/houses.bin): footprint box, ground y and the floor / stair / roof surfaces. */
    class House(@JvmField val minX: Float, @JvmField val maxX: Float, @JvmField val minZ: Float, @JvmField val maxZ: Float, @JvmField val y0: Float,
                @JvmField val surf: Array<FloatArray>)
    val houses = ArrayList<House>()
    /** v54 instanced buildings (houses.bin "BJI1"): every building model is stored once (Tpl, local coords) and placed many times (Inst). inst i belongs to houses[i]. */
    class Tpl(@JvmField val verts: FloatArray, @JvmField val idx: IntArray)
    class Inst(@JvmField val tpl: Int, @JvmField val face: Int, @JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float, @JvmField val rad: Float)
    val tpls = ArrayList<Tpl>()
    val insts = ArrayList<Inst>()
    /** v53: roof raster per house for the rain (see loadRoofs). Declared BEFORE init{} on purpose: init runs the property initialisers in order. */
    private class Roof(@JvmField val ox: Float, @JvmField val oz: Float, @JvmField val w: Int, @JvmField val d: Int, @JvmField val y0: Float, @JvmField val top: ByteArray)
    private var roofs: Array<Roof?> = arrayOfNulls(0)
    /** v57: real building boxes for the minimap / big map, 5 floats each: minX, minZ, maxX, maxZ, roof height (m). One entry per house / shop / school ... (houses.bin order). */
    var bldg: FloatArray = FloatArray(0); private set
    /** v57: the colour of every terrain mesh vertex (m x m, 3 floats each, vertex spacing 12.8 m) - the satellite-style map is painted from it. */
    var terrCol: FloatArray = FloatArray(0); private set
    var terrM = 0; private set
    private val rainNear = IntArray(128)
    private var rainNearN = 0
    val dyn = ArrayList<Dyn>()
    @JvmField var onDoor: ((Dyn) -> Unit)? = null
    /** Loot points INSIDE the houses (x, y, z) on every floor. Kept apart from houseSpots so the open-ground loot stays in the open. */
    val homeLoot = ArrayList<FloatArray>()

    init {
        progress(0.05f)
        for (i in 0 until n * n) walk[i] = if (gh[i] > 0.02f) 1 else 0
        progress(0.15f)
        buildTerrain()
        progress(0.45f)
        buildRoads()
        try { buildRoadLine(ctx) } catch (e: Exception) { android.util.Log.w("MapData", "roadline.bin not loaded: " + e) }
        try { loadHouses(ctx) } catch (e: Exception) { houses.clear(); homeLoot.clear(); tpls.clear(); insts.clear(); android.util.Log.w("MapData", "houses.bin not loaded: " + e) }
        try { loadRoofs(ctx) } catch (e: Exception) { roofs = arrayOfNulls(0); android.util.Log.w("MapData", "roofs.bin not loaded: " + e) }
        if (bldg.isEmpty() && houses.isNotEmpty()) buildBldgFromHouses()
        progress(0.6f)
        buildRoadMask()
        progress(0.85f)
        buildGrid()
        buildWater()
        progress(1f)
    }

    // ---- meshes

    /** The land: a grid mesh with a vertex every 2 cells (12.8 m). Quads that lie deep under the sea are left out (the sea hides them anyway). */
    private fun buildTerrain() {
        val st = 2; val m = (n - 1) / st + 1
        val v = FloatArray(m * m * STRIDE)
        val plain = floatArrayOf(.60f, .56f, .31f); val rich = floatArrayOf(.45f, .46f, .24f)   // v57: dry olive grass like Free Fire (was bright green .345 .678 .247)
        val dirt = floatArrayOf(.53f, .43f, .30f); val tan = floatArrayOf(.70f, .62f, .40f)
        val sand = floatArrayOf(.77f, .70f, .49f); val deep = floatArrayOf(.345f, .50f, .44f)
        for (kk in 0 until m) for (ii in 0 until m) {
            val i = min(n - 1, ii * st); val k = min(n - 1, kk * st)
            val x = x0 + i * cell; val z = x0 + k * cell
            val h = gh[k * n + i]
            val i0 = max(0, i - st); val i1 = min(n - 1, i + st); val k0 = max(0, k - st); val k1 = min(n - 1, k + st)
            val dx = (gh[k * n + i1] - gh[k * n + i0]) / ((i1 - i0) * cell)
            val dz = (gh[k1 * n + i] - gh[k0 * n + i]) / ((k1 - k0) * cell)
            val l = sqrt(dx * dx + 1f + dz * dz)
            val o = (kk * m + ii) * STRIDE
            v[o] = x; v[o + 1] = h; v[o + 2] = z
            v[o + 3] = -dx / l; v[o + 4] = 1f / l; v[o + 5] = -dz / l
            v[o + 6] = x / 24f; v[o + 7] = z / 24f
            val r: Float; val g: Float; val b: Float
            if (h <= 0.05f) {
                val t = sst(0f, 10f, -h)
                r = sand[0] + (deep[0] - sand[0]) * t; g = sand[1] + (deep[1] - sand[1]) * t; b = sand[2] + (deep[2] - sand[2]) * t
            } else {
                val n1 = fbm(x * .02f, z * .02f, 11); val n2 = fbm(x * .09f, z * .09f, 12)
                val t = sst(.25f, .75f, n1); val mul = (.9f + .2f * n2) * 0.92f
                var pr = plain[0] + (rich[0] - plain[0]) * t; var pg = plain[1] + (rich[1] - plain[1]) * t; var pb = plain[2] + (rich[2] - plain[2]) * t
                val dm = sst(.56f, .72f, fbm(x * .035f, z * .035f, 21)) * 0.85f       // bare dirt patches
                pr += (dirt[0] - pr) * dm; pg += (dirt[1] - pg) * dm; pb += (dirt[2] - pb) * dm
                val tm = sst(.55f, .78f, fbm(x * .012f, z * .012f, 31)) * 0.65f       // sun-bleached tan fields
                pr += (tan[0] - pr) * tm; pg += (tan[1] - pg) * tm; pb += (tan[2] - pb) * tm
                r = pr * mul; g = pg * mul; b = pb * mul
            }
            // v54: the colour follows the HEIGHT (v53 only added a faint yellow): hollows (< 2.4 m) are darker and lusher, hill tops dry olive -> tan, steep sides earthy.
            // 0 change on the flat 2.4 m land (houses, roads, villages), the beach and the sea.
            val dry = sst(2.8f, 7.5f, h)                       // 0 .. 1 from the plain up to the hill tops (v57: hills now reach ~10 m)
            val wet = sst(2.3f, 1.5f, h)                       // 0 .. 1 from the plain down into the hollows
            val slp = sst(0.012f, 0.07f, sqrt(dx * dx + dz * dz))
            var cr = r; var cg = g; var cb = b
            if (h > 0.05f) {
                cr = cr * (1f - 0.22f * wet) ; cg = cg * (1f - 0.30f * wet); cb = cb * (1f - 0.20f * wet)          // damp, darker hollow
                cr += (0.68f - cr) * 0.55f * dry; cg += (0.61f - cg) * 0.55f * dry; cb += (0.42f - cb) * 0.55f * dry   // sun-dried tan top
                cr += (0.50f - cr) * 0.55f * slp; cg += (0.46f - cg) * 0.55f * slp; cb += (0.40f - cb) * 0.55f * slp   // grey-brown rock / earth on the slopes
            }
            v[o + 8] = cr; v[o + 9] = cg; v[o + 10] = cb
        }
        var cnt = 0
        val idx = IntArray((m - 1) * (m - 1) * 6)
        for (kk in 0 until m - 1) for (ii in 0 until m - 1) {
            val a = kk * m + ii; val b = a + 1; val c = a + m; val d = c + 1
            val top = max(max(v[a * STRIDE + 1], v[b * STRIDE + 1]), max(v[c * STRIDE + 1], v[d * STRIDE + 1]))
            if (top < -0.6f) continue
            idx[cnt++] = a; idx[cnt++] = c; idx[cnt++] = b; idx[cnt++] = b; idx[cnt++] = c; idx[cnt++] = d
        }
        val tc = FloatArray(m * m * 3)
        for (q in 0 until m * m) { tc[q * 3] = v[q * STRIDE + 8]; tc[q * 3 + 1] = v[q * STRIDE + 9]; tc[q * 3 + 2] = v[q * STRIDE + 10] }
        terrCol = tc; terrM = m
        groups.add(Group("terrain", v, idx.copyOf(cnt)))
    }

    /** All roads of the map file (flat black asphalt a few cm above the ground) + the connectors that close gaps / dead ends. */
    private fun buildRoads() {
        for (r in roadLayers) {
            val nv = r.xz.size / 2
            val v = FloatArray(nv * STRIDE)
            for (q in 0 until nv) {
                val x = r.xz[q * 2]; val z = r.xz[q * 2 + 1]; val o = q * STRIDE
                v[o] = x; v[o + 1] = meshY(x, z) + 0.07f + r.lvl * 0.004f; v[o + 2] = z
                v[o + 4] = 1f; v[o + 8] = 1f; v[o + 9] = 1f; v[o + 10] = 1f
            }
            groups.add(Group("road", v, r.idx))
        }
        val nc = connXZ.size / 5
        if (nc > 0) {
            val v = FloatArray(nc * 4 * STRIDE); val ix = IntArray(nc * 6)
            for (q in 0 until nc) {
                val x1 = connXZ[q * 5]; val z1 = connXZ[q * 5 + 1]; val x2 = connXZ[q * 5 + 2]; val z2 = connXZ[q * 5 + 3]; val w = connXZ[q * 5 + 4]
                var dx = x2 - x1; var dz = z2 - z1; val l = sqrt(dx * dx + dz * dz).let { if (it < 1e-4f) 1f else it }
                dx /= l; dz /= l
                val ex = dx * 2.5f; val ez = dz * 2.5f; val nx = -dz * w / 2f; val nz = dx * w / 2f
                val px = floatArrayOf(x1 - ex + nx, x1 - ex - nx, x2 + ex + nx, x2 + ex - nx)
                val pz = floatArrayOf(z1 - ez + nz, z1 - ez - nz, z2 + ez + nz, z2 + ez - nz)
                for (c in 0 until 4) {
                    val o = (q * 4 + c) * STRIDE
                    v[o] = px[c]; v[o + 1] = meshY(px[c], pz[c]) + 0.075f; v[o + 2] = pz[c]
                    v[o + 4] = 1f; v[o + 8] = 1f; v[o + 9] = 1f; v[o + 10] = 1f
                }
                val b = q * 4; val j = q * 6
                ix[j] = b; ix[j + 1] = b + 1; ix[j + 2] = b + 2; ix[j + 3] = b + 1; ix[j + 4] = b + 3; ix[j + 5] = b + 2
            }
            groups.add(Group("road", v, ix))
        }
    }

    /** v57: white dashed centre line on every road (assets/maps/roadline.bin from tools/make_roadline.py: x1 z1 x2 z2 per dash). Material "roadline". */
    private fun buildRoadLine(ctx: Context) {
        val bytes = ctx.assets.open("maps/roadline.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); bb.get(magic)
        if (String(magic, Charsets.US_ASCII) != "BJL1") return
        val nd = bb.getInt()
        if (nd <= 0 || nd > 200000) return
        val v = FloatArray(nd * 4 * STRIDE); val ix = IntArray(nd * 6)
        for (q in 0 until nd) {
            val x1 = bb.getFloat(); val z1 = bb.getFloat(); val x2 = bb.getFloat(); val z2 = bb.getFloat()
            var dx = x2 - x1; var dz = z2 - z1; val l = sqrt(dx * dx + dz * dz).let { if (it < 1e-4f) 1f else it }
            dx /= l; dz /= l
            val nx = -dz * 0.18f; val nz = dx * 0.18f
            val px = floatArrayOf(x1 + nx, x1 - nx, x2 + nx, x2 - nx)
            val pz = floatArrayOf(z1 + nz, z1 - nz, z2 + nz, z2 - nz)
            for (c in 0 until 4) {
                val o = (q * 4 + c) * STRIDE
                v[o] = px[c]; v[o + 1] = meshY(px[c], pz[c]) + 0.12f; v[o + 2] = pz[c]
                v[o + 4] = 1f; v[o + 8] = 1f; v[o + 9] = 1f; v[o + 10] = 1f
            }
            val b = q * 4; val j = q * 6
            ix[j] = b; ix[j + 1] = b + 1; ix[j + 2] = b + 2; ix[j + 3] = b + 1; ix[j + 4] = b + 3; ix[j + 5] = b + 2
        }
        groups.add(Group("roadline", v, ix))
    }

    /** assets/models/houses/houses.bin (little endian): see tools/houses/make_houses.py. Meshes -> groups (\"house\"), walls / slabs / furniture -> blockers. */
    private fun loadHouses(ctx: Context) {
        val bytes = ctx.assets.open("models/houses/houses.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); bb.get(magic)
        if (String(magic, Charsets.US_ASCII) != "BJH1") return
        val nh = bb.getInt()
        // everything is read into temporary lists first: a damaged file must not leave half a house (meshes without collision) in the map
        val tg = ArrayList<Group>(); val tb = ArrayList<Box>(); val tl = ArrayList<FloatArray>(); val th = ArrayList<House>()
        for (h in 0 until nh) {
            val minX = bb.getFloat(); val maxX = bb.getFloat(); val minZ = bb.getFloat(); val maxZ = bb.getFloat(); val y0 = bb.getFloat()
            bb.getFloat(); bb.getFloat()   // site centre cx, cz
            val nv = bb.getInt(); val ni = bb.getInt(); val nb = bb.getInt(); val ns = bb.getInt(); val nl = bb.getInt()
            val v = FloatArray(nv * STRIDE); for (q in v.indices) v[q] = bb.getFloat()
            val ix = IntArray(ni); for (q in ix.indices) { ix[q] = bb.getInt(); if (ix[q] < 0 || ix[q] >= nv) throw IllegalStateException("bad house index") }
            if (nv > 0) tg.add(Group("house", v, ix))   // v54: nV = 0 = the mesh is a template (BJI1)
            for (q in 0 until nb) {
                val a = bb.getFloat(); val b = bb.getFloat(); val c = bb.getFloat(); val d = bb.getFloat(); val e = bb.getFloat(); val f = bb.getFloat()
                tb.add(Box(a, b, c, d, e, f))
            }
            val sf = Array(ns) { FloatArray(18) { bb.getFloat() } }
            for (q in 0 until nl) { val x = bb.getFloat(); val y = bb.getFloat(); val z = bb.getFloat(); tl.add(floatArrayOf(x, y, z)) }
            th.add(House(minX, maxX, minZ, maxZ, y0, sf))
        }
        val td = ArrayList<Dyn>()
        if (bb.remaining() >= 8) {   // v30: doors + window panes
            val m2 = ByteArray(4); bb.get(m2)
            if (String(m2, Charsets.US_ASCII) == "BJD1") {
                val nd = bb.getInt()
                for (q in 0 until nd) {
                    val f = FloatArray(16) { bb.getFloat() }
                    val bx = Box(f[1], f[2], f[3], f[4], f[5], f[6])
                    val d = Dyn(f[0] > 0.5f, bx, f[7], f[8], f[9], f[10], f[11], f[12], f[13], f[14], f[15])
                    d.len = if (kotlin.math.abs(d.cx) > 0.5f) f[2] - f[1] else f[6] - f[5]
                    bx.dyn = d; td.add(d)
                }
            }
        }
        // v54: instanced building templates + their placements (every instance = one block above, in the same order)
        val tt = ArrayList<Tpl>(); val ti = ArrayList<Inst>()
        if (bb.remaining() >= 8) {
            val m3 = ByteArray(4); bb.get(m3)
            if (String(m3, Charsets.US_ASCII) == "BJI1") {
                val nt = bb.getInt()
                for (q in 0 until nt) {
                    val nv = bb.getInt(); val ni = bb.getInt()
                    val v = FloatArray(nv * STRIDE); for (k in v.indices) v[k] = bb.getFloat()
                    val ix = IntArray(ni); for (k in ix.indices) { ix[k] = bb.getInt(); if (ix[k] < 0 || ix[k] >= nv) throw IllegalStateException("bad template index") }
                    tt.add(Tpl(v, ix))
                }
                val nn = bb.getInt()
                for (q in 0 until nn) {
                    val t = bb.getInt(); val f = bb.getInt(); val x = bb.getFloat(); val y = bb.getFloat(); val z = bb.getFloat(); val r = bb.getFloat()
                    if (t < 0 || t >= nt || f < 0 || f > 3) throw IllegalStateException("bad instance")
                    ti.add(Inst(t, f, x, y, z, r))
                }
            }
        }
        groups.addAll(tg); blockers.addAll(tb); homeLoot.addAll(tl); houses.addAll(th)
        tpls.addAll(tt); insts.addAll(ti)
        for (d in td) blockers.add(d.box)
        dyn.addAll(td)
    }

    // ---- v53 RAIN SHELTER: rain must not fall through roofs. roofs.bin (tools/make_roofs.py) = per house a 0.5 m top-view raster of its highest surface.

    private fun loadRoofs(ctx: Context) {
        val bytes = ctx.assets.open("models/houses/roofs.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); bb.get(magic)
        if (String(magic, Charsets.US_ASCII) != "BJR1") return
        val nh = bb.getInt()
        if (nh != houses.size) return   // must match houses.bin one to one
        val tmp = arrayOfNulls<Roof>(nh)
        for (h in 0 until nh) {
            val ox = bb.getFloat(); val oz = bb.getFloat(); val w = bb.getInt(); val d = bb.getInt()
            val top = ByteArray(w * d); bb.get(top)
            tmp[h] = Roof(ox, oz, w, d, houses[h].y0, top)
        }
        roofs = tmp
        // v57: the building box = the roofed cells of its raster (the raster was grown by 1 m in make_roofs.py, so take 1 m off again)
        val bl = FloatArray(nh * 5)
        for (h in 0 until nh) {
            val q = tmp[h]!!
            var i0 = q.w; var i1 = -1; var k0 = q.d; var k1 = -1; var top = 0
            for (k in 0 until q.d) for (i in 0 until q.w) {
                val v = q.top[k * q.w + i].toInt() and 0xFF
                if (v > 0) { if (i < i0) i0 = i; if (i > i1) i1 = i; if (k < k0) k0 = k; if (k > k1) k1 = k; if (v > top) top = v }
            }
            if (i1 < 0) { i0 = 0; i1 = q.w - 1; k0 = 0; k1 = q.d - 1 }
            var ax = q.ox + i0 * 0.5f + 1f; var bx = q.ox + (i1 + 1) * 0.5f - 1f
            var az = q.oz + k0 * 0.5f + 1f; var bz = q.oz + (k1 + 1) * 0.5f - 1f
            if (bx - ax < 3f) { val c = (ax + bx) * 0.5f; ax = c - 1.5f; bx = c + 1.5f }
            if (bz - az < 3f) { val c = (az + bz) * 0.5f; az = c - 1.5f; bz = c + 1.5f }
            bl[h * 5] = ax; bl[h * 5 + 1] = az; bl[h * 5 + 2] = bx; bl[h * 5 + 3] = bz; bl[h * 5 + 4] = top * 0.1f
        }
        bldg = bl
    }

    /** v57 fallback when roofs.bin is missing: the (bigger) house footprints from houses.bin. */
    private fun buildBldgFromHouses() {
        val bl = FloatArray(houses.size * 5)
        for (h in houses.indices) {
            val q = houses[h]
            bl[h * 5] = q.minX + 1.5f; bl[h * 5 + 1] = q.minZ + 1.5f; bl[h * 5 + 2] = q.maxX - 1.5f; bl[h * 5 + 3] = q.maxZ - 1.5f; bl[h * 5 + 4] = 3f
        }
        bldg = bl
    }

    /** Call once per frame before [roofOver]: remembers the houses within [r] m of (cx, cz). */
    fun rainPrepare(cx: Float, cz: Float, r: Float) {
        rainNearN = 0
        for (h in roofs.indices) {
            val q = roofs[h] ?: continue
            if (q.ox > cx + r || q.ox + q.w * 0.5f < cx - r || q.oz > cz + r || q.oz + q.d * 0.5f < cz - r) continue
            if (rainNearN < rainNear.size) rainNear[rainNearN++] = h
        }
    }

    /** True when a roof / terrace / porch roof is above the point (x, y, z): a raindrop there would fall INSIDE a building. */
    fun roofOver(x: Float, y: Float, z: Float): Boolean {
        for (n in 0 until rainNearN) {
            val q = roofs[rainNear[n]] ?: continue
            val i = Math.floor(((x - q.ox) * 2f).toDouble()).toInt(); val k = Math.floor(((z - q.oz) * 2f).toDouble()).toInt()
            if (i < 0 || k < 0 || i >= q.w || k >= q.d) continue
            val b = q.top[k * q.w + i].toInt() and 0xFF
            if (b > 0 && y < q.y0 + b * 0.1f - 0.05f) return true
        }
        return false
    }

    /** Rasterises every road triangle and connector into [roadMask] so that loot can be kept off the asphalt. */
    private fun buildRoadMask() {
        roadN = Math.ceil((n * cell / ROAD_CELL).toDouble()).toInt() + 1
        val rn = roadN; val m = ByteArray(rn * rn)
        fun mark(i: Int, k: Int) { if (i in 0 until rn && k in 0 until rn) m[k * rn + i] = 1 }
        for (r in roadLayers) {
            val p = r.xz; val ix = r.idx
            var q = 0
            while (q + 2 < ix.size) {
                val ax = p[ix[q] * 2]; val az = p[ix[q] * 2 + 1]; val bx = p[ix[q + 1] * 2]; val bz = p[ix[q + 1] * 2 + 1]; val cx = p[ix[q + 2] * 2]; val cz = p[ix[q + 2] * 2 + 1]
                q += 3
                val den = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz)
                if (kotlin.math.abs(den) < 1e-9f) continue
                val i0 = max(0, kotlin.math.floor((min(ax, min(bx, cx)) - x0) / ROAD_CELL).toInt() - 1); val i1 = min(rn - 1, kotlin.math.ceil((max(ax, max(bx, cx)) - x0) / ROAD_CELL).toInt() + 1)
                val k0 = max(0, kotlin.math.floor((min(az, min(bz, cz)) - x0) / ROAD_CELL).toInt() - 1); val k1 = min(rn - 1, kotlin.math.ceil((max(az, max(bz, cz)) - x0) / ROAD_CELL).toInt() + 1)
                for (k in k0..k1) for (i in i0..i1) {
                    val px = x0 + i * ROAD_CELL; val pz = x0 + k * ROAD_CELL
                    val l1 = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / den
                    val l2 = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / den
                    val l3 = 1f - l1 - l2
                    if (l1 >= -0.25f && l2 >= -0.25f && l3 >= -0.25f) m[k * rn + i] = 1
                }
            }
        }
        val nc = connXZ.size / 5
        for (q in 0 until nc) {
            val x1 = connXZ[q * 5]; val z1 = connXZ[q * 5 + 1]; val x2 = connXZ[q * 5 + 2]; val z2 = connXZ[q * 5 + 3]; val w = connXZ[q * 5 + 4]
            val len = sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1)); val steps = max(1, (len / 1.0f).toInt())
            val rad = (w / 2f / ROAD_CELL).toInt() + 1
            for (s in 0..steps) {
                val t = s.toFloat() / steps; val x = x1 + (x2 - x1) * t; val z = z1 + (z2 - z1) * t
                val ci = Math.round((x - x0) / ROAD_CELL); val ck = Math.round((z - x0) / ROAD_CELL)
                for (a in -rad..rad) for (b in -rad..rad) mark(ci + a, ck + b)
            }
        }
        roadMask = m
    }

    /** True when (x, z) is on the asphalt of a road (or within about a metre of it). */
    fun roadAt(x: Float, z: Float): Boolean {
        if (roadMask.isEmpty()) return false
        val i = Math.round((x - x0) / ROAD_CELL); val k = Math.round((z - x0) / ROAD_CELL)
        return i in 0 until roadN && k in 0 until roadN && roadMask[k * roadN + i].toInt() == 1
    }

    fun ground(x: Float, z: Float): Float {
        var fx = (x - x0) / cell; var fz = (z - x0) / cell
        fx = fx.coerceIn(0f, n - 1.001f); fz = fz.coerceIn(0f, n - 1.001f)
        val i = fx.toInt(); val k = fz.toInt(); val tx = fx - i; val tz = fz - k; val a = k * n + i
        return gh[a] * (1 - tx) * (1 - tz) + gh[a + 1] * tx * (1 - tz) + gh[a + n] * (1 - tx) * tz + gh[a + n + 1] * tx * tz
    }

    /** Height of the terrain MESH you actually see (a vertex every 2 cells = 12.8 m, quads cut b-c as in buildTerrain). Roads sit on this, so
     *  they never sink into the coarse mesh near the coast (ground() is the finer 6.4 m grid and can be up to 0.3 m higher / lower there). */
    fun meshY(x: Float, z: Float): Float {
        val m = (n - 1) / 2
        val fx = ((x - x0) / (2f * cell)).coerceIn(0f, m - 1e-4f); val fz = ((z - x0) / (2f * cell)).coerceIn(0f, m - 1e-4f)
        val i = fx.toInt(); val k = fz.toInt(); val tx = fx - i; val tz = fz - k
        val a = gh[2 * k * n + 2 * i]; val bb = gh[2 * k * n + 2 * i + 2]; val c = gh[(2 * k + 2) * n + 2 * i]; val d = gh[(2 * k + 2) * n + 2 * i + 2]
        return if (tx + tz <= 1f) a + (bb - a) * tx + (c - a) * tz else d + (bb - d) * (1f - tz) + (c - d) * (1f - tx)
    }

    fun walkAt(x: Float, z: Float): Boolean {
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        return i in 0 until n && k in 0 until n && walk[k * n + i].toInt() == 1
    }

    /** Terrain part of free(): water and cliffs block ground movement. */
    fun terrainBlocked(x: Float, z: Float, r: Float, y: Float = 0f, swim: Boolean = false): Boolean {
        if (y > 1.2f && y > ground(x, z) + 1.5f) return false   // a body high above the ground (roofs, decks) is not stopped by water / cliffs below
        if (swim && wl.isNotEmpty()) {   // swimMode: water cells are walkable too. The sea goes on in every direction: open water, NO wall
            return !((walkAt(x, z) || wetCell(x, z)) && (walkAt(x + r, z) || wetCell(x + r, z)) && (walkAt(x - r, z) || wetCell(x - r, z)) &&
                (walkAt(x, z + r) || wetCell(x, z + r)) && (walkAt(x, z - r) || wetCell(x, z - r)))
        }
        return !(walkAt(x, z) && walkAt(x + r, z) && walkAt(x - r, z) && walkAt(x, z + r) && walkAt(x, z - r))
    }

    // ---- water (sea + lakes / rivers): the sea = every cell with ground <= 0, lakes / rivers = meshes of material "water"
    private fun buildWater() {
        val w = FloatArray(n * n) { -999f }
        for (i in 0 until n * n) if (gh[i] <= 0.02f) w[i] = 0f   // v62: same 2 cm limit as `walk` (before, cells of 1 - 2 cm were neither land nor water: an invisible wall for a swimmer at the shore)
        for (g in groups) {
            if (g.mat != "water") continue
            val v = g.verts; val ix = g.indices; val S = STRIDE
            var q = 0
            while (q + 2 < ix.size) {
                val a = ix[q] * S; val b = ix[q + 1] * S; val c = ix[q + 2] * S; q += 3
                val ax = v[a]; val az = v[a + 2]; val bx = v[b]; val bz = v[b + 2]; val cx = v[c]; val cz = v[c + 2]
                val den = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz)
                if (kotlin.math.abs(den) < 1e-9f) continue
                val i0 = max(0, kotlin.math.floor((min(ax, min(bx, cx)) - x0) / cell).toInt()); val i1 = min(n - 1, kotlin.math.ceil((max(ax, max(bx, cx)) - x0) / cell).toInt())
                val k0 = max(0, kotlin.math.floor((min(az, min(bz, cz)) - x0) / cell).toInt()); val k1 = min(n - 1, kotlin.math.ceil((max(az, max(bz, cz)) - x0) / cell).toInt())
                for (k in k0..k1) for (i in i0..i1) {
                    val px = x0 + i * cell; val pz = x0 + k * cell
                    val l1 = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / den
                    val l2 = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / den
                    val l3 = 1f - l1 - l2
                    if (l1 >= -0.02f && l2 >= -0.02f && l3 >= -0.02f) {
                        val y = l1 * v[a + 1] + l2 * v[b + 1] + l3 * v[c + 1]
                        if (y > w[k * n + i]) w[k * n + i] = y
                    }
                }
            }
        }
        wl = w
    }

    fun wetCell(x: Float, z: Float): Boolean {
        if (wl.isEmpty()) return false
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        if (i !in 0 until n || k !in 0 until n) return true   // outside the grid = open sea
        return wl[k * n + i] > -900f
    }

    fun waterLevelAt(x: Float, z: Float): Float {
        if (wl.isEmpty()) return -999f
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        return if (i in 0 until n && k in 0 until n) wl[k * n + i] else 0f   // outside the grid = open sea at level 0
    }

    fun waterDepth(x: Float, z: Float): Float {
        val w = waterLevelAt(x, z); if (w < -900f) return 0f
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        if (i !in 0 until n || k !in 0 until n) return OPEN_SEA_DEPTH   // outside the grid: deep open sea
        val d = w - ground(x, z); return if (d > 0f) d else 0f
    }

    fun swimSpeed(x: Float, z: Float): Float { val d = waterDepth(x, z); return if (d > 1.25f) 0.55f else if (d > 0.45f) 0.8f else 1f }

    /** Height the feet float at when the water is deeper than 1.25 m (the body sinks to the neck and bobs a little), else -1e9. */
    fun swimFloor(x: Float, z: Float, t: Float): Float {
        val d = waterDepth(x, z); if (d <= 1.25f) return -1e9f
        return waterLevelAt(x, z) - 1.32f * PLAYER_K + 0.05f * kotlin.math.sin(t * 1000f / 450f)
    }

    private fun blocked(x: Float, z: Float, r: Float, y: Float): Boolean {
        val cl = boxesAt(x, z) ?: return false
        for (qi in 0 until cl.size) {
            val b = cl[qi]
            if (x > b.minX - r && x < b.maxX + r && z > b.minZ - r && z < b.maxZ + r && y < b.maxY - 0.3f && y + 1.7f > b.minY) return true
        }
        return false
    }

    // ---- collision grid
    private fun buildGrid() {
        bgN = Math.ceil((n * cell / BC).toDouble()).toInt() + 2
        bgrid = arrayOfNulls(bgN * bgN)
        for (b in blockers) gridInsert(b)
    }

    private fun gridInsert(b: Box) {
        val i0 = kotlin.math.floor((b.minX - 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val i1 = kotlin.math.floor((b.maxX + 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val k0 = kotlin.math.floor((b.minZ - 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val k1 = kotlin.math.floor((b.maxZ + 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        for (k in k0..k1) for (i in i0..i1) {
            var l = bgrid[k * bgN + i]
            if (l == null) { l = ArrayList(); bgrid[k * bgN + i] = l }
            l.add(b)
        }
    }

    // ---- v30: doors that swing and window panes that break (a shut door / intact pane is a box in the grid = a wall; open / broken = removed from the grid)
    private fun gridRemove(b: Box) {
        val i0 = kotlin.math.floor((b.minX - 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val i1 = kotlin.math.floor((b.maxX + 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val k0 = kotlin.math.floor((b.minZ - 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val k1 = kotlin.math.floor((b.maxZ + 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        for (k in k0..k1) for (i in i0..i1) bgrid[k * bgN + i]?.remove(b)
    }

    fun setSolid(d: Dyn, s: Boolean) { if (d.solid == s) return; d.solid = s; if (s) gridInsert(d.box) else gridRemove(d.box) }

    /**
     * v37 DOORS ARE MANUAL FOR THE PLAYER. bodies = flat x,z list, entry 0 is ALWAYS the player, the rest are the living bots.
     * - The player no longer opens a door by walking up to it: the DOOR button (GameRenderer.toggleDoor) opens it (pm = 1, stays open) or shuts it (pm = 2).
     * - A door the player shut (pm = 2) is BARRED: bots do not open it while the player is within 20 m. Farther away it goes back to bot-auto (pm = 0).
     * - A door the player left open (pm = 1) stays open until he walks 40 m away, then bot-auto closes it when nobody is near.
     * - Bots (pm = 0) open a door within 2.2 m and shut it when no bot is within 3.8 m, as before.
     * - A door never shuts on a body standing in the doorway (player within 1.1 m of the leaf centre keeps it open).
     * The swing takes ~0.62 s; the door is a wall until it is 30 % open.
     */
    fun updateDoors(dt: Float, bodies: FloatArray, nb: Int) {
        val px = if (nb > 0) bodies[0] else 1e9f; val pz = if (nb > 0) bodies[1] else 1e9f
        for (qi in 0 until dyn.size) {
            val d = dyn[qi]
            if (!d.door) continue
            val b = d.box; val cx = (b.minX + b.maxX) * .5f; val cz = (b.minZ + b.maxZ) * .5f
            val pdx = px - cx; val pdz = pz - cz; val pd2 = pdx * pdx + pdz * pdz
            if (d.pm == 2 && pd2 > 400f) d.pm = 0
            if (d.pm == 1 && pd2 > 1600f) d.pm = 0
            var md = 1e9f
            for (k in 1 until nb) { val ax = bodies[k * 2] - cx; val az = bodies[k * 2 + 1] - cz; val q = ax * ax + az * az; if (q < md) md = q }
            val old = d.tgt
            when (d.pm) {
                1 -> d.tgt = 1f
                2 -> d.tgt = 0f
                else -> if (md < 4.84f) d.tgt = 1f else if (md > 14.44f) d.tgt = 0f
            }
            if (d.tgt < 0.5f && d.a > 0.05f && pd2 < 1.21f) d.tgt = 1f   // the player stands in the doorway of a door that is not fully shut: it swings back open instead of closing on him
            if (d.tgt != old) onDoor?.invoke(d)
            if (d.a != d.tgt) {
                d.a += (if (d.tgt > d.a) 1f else -1f) * dt * 1.6f   // v38: 0.62 s swing (was 0.45 s); the leaf is drawn with an ease-in-out curve (GameRenderer.drawDyn)
                if ((d.tgt > 0.5f && d.a > d.tgt) || (d.tgt < 0.5f && d.a < d.tgt)) d.a = d.tgt
            }
            setSolid(d, d.a < 0.3f)
        }
    }

    /** v37 the door the DOOR button works on: nearest door leaf within [reach] m (flat distance to the leaf centre) on the same floor as the feet at y, or null. */
    fun doorNear(x: Float, y: Float, z: Float, reach: Float): Dyn? {
        var best: Dyn? = null; var bd = reach * reach
        for (qi in 0 until dyn.size) {
            val d = dyn[qi]
            if (!d.door) continue
            val b = d.box
            if (kotlin.math.abs(y - b.minY) > 1.3f) continue
            val dx = (b.minX + b.maxX) * .5f - x; if (dx > reach || dx < -reach) continue
            val dz = (b.minZ + b.maxZ) * .5f - z; val q = dx * dx + dz * dz
            if (q >= bd) continue
            // v38: the player must stand IN FRONT of the leaf (not beside it behind a wall): sideways offset <= half the leaf + 1 m, distance through the doorway <= 2.4 m
            val lat = kotlin.math.abs(dx * d.cx + dz * d.cz); val thr = kotlin.math.abs(-dx * d.cz + dz * d.cx)
            if (lat > d.len * 0.5f + 1.0f || thr > 2.4f) continue
            bd = q; best = d
        }
        return best
    }

    /** The first thing a shot of length tw hits is an intact glass pane: that pane, else null. */
    fun paneAt(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, tw: Float): Dyn? {
        if (tw < 0f) return null
        val cl = boxesAt(ox + dx * tw, oz + dz * tw) ?: return null
        for (i in 0 until cl.size) {
            val d = cl[i].dyn ?: continue
            if (d.door || d.broken) continue
            val b = d.box
            val t = rayBox(ox, oy, oz, dx, dy, dz, b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ, tw + 0.3f)
            if (t >= 0f && kotlin.math.abs(t - tw) < 0.2f) return d
        }
        return null
    }

    fun breakPane(d: Dyn) { d.broken = true; setSolid(d, false) }
    @JvmField var onGlass: ((Dyn) -> Unit)? = null

    /** A bullet (bot or player): breaks every glass pane on its way (they stop being walls) and returns the distance to the first real obstacle, or -1. */
    fun shootThrough(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxD: Float): Float {
        var tw = rayWorld(ox, oy, oz, dx, dy, dz, maxD); var pn = paneAt(ox, oy, oz, dx, dy, dz, tw); var g = 0
        while (pn != null && g++ < 4) { breakPane(pn); onGlass?.invoke(pn); tw = rayWorld(ox, oy, oz, dx, dy, dz, maxD); pn = paneAt(ox, oy, oz, dx, dy, dz, tw) }
        return tw
    }

    /** Every box within 4 m of (x, z) (the list of its grid cell), or null. */
    fun boxesAt(x: Float, z: Float): ArrayList<Box>? {
        val i = kotlin.math.floor((x - x0) / BC).toInt(); val k = kotlin.math.floor((z - x0) / BC).toInt()
        if (i < 0 || k < 0 || i >= bgN || k >= bgN) return null
        return bgrid[k * bgN + i]
    }

    /** Push (x,z) out of any collision box the body overlaps. */
    // ---- v63: round solid objects (tree trunks): a body can no longer walk through them
    private var ccx = FloatArray(0); private var ccz = FloatArray(0); private var ccr = FloatArray(0)
    private var cgrid: HashMap<Int, IntArray> = HashMap()
    private fun ckey(ix: Int, iz: Int) = ix * 4096 + iz
    /** registers solid circles (centre x,z and radius r, metres); the later resolve() calls push a body out of them */
    fun setCircleColliders(xs: FloatArray, zs: FloatArray, rs: FloatArray) {
        ccx = xs; ccz = zs; ccr = rs
        val tmp = HashMap<Int, ArrayList<Int>>()
        for (i in xs.indices) {
            val ix = kotlin.math.floor((xs[i] + 3000f) / 4f).toInt(); val iz = kotlin.math.floor((zs[i] + 3000f) / 4f).toInt()
            tmp.getOrPut(ckey(ix, iz)) { ArrayList() }.add(i)
        }
        val g = HashMap<Int, IntArray>(); for ((k, v) in tmp) g[k] = v.toIntArray()
        cgrid = g
    }
    /** true when a body of radius r at (x,z) would overlap a tree trunk (used by the bots to walk round trees) */
    fun circleBlocked(x: Float, z: Float, r: Float): Boolean {
        if (ccx.isEmpty()) return false
        val ix = kotlin.math.floor((x + 3000f) / 4f).toInt(); val iz = kotlin.math.floor((z + 3000f) / 4f).toInt()
        for (a in -1..1) for (b in -1..1) {
            val l = cgrid[ckey(ix + a, iz + b)] ?: continue
            for (i in l) { val dx = x - ccx[i]; val dz = z - ccz[i]; val rr = ccr[i] + r; if (dx * dx + dz * dz < rr * rr) return true }
        }
        return false
    }
    private fun resolveCircles(pos: FloatArray, r: Float) {
        if (ccx.isEmpty()) return
        if (pos[1] > ground(pos[0], pos[2]) + 2.5f) return   // up on a roof / deck: the trunk below does not stop you
        val ix = kotlin.math.floor((pos[0] + 3000f) / 4f).toInt(); val iz = kotlin.math.floor((pos[2] + 3000f) / 4f).toInt()
        for (a in -1..1) for (b in -1..1) {
            val l = cgrid[ckey(ix + a, iz + b)] ?: continue
            for (i in l) {
                val dx = pos[0] - ccx[i]; val dz = pos[2] - ccz[i]; val rr = ccr[i] + r
                val d2 = dx * dx + dz * dz
                if (d2 >= rr * rr) continue
                if (d2 > 1e-8f) { val d = kotlin.math.sqrt(d2); pos[0] = ccx[i] + dx / d * rr; pos[2] = ccz[i] + dz / d * rr }
                else pos[0] = ccx[i] + rr
            }
        }
    }

    /** v64: up to 4 passes - pushing a body out of a tree / rock can push it into a wall (and the other way round), so repeat until nothing moves. */
    fun resolve(pos: FloatArray, r: Float) {
        for (pass in 0 until 4) {
            val x0 = pos[0]; val z0 = pos[2]
            resolveOnce(pos, r)
            if (kotlin.math.abs(pos[0] - x0) < 1e-4f && kotlin.math.abs(pos[2] - z0) < 1e-4f) break
        }
    }

    private fun resolveOnce(pos: FloatArray, r: Float) {
        resolveCircles(pos, r)
        val y = pos[1]
        val cl = boxesAt(pos[0], pos[2]) ?: return
        for (qi in 0 until cl.size) {
            val b = cl[qi]
            if (y >= b.maxY - 0.3f || y + 1.7f <= b.minY) continue
            val cx = pos[0].coerceIn(b.minX, b.maxX); val cz = pos[2].coerceIn(b.minZ, b.maxZ)
            var dx = pos[0] - cx; var dz = pos[2] - cz
            val d2 = dx * dx + dz * dz
            if (d2 >= r * r) continue
            if (d2 > 1e-8f) { val d = kotlin.math.sqrt(d2); pos[0] = cx + dx / d * r; pos[2] = cz + dz / d * r }
            else { // centre inside the box: leave through the nearest face
                val l = pos[0] - b.minX; val rr = b.maxX - pos[0]; val t = pos[2] - b.minZ; val bt = b.maxZ - pos[2]
                val m = min(min(l, rr), min(t, bt))
                when (m) { l -> pos[0] = b.minX - r; rr -> pos[0] = b.maxX + r; t -> pos[2] = b.minZ - r; else -> pos[2] = b.maxZ + r }
            }
        }
    }

    /** v36: set by rayWorld - true when the hit it returned was the ground / a hill (never penetrable), false when it was a collision box (wall, door, furniture) */
    @JvmField var lastHitTerrain = false

    /** v36: set by rayWorld - the collision box that the returned hit was on (null for ground / no hit) */
    @JvmField var lastBox: Box? = null
    private val nrm = FloatArray(3)

    /** v36 MATERIAL OF AN IMPACT: call right after rayWorld / shootThrough, with the hit point. Impact.CONCRETE / WOOD / DIRT (ground; a road counts as concrete). */
    fun impactMaterial(hx: Float, hz: Float): Int {
        val b = lastBox
        if (b != null) return Impact.boxMaterial(b, b.dyn?.door == true)
        return if (roadAt(hx, hz)) Impact.CONCRETE else Impact.DIRT
    }

    /** v36: outward normal of the surface of the last rayWorld hit (box face, or straight up for the ground). Returns a shared array of 3 - copy it if you keep it. */
    fun impactNormal(hx: Float, hy: Float, hz: Float, dx: Float, dy: Float, dz: Float): FloatArray {
        val b = lastBox
        if (b == null) { nrm[0] = 0f; nrm[1] = 1f; nrm[2] = 0f } else Impact.faceNormal(b, hx, hy, hz, dx, dy, dz, nrm)
        return nrm
    }

    /** v36: distance at which a falling bullet (ox,oy,oz) + t * (dx,dy,dz) crosses the water surface within maxD, or -1 (not over water / already under the surface). */
    fun waterCross(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxD: Float): Float {
        if (wl.isEmpty() || dy > -1e-5f) return -1f
        val lvl = waterLevelAt(ox + dx * maxD, oz + dz * maxD)
        if (lvl < -900f || oy <= lvl) return -1f
        val t = (lvl - oy) / dy
        if (t <= 0f || t > maxD) return -1f
        return if (waterDepth(ox + dx * t, oz + dz * t) > 0.1f) t else -1f
    }

    /** v36 wall penetration: thickness (m) of the wall a bullet enters tIn metres along the ray, or -1 when the gun (cap = Penetration.limit) cannot get through it. */
    fun wallThickness(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, tIn: Float, cap: Float): Float =
        Penetration.thickness(boxesAt(ox + dx * tIn, oz + dz * tIn), ox, oy, oz, dx, dy, dz, tIn, cap)

    /** Distance along the ray to the first world hit (collision boxes or terrain), or -1. */
    fun rayWorld(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxD: Float, skipGlass: Boolean = false): Float {
        var best = -1f
        lastHitTerrain = false; lastBox = null
        // collision boxes: walk the 24 m grid along the ray (a box is listed in every cell within 4 m, so a 6 m step never misses one)
        stampNow++
        var s = 0f
        while (true) {
            val cl = boxesAt(ox + dx * s, oz + dz * s)
            if (cl != null) for (qi in 0 until cl.size) {
                val b = cl[qi]
                if (b.stamp == stampNow) continue
                b.stamp = stampNow
                if (skipGlass) { val dd = b.dyn; if (dd != null && !dd.door) continue }   // v30b: bots look through intact glass
                val t = rayBox(ox, oy, oz, dx, dy, dz, b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ, maxD)
                if (t > 0.05f && (best < 0f || t < best)) { best = t; lastBox = b }
            }
            if (s >= maxD) break
            s = min(s + 6f, maxD)
            if (best >= 0f && s > best + 6f) break
        }
        // terrain: march, then refine
        var t = 0.5f; var prev = 0f
        val limit = if (best > 0f) best else maxD
        while (t < limit) {
            val y = oy + dy * t
            if (y <= ground(ox + dx * t, oz + dz * t)) {
                var lo = prev; var hi = t
                for (i in 0 until 6) { val m = (lo + hi) / 2; if (oy + dy * m <= ground(ox + dx * m, oz + dz * m)) hi = m else lo = m }
                best = hi; lastHitTerrain = true; lastBox = null; break
            }
            prev = t; t += 1.0f
        }
        return best
    }

    /** True when a body of radius r standing at (x,y,z) overlaps a collision box. */
    fun boxBlocked(x: Float, z: Float, r: Float, y: Float) = blocked(x, z, r, y)

    /**
     * v29 sound path: how many solid partitions lie between two points. A wall / pillar (box >= 1.8 m tall) or a floor / roof slab counts 1
     * (furniture does not), a hill between the two points counts 1. Capped at 3. Gaps (doors, windows) let the sound through, as in real life.
     * Used for the muffling of gunshots / steps you hear and for the hearing of the bots.
     */
    fun soundWalls(ox: Float, oy: Float, oz: Float, tx: Float, ty: Float, tz: Float): Int {
        var dx = tx - ox; var dy = ty - oy; var dz = tz - oz
        val d = sqrt(dx * dx + dy * dy + dz * dz); if (d < 0.6f) return 0
        dx /= d; dy /= d; dz /= d
        var n = 0
        stampNow++
        var s = 0f
        while (true) {
            val cl = boxesAt(ox + dx * s, oz + dz * s)
            if (cl != null) for (qi in 0 until cl.size) {
                val b = cl[qi]
                if (b.stamp == stampNow) continue
                b.stamp = stampNow
                val h = b.maxY - b.minY; val w = min(b.maxX - b.minX, b.maxZ - b.minZ)
                if (h < 1.8f && !(w >= 2.5f && h <= 0.6f)) continue
                val t = rayBox(ox, oy, oz, dx, dy, dz, b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ, d)
                if (t > 0.05f && t < d - 0.05f) n++
            }
            if (s >= d) break
            s = min(s + 6f, d)
        }
        var t = 3f
        while (t < d - 2f) { if (oy + dy * t < ground(ox + dx * t, oz + dz * t) - 0.3f) { n++; break }; t += 4f }
        return min(n, 3)
    }

    /** v29: a roof / ceiling is within 6 m above the head at (x, y, z) = inside a house (room reverb for the sounds). */
    fun covered(x: Float, y: Float, z: Float): Boolean = houses.isNotEmpty() && ceilAt(x, z, y + 0.5f, y + 6f) < 1e8f

    /** Head bump under a ceiling / arch: underside height inside (y0, y1] or 1e9 (= no ceiling). The Bijnor map has none. */
    @Suppress("UNUSED_PARAMETER")
    fun ceilAt(x: Float, z: Float, y0: Float, y1: Float): Float {
        var best = 1e9f
        for (h in houses) {
            if (x < h.minX || x > h.maxX || z < h.minZ || z > h.maxZ) continue
            for (s in h.surf) {
                if (s[16].toInt() and 2 == 0) continue
                if (x < s[0] || x > s[1] || z < s[2] || z > s[3]) continue
                val under = surfH(s, x, z) - s[17]
                if (under > y0 && under <= y1 && under < best) best = under
            }
        }
        return best
    }

    /** Height of a house surface at (x, z): the lowest of its (up to 4) planes y = a*x + b*z + c. */
    private fun surfH(s: FloatArray, x: Float, z: Float): Float {
        var h = 1e9f
        for (p in 0 until 4) { val v = s[4 + p * 3] * x + s[5 + p * 3] * z + s[6 + p * 3]; if (v < h) h = v }
        return h
    }

    /** Standing height at (x, z): the ground (a new map may add decks / floors here). */
    @Suppress("UNUSED_PARAMETER")
    fun footY(x: Float, z: Float, y: Float): Float {
        val g = ground(x, z)
        if (houses.isEmpty()) return g
        var best = g
        for (h in houses) {
            if (x < h.minX || x > h.maxX || z < h.minZ || z > h.maxZ) continue
            for (s in h.surf) {
                if (s[16].toInt() and 1 == 0) continue
                if (x < s[0] || x > s[1] || z < s[2] || z > s[3]) continue
                val hh = surfH(s, x, z)
                if (hh <= y + 0.55f && hh > best) best = hh
            }
        }
        return best
    }

    /**
     * Parachute / freefall landing: the HIGHEST standable house surface (roof, balcony, porch roof, floor) that a body at (x, z) crosses while its feet drop
     * from y0 down to y1 (y1 <= y0), or -1e9 when it crosses none (then only the terrain stops it). Pass y1 = -1e9 to ask "what is the highest surface under me".
     * Before v24 the landing only looked at the terrain, so a player coming down on a roof fell through it and touched down inside the house.
     */
    fun landSurface(x: Float, z: Float, y0: Float, y1: Float): Float {
        var best = -1e9f
        for (h in houses) {
            if (x < h.minX || x > h.maxX || z < h.minZ || z > h.maxZ) continue
            for (s in h.surf) {
                if (s[16].toInt() and 1 == 0) continue
                if (x < s[0] || x > s[1] || z < s[2] || z > s[3]) continue
                val hh = surfH(s, x, z)
                if (hh <= y0 + 0.05f && hh >= y1 && hh > best) best = hh
            }
        }
        return best
    }

    /**
     * A body that touches down on a roof / deck at height y may overlap a parapet, a stair rail or a chimney. Slide it toward the middle of its house
     * (0.25 m steps, at most 3 m) until it is free and return the height it now stands at (the roof surface of the new spot; a pitched roof rises toward the ridge).
     * Returns y unchanged when the spot is already free.
     */
    fun freeLandingSpot(p: FloatArray, y: Float, r: Float): Float {
        if (!blocked(p[0], p[2], r, y + 0.05f)) return y
        var hx = p[0]; var hz = p[2]
        for (h in houses) if (p[0] >= h.minX && p[0] <= h.maxX && p[2] >= h.minZ && p[2] <= h.maxZ) { hx = (h.minX + h.maxX) * 0.5f; hz = (h.minZ + h.maxZ) * 0.5f; break }
        val dx = hx - p[0]; val dz = hz - p[2]; val dl = kotlin.math.sqrt(dx * dx + dz * dz)
        if (dl < 0.01f) return y
        var t = 0.25f
        while (t <= 3f) {
            val qx = p[0] + dx / dl * t; val qz = p[2] + dz / dl * t
            val sy = landSurface(qx, qz, y + 0.8f * t + 0.3f, -1e9f)   // the roof here: highest surface below a bound that grows faster than any roof slope (0.62)
            if (sy > -1e8f && !blocked(qx, qz, r, sy + 0.05f)) { p[0] = qx; p[2] = qz; return sy }
            t += 0.25f
        }
        return y
    }

    /** If (x, z) lies over a house, a point 3 m outside its footprint (nearest edge first, on dry land); else null. Bots use it: they never touch down on a roof. */
    fun houseEscape(x: Float, z: Float): FloatArray? {
        for (h in houses) {
            if (x < h.minX || x > h.maxX || z < h.minZ || z > h.maxZ) continue
            val d = floatArrayOf(x - h.minX, h.maxX - x, z - h.minZ, h.maxZ - z)
            val order = d.indices.sortedBy { d[it] }
            for (e in order) {
                val ox = when (e) { 0 -> h.minX - 3f; 1 -> h.maxX + 3f; else -> x }
                val oz = when (e) { 2 -> h.minZ - 3f; 3 -> h.maxZ + 3f; else -> z }
                if (!terrainBlocked(ox, oz, 0.6f) && !blocked(ox, oz, 0.6f, ground(ox, oz) + 0.05f)) return floatArrayOf(ox, oz)
            }
            return floatArrayOf(h.minX - 3f, z)
        }
        return null
    }

    /**
     * One try of `dxStockHouses()`: 65 % a point INSIDE a house footprint, 35 % on the porch / yard strip 1.4-3 m outside one wall.
     * Returns null if the point is in water, inside a wall / blocker or the list is empty (the caller tries again). The Bijnor map has no houses.
     */
    fun houseSpotTry(rnd: kotlin.random.Random): FloatArray? {
        if (houseSpots.isEmpty()) return null
        return houseSpotAt(rnd, rnd.nextInt(houseSpots.size))
    }

    /** Same as houseSpotTry() but for one chosen house. A house whose inside cannot be reached (hh[4] == 0) only gets porch spots. */
    fun houseSpotAt(rnd: kotlin.random.Random, i: Int): FloatArray? {
        val hh = houseSpots[i]
        val cx = hh[0]; val cz = hh[1]; val w = hh[2]; val d = hh[3]
        val x: Float; val z: Float
        if (hh[4] > 0.5f && rnd.nextFloat() < .65f) {
            x = cx + (rnd.nextFloat() - .5f) * (w - 3.4f).coerceAtLeast(0.5f)
            z = cz + (rnd.nextFloat() - .5f) * (d - 3.4f).coerceAtLeast(0.5f)
        } else {
            val side = rnd.nextInt(4); val t = rnd.nextFloat() * 2f - 1f; val off = 1.4f + rnd.nextFloat() * 1.6f
            when (side) {
                0 -> { x = cx + t * w / 2f; z = cz + d / 2f + off }
                1 -> { x = cx + t * w / 2f; z = cz - d / 2f - off }
                2 -> { x = cx + w / 2f + off; z = cz + t * d / 2f }
                else -> { x = cx - w / 2f - off; z = cz + t * d / 2f }
            }
        }
        if (terrainBlocked(x, z, 0.6f) || !onMainland(x, z)) return null
        val g = ground(x, z)
        if (g < 1f) return null
        val y = footY(x, z, g + 0.5f)
        if (blocked(x, z, 0.7f, y + 0.05f)) return null
        return floatArrayOf(x, y, z)
    }

    /** True when (x, z) is on the main land (or when no mainland mask exists). Small islands can be excluded by a mask. */
    fun onMainland(x: Float, z: Float): Boolean {
        if (mainland.isEmpty()) return true
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        if (i < 0 || k < 0 || i >= n || k >= n) return false
        return mainland[k * n + i].toInt() != 0
    }

    /** Random walkable land point between minD..maxD metres from (cx,cz); null if none found. */
    fun randomLand(rnd: kotlin.random.Random, cx: Float, cz: Float, minD: Float, maxD: Float): FloatArray? {
        for (n in 0 until 300) {
            val a = rnd.nextFloat() * 6.2831855f; val d = minD + rnd.nextFloat() * (maxD - minD)
            val x = cx + kotlin.math.sin(a) * d; val z = cz - kotlin.math.cos(a) * d
            if (terrainBlocked(x, z, 0.6f)) continue
            if (waterDepth(x, z) > 0.05f) continue   // never pick a spot under a lake
            if (!onMainland(x, z)) continue
            val y = ground(x, z)
            if (y < 1f || blocked(x, z, 0.6f, y)) continue
            return floatArrayOf(x, y, z)
        }
        return null
    }

    /** v62: true when the 8 points r metres around (x, z) are dry walkable land of at least 0.6 m height (so not the beach ramp / the water's edge). */
    private fun shoreClear(x: Float, z: Float, r: Float): Boolean {
        for (k in 0 until 8) {
            val a = k * 0.7853982f; val qx = x + kotlin.math.cos(a) * r; val qz = z + kotlin.math.sin(a) * r
            if (!walkAt(qx, qz) || ground(qx, qz) < 0.6f) return false
        }
        return true
    }

    /**
     * Random point of OPEN GROUND anywhere on the 4561 m map (uniform over the land outline's box, rejected when it is sea, coast strip, a road or
     * inside a collision box). Ground loot and the starting spots of the bots use it. null if none found (practically never).
     */
    fun randomLandAny(rnd: kotlin.random.Random, avoidRoad: Boolean = true): FloatArray? {
        for (t in 0 until 400) {
            val x = (rnd.nextFloat() * 2f - 1f) * LAND_HALF_X; val z = (rnd.nextFloat() * 2f - 1f) * LAND_HALF_Z
            if (terrainBlocked(x, z, 0.6f)) continue
            val y = ground(x, z)
            // v62: the old rule "ground < 2 m = coast" also threw away every low field / valley (1.3 - 2.0 m, about 21 % of the land, 2.1 km2): no loot, no bots, no airdrops there.
            // Now a real coast test: the point itself is at least 1.0 m high and 10 m around it is dry, walkable land.
            if (y < 1f) continue
            if (!shoreClear(x, z, 10f)) continue
            if (avoidRoad && roadAt(x, z)) continue
            if (blocked(x, z, 0.6f, y)) continue
            if (circleBlocked(x, z, 0.6f)) continue   // v64: no loot / bot start inside a tree, boulder or shrub
            return floatArrayOf(x, y, z)
        }
        return null
    }
}
