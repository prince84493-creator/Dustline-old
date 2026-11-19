package com.bijnor.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v57 SATELLITE MAP of the BIJNOR map, painted from the real game data (not a flat green sketch any more):
 *  - ground colour of the 3D terrain (the same colours the game draws) + hill shading, sea with depth, beach,
 *  - every road (asphalt), every building (roof colours with a little shadow), every tree (dark green blobs).
 * One [SIZE] x [SIZE] bitmap covers the whole map (world x/z in [-half, half], z grows downwards). The minimap and the big map both draw
 * from it with [drawView]; when zoomed in, roads / buildings / trees are drawn again as sharp vectors on top.
 */
@Suppress("UNUSED_PARAMETER")
class MapImage(ctx0: Context, private val map: MapData) {
    companion object {
        const val SIZE = 2048
        const val SEA = 0xFF1664B2.toInt()
        const val ROAD = 0xFF2A2B30.toInt()
        const val ROAD_EDGE = 0xFF55565B.toInt()
        private const val CELL = 128f
        private val ROOFS = intArrayOf(0xFFC8785A.toInt(), 0xFFE6DCC4.toInt(), 0xFFA9ABB0.toInt(), 0xFF7C9CC4.toInt(), 0xFFC49A5A.toInt(), 0xFFB5523C.toInt())
        const val B_BIG = 0xFF8EA2C4.toInt()
        const val B_EDGE = 0xFF3A2E20.toInt()
        const val TREE = 0xFF2F5A28.toInt()
        private val lock = Any()
        private var shared: Pair<MapData, Bitmap>? = null
    }
    val half: Float = abs(map.x0)
    private val mpp = 2f * half / SIZE                  // metres per bitmap pixel
    private val pic: Bitmap = obtain()
    private var treesBaked = false
    val seaColor: Int get() = SEA

    /** The picture of the whole map. */
    fun pick(destW: Float): Bitmap = pic

    /** Call every frame with renderer.treeXZ(): the first time it is not empty the trees are painted into the picture. */
    fun trees(t: FloatArray) {
        if (t.isEmpty()) return
        if (!treesBaked) {
            treesBaked = true
            val c = Canvas(pic); val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.FILL; p.color = TREE; p.alpha = 215
            val s = SIZE / (2f * half); var i = 0
            while (i + 1 < t.size) { c.drawCircle((t[i] + half) * s, (t[i + 1] + half) * s, 2.1f, p); i += 2 }
        }
        treesXZ = t
    }
    private var treesXZ = FloatArray(0)

    // ---- road triangles in a 128 m grid (vector layer when zoomed in)
    private var tri = FloatArray(0)
    private var gridN = 0
    private var cellStart = IntArray(0)
    private var cellTri = IntArray(0)
    private var stamp = IntArray(0)
    private var stampNow = 0
    private val roadPath = Path()
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcR = Rect(); private val dstR = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init { buildIndex() }

    private fun buildIndex() {
        var nt = 0
        for (r in map.roadLayers) nt += r.idx.size / 3
        tri = FloatArray(nt * 6)
        var q = 0
        for (r in map.roadLayers) { var i = 0; while (i + 2 < r.idx.size) { for (c in 0 until 3) { tri[q++] = r.xz[r.idx[i + c] * 2]; tri[q++] = r.xz[r.idx[i + c] * 2 + 1] }; i += 3 } }
        gridN = ceil(2f * half / CELL).toInt() + 1
        val counts = IntArray(gridN * gridN + 1)
        fun span(t: Int, f: (Int, Int) -> Unit) {
            val o = t * 6
            val minX = min(tri[o], min(tri[o + 2], tri[o + 4])); val maxX = max(tri[o], max(tri[o + 2], tri[o + 4]))
            val minZ = min(tri[o + 1], min(tri[o + 3], tri[o + 5])); val maxZ = max(tri[o + 1], max(tri[o + 3], tri[o + 5]))
            val i0 = floor((minX + half) / CELL).toInt().coerceIn(0, gridN - 1); val i1 = floor((maxX + half) / CELL).toInt().coerceIn(0, gridN - 1)
            val k0 = floor((minZ + half) / CELL).toInt().coerceIn(0, gridN - 1); val k1 = floor((maxZ + half) / CELL).toInt().coerceIn(0, gridN - 1)
            for (k in k0..k1) for (i in i0..i1) f(i, k)
        }
        for (t in 0 until nt) span(t) { i, k -> counts[k * gridN + i + 1]++ }
        for (c in 0 until gridN * gridN) counts[c + 1] += counts[c]
        cellStart = counts.copyOf()
        cellTri = IntArray(counts[gridN * gridN])
        val fill = IntArray(gridN * gridN)
        for (t in 0 until nt) span(t) { i, k -> val c = k * gridN + i; cellTri[cellStart[c] + fill[c]++] = t }
        stamp = IntArray(nt)
    }

