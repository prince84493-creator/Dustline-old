package com.bijnor.game

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Retarget core of the pistol animation pack (PistolAnim) for the game's 12-bone rig.
 * Kotlin port of tools/pistol_anim/retarget.py (gun_solve / solve_arm2 / foot_drop), plus the aim-line correction.
 *
 * One call to [solve] takes ONE baked pose (PistolState.cur, K floats, layout in PistolAnim) and the per-gun baked rifle hold (GunHold: uq / fq / e1)
 * and produces everything CharModel.skin() needs:
 *   - gun displacement + rotation (qD, d) relative to the baked hold, in "chest space" (origin = middle of the shoulders, x right, y up, -z forward)
 *   - 2-bone IK of BOTH arms: right hand stays on the grip, left hand goes to the gun (weight f[34], at least `leftMin`) or stays free
 *   - upper arm / forearm quaternions + elbow positions (relative to each shoulder, chest space)
 *   - aim-line correction: the pack aims with a bladed chest (12 deg two-hand, 42 deg one-hand) and the gun yawed against it. The game turns the body to the
 *     crosshair, so the whole upper body is turned (yaw) and the arms + gun are tilted (pitch) until the muzzle points exactly along the aim line again.
 *   - foot-grounding hip drop of the baked legs.
 * No allocation per call (everything lives in scratch arrays) - it runs for the player and every visible bot, every frame.
 */
class PistolRig {
    companion object {
        /** shoulder -> elbow and elbow -> wrist vectors of the rig in bone space (left [0], right [1]) */
        private val RU = arrayOf(floatArrayOf(-0.05835416f, -0.36471350f, 0.02188281f), floatArrayOf(0.05835416f, -0.36471350f, 0.02188281f))
        private val RF = arrayOf(floatArrayOf(0.007384f, -0.369175f, -0.12552f), floatArrayOf(-0.007384f, -0.369175f, -0.12552f))
        private const val SX0 = -0.27f
        private const val SX1 = 0.27f
        private val LU = sqrt(RU[0][0] * RU[0][0] + RU[0][1] * RU[0][1] + RU[0][2] * RU[0][2])
        private val LF = sqrt(RF[0][0] * RF[0][0] + RF[0][1] * RF[0][1] + RF[0][2] * RF[0][2])
        /** arms are never asked to stretch further than this (a fully straight arm looks broken) */
        private val REACH = 0.985f * (LU + LF)
        /** y of the hips and of the shoulder line in the rig (CharModel) */
        const val HIP_Y = 1.04f
        const val SHOULDER_Y = 1.67f
        /** the legs of the rig: thigh length (+ small z offset of the knee) and shin length (knee -> ankle) */
        private const val THIGH = 0.5f
        private const val THIGH_Z = 0.015f
        private const val SHIN = 0.54f
        /** share of the aim yaw that is also given to the pelvis / legs while standing (so the hips do not twist the full 30-40 deg against the legs) */
        private const val LEG_YAW_SHARE = 0.4f

        fun smooth01(x: Float): Float { val t = if (x < 0f) 0f else if (x > 1f) 1f else x; return t * t * (3f - 2f * t) }
    }

    // ------------------------------------------------------------------ results of solve()
    /** gun rotation (relative to the baked hold) and displacement, chest space */
    val qD = FloatArray(4); val d = FloatArray(3)
    /** right-hand grip pivot of the baked hold (chest space): the gun turns about this point */
    val pivot = FloatArray(3)
    /** upper-arm / forearm rotation (bone space, relative to the shoulder frame) and elbow position relative to the shoulder, per arm [0] left [1] right */
    val upQ = Array(2) { FloatArray(4) }; val foQ = Array(2) { FloatArray(4) }; val elbow = Array(2) { FloatArray(3) }
    /** chest rotation AFTER the aim-line yaw correction (what the body bone gets) */
    val chestQ = FloatArray(4)
    /** yaw (rad, about the vertical axis) given to the pelvis / legs, and the extra yaw of the chest on top of it */
    var legYaw = 0f; var chestYaw = 0f
    /** pitch (rad, about the shoulder line) that levels the gun; the caller adds the player's look pitch */
    var armPitch = 0f
    /** how far an arm had to be shortened to stay inside its reach (m of rig space), for the checks only */
    var shortL = 0f; var shortR = 0f
    /** hip lowering that puts the lower foot on the ground */
    var drop = 0f

