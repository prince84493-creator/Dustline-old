package com.bijnor.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sound engine. The HTML builds every effect with WebAudio oscillators + filtered noise (audio.event()).
 * Here the very same recipes (frequency sweeps, noise cut-offs, gains, delays from audio.event) are rendered once
 * into PCM, written as small WAV files in the cache and played with SoundPool (low latency, stereo pan, distance gain).
 * Loops: wind (freefall / parachute) and the aircraft motor.
 */
class SoundFx(private val ctx: Context) {
    private val SR = 22050
    private var pool: SoundPool? = null
    private val ids = ConcurrentHashMap<String, Int>()
    private val loaded = ConcurrentHashMap<Int, Boolean>()
    private var windStream = 0
    private var motorStream = 0
    private val main = Handler(Looper.getMainLooper())   // v29: sound that travels far arrives late (speed of sound)

    // v45 REAL LOADING: the loading screen waits until every sound is synthesised AND loaded into the SoundPool
    @Volatile private var built = false
    @Volatile private var total = 0
    @Volatile private var builtAt = 0L
    val ready: Boolean get() = built && (loaded.size >= total || System.nanoTime() - builtAt > 4_000_000_000L)
    val progress: Float get() = if (ready) 1f else if (built && total > 0) (loaded.size.toFloat() / total).coerceAtMost(0.98f) else 0f