    /**
     * Draws the square of the world [viewM] metres across, centred on (cx, cz), into [dest] (the canvas is clipped / rotated by the caller).
     * [vectors] = also draw roads, buildings and trees as sharp vectors (used for the minimap and when zoomed in).
     */
    fun drawView(c: Canvas, dest: RectF, cx: Float, cz: Float, viewM: Float, vectors: Boolean) {
        val k = dest.width() / viewM                       // pixels per metre on the screen
        pt.style = Paint.Style.FILL; pt.color = SEA
        c.drawRect(dest, pt)
        // --- the satellite picture
        val x0w = cx - viewM / 2f; val z0w = cz - viewM / 2f
        var sl = (x0w + half) / mpp; var st = (z0w + half) / mpp; var sr = (x0w + viewM + half) / mpp; var sb = (z0w + viewM + half) / mpp
        var dl = dest.left; var dt = dest.top; var dr = dest.right; var db = dest.bottom
        val fx = (dr - dl) / (sr - sl); val fz = (db - dt) / (sb - st)
        if (sl < 0f) { dl += (0f - sl) * fx; sl = 0f }
        if (st < 0f) { dt += (0f - st) * fz; st = 0f }
        if (sr > SIZE) { dr -= (sr - SIZE) * fx; sr = SIZE.toFloat() }
        if (sb > SIZE) { db -= (sb - SIZE) * fz; sb = SIZE.toFloat() }
        if (sr - sl >= 1f && sb - st >= 1f) {
            srcR.set(sl.toInt(), st.toInt(), min(SIZE, ceil(sr).toInt()), min(SIZE, ceil(sb).toInt()))
            dstR.set(dl, dt, dr, db)
            c.drawBitmap(pic, srcR, dstR, bmpPaint)
        }
        if (!vectors) return
        fun sx(x: Float) = dest.centerX() + (x - cx) * k
        fun sz(z: Float) = dest.centerY() + (z - cz) * k
        val pad = viewM * 0.5f * 1.5f + 30f               // (the caller may rotate the canvas, so look a bit beyond the square)
        // --- roads
        stampNow++
        roadPath.reset()
        val i0 = floor((cx - pad + half) / CELL).toInt().coerceIn(0, gridN - 1); val i1 = floor((cx + pad + half) / CELL).toInt().coerceIn(0, gridN - 1)
        val k0 = floor((cz - pad + half) / CELL).toInt().coerceIn(0, gridN - 1); val k1 = floor((cz + pad + half) / CELL).toInt().coerceIn(0, gridN - 1)
        for (kk in k0..k1) for (ii in i0..i1) {
            val cell = kk * gridN + ii
            for (q in cellStart[cell] until cellStart[cell + 1]) {
                val t = cellTri[q]
                if (stamp[t] == stampNow) continue
                stamp[t] = stampNow
                val o = t * 6
                roadPath.moveTo(sx(tri[o]), sz(tri[o + 1])); roadPath.lineTo(sx(tri[o + 2]), sz(tri[o + 3])); roadPath.lineTo(sx(tri[o + 4]), sz(tri[o + 5])); roadPath.close()
            }
        }
        pt.style = Paint.Style.FILL_AND_STROKE; pt.strokeWidth = 1.2f; pt.strokeJoin = Paint.Join.ROUND; pt.color = ROAD
        c.drawPath(roadPath, pt)
        val cn = map.connXZ
        pt.style = Paint.Style.STROKE; pt.strokeCap = Paint.Cap.ROUND
        var q = 0
        while (q + 4 < cn.size) {
            val x1 = cn[q]; val z1 = cn[q + 1]; val x2 = cn[q + 2]; val z2 = cn[q + 3]; val w = cn[q + 4]; q += 5
            if (abs(x1 - cx) > pad + 20f && abs(x2 - cx) > pad + 20f) continue
            if (abs(z1 - cz) > pad + 20f && abs(z2 - cz) > pad + 20f) continue
            pt.strokeWidth = max(1.5f, w * k); c.drawLine(sx(x1), sz(z1), sx(x2), sz(z2), pt)
        }
        pt.strokeCap = Paint.Cap.BUTT; pt.style = Paint.Style.FILL
        // --- trees
        val tr = treesXZ
        if (tr.isNotEmpty()) {
            pt.color = TREE; pt.alpha = 225
            val rad = max(1.6f, 3.2f * k)
            var ti = 0
            while (ti + 1 < tr.size) {
                val x = tr[ti]; val z = tr[ti + 1]; ti += 2
                if (x < cx - pad || x > cx + pad || z < cz - pad || z > cz + pad) continue
                c.drawCircle(sx(x), sz(z), rad, pt)
            }
            pt.alpha = 255
        }
        // --- buildings: roof colour per building, a small shadow, a dark edge
        val bl = map.bldg
        var bi = 0; var idx = 0
        val edgeW = max(0.8f, 0.5f * k)
        while (bi + 4 < bl.size) {
            val ax = bl[bi]; val az = bl[bi + 1]; val bx = bl[bi + 2]; val bz = bl[bi + 3]; val top = bl[bi + 4]; bi += 5
            val id = idx++
            if (bx < cx - pad || ax > cx + pad || bz < cz - pad || az > cz + pad) continue
            val area = (bx - ax) * (bz - az)
            val l = sx(ax); val t = sz(az); val r = sx(bx); val b = sz(bz)
            val off = max(1f, 0.04f * (r - l))
            pt.style = Paint.Style.FILL; pt.color = 0x66000000; c.drawRect(l + off, t + off, r + off, b + off, pt)
            pt.color = if (area >= 170f) B_BIG else ROOFS[(id * 7 + (top * 3f).toInt()) % ROOFS.size]
            c.drawRect(l, t, r, b, pt)
            pt.style = Paint.Style.STROKE; pt.strokeWidth = edgeW; pt.color = B_EDGE; c.drawRect(l, t, r, b, pt)
        }
        pt.style = Paint.Style.FILL
    }