    // ------------------------------------------------------------------ v55 two-hand RIFLE animation (PistolState rifle mode) - inputs set by the caller before solve()
    /** true = the gun displacement f[28..30] is taken as it is (relative to the hold); the absolute gun origin f[37..39] of the pistol pack is not mixed in */
    var rel = false
    /** weight 0..1 of the left hand holding a point of the gun (the magazine while reloading), that point in unscaled gun-mesh space, and the origin of the mesh in the gun frame (GunHold.OX / OY / oz) */
    var lhMagW = 0f; val lhMagPt = FloatArray(3); val gunO = FloatArray(3)

    // ------------------------------------------------------------------ scratch
    private val w0 = Array(2) { FloatArray(3) }
    private val q1 = FloatArray(4); private val q2 = FloatArray(4); private val q3 = FloatArray(4); private val qc = FloatArray(4)
    private val v1 = FloatArray(3); private val v2 = FloatArray(3); private val v3 = FloatArray(3)
    private val free = FloatArray(3); private val tl = FloatArray(3); private val tg = FloatArray(3)
    private val ee = FloatArray(3); private val wr = FloatArray(3)

    // ------------------------------------------------------------------ small quaternion helpers (x, y, z, w)
    /** o = a * b (o must not be a or b) */
    private fun qmul(a: FloatArray, b: FloatArray, o: FloatArray) {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]; val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        o[0] = aw * bx + ax * bw + ay * bz - az * by
        o[1] = aw * by - ax * bz + ay * bw + az * bx
        o[2] = aw * bz + ax * by - ay * bx + az * bw
        o[3] = aw * bw - ax * bx - ay * by - az * bz
    }
    private fun qnorm(q: FloatArray) {
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (l > 1e-6f) { q[0] /= l; q[1] /= l; q[2] /= l; q[3] /= l } else { q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f }
    }
    /** o = q rotated vector (vx, vy, vz) */
    private fun qrot(q: FloatArray, vx: Float, vy: Float, vz: Float, o: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val tx = 2f * (y * vz - z * vy); val ty = 2f * (z * vx - x * vz); val tz = 2f * (x * vy - y * vx)
        o[0] = vx + w * tx + (y * tz - z * ty); o[1] = vy + w * ty + (z * tx - x * tz); o[2] = vz + w * tz + (x * ty - y * tx)
    }
    /** o = shortest-arc rotation a -> b (both unit vectors) */
    private fun qarc(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, o: FloatArray) {
        val dt = ax * bx + ay * by + az * bz
        if (dt > 0.999999f) { o[0] = 0f; o[1] = 0f; o[2] = 0f; o[3] = 1f; return }
        if (dt < -0.999999f) {
            var cx = ay * 0f - az * 0f; var cy = az * 1f - ax * 0f; var cz = ax * 0f - ay * 1f   // a x (1,0,0)
            var l = sqrt(cx * cx + cy * cy + cz * cz)
            if (l < 1e-4f) { cx = ay * 1f - az * 0f; cy = az * 0f - ax * 1f; cz = 0f; l = sqrt(cx * cx + cy * cy + cz * cz) }   // a x (0,0,1)
            o[0] = cx / l; o[1] = cy / l; o[2] = cz / l; o[3] = 0f; return
        }
        val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
        o[0] = cx; o[1] = cy; o[2] = cz; o[3] = 1f + dt; qnorm(o)
    }
    /** o = normalised lerp identity -> q with weight w (q is the short-side quaternion, w >= 0 expected) */
    private fun qpart(q: FloatArray, w: Float, o: FloatArray) {
        val s = if (q[3] < 0f) -1f else 1f
        o[0] = q[0] * s * w; o[1] = q[1] * s * w; o[2] = q[2] * s * w; o[3] = 1f + (q[3] * s - 1f) * w; qnorm(o)
    }
    private fun len3(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    /**
     * @param f       the pose (PistolState.cur), K floats
     * @param uq0 fq0 e0   the baked rifle hold of the gun in the hands (GunHold.arms) [0] left [1] right (e0 = elbow offsets)
     * @param leftMin minimum weight of the left hand on the gun (1 = long guns are never one-handed)
     * @param aimW    0..1 how much the upper body is in an aim pose (PistolState.aimComp): fades the aim-line correction in
     * @param moving  0..1 how fast the character moves (the legs share the aim yaw only while standing)
     */
    fun solve(f: FloatArray, uq0: Array<FloatArray>, fq0: Array<FloatArray>, e0: Array<FloatArray>, leftMin: Float, aimW: Float, moving: Float) {
        // --- baked wrists of the rifle hold (chest space): shoulder + elbow + forearm vector
        for (i in 0..1) {
            qrot(fq0[i], RF[i][0], RF[i][1], RF[i][2], v1)
            w0[i][0] = (if (i == 0) SX0 else SX1) + e0[i][0] + v1[0]; w0[i][1] = e0[i][1] + v1[1]; w0[i][2] = e0[i][2] + v1[2]
        }
        val rx = w0[1][0]; val ry = w0[1][1]; val rz = w0[1][2]
        pivot[0] = rx; pivot[1] = ry; pivot[2] = rz
        for (k in 0..3) qD[k] = f[24 + k]; qnorm(qD)

        // --- gun displacement: small moves are relative to the hold, big moves (reload, gestures) go to the absolute gun origin
        val dd0 = f[28]; val dd1 = f[29]; val dd2 = f[30]
        val lam = if (rel) 0f else smooth01(len3(dd0, dd1, dd2) / 0.30f)
        d[0] = dd0 * (1f - lam) + (f[37] - rx) * lam
        d[1] = dd1 * (1f - lam) + (f[38] - ry) * lam
        d[2] = dd2 * (1f - lam) + (f[39] - rz) * lam
        val wG = min(1f, max(f[34], leftMin))
        free[0] = SX0 + f[31]; free[1] = f[32]; free[2] = f[33]
        // reach correction: shorten the gun move until both hands can reach it (8 passes: 5 cm worst residual, 4 passes gave 10 cm; the final clamp below removes the rest)
        // baked left wrist carried with the gun: qD * (W0L - W0R) + W0R + d
        qrot(qD, w0[0][0] - rx, w0[0][1] - ry, w0[0][2] - rz, v2)
        for (it in 0 until 8) {
            var ax = rx + d[0] - SX1; var ay = ry + d[1]; var az = rz + d[2]
            var n = len3(ax, ay, az)
            if (n > REACH) { val k = 1f - REACH / n; d[0] -= ax * k; d[1] -= ay * k; d[2] -= az * k }
            tg[0] = v2[0] + rx + d[0]; tg[1] = v2[1] + ry + d[1]; tg[2] = v2[2] + rz + d[2]
            tl[0] = free[0] + (tg[0] - free[0]) * wG; tl[1] = free[1] + (tg[1] - free[1]) * wG; tl[2] = free[2] + (tg[2] - free[2]) * wG
            ax = tl[0] - SX0; ay = tl[1]; az = tl[2]
            n = len3(ax, ay, az)
            if (n > REACH && wG > 0f) { val k = (1f - REACH / n) * min(1f, wG) * 0.7f; d[0] -= ax * k; d[1] -= ay * k; d[2] -= az * k }
        }
        // final carried left wrist
        tg[0] = v2[0] + rx + d[0]; tg[1] = v2[1] + ry + d[1]; tg[2] = v2[2] + rz + d[2]
        tl[0] = free[0] + (tg[0] - free[0]) * wG; tl[1] = free[1] + (tg[1] - free[1]) * wG; tl[2] = free[2] + (tg[2] - free[2]) * wG
        // v55 reload: the left hand goes to a point of the gun (the magazine): chest = pivot + d + qD * (-pivot + gunO + 0.85 * point)  (0.85 = scale of the gun model in the hands)
        if (lhMagW > 0.001f) {
            val wm = if (lhMagW > 1f) 1f else lhMagW
            qrot(qD, -rx + gunO[0] + 0.85f * lhMagPt[0], -ry + gunO[1] + 0.85f * lhMagPt[1], -rz + gunO[2] + 0.85f * lhMagPt[2], v3)
            tl[0] += (rx + d[0] + v3[0] - tl[0]) * wm; tl[1] += (ry + d[1] + v3[1] - tl[1]) * wm; tl[2] += (rz + d[2] + v3[2] - tl[2]) * wm
        }
        // last resort for the free / weakly gripping left hand: the loop above only pulls the gun by wG * 0.7 per pass, so a hand that is mostly free (idle, lower, aim1, comms)
        // kept 2-5 cm of shortfall. Pull the left TARGET itself onto the reach sphere of the left shoulder (the gun is not moved, so the right hand stays exact).
        run {
            val lx = tl[0] - SX0; val ly = tl[1]; val lz = tl[2]
            val ln = len3(lx, ly, lz)
            if (ln > REACH) { val k = REACH / ln; tl[0] = SX0 + lx * k; tl[1] = ly * k; tl[2] = lz * k }
        }

        // --- 2-bone IK of both arms
        for (s in 0..1) {
            val sx = if (s == 0) SX0 else SX1
            val tx: Float; val ty: Float; val tz: Float; val wg: Float
            if (s == 1) { tx = rx + d[0] - sx; ty = ry + d[1]; tz = rz + d[2]; wg = 1f }
            else { tx = tl[0] - sx; ty = tl[1]; tz = tl[2]; wg = if (lhMagW > wG) (if (lhMagW > 1f) 1f else lhMagW) else wG }
            // elbow hint: free arm = elbow out and down, arm on the gun = the baked elbow direction turned with the gun
            val sg = if (s == 0) -1f else 1f
            qrot(qD, e0[s][0], e0[s][1], e0[s][2], v3)
            val hx = sg * 0.35f + (v3[0] - sg * 0.35f) * wg; val hy = -0.9f + (v3[1] + 0.9f) * wg; val hz = 0.10f + (v3[2] - 0.10f) * wg
            val dist = len3(tx, ty, tz)
            val lo = abs(LU - LF) + 1e-3f; val hi = LU + LF - 1e-3f
            val sh = max(0f, dist - hi); if (s == 0) shortL = sh else shortR = sh
            val dc = min(hi, max(lo, dist))
            val ux: Float; val uy: Float; val uz: Float
            if (dist > 1e-5f) { ux = tx / dist; uy = ty / dist; uz = tz / dist } else { ux = 0f; uy = -1f; uz = 0f }
            val a = (LU * LU - LF * LF + dc * dc) / (2f * dc)
            val h = sqrt(max(0f, LU * LU - a * a))
            val hu = hx * ux + hy * uy + hz * uz
            var px = hx - ux * hu; var py = hy - uy * hu; var pz = hz - uz * hu
            var pl = len3(px, py, pz)
            if (pl < 1e-5f) { px = uy * 0f - uz * 1f; py = uz * 0f - ux * 0f; pz = ux * 1f - uy * 0f; pl = len3(px, py, pz) }   // u x (0,1,0)
            if (pl < 1e-6f) { px = 1f; py = 0f; pz = 0f; pl = 1f }
            px /= pl; py /= pl; pz /= pl
            ee[0] = ux * a + px * h; ee[1] = uy * a + py * h; ee[2] = uz * a + pz * h
            wr[0] = ux * dc; wr[1] = uy * dc; wr[2] = uz * dc
            elbow[s][0] = ee[0]; elbow[s][1] = ee[1]; elbow[s][2] = ee[2]
            // hand / arm roll follows the gun (partly for the free hand): start from qD * baked, then the shortest arc onto the new bone direction
            qpart(qD, wg, q1)
            qmul(q1, uq0[s], q2)                                   // q2 = start upper-arm rotation
            qrot(q2, RU[s][0], RU[s][1], RU[s][2], v1)
            var l1 = len3(v1[0], v1[1], v1[2]); val el = len3(ee[0], ee[1], ee[2])
            qarc(v1[0] / l1, v1[1] / l1, v1[2] / l1, ee[0] / el, ee[1] / el, ee[2] / el, q3)
            qmul(q3, q2, upQ[s]); qnorm(upQ[s])
            qmul(q1, fq0[s], q2)                                   // start forearm rotation
            qrot(q2, RF[s][0], RF[s][1], RF[s][2], v1)
            l1 = len3(v1[0], v1[1], v1[2])
            val fx = wr[0] - ee[0]; val fy = wr[1] - ee[1]; val fz = wr[2] - ee[2]; val fl = len3(fx, fy, fz)
            qarc(v1[0] / l1, v1[1] / l1, v1[2] / l1, fx / fl, fy / fl, fz / fl, q3)
            qmul(q3, q2, foQ[s]); qnorm(foQ[s])
        }

        // --- aim-line correction. Gun world direction = chest * Rx(pitch) * qD * (0,0,-1) (the baked hold looks along the chest's -z).
        for (k in 0..3) qc[k] = f[k]; qnorm(qc)
        val w = if (aimW < 0f) 0f else if (aimW > 1f) 1f else aimW
        // 1) level the gun: tilt the arms about the shoulder line until the muzzle is horizontal (2 passes, the chest lean makes it non-linear)
        var dl = 0f
        for (it in 0..1) {
            val hx2 = dl * 0.5f; q2[0] = sin(hx2); q2[1] = 0f; q2[2] = 0f; q2[3] = cos(hx2)   // Rx(dl)
            qmul(qc, q2, q3); qmul(q3, qD, q1)
            qrot(q1, 0f, 0f, -1f, v1)
            dl -= asin(v1[1].coerceIn(-1f, 1f))
        }
        dl = dl.coerceIn(-0.7f, 0.7f)
        armPitch = dl * w
        // 2) yaw about the vertical axis (applied outermost, so it does not change the pitch) brings the muzzle onto the aim line
        val hx3 = armPitch * 0.5f; q2[0] = sin(hx3); q2[1] = 0f; q2[2] = 0f; q2[3] = cos(hx3)
        qmul(qc, q2, q3); qmul(q3, qD, q1)
        qrot(q1, 0f, 0f, -1f, v1)
        val yaw = -atan2(-v1[0], -v1[2]) * w
        legYaw = yaw * LEG_YAW_SHARE * (1f - moving); chestYaw = yaw - legYaw
        val hy2 = chestYaw * 0.5f; q2[0] = 0f; q2[1] = sin(hy2); q2[2] = 0f; q2[3] = cos(hy2)
        qmul(q2, qc, chestQ); qnorm(chestQ)
        drop = footDrop(f)
    }

    /** y of the ankle of one baked leg relative to the hip (thigh world quat at [t0], shin local quat at [s0]) */
    private fun ankleY(f: FloatArray, t0: Int, s0: Int): Float {
        for (k in 0..3) q1[k] = f[t0 + k]; qnorm(q1)
        for (k in 0..3) q2[k] = f[s0 + k]; qnorm(q2)
        qrot(q1, 0f, -THIGH, -THIGH_Z, v1)
        qrot(q2, 0f, -SHIN, 0f, v2)
        qrot(q1, v2[0], v2[1], v2[2], v3)
        return v1[1] + v3[1]
    }

    /**
     * Foot grounding: the hips are lowered so the lower foot of the baked pose stands on the floor. A soft minimum of the two ankles
     * (no kink when the feet pass each other, so the hips do not jerk).
     */
    fun footDrop(f: FloatArray): Float {
        val yl = ankleY(f, 8, 12); val yr = ankleY(f, 16, 20)
        val k = 0.03f
        val m = min(yl, yr)
        val sm = m - k * ln(exp(-(yl - m) / k) + exp(-(yr - m) / k))
        return (HIP_Y + sm).coerceIn(0f, 0.75f)
    }
}