    init {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val sp = SoundPool.Builder().setMaxStreams(16).setAudioAttributes(attrs).build()
        pool = sp
        sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded[id] = true }
        Thread {
            try {
                val dir = File(ctx.cacheDir, "sfx"); dir.mkdirs()
                val all = renderAll(); total = all.size
                for ((name, pcm) in all) {
                    val f = File(dir, "$name.wav"); writeWav(f, pcm)
                    ids[name] = sp.load(f.absolutePath, 1)
                }
            } catch (e: Throwable) { /* no sound is better than a crash */ }
            finally { if (total == 0 || ids.size < total) total = ids.size; builtAt = System.nanoTime(); built = true }
        }.start()
    }

    // ------------------------------------------------------------------ playback
    private fun idOf(name: String): Int { val id = ids[name] ?: return 0; return if (loaded[id] == true) id else 0 }

    fun play(name: String, gain: Float = 1f, pan: Float = 0f) {
        if (!Prefs.sfx) return
        val id = idOf(name); if (id == 0) return
        val v = (Prefs.vol / 100f * gain * 1.6f).coerceIn(0f, 1f)
        if (v < 0.004f) return
        val l = v * (if (pan > 0f) 1f - pan else 1f); val r = v * (if (pan < 0f) 1f + pan else 1f)
        pool?.play(id, l, r, 1, 0, 1f)
    }

    /** Same distance gain / pan as audio.position(): gain 1/(1+d*.12), silent beyond 95 m. */
    fun playAt(name: String, x: Float, z: Float, lx: Float, lz: Float, yaw: Float, gain: Float = 1f) {
        val dx = x - lx; val dz = z - lz; val d = hypot(dx, dz)
        if (d > 95f) return
        val pan = ((dx * cos(yaw) + dz * sin(yaw)) / max(4f, d)).coerceIn(-0.9f, 0.9f)
        play(name, gain / (1f + d * 0.12f), pan)
    }

    /** v29: sound made by the player himself: inside a house it rings with a short room reverb. */
    fun playOwn(name: String, indoor: Boolean, gain: Float = 1f) {
        play(if (indoor && ids.containsKey(name + "_in")) name + "_in" else name, gain)
    }

    /**
     * v29 REAL 3-D sound. dist = metres from the ears, walls = solid partitions in between (0..3), srcIn / lisIn = source / listener are under a roof,
     * range = how far this sound carries in the open air.
     *  - volume falls with distance and reaches zero at `range` (each wall cuts the range by 38 % and the volume by 40 %)
     *  - far sounds lose their highs and get an echo tail (name_far), sounds behind a wall are dull and thick (name_muf),
     *    sounds with both of you inside a house ring with a room reverb (name_in)
     *  - a sound that travels farther than 25 m arrives late: 343 m/s
     */
    fun playWorld(name: String, dist: Float, pan: Float, walls: Int, srcIn: Boolean, lisIn: Boolean, range: Float, gain: Float = 1f) {
        if (!Prefs.sfx) return
        var r = range
        for (i in 0 until walls) r *= 0.62f
        if (dist >= r) return
        val k = 1f - dist / r
        var g = gain * k * k / (1f + dist * 0.03f)
        for (i in 0 until walls) g *= 0.6f
        var v = name
        if (walls > 0) v = name + "_muf"
        else if (dist > range * 0.35f) v = name + "_far"
        else if (srcIn && lisIn) v = name + "_in"
        if (!ids.containsKey(v)) v = name
        val p = pan * (if (walls > 0) 0.6f else 1f)
        val delay = if (dist > 25f) ((dist - 25f) / 343f * 1000f).toLong() else 0L
        if (delay <= 0L) play(v, g, p) else main.postDelayed({ play(v, g, p) }, delay)
    }

    /** level 0..1 for the freefall / parachute wind loop and the aircraft motor loop. */
    fun ambience(wind: Float, motor: Float) {
        val sp = pool ?: return
        loop("wind", wind, true, sp); loop("motor", motor, false, sp)
    }

    private fun loop(name: String, level: Float, wind: Boolean, sp: SoundPool) {
        val stream = if (wind) windStream else motorStream
        val v = if (Prefs.sfx) (level * Prefs.vol / 100f * 3f).coerceIn(0f, 1f) else 0f
        if (v < 0.003f) { if (stream != 0) { sp.stop(stream); if (wind) windStream = 0 else motorStream = 0 }; return }
        if (stream == 0) {
            val id = idOf(name); if (id == 0) return
            val s = sp.play(id, v, v, 1, -1, 1f); if (wind) windStream = s else motorStream = s
        } else sp.setVolume(stream, v, v)
    }

    fun stopAll() {
        val sp = pool ?: return
        if (windStream != 0) sp.stop(windStream); if (motorStream != 0) sp.stop(motorStream); windStream = 0; motorStream = 0
    }

    fun release() { main.removeCallbacksAndMessages(null); stopAll(); pool?.release(); pool = null }

    // ------------------------------------------------------------------ synthesis (audio.tone / audio.hiss / audio.voice)
    private class Buf(val sr: Int, seconds: Float, seed: Int) {
        val d = FloatArray((seconds * sr).toInt())
        private val rnd = Random(seed)
        /** voice(): 5 ms exponential attack to `g`, then exponential decay to 0.0001 at `dur`. */
        private fun env(t: Float, dur: Float, g: Float): Float {
            val a = 0.005f; val g0 = max(g, 0.0002f)
            return if (t < a) 0.0001f * (g0 / 0.0001f).pow(t / a) else g0 * (0.0001f / g0).pow(((t - a) / (dur - a)).coerceIn(0f, 1f))
        }
        /** type: 0 sine, 1 triangle, 2 square, 3 sawtooth. Exponential frequency ramp f0 -> f1. */
        fun tone(f0: Float, f1: Float, dur: Float, g: Float, type: Int = 0, delay: Float = 0f) {
            val s0 = (delay * sr).toInt(); val n = (dur * sr).toInt(); var ph = 0.0
            val fe = max(15f, f1)
            for (i in 0 until n) {
                val idx = s0 + i; if (idx >= d.size) break
                val t = i.toFloat() / sr
                val f = f0 * (fe / f0).pow(t / dur)
                ph += f.toDouble() / sr
                val x = (ph - floor(ph)).toFloat()
                val w = when (type) { 0 -> sin(2.0 * PI * x).toFloat(); 1 -> 4f * abs(x - 0.5f) - 1f; 2 -> if (x < 0.5f) 1f else -1f; else -> 2f * x - 1f }
                d[idx] += w * env(t, dur, g)
            }
        }
        /** white noise through a 2-pole low-pass at `cutoff` Hz. */
        fun hiss(dur: Float, g: Float, cutoff: Float, delay: Float = 0f) {
            val s0 = (delay * sr).toInt(); val n = (dur * sr).toInt()
            val a = (1.0 - exp(-2.0 * PI * cutoff / sr)).toFloat()
            var y1 = 0f; var y2 = 0f
            for (i in 0 until n) {
                val idx = s0 + i; if (idx >= d.size) break
                val t = i.toFloat() / sr
                y1 += a * ((rnd.nextFloat() * 2f - 1f) - y1); y2 += a * (y1 - y2)
                d[idx] += y2 * 2.2f * env(t, dur, g)
            }
        }
    }

    private fun ev(seconds: Float, seed: Int = 1, f: (Buf) -> Unit): ShortArray {
        val b = Buf(SR, seconds, seed); f(b)
        return ShortArray(b.d.size) { (b.d[it].coerceIn(-1f, 1f) * 32000f).toInt().toShort() }
    }

    private fun renderAll(): Map<String, ShortArray> {
        val m = LinkedHashMap<String, ShortArray>()
        // shot index = weapon slot: 0 long gun, 1 short gun, 2 sniper
        m["shot0"] = ev(0.2f, 2) { it.hiss(.17f, .4f, 4200f); it.tone(180f, 38f, .09f, .38f, 1) }
        m["shot1"] = ev(0.12f, 3) { it.hiss(.085f, .4f, 4200f); it.tone(180f, 38f, .09f, .38f, 1) }
        m["shot2"] = ev(0.6f, 4) { it.hiss(.5f, .7f, 1900f); it.tone(105f, 38f, .36f, .7f, 1); it.tone(720f, 230f, .08f, .045f, 2, .16f) }
        m["reload"] = ev(0.36f, 5) { it.hiss(.1f, .13f, 2800f); it.tone(330f, 110f, .055f, .12f, 2); it.hiss(.09f, .16f, 4200f, .22f) }
        m["ready"] = ev(0.1f, 6) { it.tone(480f, 160f, .05f, .12f, 2); it.hiss(.045f, .12f, 4300f) }
        m["step"] = ev(0.08f, 7) { it.hiss(.055f, .14f, 650f); it.tone(115f, 50f, .045f, .11f) }
        m["jump"] = ev(0.14f, 8) { it.hiss(.12f, .1f, 750f) }
        m["splash"] = ev(0.32f, 31) { it.hiss(.32f, .22f, 900f) }
        m["swim"] = ev(0.14f, 32) { it.hiss(.10f, .14f, 700f) }
        m["shotS"] = ev(0.14f, 51) { it.hiss(.09f, .12f, 1800f); it.tone(260f, 90f, .05f, .12f, 1) }   // suppressed (VSS)
        m["shotG"] = ev(0.3f, 52) { it.hiss(.26f, .7f, 2600f); it.tone(140f, 30f, .16f, .6f, 1) }   // shotgun
        m["land"] = ev(0.18f, 9) { it.hiss(.15f, .22f, 500f); it.tone(95f, 30f, .13f, .25f) }
        m["chute"] = ev(0.65f, 10) { it.hiss(.6f, .36f, 1400f); it.tone(130f, 40f, .18f, .2f, 1) }
        m["throw"] = ev(0.16f, 11) { it.hiss(.13f, .16f, 2300f); it.tone(650f, 320f, .045f, .07f, 2) }
        m["bounce"] = ev(0.09f, 12) { it.tone(230f, 80f, .065f, .22f, 1) }
        m["blast"] = ev(0.95f, 13) { it.hiss(.9f, .8f, 1050f); it.tone(95f, 22f, .65f, .95f, 0) }
        m["hit"] = ev(0.07f, 14) { it.tone(950f, 390f, .045f, .08f, 1) }
        m["hurt"] = ev(0.15f, 15) { it.hiss(.11f, .2f, 500f); it.tone(150f, 45f, .12f, .18f) }
        m["win"] = ev(1.5f, 40) { it.tone(523f, 523f, .2f, .13f, 2); it.tone(659f, 659f, .2f, .13f, 2, .16f); it.tone(784f, 784f, .22f, .13f, 2, .32f); it.tone(1047f, 1047f, .7f, .15f, 2, .5f); it.tone(1319f, 1319f, .6f, .08f, 0, .5f) }
        m["firework"] = ev(0.8f, 41) { it.tone(1200f, 300f, .22f, .1f, 1); it.hiss(.55f, .5f, 2600f, .18f); it.tone(120f, 40f, .4f, .4f, 0, .18f) }
        m["pickup"] = ev(0.25f, 16) { it.tone(560f, 560f, .1f, .1f); it.tone(840f, 840f, .14f, .08f, 0, .08f) }
        m["heal"] = ev(0.36f, 17) { it.tone(440f, 700f, .17f, .1f); it.tone(740f, 900f, .16f, .09f, 0, .14f) }
        m["alert"] = ev(0.4f, 18) { it.tone(880f, 880f, .1f, .12f, 1); it.tone(1175f, 1175f, .16f, .12f, 1, .12f) }
        m["glass"] = ev(0.55f, 61) { it.hiss(.06f, .9f, 9000f); it.tone(3300f, 2500f, .2f, .16f); it.tone(4800f, 3600f, .16f, .12f, 0, .03f); it.hiss(.34f, .28f, 7500f, .05f) }   // v30 window breaks
        m["doorc"] = ev(0.3f, 63) { it.hiss(.07f, .45f, 520f); it.tone(95f, 40f, .14f, .65f, 1, .05f); it.tone(1100f, 700f, .03f, .16f, 1, .17f) }   // v38 door shuts: wood thud + latch click
        m["whiz"] = ev(0.22f, 71) { it.tone(3400f, 700f, .16f, .26f, 3); it.hiss(.12f, .16f, 7000f) }    // v44 supersonic bullet crack as it passes
        m["whizS"] = ev(0.16f, 72) { it.tone(1500f, 420f, .11f, .16f, 1); it.hiss(.08f, .10f, 3500f) }   // v44 subsonic bullet (VSS / VSK): only a soft whoosh
        m["jam"] = ev(0.14f, 73) { it.tone(1300f, 500f, .025f, .22f, 2); it.hiss(.03f, .2f, 2600f, .07f); it.tone(260f, 120f, .04f, .14f, 1, .09f) }   // v44 dry click of a jammed gun
        m["unjam"] = ev(0.45f, 74) { it.tone(420f, 160f, .05f, .16f, 2); it.hiss(.06f, .14f, 3200f, .12f); it.tone(520f, 220f, .05f, .16f, 2, .34f) }   // v44 charging handle: clearing the jam
        m["door"] = ev(0.3f, 62) { it.tone(150f, 65f, .13f, .5f, 1); it.hiss(.2f, .1f, 700f, .05f) }   // v30 door swings
        // v29 real acoustics: every important sound also exists as _far (distance: dull + echo tail), _muf (behind a wall: very dull) and _in (room reverb)
        for (base in listOf("shot0", "shot1", "shot2", "shotS", "shotG", "blast", "land", "step", "jump", "bounce")) {
            val src = m[base] ?: continue
            val heavy = base.startsWith("shot") || base == "blast"
            m[base + "_muf"] = lowPass(lowPass(src, 380f), 380f)
            m[base + "_far"] = if (heavy || base == "land") echo(lowPass(src, 1400f), floatArrayOf(0.17f, 0.36f), floatArrayOf(0.35f, 0.18f))
                               else lowPass(src, 1400f)   // far footsteps are only duller, they get no echo tail
            m[base + "_in"] = if (heavy) echo(lowPass(src, 7000f), floatArrayOf(0.025f, 0.05f, 0.085f, 0.13f), floatArrayOf(0.5f, 0.38f, 0.26f, 0.16f))
                              else echo(src, floatArrayOf(0.03f, 0.06f), floatArrayOf(0.22f, 0.12f))
        }
        // loops: wind (noise, low-pass 950 Hz) and aircraft motor (61 Hz saw through a 190 Hz low-pass)
        m["wind"] = run {
            val n = SR * 2; val a = (1.0 - exp(-2.0 * PI * 950.0 / SR)).toFloat(); val r = Random(99)
            var y1 = 0f; var y2 = 0f; val f = FloatArray(n)
            for (i in 0 until n) { y1 += a * ((r.nextFloat() * 2f - 1f) - y1); y2 += a * (y1 - y2); f[i] = y2 * 2.4f }
            val fade = SR / 10 // cross-fade the tail into the head so the loop seam does not click
            for (i in 0 until fade) { val k = i / fade.toFloat(); f[i] = f[i] * k + f[n - fade + i] * (1f - k) }
            ShortArray(n - fade) { (f[it].coerceIn(-1f, 1f) * 24000f).toInt().toShort() }
        }
        m["motor"] = run {
            val n = SR; val f = FloatArray(n); val a = (1.0 - exp(-2.0 * PI * 190.0 / SR)).toFloat(); var y = 0f; var y2 = 0f
            for (i in 0 until n) { val x = (61.0 * i / SR) % 1.0; y += a * ((2.0 * x - 1.0).toFloat() - y); y2 += a * (y - y2); f[i] = y2 * 1.8f }
            ShortArray(n) { (f[it].coerceIn(-1f, 1f) * 24000f).toInt().toShort() }
        }
        return m
    }

    /** two 1-pole low-passes in a row are made by calling this twice */
    private fun lowPass(a: ShortArray, cutoff: Float): ShortArray {
        val k = (1.0 - exp(-2.0 * PI * cutoff / SR)).toFloat()
        val o = ShortArray(a.size); var y = 0f
        for (i in a.indices) { y += k * (a[i].toFloat() - y); o[i] = y.toInt().toShort() }
        return o
    }

    /** adds delayed, quieter copies (room reflections / far echoes) behind the sound */
    private fun echo(a: ShortArray, delays: FloatArray, gains: FloatArray): ShortArray {
        var extra = 0; for (d in delays) extra = max(extra, (d * SR).toInt())
        val o = FloatArray(a.size + extra)
        for (i in a.indices) o[i] += a[i].toFloat()
        for (q in delays.indices) { val s0 = (delays[q] * SR).toInt(); for (i in a.indices) o[i + s0] += a[i].toFloat() * gains[q] }
        return ShortArray(o.size) { o[it].coerceIn(-32000f, 32000f).toInt().toShort() }
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        FileOutputStream(f).use { o ->
            val dataLen = pcm.size * 2
            fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun i16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            o.write("RIFF".toByteArray()); o.write(i32(36 + dataLen)); o.write("WAVE".toByteArray()); o.write("fmt ".toByteArray())
            o.write(i32(16)); o.write(i16(1)); o.write(i16(1)); o.write(i32(SR)); o.write(i32(SR * 2)); o.write(i16(2)); o.write(i16(16))
            o.write("data".toByteArray()); o.write(i32(dataLen))
            val b = ByteArray(dataLen); for (i in pcm.indices) { b[i * 2] = pcm[i].toInt().toByte(); b[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte() }
            o.write(b)
        }
    }
}
