package com.bijnor.game

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * FACE TUNE for the survivor: a more human face + a light face reshape. Both steps run ONCE while the model loads (CPU), the asset files stay untouched
 * (and stay scrambled, see Prot.kt) - nothing is written to disk.
 *
 * 1) warp()  - light reshape (slimmer jaw, slightly longer / more defined chin, ~5 % bigger eyes, slightly narrower nose, a touch fuller lips).
 *    It is ONE function of the rest-pose 3D position and CharModel applies it to every head part (skin, eyes, brows, lashes, mouth, hair) with the same
 *    maths, so the eyes, lashes, brows, lips and hair move TOGETHER and keep sitting on the skin. The head bone weights are decided from the ORIGINAL
 *    position, so the skinning (head turn, crouch, prone, jump ...) is unchanged.
 * 2) paintSkin() - texture edits driven by 3D coordinates: every texel of the face UV islands is rasterised, its rest-pose 3D position is interpolated
 *    and the masks (baked shadows / ambient occlusion, lips, stubble) are functions of that 3D position, so they land exactly on the eye sockets,
 *    nose wings, mouth, jaw ... whatever the UV layout (the UV island of this face is an odd, flipped one).
 *
 * Model space: y up, the face looks to -z, x = character right/left (the face is mirror symmetric).
 * Landmarks (rest pose): eyes (+-0.0332, 1.9724, -0.150), nose tip (0, 1.931, -0.203), mouth line y = 1.8935, chin (0, 1.83, -0.12), brow y ~ 1.99.
 */
object FaceTune {
    /** master switch: false = original face (no reshape, no texture edits) */
    const val ENABLED = true