    private fun obtain(): Bitmap {
        synchronized(lock) {
            val old = shared
            if (old != null && old.first === map) return old.second
            val b = makeSat()
            shared = Pair(map, b)
            return b
        }
    }

    /** Whole-map satellite picture: terrain colours + hill shading + sea depth, roads and buildings on top (trees are added by [trees]). */
    private fun makeSat(): Bitmap {
        val S = SIZE
        val px = IntArray(S * S)
        val n = map.n; val cellM = map.cell; val x0 = map.x0
        val tc = map.terrCol; val tm = map.terrM
        val haveCol = tc.size >= tm * tm * 3 && tm > 1
        val g = FloatArray(n * n); for (k in 0 until n) for (i in 0 until n) g[k * n + i] = map.ground(x0 + i * cellM, x0 + k * cellM)
        val lx = -0.608f; val ly = 0.746f; val lz = 0.387f
        for (py in 0 until S) {
            val z = -half + (py + 0.5f) * mpp
            val fz = ((z - x0) / cellM).coerceIn(0f, n - 1.001f); val kz = fz.toInt(); val tz = fz - kz
            for (pxl in 0 until S) {
                val x = -half + (pxl + 0.5f) * mpp
                val fx = ((x - x0) / cellM).coerceIn(0f, n - 1.001f); val ix = fx.toInt(); val tx = fx - ix
                val a = kz * n + ix
                val h = g[a] * (1 - tx) * (1 - tz) + g[a + 1] * tx * (1 - tz) + g[a + n] * (1 - tx) * tz + g[a + n + 1] * tx * tz
                var r: Float; var gg: Float; var b: Float
                if (h <= 0.05f) {
                    val t = (-h / 6f).coerceIn(0f, 1f)
                    r = 0.22f + (0.05f - 0.22f) * t; gg = 0.58f + (0.26f - 0.58f) * t; b = 0.72f + (0.48f - 0.72f) * t
                } else {
                    // slope -> light
                    val i0 = max(0, ix - 1); val i1 = min(n - 1, ix + 2); val k0 = max(0, kz - 1); val k1 = min(n - 1, kz + 2)
                    val dx = (g[kz * n + i1] - g[kz * n + i0]) / ((i1 - i0) * cellM)
                    val dz = (g[k1 * n + ix] - g[k0 * n + ix]) / ((k1 - k0) * cellM)
                    val l = sqrt(dx * dx + 1f + dz * dz)
                    val dot = (-dx * lx + ly - dz * lz) / l
                    val sh = (0.9f + (dot - ly) * 2.2f).coerceIn(0.62f, 1.18f)
                    if (haveCol) {
                        val ft = ((x - x0) / (2f * cellM)).coerceIn(0f, tm - 1.001f); val fk = ((z - x0) / (2f * cellM)).coerceIn(0f, tm - 1.001f)
                        val ti = ft.toInt(); val tk = fk.toInt(); val ax = ft - ti; val az = fk - tk
                        val o = (tk * tm + ti) * 3
                        val w00 = (1 - ax) * (1 - az); val w10 = ax * (1 - az); val w01 = (1 - ax) * az; val w11 = ax * az
                        r = tc[o] * w00 + tc[o + 3] * w10 + tc[o + tm * 3] * w01 + tc[o + tm * 3 + 3] * w11
                        gg = tc[o + 1] * w00 + tc[o + 4] * w10 + tc[o + tm * 3 + 1] * w01 + tc[o + tm * 3 + 4] * w11
                        b = tc[o + 2] * w00 + tc[o + 5] * w10 + tc[o + tm * 3 + 2] * w01 + tc[o + tm * 3 + 5] * w11
                    } else { r = 0.58f; gg = 0.54f; b = 0.32f }
                    r *= sh; gg *= sh; b *= sh
                }
                val ri = (r * 255f).toInt().coerceIn(0, 255); val gi = (gg * 255f).toInt().coerceIn(0, 255); val bi2 = (b * 255f).toInt().coerceIn(0, 255)
                px[py * S + pxl] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi2
            }
        }
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, S, 0, 0, S, S)
        val c = Canvas(bmp)
        val s = S / (2f * half)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // roads
        p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.2f; p.color = ROAD
        for (rl in map.roadLayers) {
            val path = Path()
            var t = 0
            while (t + 2 < rl.idx.size) {
                for (cc in 0 until 3) { val x = (rl.xz[rl.idx[t + cc] * 2] + half) * s; val y = (rl.xz[rl.idx[t + cc] * 2 + 1] + half) * s; if (cc == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close(); t += 3
            }
            c.drawPath(path, p)
        }
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        val cn = map.connXZ
        var q = 0
        while (q + 4 < cn.size) {
            p.strokeWidth = max(2f, cn[q + 4] * s)
            c.drawLine((cn[q] + half) * s, (cn[q + 1] + half) * s, (cn[q + 2] + half) * s, (cn[q + 3] + half) * s, p)
            q += 5
        }
        // buildings
        val bl = map.bldg
        var bi = 0; var idx = 0
        p.strokeWidth = 0.7f
        while (bi + 4 < bl.size) {
            var l = (bl[bi] + half) * s; var t = (bl[bi + 1] + half) * s; var r = (bl[bi + 2] + half) * s; var bt = (bl[bi + 3] + half) * s
            val area = (bl[bi + 2] - bl[bi]) * (bl[bi + 3] - bl[bi + 1]); val top = bl[bi + 4]; bi += 5
            val id = idx++
            if (r - l < 3f) { val m = (l + r) * 0.5f; l = m - 1.5f; r = m + 1.5f }
            if (bt - t < 3f) { val m = (t + bt) * 0.5f; t = m - 1.5f; bt = m + 1.5f }
            p.style = Paint.Style.FILL; p.color = 0x55000000; c.drawRect(l + 1f, t + 1f, r + 1f, bt + 1f, p)
            p.color = if (area >= 170f) B_BIG else ROOFS[(id * 7 + (top * 3f).toInt()) % ROOFS.size]
            c.drawRect(l, t, r, bt, p)
            p.style = Paint.Style.STROKE; p.color = B_EDGE; c.drawRect(l, t, r, bt, p)
        }
        return bmp
    }
}
