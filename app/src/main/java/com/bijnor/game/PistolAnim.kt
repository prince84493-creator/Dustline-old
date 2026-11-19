package com.bijnor.game

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Pistol animation pack (all 17 clips + 2 recoil clips), baked by tools/pistol_anim/bake_pistol.js into assets/models/anim/pistol.anim.
 *
 * One frame = K (40) floats, game axes (-Z forward, -X left), positions in game character units:
 *   [0..3]   chest (torso) world rotation quaternion x,y,z,w
 *   [4..7]   head rotation relative to the chest
 *   [8..11]  left thigh world rotation      [12..15] left shin rotation relative to the thigh
 *   [16..19] right thigh world rotation     [20..23] right shin rotation relative to the thigh
 *   [24..27] gun rotation relative to the two-hand aim hold (quaternion)
 *   [28..30] gun displacement relative to the aim hold (chest space)
 *   [31..33] left wrist relative to the left shoulder (chest space)
 *   [34]     weight 0..1 of the left hand being on the gun    [35] muzzle flash 0/1    [36] slide 0..1    [37..39] absolute gun origin (chest space)
 */
object PistolAnim {
    const val K = 40
    // clip indices (same order as LIST in bake_pistol.js)
    const val IDLE = 0; const val WALK = 1; const val RUN = 2; const val TURN_L = 3; const val TURN_R = 4
    const val READY = 5; const val AIM1 = 6; const val AIM2 = 7; const val HIGH = 8; const val LOWER = 9; const val COMMS = 10
    const val RELOAD = 11; const val CROUCH_DOWN = 12; const val CROUCH_IDLE = 13; const val CROUCH_WALK = 14
    const val CROUCH_RELOAD = 15; const val STAND_UP = 16; const val KICK2 = 17; const val KICK1 = 18
    /** time (s) inside AIM1 / AIM2 where the gun is held steady */
    const val AIM1_HOLD = 1.10f; const val AIM2_HOLD = 1.05f
    /** first / last frame index of the slots that hold quaternions (blended with a sign-safe nlerp) */
    private val QUAT_SLOTS = intArrayOf(0, 4, 8, 12, 16, 20, 24)

    class Clip(val fps: Float, val dur: Float, val loop: Boolean, val speed: Float, val n: Int, val data: FloatArray)

    @Volatile var clips: Array<Clip> = emptyArray(); private set
    @Volatile var ready = false; private set

    /** Loads assets/models/anim/pistol.anim once. Returns false (and the game keeps its old animation) if the file is missing or damaged. */
    @Synchronized fun load(ctx: Context): Boolean {
        if (ready) return true
        try {
            val b = ctx.assets.open("models/anim/pistol.anim").use { it.readBytes() }
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            if (b.size < 12 || bb.getInt(0) != 0x4D4E4150) return false   // "PANM"
            val count = bb.getInt(4); val k = bb.getInt(8)
            if (k != K || count <= 0) return false
            var o = 12
            val list = ArrayList<Clip>(count)
            for (i in 0 until count) {
                val fps = bb.getFloat(o); val dur = bb.getFloat(o + 4); val loop = bb.getInt(o + 8) == 1
                val v = bb.getFloat(o + 12); val n = bb.getInt(o + 16)
                o += 24
                if (n < 2 || o + n * K * 4 > b.size) return false
                val d = FloatArray(n * K)
                for (j in d.indices) d[j] = bb.getFloat(o + j * 4)
                o += n * K * 4
                list.add(Clip(fps, dur, loop, v, n, d))
            }
            clips = list.toTypedArray(); ready = true
            return true
        } catch (e: Throwable) { return false }
    }

    fun clipDuration(ci: Int): Float = if (ready && ci in clips.indices) clips[ci].dur else 0f
    fun clipLoops(ci: Int): Boolean = ready && ci in clips.indices && clips[ci].loop
    /** ground speed (m/s) the clip was made for (walk / run / crouch walk); 0 for the others - use it to set the playback rate */
    fun clipSpeed(ci: Int): Float = if (ready && ci in clips.indices) clips[ci].speed else 0f

    /** Pose of clip [ci] at clip time [t] (seconds, looped clips wrap, others clamp) -> out[0 until K]. */
    fun sample(ci: Int, t: Float, out: FloatArray) {
        if (!ready || ci !in clips.indices) return
        val c = clips[ci]
        var tt = t
        if (c.loop) { tt %= c.dur; if (tt < 0f) tt += c.dur } else tt = tt.coerceIn(0f, c.dur)
        val f = (tt * c.fps).coerceIn(0f, (c.n - 1).toFloat())
        val i0 = floor(f).toInt().coerceIn(0, c.n - 1)
        val i1 = minOf(i0 + 1, c.n - 1)
        val a = f - i0
        mix(c.data, i0 * K, c.data, i1 * K, a, out)
    }

    /** out = a * (1 - w) + b * w (quaternions nlerp'd on the short side). a, b, out may be the same array. */
    fun blend(a: FloatArray, b: FloatArray, w: Float, out: FloatArray) = mix(a, 0, b, 0, w.coerceIn(0f, 1f), out)

    private fun mix(a: FloatArray, ao: Int, b: FloatArray, bo: Int, w: Float, out: FloatArray) {
        val qa = FloatArray(4); val qb = FloatArray(4)
        // plain slots first (positions, weights, fx)
        for (k in 28 until K) out[k] = a[ao + k] + (b[bo + k] - a[ao + k]) * w
        for (s in QUAT_SLOTS) {
            for (j in 0..3) { qa[j] = a[ao + s + j]; qb[j] = b[bo + s + j] }
            val dot = qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]
            val sg = if (dot < 0f) -1f else 1f
            var x = qa[0] + (qb[0] * sg - qa[0]) * w; var y = qa[1] + (qb[1] * sg - qa[1]) * w
            var z = qa[2] + (qb[2] * sg - qa[2]) * w; var q = qa[3] + (qb[3] * sg - qa[3]) * w
            val l = sqrt(x * x + y * y + z * z + q * q)
            if (l > 1e-6f) { x /= l; y /= l; z /= l; q /= l } else { x = 0f; y = 0f; z = 0f; q = 1f }
            out[s] = x; out[s + 1] = y; out[s + 2] = z; out[s + 3] = q
        }
    }
}