    private fun ss(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun g3(x: Float, y: Float, z: Float, cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float): Float {
        // beyond 3 sigma on any axis the blob is < exp(-9) = 1.2e-4: far below one 8-bit level for every weight used here -> skip the divisions and the exp
        val dy = y - cy; if (abs(dy) > 3f * sy) return 0f
        val dx = x - cx; if (abs(dx) > 3f * sx) return 0f
        val dz = z - cz; if (abs(dz) > 3f * sz) return 0f
        val a = dx / sx; val b = dy / sy; val c = dz / sz
        val q = a * a + b * b + c * c
        return if (q > 9f) 0f else exp(-q)
    }
    /** gaussian blob mirrored to both sides of the face (centre cx is the +x one) */
    private fun g3s(x: Float, y: Float, z: Float, cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float) =
        g3(x, y, z, cx, cy, cz, sx, sy, sz) + g3(x, y, z, -cx, cy, cz, sx, sy, sz)

    // ------------------------------------------------------------------ 1) reshape
    /** vertex index of CharModel's prim list that belong to the head: 0 skin, 1 eyes, 2 brows, 3 lashes, 4 mouth, 5 hair */
    fun isHeadPrim(pi: Int) = pi in 0..5

    /** warps the position stored at v[o], v[o+1], v[o+2] in place (rest pose, model space). Vertices below the neck (y < 1.78) are not touched. */
    fun warp(v: FloatArray, o: Int) {
        if (!ENABLED) return
        var x = v[o]; var y = v[o + 1]; var z = v[o + 2]
        val gate = ss(1.78f, 1.83f, y)
        if (gate <= 0f) return
        // jaw: ~5 % narrower in the lower front / side of the face
        val wj = ss(1.80f, 1.85f, y) * (1f - ss(1.93f, 2.00f, y)) * ss(0.04f, -0.06f, z)
        x *= 1f - 0.055f * wj * gate
        // chin: slightly longer and forward
        val wc = g3(x, y, z, 0f, 1.835f, -0.12f, 0.05f, 0.035f, 0.06f)
        y -= 0.006f * wc; z -= 0.007f * wc
        // eyes: ~5 % bigger (radial about each eye centre; the eyeball, lashes, lids and skin around them all use this same function)
        var sgn = -1f
        while (sgn <= 1f) {          // left eye, then right eye (no per-vertex array allocation)
            val cx = sgn * 0.0332f; val cy = 1.9724f
            val dx = x - cx; val dy = y - cy
            val w = exp(-((dx * dx + dy * dy) / (0.030f * 0.030f))) * ss(-0.08f, -0.14f, z)
            x += dx * 0.05f * w; y += dy * 0.05f * w
            sgn += 2f
        }
        // nose: slightly narrower tip / wings
        val wn = g3(x, y, z, 0f, 1.935f, -0.19f, 0.030f, 0.028f, 0.04f)
        x *= 1f - 0.06f * wn
        // lips: a touch fuller
        val wl = g3(x, y, z, 0f, 1.891f, -0.186f, 0.030f, 0.011f, 0.05f)
        z -= 0.0025f * wl
        v[o] = x; v[o + 1] = y; v[o + 2] = z
    }

    // ------------------------------------------------------------------ 2) texture
    private fun hash2(a: Int, b: Int): Float {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    /**
     * Paints the face into the (mutable, ARGB_8888) skin atlas `bmp`.
     * pos = rest-pose positions (x, y, z per vertex, BEFORE warp()), uv = (u, v) per vertex, idx = triangle indices of the skin prim.
     * The bitmap is processed in horizontal bands so only a few MB of pixels are in memory at a time.
     */
    fun paintSkin(bmp: Bitmap, pos: FloatArray, uv: FloatArray, idx: IntArray) {
        if (!ENABLED) return
        val bw = bmp.width; val bh = bmp.height
        // head triangles (front half of the head incl. jaw / neck line) and the pixel bounding box of their UV islands
        val tris = ArrayList<Int>()
        var minY = bh; var maxY = 0
        var t = 0
        while (t + 2 < idx.size) {
            val a = idx[t]; val b = idx[t + 1]; val c = idx[t + 2]
            if (pos[a * 3 + 1] > 1.79f && pos[b * 3 + 1] > 1.79f && pos[c * 3 + 1] > 1.79f && (pos[a * 3 + 2] < 0f || pos[b * 3 + 2] < 0f || pos[c * 3 + 2] < 0f)) {
                tris.add(t)
                val v0 = uv[a * 2 + 1] * bh; val v1 = uv[b * 2 + 1] * bh; val v2 = uv[c * 2 + 1] * bh
                minY = min(minY, min(v0, min(v1, v2)).toInt() - 2); maxY = max(maxY, max(v0, max(v1, v2)).toInt() + 2)
            }
            t += 3
        }
        if (tris.isEmpty()) return
        minY = (minY - 2).coerceIn(0, bh - 1); maxY = (maxY + 2).coerceIn(0, bh - 1)
        val band = 256
        val px = IntArray(bw * band); val done = BooleanArray(bw * band)
        var rowY = minY
        while (rowY <= maxY) {
            val bhh = min(band, maxY - rowY + 1)
            bmp.getPixels(px, 0, bw, 0, rowY, bw, bhh)
            java.util.Arrays.fill(done, false)
            var touched = false
            // pass 0: texels whose centre lies inside a triangle.  pass 1: texels that no triangle claimed but lie within 1.5 texels of one -> closes the hair-thin
            // gaps between UV triangles (they showed as light cracks, e.g. across the lips) and pads the island edge so bilinear / mip sampling sees the same face paint.
            for (pass in 0..1) {
                val grow = if (pass == 0) 0f else 1.5f
                val pad = if (pass == 0) 1 else 3
                for (ti in tris) {
                    val a = idx[ti]; val b = idx[ti + 1]; val c = idx[ti + 2]
                    val u0 = uv[a * 2] * bw; val u1 = uv[b * 2] * bw; val u2 = uv[c * 2] * bw
                    val v0 = uv[a * 2 + 1] * bh; val v1 = uv[b * 2 + 1] * bh; val v2 = uv[c * 2 + 1] * bh
                    val ty0 = max(rowY, min(v0, min(v1, v2)).toInt() - pad); val ty1 = min(rowY + bhh - 1, max(v0, max(v1, v2)).toInt() + pad)
                    if (ty1 < ty0) continue
                    val tx0 = max(0, min(u0, min(u1, u2)).toInt() - pad); val tx1 = min(bw - 1, max(u0, max(u1, u2)).toInt() + pad)
                    val den = (v1 - v2) * (u0 - u2) + (u2 - u1) * (v0 - v2)
                    val ad = abs(den)
                    if (ad < 1e-9f || (pass == 1 && ad < 4f)) continue          // pass 1 skips sliver triangles (their extrapolation is unreliable; neighbours fill the gap)
                    // barycentric tolerance = `grow` texels measured from each edge (w_i * altitude_i = distance of the texel to edge i)
                    val m0 = -(grow * sqrt((u1 - u2) * (u1 - u2) + (v1 - v2) * (v1 - v2)) / ad) - 1e-4f
                    val m1 = -(grow * sqrt((u2 - u0) * (u2 - u0) + (v2 - v0) * (v2 - v0)) / ad) - 1e-4f
                    val m2 = -(grow * sqrt((u0 - u1) * (u0 - u1) + (v0 - v1) * (v0 - v1)) / ad) - 1e-4f
                    val ax = pos[a * 3]; val ay = pos[a * 3 + 1]; val az = pos[a * 3 + 2]
                    val bx = pos[b * 3]; val byy = pos[b * 3 + 1]; val bz = pos[b * 3 + 2]
                    val cx = pos[c * 3]; val cy = pos[c * 3 + 1]; val cz = pos[c * 3 + 2]
                    for (yy in ty0..ty1) {
                        val gy = yy + 0.5f
                        for (xx in tx0..tx1) {
                            val k = (yy - rowY) * bw + xx
                            if (done[k]) continue
                            val gx = xx + 0.5f
                            val w0 = ((v1 - v2) * (gx - u2) + (u2 - u1) * (gy - v2)) / den
                            val w1 = ((v2 - v0) * (gx - u2) + (u0 - u2) * (gy - v2)) / den
                            val w2 = 1f - w0 - w1
                            if (w0 < m0 || w1 < m1 || w2 < m2) continue
                            done[k] = true
                            val x = w0 * ax + w1 * bx + w2 * cx
                            val y = w0 * ay + w1 * byy + w2 * cy
                            val z = w0 * az + w1 * bz + w2 * cz
                            if (y < 1.78f || y > 2.07f || z > 0f) continue
                            px[k] = paintTexel(px[k], x, y, z, xx, yy)
                            touched = true
                        }
                    }
                }
            }
            if (touched) bmp.setPixels(px, 0, bw, 0, rowY, bw, bhh)
            rowY += band
        }
    }

    private fun lum(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    /** one texel: argb in -> argb out. (x, y, z) = rest-pose 3D position of the texel on the face. */
    private fun paintTexel(argb: Int, x: Float, y: Float, z: Float, tx: Int, ty: Int): Int {
        var r = ((argb shr 16) and 255) / 255f; var g = ((argb shr 8) and 255) / 255f; var b = (argb and 255) / 255f
        val l0 = lum(r, g, b)
        val ax = abs(x)
        val front = ss(-0.04f, -0.10f, z) * ss(1.79f, 1.83f, y) * (1f - ss(2.03f, 2.07f, y))
        // the stubble mask's cheap gates; when the texel is outside the face mask AND outside the beard area nothing below can change it
        var beard = ss(1.945f, 1.905f, y) * (1f - ss(1.80f, 1.78f, y)) * ss(0.0f, -0.05f, z) * ss(0.105f, 0.075f, ax)
        if (front <= 0f && beard <= 0.01f) return argb

        // ---- baked shadows / ambient occlusion (warm multiply), placed by 3D landmark
        var sh = 0f
        sh += 0.34f * g3s(x, y, z, 0.033f, 1.992f, -0.168f, 0.040f, 0.0085f, 0.05f)   // under the brow ridge
        sh += 0.30f * g3s(x, y, z, 0.020f, 1.968f, -0.172f, 0.011f, 0.024f, 0.04f)    // inner eye corner / nose bridge
        sh += 0.18f * g3s(x, y, z, 0.034f, 1.957f, -0.160f, 0.030f, 0.007f, 0.05f)    // lower lid / eye bag
        sh += 0.26f * g3s(x, y, z, 0.021f, 1.918f, -0.192f, 0.0085f, 0.011f, 0.03f)   // nose wings
        sh += 0.22f * g3s(x, y, z, 0.035f, 1.905f, -0.178f, 0.007f, 0.020f, 0.04f)    // nasolabial folds
        sh += 0.16f * g3s(x, y, z, 0.074f, 1.950f, -0.120f, 0.020f, 0.040f, 0.05f)    // temples
        sh += 0.16f * g3s(x, y, z, 0.034f, 1.832f, -0.100f, 0.035f, 0.012f, 0.07f)    // under the jaw
        sh += 0.30f * g3(x, y, z, 0f, 1.921f, -0.196f, 0.016f, 0.0055f, 0.03f)        // under the nose
        sh += 0.26f * g3(x, y, z, 0f, 1.866f, -0.186f, 0.022f, 0.0065f, 0.03f)        // fold under the lower lip
        sh += 0.22f * g3(x, y, z, 0f, 1.822f, -0.115f, 0.07f, 0.014f, 0.07f)          // under the chin
        sh += 0.10f * g3(x, y, z, 0f, 2.030f, -0.150f, 0.07f, 0.015f, 0.05f)          // below the hairline
        sh = min(max(sh, 0f), 0.65f) * front
        r *= 1f - sh * (1f - 0.62f); g *= 1f - sh * (1f - 0.40f); b *= 1f - sh * (1f - 0.34f)

        // ---- lips: superellipse around the mouth line, upper lip darker, lower lip fuller / lighter, dark parting line, darker rim
        val dx = ax / 0.0295f
        val dy = if (y > 1.8935f) (y - 1.8935f) / 0.0105f else (y - 1.8935f) / 0.0125f
        val inMouth = ax < 0.0300f && y > 1.880f && y < 1.905f      // pow() only where the lips can be
        val rr = if (inMouth) Math.pow(dx.toDouble(), 2.6).toFloat() + Math.pow(abs(dy).toDouble(), 2.2).toFloat() else 9f
        val lipm = (1f - ss(0.78f, 1.0f, rr)) * front
        if (lipm > 0f) {
            val up = y > 1.8935f
            val lr = if (up) 0.58f else 0.67f; val lg = if (up) 0.31f else 0.36f; val lb = if (up) 0.27f else 0.31f
            val k = (l0 / 0.62f).coerceIn(0.55f, 1.35f)            // keep the skin micro detail
            val edge = ss(0.55f, 1.0f, rr)
            val e = 1f - 0.22f * edge
            val lm = lipm * 0.64f
            r = r * (1f - lm) + k * lr * e * lm; g = g * (1f - lm) + k * lg * e * lm; b = b * (1f - lm) + k * lb * e * lm
        }
        val dl = (y - 1.8935f) / 0.0010f
        val line = if (dl * dl > 9f) 0f else exp(-(dl * dl)) * (1f - ss(0.55f, 0.95f, dx)) * front * 0.55f
        if (line > 0.001f) { r *= 1f - line * (1f - 0.45f); g *= 1f - line * (1f - 0.20f); b *= 1f - line * (1f - 0.20f) }
        val hl = 0.05f * g3(x, y, z, 0f, 1.884f, -0.188f, 0.014f, 0.0035f, 0.03f) * front   // wet highlight on the lower lip
        r += hl; g += hl; b += hl

        // ---- light stubble: 3D beard mask (jaw, chin, upper lip, cheeks / sideburns) x per-texel noise dots + a faint 5-o'clock shadow
        beard *= 1f - 0.95f * g3(x, y, z, 0f, 1.925f, -0.20f, 0.028f, 0.030f, 0.04f)   // nose stays clean
        beard *= 1f - 0.90f * lipm                                                        // lips stay clean
        beard *= 1f - 0.55f * g3(x, y, z, 0f, 1.905f, -0.19f, 0.05f, 0.016f, 0.05f)
        if (beard > 0.01f) {
            val dense = 0.35f * g3(x, y, z, 0f, 1.912f, -0.19f, 0.040f, 0.010f, 0.05f) + 0.30f * g3(x, y, z, 0f, 1.850f, -0.17f, 0.05f, 0.020f, 0.06f)
            val dens = (beard * (0.55f + dense)).coerceIn(0f, 1f)
            val dots = ss(0.80f, 0.93f, hash2(tx, ty)) * (0.5f + 0.5f * hash2(tx + 17, ty * 3 + 5))
            val sm = dens * 0.85f * dots
            r = r * (1f - sm) + 0.17f * sm; g = g * (1f - sm) + 0.13f * sm; b = b * (1f - sm) + 0.12f * sm
            val s5 = dens * 0.20f
            r *= 1f - s5 * (1f - 0.78f); g *= 1f - s5 * (1f - 0.72f); b *= 1f - s5 * (1f - 0.72f)
        }
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt(); val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt(); val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (argb and 0xFF000000.toInt()) or (ri shl 16) or (gi shl 8) or bi
    }
}
