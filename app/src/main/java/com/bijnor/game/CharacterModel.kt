package com.bijnor.game

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30 as GL
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rigged character of the Android build (SURVIVOR), ported 1:1:
 * every vertex is skinned to the same 11-bone rig (+ a 12th 'head' bone, verts above y=1.72 re-weighted exactly like crimInit()).
 * Mesh data = models/character/bin_0X.bin, rig / bind pose / rifle pose = models/character/<id>.json (taken from CRIM_DATA / SV_DATA).
 */
object CharRig {
    val BONES = arrayOf("body", "thighL", "thighR", "shinL", "shinR", "upperL", "upperR", "foreL", "foreR", "handL", "handR", "head")
    const val NB = 12
    val NECK = floatArrayOf(0f, 1.76f, -0.02f)
    const val SCALE = 0.83f * MapData.PLAYER_K // v48: mesh is ~2.15 high -> 1.78 m (MapData.PERSON_H), the size of the doors / windows of the houses
}

/** What the animation needs to know. Angles in radians, same meaning as m.left.rotation.x etc. of the HTML. */
class CharPose {
    var mode = RIFLE
    var walk = 0f        // walk phase (radians)
    var speed = 0f       // m/s
    var sprint = false
    /** v49: 0 = WALK clip, 1 = RUN clip (Loco). Only the RUN button (player) / a hurrying bot raises it; the joystick alone is always the walk. The caller sets it before every draw. */
    var runW = 0f
    var crouch = 0f      // 0..1
    var headYaw = 0f
    var headPitch = 0f
    var aimPitch = 0f    // raises / lowers the rifle arms a little
    var airT = 0f        // freefall pose blend 0..1 (CHUTE: landing flare)
    // v33 LOOT PICK-UP (PickupAnim): body bend 0..1, right arm reaching to the ground 0..1, right hand carrying the item to the backpack 0..1. The caller clears them after drawing.
    var pickup = 0f; var pickReach = 0f; var pickBag = 0f
    /** where the right hand has to be (player space: right, up, forward, metres of the world) while pickup > 0; the arm reaches it by 2-bone IK */
    var pickTx = 0f; var pickTy = 0f; var pickTz = 0f

    // JUMP (JumpAnim.kt): clip time (s) of the baked jump motion, blend 0..1 into the normal pose, root-lowering weight 0..1 (landing).
    // Only RIFLE / RELAXED use it; the caller must clear it (jumpW = 0) after drawing, the pose object is shared with the bots.
    var jumpT = -1f; var jumpW = 0f; var jumpDrop = 0f
    // written by CharModel.skin(): torso lean (rad, <0 = forward) and hip lowering (m) that were really applied, so the 3D gun can follow them
    var outLean = 0f; var outDrop = 0f
    /** v44 LEAN / PEEK: sideways body roll (rad, < 0 = leaning to the right); the caller clears it after drawing (shared with the bots) */
    var leanRoll = 0f
    // PRONE 0..1 (lying flat, face down). crouch stays 1 while prone; skin() blends crouch -> prone.
    var prone = 0f
    var supine = 0f   // 0..1 while prone: lie on the BACK (face up, feet first) instead of face down
    var swim = 0f     // 0..1 SWIMMING (front crawl): prone root + circling arms + flutter kick; walk = stroke phase (rad). Caller clears it after drawing (shared pose object)
    // written by skin() in RIFLE mode: frame of the shoulders (body space) the gun is attached to, so the 3D gun moves WITH the hands
    val gunFrame = FloatArray(16); var hasGun = false
    // id of the gun in the hands (RIFLE mode): selects the baked per-gun arm pose + grip (GunHold). null = the generic rifle pose. The caller sets it before every draw.
    var gunId: String? = null
    // PISTOL ANIMATION PACK (PistolState.kt): when set (RIFLE mode only) skin() poses legs / torso / head from the baked clips, both hands by 2-bone IK on the gun.
    // The caller sets it before the draw and clears it afterwards (the pose object is shared with the bots).
    var pa: PistolState? = null

    // BOXING (mode BOX = fists in hand): time drives the idle foot bounce; punchU = 0..1 progress of the running punch (< 0 = no punch),
    // punchKind 0 jab (lead / left), 1 cross (rear / right), 2 lead hook (left), 3 rear uppercut (right). See BoxAnim.
    var time = 0f
    var punchU = -1f
    var punchKind = 0

    // AIR mode (Phase 28): every angle is given directly - port of dskAirPose() of the HTML, which drives the skate stance,
    // the head-first dive and the parachute hang through ONE smoothed pose. Same meaning as m.armR.rotation.z etc.
    var exTw = 0f                       // torso twist (m.body.rotation.y = -tw); shoulders + head follow it
    var exArmLx = 0f; var exArmRx = 0f; var exArmLz = 0f; var exArmRz = 0f
    var exForeLx = 0f; var exForeRx = 0f; var exForeLz = 0f; var exForeRz = 0f
    var exLegLx = 0f; var exLegRx = 0f; var exLegLz = 0f; var exLegRz = 0f; var exKneeL = 0f; var exKneeR = 0f

    companion object { const val RIFLE = 0; const val RELAXED = 1; const val DIVE = 3; const val CHUTE = 4; const val AIR = 5; const val BOX = 6 }
}

/**
 * Boxing animation tables (fists in hand). Arm angles are those of CharModel.skin(): (upper arm x, upper arm z, forearm x, forearm z);
 * they were solved with a small inverse-kinematics script so the fist really lands where a boxer's fist lands (guard at the chin, straight jab,
 * cross over the centre line, hook with the elbow out, uppercut from below). Values are for the LEFT arm, the right arm uses the mirror
 * (z angles negated). Punch order of the combo: JAB (left) -> CROSS (right) -> HOOK (left) -> UPPERCUT (right).
 */
object BoxAnim {
    const val JAB = 0; const val CROSS = 1; const val HOOK = 2; const val UPPER = 3
    /** guard: elbows tucked, both fists up in front of the chin */
    val GUARD = floatArrayOf(0.336f, -0.117f, 2.222f, 0.506f)
    /** key frames per punch: rows of u, ax, az, fx, fz (u = 0..1 progress of the punch) */
    private val KEYS = arrayOf(
        arrayOf(floatArrayOf(0f, 0.336f, -0.117f, 2.222f, 0.506f), floatArrayOf(0.10f, -0.006f, -0.231f, 2.352f, 0.485f), floatArrayOf(0.36f, 1.572f, 0.133f, 0.03f, 0.204f), floatArrayOf(0.55f, 1.572f, 0.133f, 0.03f, 0.204f), floatArrayOf(1f, 0.336f, -0.117f, 2.222f, 0.506f)),
        arrayOf(floatArrayOf(0f, 0.336f, -0.117f, 2.222f, 0.506f), floatArrayOf(0.16f, -0.006f, -0.231f, 2.352f, 0.485f), floatArrayOf(0.42f, 1.545f, 0.251f, 0.03f, 0.099f), floatArrayOf(0.60f, 1.545f, 0.251f, 0.03f, 0.099f), floatArrayOf(1f, 0.336f, -0.117f, 2.222f, 0.506f)),
        arrayOf(floatArrayOf(0f, 0.336f, -0.117f, 2.222f, 0.506f), floatArrayOf(0.22f, 0.926f, -1.249f, 1.814f, 1.0f), floatArrayOf(0.50f, 1.143f, -0.306f, 1.369f, 1.0f), floatArrayOf(0.65f, 1.143f, -0.306f, 1.369f, 1.0f), floatArrayOf(1f, 0.336f, -0.117f, 2.222f, 0.506f)),
        arrayOf(floatArrayOf(0f, 0.336f, -0.117f, 2.222f, 0.506f), floatArrayOf(0.25f, -0.055f, -0.185f, 1.308f, 0.562f), floatArrayOf(0.52f, 0.813f, 0.142f, 1.647f, 0.437f), floatArrayOf(0.66f, 0.813f, 0.142f, 1.647f, 0.437f), floatArrayOf(1f, 0.336f, -0.117f, 2.222f, 0.506f))
    )
    /** u at which the fist lands (damage is applied there) and u at which it starts to come back */
    val PEAK = floatArrayOf(0.36f, 0.42f, 0.50f, 0.52f)
    val HOLD = floatArrayOf(0.55f, 0.60f, 0.65f, 0.66f)
    /** the left arm throws jab + hook, the right arm cross + uppercut */
    fun leftArm(kind: Int) = kind == JAB || kind == HOOK
    /** extra shoulder / hip twist and forward lean at the strike (stance twist is -0.22: left shoulder forward) */
    val TWIST = floatArrayOf(-0.06f, 0.40f, -0.22f, 0.28f)
    val LEAN = floatArrayOf(-0.06f, -0.10f, -0.08f, -0.04f)
    const val STANCE_TWIST = -0.22f
    /** damage, cool-down (s) and animation length (s) of every punch */
    val DAMAGE = floatArrayOf(15f, 24f, 28f, 26f)
    val COOLDOWN = floatArrayOf(0.34f, 0.46f, 0.52f, 0.50f)
    val DURATION = floatArrayOf(0.30f, 0.38f, 0.44f, 0.42f)

    private fun sstep(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** out[0..3] = ax, az, fx, fz of the LEFT-arm frame at progress u of the punch `kind` */
    fun sample(kind: Int, u: Float, out: FloatArray) {
        val k = KEYS[kind.coerceIn(0, 3)]; val uu = u.coerceIn(0f, 1f)
        var i = 0
        while (i < k.size - 2 && uu > k[i + 1][0]) i++
        val a = k[i]; val b = k[i + 1]
        val t = sstep((uu - a[0]) / (b[0] - a[0]))
        for (j in 0..3) out[j] = a[j + 1] + (b[j + 1] - a[j + 1]) * t
    }

    /** 0..1: how far the punch is thrown (0 in guard, 1 at the strike, back to 0) - drives the body twist */
    fun envelope(kind: Int, u: Float): Float {
        val kk = kind.coerceIn(0, 3)
        return if (u <= 0f) 0f else if (u < PEAK[kk]) sstep(u / PEAK[kk]) else if (u < HOLD[kk]) 1f else 1f - sstep((u - HOLD[kk]) / (1f - HOLD[kk]))
    }
}

/**
 * GAIT TIMING (v49, run v50): phase speed of the walk / run cycle (Loco). One step = pi rad of phase, a full cycle (left + right step) = 2 pi.
 * The WALK clip of the reference video has 1.9 steps/s; the game moves faster than a real walk, so the cadence rises a little with the speed (cap 2.75 steps/s)
 * but it stays the WALK clip. The RUN clip is the "running animation" video: 15 frames @ 24 fps per full cycle = 3.2 steps/s, rising a little with the speed (cap 3.7). [run] 0..1 = blend walk -> run.
 */
object Gait {
    fun cadence(v: Float, run: Float = 0f): Float {
        val w = min(1.9f + 0.27f * v, 3.0f)   // v54: steps come faster with speed (was 1.9 + 0.13 v, max 2.75): shorter strides = less foot sliding
        val r = min(3.2f + 0.045f * v, 3.7f)
        return w + (r - w) * run.coerceIn(0f, 1f)
    }
    /** phase speed (rad/s): one step = pi rad. crouch / prone move with a slower step rate. 0 when standing still. */
    fun omega(v: Float, crouch: Boolean = false, prone: Boolean = false, run: Float = 0f): Float {
        val k = (v / 0.8f).coerceIn(0f, 1f); val kk = k * k * (3f - 2f * k)
        val c = cadence(v, run) * (if (prone) 0.45f else if (crouch) 0.6f else 1f)
        return Math.PI.toFloat() * c * kk
    }
}

class CharModel(ctx: Context, val id: String) {
    class Prim(val vao: Int, val count: Int, val mat: Int, val vbo: Int = 0) {
        // kept only for meshes the store may trim (cut): CPU copy of the vertices / indices + the trimmed VAO per kind
        var vb: FloatArray? = null; var ib: IntArray? = null
        val alt = HashMap<String, IntArray>()   // kind -> [vao, indexCount]
    }

    val prims = ArrayList<Prim>()
    val texIds = ArrayList<Int>()
    val bind = Array(CharRig.NB) { FloatArray(16) }
    private val uq = Array(2) { FloatArray(4) }; private val fq = Array(2) { FloatArray(4) }; private val e1 = Array(2) { FloatArray(3) }
    private val elbow = Array(2) { FloatArray(3) }
    private val lo = Loco.Out()                                   // v49: walk / run sample of this frame
    private val outLoco = FloatArray(CharRig.NB * 16)             // v49: leg matrices of the walk / run cycle (blended over the pistol-pack legs)
    var vertexCount = 0

    init {
        val meta = JSONObject(Prot.open(ctx, "models/character/$id.json").use { String(it.readBytes()) })
        val bin = Prot.open(ctx, "models/character/" + meta.getString("bin")).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val bd = meta.getJSONObject("bind")
        for (i in 0 until 11) { val a = bd.getJSONArray(CharRig.BONES[i]); for (k in 0..15) bind[i][k] = a.getDouble(k).toFloat() }
        Matrix.setIdentityM(bind[11], 0); bind[11][12] = CharRig.NECK[0]; bind[11][13] = CharRig.NECK[1]; bind[11][14] = CharRig.NECK[2]
        val rf = meta.getJSONObject("rifle"); val rl = meta.getJSONObject("rel")
        for ((s, t) in arrayOf("L", "R").withIndex()) {
            val r = rf.getJSONObject(t)
            for (k in 0..3) { uq[s][k] = r.getJSONArray("uq").getDouble(k).toFloat(); fq[s][k] = r.getJSONArray("fq").getDouble(k).toFloat() }
            for (k in 0..2) { e1[s][k] = r.getJSONArray("E1").getDouble(k).toFloat(); elbow[s][k] = rl.getJSONObject(t).getJSONArray("elbow").getDouble(k).toFloat() }
        }
        // GUN HOLD FIX: re-targeted arm pose (2-bone IK): right hand on the grip, left hand under the handguard, gun level at chest height
        run {
            val uqL = floatArrayOf(0.381092f, -0.046634f, 0.23902f, 0.891888f); val fqL = floatArrayOf(0.557279f, -0.10629f, 0.433158f, 0.700369f); val e1L = floatArrayOf(0.119195f, -0.255274f, -0.239849f)
            val uqR = floatArrayOf(0.014167f, 0.004131f, 0.031077f, 0.999408f); val fqR = floatArrayOf(0.671785f, 0.113257f, -0.392101f, 0.61817f); val e1R = floatArrayOf(0.081052f, -0.360845f, 0.011021f)
            for (k in 0..3) { uq[0][k] = uqL[k]; fq[0][k] = fqL[k]; uq[1][k] = uqR[k]; fq[1][k] = fqR[k] }
            for (k in 0..2) { e1[0][k] = e1L[k]; e1[1][k] = e1R[k] }
        }
        val pa = meta.getJSONArray("prims")
        var skinPos: FloatArray? = null; var skinUv: FloatArray? = null; var skinIdx: IntArray? = null; var skinMat = -1   // FaceTune: face skin = prim 0
        for (pi in 0 until pa.length()) {
            val p = pa.getJSONObject(pi); val n = p.getInt("n"); val ni = p.getInt("i")
            val pos = p.getInt("pos"); val nor = p.getInt("nor"); val uv = p.getInt("uv"); val idx = p.getInt("idx"); val si = p.getInt("si"); val sw = p.getInt("sw")
            val vb = FloatArray(n * 16)
            val idi = IntArray(4); val wf = FloatArray(4)
            val keepSkin = pi == 0 && FaceTune.ENABLED
            val sPos = if (keepSkin) FloatArray(n * 3) else null; val sUv = if (keepSkin) FloatArray(n * 2) else null
            for (v in 0 until n) {
                val o = v * 16
                val px = bb.getFloat(pos + v * 12); val py = bb.getFloat(pos + v * 12 + 4); val pz = bb.getFloat(pos + v * 12 + 8)
                vb[o] = px; vb[o + 1] = py; vb[o + 2] = pz
                vb[o + 3] = bin[nor + v * 3] / 127f; vb[o + 4] = bin[nor + v * 3 + 1] / 127f; vb[o + 5] = bin[nor + v * 3 + 2] / 127f
                vb[o + 6] = bb.getFloat(uv + v * 8); vb[o + 7] = bb.getFloat(uv + v * 8 + 4)
                if (sPos != null && sUv != null) { sPos[v * 3] = px; sPos[v * 3 + 1] = py; sPos[v * 3 + 2] = pz; sUv[v * 2] = vb[o + 6]; sUv[v * 2 + 1] = vb[o + 7] }
                var s = 0f
                for (k in 0..3) { idi[k] = bin[si + v * 4 + k].toInt() and 255; wf[k] = (bin[sw + v * 4 + k].toInt() and 255).toFloat(); s += wf[k] }
                if (s == 0f) s = 1f
                for (k in 0..3) wf[k] /= s
                // head bone: verts above the collar are re-weighted to bone 11 (smoothstep over 0.12 m), as in crimInit()
                if (py >= 1.72f) {
                    var bi = -1
                    for (k in 0..3) if (idi[k] == 0 && wf[k] > 0f) { bi = k; break }
                    if (bi >= 0) {
                        val u = ((py - 1.72f) / 0.12f).coerceIn(0f, 1f); val hw = wf[bi] * (u * u * (3f - 2f * u))
                        if (hw > 0f) {
                            var slot = -1
                            for (k in 0..3) if (k != bi && wf[k] <= 0f) { slot = k; break }
                            if (slot < 0) { var mn = 9f; for (k in 0..3) if (k != bi && wf[k] < mn) { mn = wf[k]; slot = k }; wf[bi] += wf[slot] }
                            wf[bi] -= hw; idi[slot] = 11; wf[slot] = hw
                        }
                    }
                }
                for (k in 0..3) { vb[o + 8 + k] = idi[k].toFloat(); vb[o + 12 + k] = wf[k] }
                // FACE TUNE: light reshape. Same function for skin, eyes, brows, lashes, mouth and hair -> everything keeps moving together with the head bone.
                if (FaceTune.isHeadPrim(pi)) FaceTune.warp(vb, o)
            }
            val ib = IntArray(ni) { (bin[idx + it * 2].toInt() and 255) or ((bin[idx + it * 2 + 1].toInt() and 255) shl 8) }
            if (sPos != null && sUv != null) { skinPos = sPos; skinUv = sUv; skinIdx = ib; skinMat = p.getInt("mat") }
            prims.add(upload(vb, ib, p.getInt("mat")))
            if (Cloth.CFG[id]?.cut?.containsKey(pi) == true) { prims.last().vb = vb; prims.last().ib = ib }
            vertexCount += n
        }
        val ta = meta.getJSONArray("tex")
        for (i in 0 until ta.length()) {
            val sp = skinPos; val su = skinUv; val si = skinIdx
            // FACE TUNE: realistic face (baked shadows, lips, stubble) painted from the 3D coordinates into the skin atlas while it is decoded
            val paint: ((android.graphics.Bitmap) -> Unit)? = if (i == skinMat && sp != null && su != null && si != null) fun(bmp: android.graphics.Bitmap) { FaceTune.paintSkin(bmp, sp, su, si) } else null
            texIds.add(loadTex(ctx, "models/character/" + ta.getString(i), paint))
        }
    }

    private fun upload(v: FloatArray, idx: IntArray, mat: Int): Prim {
        val vao = IntArray(1); GL.glGenVertexArrays(1, vao, 0); GL.glBindVertexArray(vao[0])
        val b = IntArray(2); GL.glGenBuffers(2, b, 0)
        val vb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()); vb.asFloatBuffer().put(v)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, b[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, v.size * 4, vb, GL.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()); ib.asIntBuffer().put(idx)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, b[1]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GL.GL_STATIC_DRAW)
        val st = 64
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 4, GL.GL_FLOAT, false, st, 32)
        GL.glEnableVertexAttribArray(4); GL.glVertexAttribPointer(4, 4, GL.GL_FLOAT, false, st, 48)
        GL.glBindVertexArray(0)
        return Prim(vao[0], idx.size, mat, b[0])
    }

    /**
     * storeCutGeo(): a copy of the mesh without the triangles of the top part while a store top is worn.
     * kind 'sv' (Survivor jacket) hides centroid y in (1.2, 1.8); any other kind hides y > 1.05 and every arm triangle (dominant bone 5..8).
     * Returns [vao, indexCount] or null. Shares the vertex buffer with the original mesh.
     */
    fun ensureCut(p: Prim, kind: String): IntArray? {
        p.alt[kind]?.let { return it }
        val vb = p.vb ?: return null; val ib = p.ib ?: return null
        fun dom(v: Int): Int { var b = 0; var bw = -1f; for (k in 0..3) { val w = vb[v * 16 + 12 + k]; if (w > bw) { bw = w; b = vb[v * 16 + 8 + k].toInt() } }; return b }
        val out = ArrayList<Int>(ib.size)
        var t = 0
        while (t + 2 < ib.size) {
            val a = ib[t]; val b = ib[t + 1]; val c = ib[t + 2]
            val cy = (vb[a * 16 + 1] + vb[b * 16 + 1] + vb[c * 16 + 1]) / 3f
            val hide = if (kind == "sv") cy < 1.80f && cy > 1.2f
            else { val arm = { v: Int -> val d = dom(v); d in 5..8 }; cy > 1.05f || (arm(a) && arm(b) && arm(c)) }
            if (!hide) { out.add(a); out.add(b); out.add(c) }
            t += 3
        }
        val ia = out.toIntArray()
        val vao = IntArray(1); GL.glGenVertexArrays(1, vao, 0); GL.glBindVertexArray(vao[0])
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, p.vbo)
        val st = 64
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 4, GL.GL_FLOAT, false, st, 32)
        GL.glEnableVertexAttribArray(4); GL.glVertexAttribPointer(4, 4, GL.GL_FLOAT, false, st, 48)
        val eb = IntArray(1); GL.glGenBuffers(1, eb, 0)
        val ibb = ByteBuffer.allocateDirect(ia.size * 4).order(ByteOrder.nativeOrder()); ibb.asIntBuffer().put(ia)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, eb[0]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, ia.size * 4, ibb, GL.GL_STATIC_DRAW)
        GL.glBindVertexArray(0)
        val r = intArrayOf(vao[0], ia.size); p.alt[kind] = r
        return r
    }

    private fun loadTex(ctx: Context, path: String, paint: ((android.graphics.Bitmap) -> Unit)? = null): Int {
        // a 4096 px texture on a GPU whose limit is lower is halved while decoding instead of failing (black character)
        val mx = IntArray(1); GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, mx, 0)
        val lim = if (mx[0] > 0) mx[0] else 2048
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        Prot.open(ctx, path).use { BitmapFactory.decodeStream(it, null, bo) }
        var ss = 1
        while (maxOf(bo.outWidth, bo.outHeight) / ss > lim) ss *= 2
        // low-RAM phones (or a small Java heap): the 4096 px skin atlas would cost ~43 MB of GPU memory -> halve it
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (am != null && (am.isLowRamDevice || am.memoryClass < 192) && maxOf(bo.outWidth, bo.outHeight) / ss > 2048) ss *= 2
        val opt = BitmapFactory.Options().apply { inSampleSize = ss; if (paint != null) { inMutable = true; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 } }
        val bmp = Prot.open(ctx, path).use { BitmapFactory.decodeStream(it, null, opt) } ?: return 0
        if (paint != null) { try { paint(bmp) } catch (e: Throwable) { /* never lose the character over a cosmetic edit: the plain skin is used */ } }
        val id = IntArray(1); GL.glGenTextures(1, id, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        // anisotropic filtering (GL_EXT_texture_filter_anisotropic): sharp skin / hair / gloves at grazing angles
        if ((GL.glGetString(GL.GL_EXTENSIONS) ?: "").contains("GL_EXT_texture_filter_anisotropic")) GL.glTexParameterf(GL.GL_TEXTURE_2D, 0x84FE, 8f)
        return id[0]
    }

    // ---------------------------------------------------------------- animation -> 12 skinning matrices
    private val g = FloatArray(16); private val a = FloatArray(16); private val t = FloatArray(16); private val r = FloatArray(16)
    private val w = FloatArray(16); private val inv = FloatArray(16); private val tmp = FloatArray(16); private val lm = FloatArray(16)
    private val bq = FloatArray(4)   // sampled boxing key frame (BoxAnim.sample)
    private val jv = FloatArray(JumpData.COLS)   // sampled jump clip angles (see JumpAnim.sample)

    private fun rotX(m: FloatArray, ang: Float) { if (ang != 0f) Matrix.rotateM(m, 0, Math.toDegrees(ang.toDouble()).toFloat(), 1f, 0f, 0f) }
    private fun rotY(m: FloatArray, ang: Float) { if (ang != 0f) Matrix.rotateM(m, 0, Math.toDegrees(ang.toDouble()).toFloat(), 0f, 1f, 0f) }
    private fun rotZ(m: FloatArray, ang: Float) { if (ang != 0f) Matrix.rotateM(m, 0, Math.toDegrees(ang.toDouble()).toFloat(), 0f, 0f, 1f) }
    private fun quat(m: FloatArray, q: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val ww = q[3]
        r[0] = 1 - 2 * (y * y + z * z); r[1] = 2 * (x * y + z * ww); r[2] = 2 * (x * z - y * ww); r[3] = 0f
        r[4] = 2 * (x * y - z * ww); r[5] = 1 - 2 * (x * x + z * z); r[6] = 2 * (y * z + x * ww); r[7] = 0f
        r[8] = 2 * (x * z + y * ww); r[9] = 2 * (y * z - x * ww); r[10] = 1 - 2 * (x * x + y * y); r[11] = 0f
        r[12] = 0f; r[13] = 0f; r[14] = 0f; r[15] = 1f
        Matrix.multiplyMM(tmp, 0, m, 0, r, 0); System.arraycopy(tmp, 0, m, 0, 16)
    }
    /** out = world * inverse(bind). bind matrices are pure translations, so the inverse is a negated translation. */
    private fun emit(out: FloatArray, bone: Int, world: FloatArray) {
        System.arraycopy(bind[bone], 0, inv, 0, 16); inv[12] = -inv[12]; inv[13] = -inv[13]; inv[14] = -inv[14]
        Matrix.multiplyMM(out, bone * 16, world, 0, inv, 0)
    }

    private val rt = FloatArray(16); private val up = FloatArray(16)
    private fun sstep(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun footY(a: Float, z: Float, k: Float) = -0.5f * cos(z) * cos(a) + 0.015f * sin(a) + 0.54f * (-cos(z) * cos(k) * cos(a) + sin(k) * sin(a))


    // ---- v33 pick-up: 2-bone IK of the right arm (shoulder -> elbow -> hand) onto a target given in player space ----
    private val ikI = FloatArray(16); private val ikT = FloatArray(4); private val ikR = FloatArray(4)
    private val ikO = FloatArray(4)
    /** angles (th about Z, ph about X) so that Rx(ph) * Rz(th) * rest = u ; rest = the unit rest direction of the bone */
    private fun solveRot(ex: Float, ey: Float, ez: Float, ux: Float, uy: Float, uz: Float, o: FloatArray, at: Int) {
        val r = kotlin.math.sqrt(ex * ex + ey * ey).coerceAtLeast(1e-4f); val alpha = kotlin.math.atan2(ey, ex)
        val th = -alpha - kotlin.math.acos((ux / r).coerceIn(-1f, 1f))
        val vy = ex * sin(th) + ey * cos(th); val vz = ez
        o[at] = th; o[at + 1] = kotlin.math.atan2(uz, uy) - kotlin.math.atan2(vz, vy)
    }
    /** fills ikO = armRx, armRz, foreRx, foreRz; false = no solution (the plain angles stay) */
    private fun pickIk(lm: FloatArray, tw: Float, p: CharPose): Boolean {
        val k = 1f / CharRig.SCALE
        ikT[0] = p.pickTx * k; ikT[1] = p.pickTy * k; ikT[2] = -p.pickTz * k; ikT[3] = 1f
        if (!Matrix.invertM(ikI, 0, lm, 0)) return false
        Matrix.multiplyMV(ikR, 0, ikI, 0, ikT, 0)
        val sx = 0.27f * cos(tw); val sy = 1.67f; val sz = 0.27f * sin(tw)
        val e = elbow[1]; val l1 = kotlin.math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]); val l2 = 0.36f
        var dx = ikR[0] - sx; var dy = ikR[1] - sy; var dz = ikR[2] - sz
        val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz); if (dl < 1e-4f) return false
        val dist = dl.coerceIn(0.14f, (l1 + l2) * 0.985f)
        dx /= dl; dy /= dl; dz /= dl
        val cb = ((l1 * l1 + dist * dist - l2 * l2) / (2f * l1 * dist)).coerceIn(-1f, 1f); val sb = kotlin.math.sqrt(1f - cb * cb)
        // the elbow points down / back / a little outwards (pole)
        val px = 0.55f; val py = -0.5f; val pz = 0.7f; val pd = px * dx + py * dy + pz * dz
        var nx = px - dx * pd; var ny = py - dy * pd; var nz = pz - dz * pd
        val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz); if (nl < 1e-4f) return false
        nx /= nl; ny /= nl; nz /= nl
        val ux = dx * cb + nx * sb; val uy = dy * cb + ny * sb; val uz = dz * cb + nz * sb          // upper arm direction
        val ex = sx + ux * l1; val ey = sy + uy * l1; val ez = sz + uz * l1                           // elbow
        var fx = sx + dx * dist - ex; var fy = sy + dy * dist - ey; var fz = sz + dz * dist - ez     // forearm direction
        val fl = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); if (fl < 1e-4f) return false
        fx /= fl; fy /= fl; fz /= fl
        val ee = floatArrayOf(e[0] / l1, e[1] / l1, e[2] / l1)
        solveRot(ee[0], ee[1], ee[2], ux, uy, uz, ikO, 0)          // ikO[0] = armRz, ikO[1] = armRx
        val th = ikO[0]; val ph = ikO[1]
        // forearm direction in the upper-arm frame: Rz(-th) * Rx(-ph) * f
        val y1 = fy * cos(ph) + fz * sin(ph); val z1 = -fy * sin(ph) + fz * cos(ph)
        val x2 = fx * cos(th) + y1 * sin(th); val y2 = -fx * sin(th) + y1 * cos(th)
        solveRot(ee[0], ee[1], ee[2], x2, y2, z1, ikO, 2)          // ikO[2] = foreRz, ikO[3] = foreRx
        return true
    }

    private fun skinOld(out: FloatArray, p: CharPose) {
        val pr = p.prone.coerceIn(0f, 1f); val su = p.supine.coerceIn(0f, 1f); val cw = p.crouch.coerceIn(0f, 1f) * (1f - pr)
        // v49 WALK / RUN: the old sin() gait is gone. Legs, arms, torso come from Loco (walk clip from the reference video, separate run clip).
        // moveW = 0 standing .. 1 moving (the idle pose is kept while standing); rw = 0 walk .. 1 run (RUN button only, see CharPose.runW).
        val rw = p.runW.coerceIn(0f, 1f)
        val moveW = sstep(p.speed / 1.0f)
        Loco.sample(p.walk, rw, p.speed, lo)
        var la = lo.hipL * moveW; var ra = lo.hipR * moveW; var lk = lo.kneeL * moveW; var rk = lo.kneeR * moveW; var lz = 0f; var rz = 0f
        val onFoot = p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED || p.mode == CharPose.BOX
        val rm = moveW
        var armRx = 0f; var armLx = 0f; var armRz = 0f; var armLz = 0f; var foreRx = 0f; var foreLx = 0f; var foreRz = 0f; var foreLz = 0f
        var tw = 0f
        var rifle = p.mode == CharPose.RIFLE
        val ga = if (rifle) GunHold.arms(p.gunId) else null
        val aUq = ga?.uq ?: uq; val aFq = ga?.fq ?: fq; val aE1 = ga?.e1 ?: e1
        when (p.mode) {
            CharPose.DIVE -> { la = 0.12f; ra = 0.12f; lk = 0f; rk = 0f; armRz = -2.7f; armLz = 2.7f; rifle = false }
            CharPose.CHUTE -> { la = 0.22f; ra = 0.12f; lk = -0.25f; rk = -0.2f; armRz = -2.8f; armLz = 2.8f; foreRx = -0.2f; foreLx = -0.2f; rifle = false }
            CharPose.RELAXED, CharPose.BOX -> {
                // v49: free arm swing of the walk clip (video) / v50: bent, pumping arms of the run clip (fist up at the chin, the other arm back and OUT with an open hand) - opposite to the legs
                armLx = lo.armL * rm; armRx = lo.armR * rm
                foreLx = lo.foreL * rm; foreRx = lo.foreR * rm
                armLz = -lo.abdL * rm; armRz = lo.abdR * rm   // rotZ: left arm outward = negative, right arm outward = positive (same as the jump clip's arms-out)
            }
            CharPose.AIR -> {
                la = p.exLegLx; ra = p.exLegRx; lz = p.exLegLz; rz = p.exLegRz; lk = p.exKneeL; rk = p.exKneeR
                armLx = p.exArmLx; armRx = p.exArmRx; armLz = p.exArmLz; armRz = p.exArmRz
                foreLx = p.exForeLx; foreRx = p.exForeRx; foreLz = p.exForeLz; foreRz = p.exForeRz
                tw = p.exTw; rifle = false
            }
        }
        // sprint with the rifle: both arms together (the gun is attached to the hands, so the hands must stay together)
        if (rifle && p.sprint && p.speed > 1.5f) { val v = 0.12f + sin(p.walk * 2f) * 0.04f; armLx = v; armRx = v }
        if (rifle) { armLx += p.aimPitch; armRx += p.aimPitch }

        // CROUCH: standing still = deep squat. Moving = a tactical crouch-walk: higher hips, long alternating strides, the stepping foot lifts (knee bends more)
        // and the stance leg straightens a little; the shoulders rock gently with every step. (mvw blends squat -> walk smoothly with the speed)
        var cLean = 0f; var rollZ = 0f; var twY = 0f
        val mvw = sstep(p.speed / 1.6f)
        if (cw > 0.01f && (p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED || p.mode == CharPose.BOX)) {
            val s1 = sin(p.walk); val c1 = cos(p.walk)
            val base = 1.20f + (0.92f - 1.20f) * mvw; val kb = -1.80f + (-1.38f + 1.80f) * mvw
            val swing = 0.40f * mvw
            val lkn = kb - mvw * (0.55f * max(0f, c1) - 0.12f * max(0f, -c1)); val rkn = kb - mvw * (0.55f * max(0f, -c1) - 0.12f * max(0f, c1))
            la = la * (1f - cw) + (base + swing * s1) * cw; ra = ra * (1f - cw) + (base - swing * s1) * cw
            lk = lk * (1f - cw) + lkn * cw; rk = rk * (1f - cw) + rkn * cw
            lz = lz * (1f - cw) - 0.03f * mvw * cw; rz = rz * (1f - cw) + 0.03f * mvw * cw
            val cl = -0.30f + 0.07f * mvw
            cLean = cl * cw
            rollZ = 0.035f * s1 * mvw * cw
            if (rifle) { armLx -= cl * cw; armRx -= cl * cw }   // keeps the gun level although the torso leans
        }
        // PRONE: lying flat. Standing still = small breathing pose. Moving = a leopard CRAWL: the legs are drawn up one after the other (knee goes up and out,
        // the other leg pushes), the hips / shoulders roll with every pull and the arms (with the gun) slide forward / back together.
        if (pr > 0.01f && (p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED || p.mode == CharPose.BOX)) {
            val ph = p.walk; val cs = sin(ph); val mv = mvw
            val drawL = max(0f, cs); val drawR = max(0f, -cs); val face = 1f - su
            la = la * (1f - pr) + (0.10f * cs * mv + 0.22f * drawL * mv * face + 0.30f * drawL * mv * su) * pr
            ra = ra * (1f - pr) + (-0.10f * cs * mv + 0.22f * drawR * mv * face + 0.30f * drawR * mv * su) * pr
            lk = lk * (1f - pr) - (0.04f + 1.05f * (1f - 0.25f * su) * drawL * mv) * pr
            rk = rk * (1f - pr) - (0.04f + 1.05f * (1f - 0.25f * su) * drawR * mv) * pr
            la -= 0.08f * su * pr; ra -= 0.08f * su * pr
            lz = lz * (1f - pr) - (0.07f + 0.50f * drawL * mv) * pr; rz = rz * (1f - pr) + (0.07f + 0.50f * drawR * mv) * pr
            cLean += (0.40f * (1f - su) - 0.30f * su) * pr
            rollZ += 0.07f * cs * mv * pr
            twY = 0.05f * cs * mv * pr
            val hp = Math.PI.toFloat() / 2f
            val ap = ((hp - (if (rifle) 0.40f else 0f)) * (1f - su) + (-hp + (if (rifle) 0.5f else 0f)) * su) * pr
            val slide = sin(ph + 0.9f) * mv * pr * (0.07f * (1f - su) + 0.05f * su)
            armLx += ap + slide; armRx += ap + slide
        }

        // SWIMMING (front crawl): face-down prone root with the head up, the arms circle over the head one after the other (pull under the body,
        // recover above the water), flutter kick, slow body roll along the long axis. p.walk = stroke phase (rad), p.swim = blend 0..1.
        val swm = p.swim.coerceIn(0f, 1f)
        if (swm > 0.01f && pr > 0.01f && !rifle) {
            val pi = Math.PI.toFloat(); val ph = p.walk % (2f * pi); val kk = sin(ph * 2.6f)
            la = la * (1f - swm) + 0.32f * kk * swm; ra = ra * (1f - swm) - 0.32f * kk * swm
            lk = lk * (1f - swm) - (0.22f + 0.30f * max(0f, kk)) * swm; rk = rk * (1f - swm) - (0.22f + 0.30f * max(0f, -kk)) * swm
            lz = lz * (1f - swm) - 0.05f * swm; rz = rz * (1f - swm) + 0.05f * swm
            armLx = armLx * (1f - swm) + (-ph) * swm; armRx = armRx * (1f - swm) + (pi - ph) * swm
            armLz = armLz * (1f - swm) + 0.20f * swm; armRz = armRz * (1f - swm) - 0.20f * swm
            foreLx = foreLx * (1f - swm) + 0.35f * swm; foreRx = foreRx * (1f - swm) + 0.35f * swm
            foreLz *= (1f - swm); foreRz *= (1f - swm)
            twY = twY * (1f - swm) + 0.28f * sin(ph) * swm
        }

        // v49 TORSO of the walk / run clip: forward lean (walk 2.6 deg, run 6.9 deg), shoulders counter-rotate against the hips, small side roll over the stance leg
        // (with a rifle only half the lean and a third of the twist, so the gun does not wobble). Not while crouching, prone or jumping.
        val rwgt = (1f - cw) * (1f - pr) * (1f - p.jumpW.coerceIn(0f, 1f))
        if (onFoot && rwgt > 0.001f) {
            val rl = lo.lean * rm * rwgt * (if (rifle) 0.5f else 1f)
            cLean += rl
            if (rifle) { armLx -= rl * 0.6f; armRx -= rl * 0.6f }
            twY += lo.twist * rm * rwgt * (if (rifle) 0.3f else 1f)
            rollZ += lo.roll * rm * rwgt * (if (rifle) 0.5f else 1f)
        }
        // BOXING (fists in hand): orthodox stance when standing (left foot + shoulder forward, knees bent, feet planted, small bounce), fists up at the
        // chin; while running the arms pump (above 7 m/s); the punch (jab / cross / hook / uppercut) overrides the throwing arm and twists the body.
        if (p.mode == CharPose.BOX && cw < 0.01f && pr < 0.01f) {
            val sb = 1f - sstep(p.speed / 1.5f); val bounce = 0.04f * sin(p.time * 7f)
            la = la * (1f - sb) + (0.26f + bounce) * sb; ra = ra * (1f - sb) + (-0.24f - bounce) * sb
            lk = lk * (1f - sb) + (-0.30f) * sb; rk = rk * (1f - sb) + (-0.36f) * sb
            lz = lz * (1f - sb) - 0.07f * sb; rz = rz * (1f - sb) + 0.07f * sb
            val runW = rw; val gw = 1f - runW; val gd = BoxAnim.GUARD
            armLx = armLx * runW + gd[0] * gw; armLz = armLz * runW + gd[1] * gw; foreLx = foreLx * runW + gd[2] * gw; foreLz = foreLz * runW + gd[3] * gw
            armRx = armRx * runW + gd[0] * gw; armRz = armRz * runW - gd[1] * gw; foreRx = foreRx * runW + gd[2] * gw; foreRz = foreRz * runW - gd[3] * gw
            var pTw = 0f; var pLean = 0f
            if (p.punchU >= 0f) {
                val kd = p.punchKind.coerceIn(0, 3); val left = BoxAnim.leftArm(kd)
                BoxAnim.sample(kd, p.punchU, bq)
                val e = BoxAnim.envelope(kd, p.punchU)
                if (left) { armLx = bq[0]; armLz = bq[1]; foreLx = bq[2]; foreLz = bq[3] } else { armRx = bq[0]; armRz = -bq[1]; foreRx = bq[2]; foreRz = -bq[3] }
                pTw = BoxAnim.TWIST[kd] * e; pLean = BoxAnim.LEAN[kd] * e
                if (kd == BoxAnim.JAB) la += 0.10f * e   // small lunge on the lead leg
                if (kd == BoxAnim.CROSS) { ra -= 0.12f * e; la += 0.08f * e; rk -= 0.15f * e }   // rear leg drives, hips rotate into the cross
                if (kd == BoxAnim.UPPER) { lk -= 0.25f * e; rk -= 0.30f * e }                    // dip the knees, then drive up
            }
            twY += BoxAnim.STANCE_TWIST * gw + pTw
            cLean += pLean
        }

        // JUMP: legs + torso always follow the baked clip; the arms too unless the rifle is held (the two-hand rifle pose stays)
        var jLean = 0f; var jDrop = 0f
        if (p.jumpW > 0.001f && p.jumpT >= 0f && (p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED || p.mode == CharPose.BOX)) {
            val jw = p.jumpW.coerceIn(0f, 1f); val j = jv
            JumpAnim.sample(p.jumpT, j)
            la += (j[0] - la) * jw; lz += (j[1] - lz) * jw; lk += (j[2] - lk) * jw
            ra += (j[3] - ra) * jw; rz += (j[4] - rz) * jw; rk += (j[5] - rk) * jw
            if (!rifle) {
                armLx += (j[6] - armLx) * jw; armLz += (j[7] - armLz) * jw; foreLx += (j[8] - foreLx) * jw; foreLz += (j[9] - foreLz) * jw
                armRx += (j[10] - armRx) * jw; armRz += (j[11] - armRz) * jw; foreRx += (j[12] - foreRx) * jw; foreRz += (j[13] - foreRz) * jw
            }
            jLean = j[14] * jw * (if (rifle) 0.45f else 0.8f)
            if (p.jumpDrop > 0f) {
                val yl = footY(la, lz, lk); val yr = footY(ra, rz, rk)
                jDrop = ((1.04f + min(yl, yr)).coerceIn(0f, 0.7f)) * p.jumpDrop
            }
        }
        if (cw > 0.01f) { val d = (1.04f + min(footY(la, lz, lk), footY(ra, rz, rk))).coerceIn(0f, 0.75f); if (d > jDrop) jDrop = d }
        // LOOT PICK-UP: squat + bend over, the right arm reaches down (hand on the item), lifts it and puts it over the shoulder into the backpack.
        // The gun stays in the left hand: only the right arm leaves the rifle pose (see the arm loop), the gun frame keeps its old pitch.
        var armRxKeep = Float.NaN
        val pk = p.pickup.coerceIn(0f, 1f)
        if (pk > 0.001f && (p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED || p.mode == CharPose.BOX) && pr < 0.01f) {
            armRxKeep = armRx
            la = la * (1f - pk) + 0.95f * pk; ra = ra * (1f - pk) + 0.70f * pk
            lk = lk * (1f - pk) + (-1.55f) * pk; rk = rk * (1f - pk) + (-1.25f) * pk
            lz *= (1f - pk); rz *= (1f - pk)
            cLean += -0.62f * pk
            val rc = p.pickReach.coerceIn(0f, 1f); val bg = p.pickBag.coerceIn(0f, 1f)
            val reachX = 0.20f + 0.55f * rc; val bagX = -1.25f
            armRx = reachX * (1f - bg) + bagX * bg; armRz = -0.10f * (1f - bg) - 0.45f * bg
            foreRx = 0.25f * (1f - bg) + 1.65f * bg; foreRz = 0f
            if (!rifle) { armLx += 0.35f * pk }
            val d = (1.04f + min(footY(la, lz, lk), footY(ra, rz, rk))).coerceIn(0f, 0.75f); if (d > jDrop) jDrop = d
        }
        // FEET ON THE GROUND (v49): the hips are lowered so the lowest foot touches the floor. Walking: fully (the stance foot never floats, the hips are lowest
        // when both feet are down and rise over the stance leg = the head bob of the video). Running: 55 % of it, the rest is the flight phase. Boxing stance: fully.
        if (onFoot && cw < 0.01f && pr < 0.01f && p.jumpW < 0.001f) {
            val boxLock = if (p.mode == CharPose.BOX) (1f - sstep(p.speed / 1.5f)) else 0f
            val lockK = max(boxLock, (1f - 0.45f * rw) * moveW)
            if (lockK > 0.001f) { val d = (1.04f + min(footY(la, lz, lk), footY(ra, rz, rk))).coerceIn(0f, 0.75f) * lockK; if (d > jDrop) jDrop = d }
        }
        p.outLean = jLean + cLean; p.outDrop = jDrop

        rollZ += p.leanRoll
        val lean = jLean + cLean
        val hipY = (1.04f - jDrop) * (1f - pr) + (0.17f * (1f - su) + 0.22f * su) * pr
        // root = hip height + prone rotation about the hip; upper = root + torso lean about the hip
        Matrix.setIdentityM(rt, 0); Matrix.translateM(rt, 0, 0f, hipY, 0f); if (pr > 0f) rotX(rt, (Math.PI.toFloat() / 2f) * pr * (2f * su - 1f)); Matrix.translateM(rt, 0, 0f, -1.04f, 0f)
        System.arraycopy(rt, 0, up, 0, 16)
        if (lean != 0f || rollZ != 0f || twY != 0f) { Matrix.translateM(up, 0, 0f, 1.04f, 0f); rotX(up, lean); rotZ(up, rollZ); rotY(up, twY); Matrix.translateM(up, 0, 0f, -1.04f, 0f) }
        System.arraycopy(up, 0, lm, 0, 16)

        // body + head
        System.arraycopy(lm, 0, w, 0, 16); rotY(w, -tw); emit(out, 0, w)
        System.arraycopy(lm, 0, w, 0, 16); rotY(w, -tw); Matrix.translateM(w, 0, CharRig.NECK[0], CharRig.NECK[1], CharRig.NECK[2]); rotY(w, p.headYaw - twY * 0.75f); rotX(w, p.headPitch - lean * 0.85f + (1.49f * (1f - su) + 0.55f * su) * pr); emit(out, 11, w)
        // legs (follow the root, not the torso lean)
        for (s in 0..1) {
            val sx = if (s == 0) -0.12f else 0.12f
            System.arraycopy(rt, 0, g, 0, 16); Matrix.translateM(g, 0, sx, 1.04f, 0f); rotX(g, if (s == 0) la else ra); rotZ(g, if (s == 0) lz else rz)
            emit(out, 1 + s, g)
            System.arraycopy(g, 0, w, 0, 16); Matrix.translateM(w, 0, 0f, -0.5f, -0.015f); rotX(w, if (s == 0) lk else rk)
            emit(out, 3 + s, w)
        }
        // arms
        if (!armRxKeep.isNaN() && pickIk(lm, tw, p)) { armRz = ikO[0]; armRx = ikO[1]; foreRz = ikO[2]; foreRx = ikO[3] }   // pick-up: the right hand really reaches its target
        for (s in 0..1) {
            val sx = if (s == 0) -0.27f else 0.27f
            System.arraycopy(lm, 0, a, 0, 16); Matrix.translateM(a, 0, sx * cos(tw), 1.67f, sx * sin(tw)); rotX(a, if (s == 0) armLx else armRx); rotZ(a, if (s == 0) armLz else armRz)
            if (rifle && !(s == 1 && !armRxKeep.isNaN())) {
                System.arraycopy(a, 0, w, 0, 16); quat(w, aUq[s]); emit(out, 5 + s, w)
                System.arraycopy(a, 0, w, 0, 16); Matrix.translateM(w, 0, aE1[s][0], aE1[s][1], aE1[s][2]); quat(w, aFq[s])
                emit(out, 7 + s, w); emit(out, 9 + s, w)
            } else {
                emit(out, 5 + s, a)
                System.arraycopy(a, 0, w, 0, 16); Matrix.translateM(w, 0, elbow[s][0], elbow[s][1], elbow[s][2]); rotX(w, if (s == 0) foreLx else foreRx); rotZ(w, if (s == 0) foreLz else foreRz)
                emit(out, 7 + s, w); emit(out, 9 + s, w)
            }
        }
        // gun attaches to the shoulders: it turns with the arms (aim pitch), the lean, the crouch and the prone pose
        p.hasGun = rifle
        if (rifle) { System.arraycopy(lm, 0, p.gunFrame, 0, 16); Matrix.translateM(p.gunFrame, 0, 0f, 1.67f, 0f); rotX(p.gunFrame, (armLx + (if (armRxKeep.isNaN()) armRx else armRxKeep)) * 0.5f) }
    }

    // ---------------------------------------------------------------- baked pistol-pack path
    private val pig = PistolRig()
    private val outB = FloatArray(CharRig.NB * 16); private val gfOld = FloatArray(16); private val fr = FloatArray(16)

    /** 12 skinning matrices of the character. With p.pa set the baked pistol pack is used (blended with the old animation while PistolState.bakedW < 1). */
    fun skin(out: FloatArray, p: CharPose) {
        val pa = p.pa
        val bw = if (pa != null && PistolAnim.ready && p.mode == CharPose.RIFLE) pa.bakedW else 0f
        if (bw <= 0.001f) { skinOld(out, p); return }
        if (bw >= 0.999f) { skinBaked(out, p, pa!!); return }
        // transition (prone / swim / jump <-> standing): every bone matrix of the old pose and of the baked pose is decomposed into translation / rotation / scale,
        // the rotations are slerped (shortest side), translation + scale are lerped and the matrix is rebuilt - a plain per-element mix of two rotation matrices would
        // shrink and shear the bones half way through. The gun frame is blended the same way.
        skinOld(out, p)
        System.arraycopy(p.gunFrame, 0, gfOld, 0, 16); val lean0 = p.outLean; val drop0 = p.outDrop
        skinBaked(outB, p, pa!!)
        for (bn in 0 until CharRig.NB) blendMat(out, bn * 16, outB, bn * 16, bw, out, bn * 16)
        blendMat(gfOld, 0, p.gunFrame, 0, bw, p.gunFrame, 0)
        p.hasGun = true
        p.outLean = lean0 + (p.outLean - lean0) * bw; p.outDrop = drop0 + (p.outDrop - drop0) * bw
    }

    // scratch for blendMat (no allocation per call)
    private val bmQa = FloatArray(4); private val bmQb = FloatArray(4); private val bmQo = FloatArray(4)
    private val bmTa = FloatArray(3); private val bmTb = FloatArray(3); private val bmSa = FloatArray(3); private val bmSb = FloatArray(3)

    /** column-major 4x4 -> translation t[3], scale s[3] (column lengths) and rotation quaternion q (x, y, z, w) of the normalised 3x3 */
    private fun decompose(m: FloatArray, o: Int, t: FloatArray, sc: FloatArray, q: FloatArray) {
        t[0] = m[o + 12]; t[1] = m[o + 13]; t[2] = m[o + 14]
        var sx = sqrt(m[o] * m[o] + m[o + 1] * m[o + 1] + m[o + 2] * m[o + 2])
        var sy = sqrt(m[o + 4] * m[o + 4] + m[o + 5] * m[o + 5] + m[o + 6] * m[o + 6])
        var sz = sqrt(m[o + 8] * m[o + 8] + m[o + 9] * m[o + 9] + m[o + 10] * m[o + 10])
        if (sx < 1e-6f) sx = 1e-6f; if (sy < 1e-6f) sy = 1e-6f; if (sz < 1e-6f) sz = 1e-6f
        sc[0] = sx; sc[1] = sy; sc[2] = sz
        val m00 = m[o] / sx; val m10 = m[o + 1] / sx; val m20 = m[o + 2] / sx      // column 0 = (m00, m10, m20)
        val m01 = m[o + 4] / sy; val m11 = m[o + 5] / sy; val m21 = m[o + 6] / sy
        val m02 = m[o + 8] / sz; val m12 = m[o + 9] / sz; val m22 = m[o + 10] / sz
        val tr = m00 + m11 + m22
        if (tr > 0f) {
            val sq = sqrt(tr + 1f) * 2f
            q[3] = 0.25f * sq; q[0] = (m21 - m12) / sq; q[1] = (m02 - m20) / sq; q[2] = (m10 - m01) / sq
        } else if (m00 > m11 && m00 > m22) {
            val sq = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / sq; q[0] = 0.25f * sq; q[1] = (m01 + m10) / sq; q[2] = (m02 + m20) / sq
        } else if (m11 > m22) {
            val sq = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / sq; q[0] = (m01 + m10) / sq; q[1] = 0.25f * sq; q[2] = (m12 + m21) / sq
        } else {
            val sq = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / sq; q[0] = (m02 + m20) / sq; q[1] = (m12 + m21) / sq; q[2] = 0.25f * sq
        }
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (l > 1e-6f) { q[0] /= l; q[1] /= l; q[2] /= l; q[3] /= l } else { q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f }
    }

    /** o = a slerped to b by w (0..1) on the shorter arc; falls back to a normalised lerp for almost equal rotations */
    private fun slerpQ(a: FloatArray, b: FloatArray, w: Float, o: FloatArray) {
        var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val sg = if (dot < 0f) -1f else 1f
        dot *= sg
        val ka: Float; val kb: Float
        if (dot > 0.9995f) { ka = 1f - w; kb = w * sg }
        else {
            val th = kotlin.math.acos(dot.coerceIn(-1f, 1f)); val sn = sin(th)
            ka = sin((1f - w) * th) / sn; kb = sin(w * th) / sn * sg
        }
        o[0] = a[0] * ka + b[0] * kb; o[1] = a[1] * ka + b[1] * kb; o[2] = a[2] * ka + b[2] * kb; o[3] = a[3] * ka + b[3] * kb
        val l = sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2] + o[3] * o[3])
        if (l > 1e-6f) { o[0] /= l; o[1] /= l; o[2] /= l; o[3] /= l } else { o[0] = 0f; o[1] = 0f; o[2] = 0f; o[3] = 1f }
    }

    /** out[oo..] = blend of matrix a (at ao) and matrix b (at bo) by w: lerp of translation + scale, slerp of rotation. out may be a or b (both are read first). */
    private fun blendMat(a: FloatArray, ao: Int, b: FloatArray, bo: Int, w: Float, out: FloatArray, oo: Int) {
        decompose(a, ao, bmTa, bmSa, bmQa); decompose(b, bo, bmTb, bmSb, bmQb)
        slerpQ(bmQa, bmQb, w, bmQo)
        val x = bmQo[0]; val y = bmQo[1]; val z = bmQo[2]; val ww = bmQo[3]
        val sx = bmSa[0] + (bmSb[0] - bmSa[0]) * w; val sy = bmSa[1] + (bmSb[1] - bmSa[1]) * w; val sz = bmSa[2] + (bmSb[2] - bmSa[2]) * w
        out[oo] = (1f - 2f * (y * y + z * z)) * sx; out[oo + 1] = 2f * (x * y + z * ww) * sx; out[oo + 2] = 2f * (x * z - y * ww) * sx; out[oo + 3] = 0f
        out[oo + 4] = 2f * (x * y - z * ww) * sy; out[oo + 5] = (1f - 2f * (x * x + z * z)) * sy; out[oo + 6] = 2f * (y * z + x * ww) * sy; out[oo + 7] = 0f
        out[oo + 8] = 2f * (x * z + y * ww) * sz; out[oo + 9] = 2f * (y * z - x * ww) * sz; out[oo + 10] = (1f - 2f * (x * x + y * y)) * sz; out[oo + 11] = 0f
        out[oo + 12] = bmTa[0] + (bmTb[0] - bmTa[0]) * w; out[oo + 13] = bmTa[1] + (bmTb[1] - bmTa[1]) * w; out[oo + 14] = bmTa[2] + (bmTb[2] - bmTa[2]) * w; out[oo + 15] = 1f
    }

    /**
     * Pistol pack pose: legs (foot-grounded hip drop), torso and head from the baked clip, both arms by IK on the gun (the gun's own grip from GunHold),
     * left-hand weight from the clip (a long gun keeps it on the gun), gun frame following the clip, aim-line yaw / pitch correction (PistolRig).
     */
    private fun skinBaked(out: FloatArray, p: CharPose, pa: PistolState) {
        val f = pa.cur
        val ga = GunHold.arms(p.gunId)
        val aUq = ga?.uq ?: uq; val aFq = ga?.fq ?: fq; val aE1 = ga?.e1 ?: e1
        val mvw = sstep(p.speed / 1.2f)
        // v55 RIFLE mode (every gun except the pistols): gun displacement is relative to the hold, the left hand can go to the magazine while reloading
        pig.rel = pa.rifle
        pig.lhMagW = pa.lhMagW
        if (pa.lhMagW > 0.001f) System.arraycopy(pa.lhMagPt, 0, pig.lhMagPt, 0, 3)
        pig.gunO[0] = GunHold.OX; pig.gunO[1] = GunHold.OY; pig.gunO[2] = GunHold.oz(p.gunId ?: "")
        pig.solve(f, aUq, aFq, aE1, pa.leftMin, pa.aimComp, mvw)
        // v49 WALK / RUN: while the character moves on its feet (not crouched) the LEGS, the hip height and a little torso sway come from Loco (walk clip of the reference video /
        // run clip) instead of the pistol-pack WALK / RUN clips. The arms + gun stay in the pack's hold / aim pose (they must keep the gun), the torso lean keeps the gun level.
        val lw = sstep(p.speed / 1.0f) * (1f - sstep((p.crouch - 0.2f) / 0.5f))
        var locoDrop = 0f; var txL = 0f; var txR = 0f; var txY = 0f
        if (lw > 0.001f) {
            val rw = p.runW.coerceIn(0f, 1f)
            Loco.sample(p.walk, rw, p.speed, lo)
            locoDrop = ((1.04f + min(footY(lo.hipL, 0f, lo.kneeL), footY(lo.hipR, 0f, lo.kneeR))).coerceIn(0f, 0.75f)) * (1f - 0.45f * rw)
            txL = lo.lean * 0.5f * lw; txR = lo.roll * 0.5f * lw; txY = lo.twist * 0.3f * lw
        }
        val hipY = 1.04f - (pig.drop + (locoDrop - pig.drop) * lw)
        // root: hips lowered so the lower foot touches the floor, legs share part of the aim yaw
        Matrix.setIdentityM(rt, 0); Matrix.translateM(rt, 0, 0f, hipY, 0f); rotY(rt, pig.legYaw); Matrix.translateM(rt, 0, 0f, -1.04f, 0f)
        // body (torso + pelvis): the chest rotation of the clip about the hip
        System.arraycopy(rt, 0, w, 0, 16); Matrix.translateM(w, 0, 0f, 1.04f, 0f); rotX(w, txL); rotZ(w, txR); rotY(w, txY); quat(w, pig.chestQ); Matrix.translateM(w, 0, 0f, -1.04f, 0f)
        emit(out, 0, w)
        // head: relative to the chest, turned back against the aim yaw of the chest so it looks along the aim line, plus the player's look yaw / pitch
        System.arraycopy(rt, 0, w, 0, 16); Matrix.translateM(w, 0, 0f, 1.04f, 0f); rotX(w, txL); rotZ(w, txR); rotY(w, txY); quat(w, pig.chestQ)
        Matrix.translateM(w, 0, CharRig.NECK[0], CharRig.NECK[1] - 1.04f, CharRig.NECK[2])
        rotY(w, -pig.chestYaw - txY * 0.75f); rotX(w, -txL * 0.85f); bq[0] = f[4]; bq[1] = f[5]; bq[2] = f[6]; bq[3] = f[7]; quat(w, bq)
        rotY(w, p.headYaw); rotX(w, p.headPitch)
        emit(out, 11, w)
        // legs: thigh = world rotation of the clip, shin = relative to the thigh
        for (s in 0..1) {
            val sx = if (s == 0) -0.12f else 0.12f; val o = if (s == 0) 8 else 16
            System.arraycopy(rt, 0, g, 0, 16); Matrix.translateM(g, 0, sx, 1.04f, 0f)
            bq[0] = f[o]; bq[1] = f[o + 1]; bq[2] = f[o + 2]; bq[3] = f[o + 3]; quat(g, bq)
            emit(out, 1 + s, g)
            System.arraycopy(g, 0, w, 0, 16); Matrix.translateM(w, 0, 0f, -0.5f, -0.015f)
            bq[0] = f[o + 4]; bq[1] = f[o + 5]; bq[2] = f[o + 6]; bq[3] = f[o + 7]; quat(w, bq)
            emit(out, 3 + s, w)
        }
        if (lw > 0.001f) {   // v49: the legs of the walk / run cycle, faded over the pack's legs by lw
            for (s in 0..1) {
                val sx = if (s == 0) -0.12f else 0.12f
                System.arraycopy(rt, 0, g, 0, 16); Matrix.translateM(g, 0, sx, 1.04f, 0f); rotX(g, if (s == 0) lo.hipL else lo.hipR)
                emit(outLoco, 1 + s, g)
                System.arraycopy(g, 0, w, 0, 16); Matrix.translateM(w, 0, 0f, -0.5f, -0.015f); rotX(w, if (s == 0) lo.kneeL else lo.kneeR)
                emit(outLoco, 3 + s, w)
            }
            for (bn in 1..4) blendMat(out, bn * 16, outLoco, bn * 16, lw, out, bn * 16)
        }
        // shoulder frame (origin = middle of the shoulders): chest + arm tilt that levels the gun + the player's look pitch (breathing sway included)
        System.arraycopy(rt, 0, fr, 0, 16); Matrix.translateM(fr, 0, 0f, 1.04f, 0f); rotX(fr, txL); rotZ(fr, txR); rotY(fr, txY); quat(fr, pig.chestQ); Matrix.translateM(fr, 0, 0f, 1.67f - 1.04f, 0f)
        rotX(fr, pig.armPitch + p.aimPitch * pa.aimComp - txL * 0.8f)
        for (s in 0..1) {
            val sx = if (s == 0) -0.27f else 0.27f
            System.arraycopy(fr, 0, a, 0, 16); Matrix.translateM(a, 0, sx, 0f, 0f)
            System.arraycopy(a, 0, w, 0, 16); quat(w, pig.upQ[s]); emit(out, 5 + s, w)
            System.arraycopy(a, 0, w, 0, 16); Matrix.translateM(w, 0, pig.elbow[s][0], pig.elbow[s][1], pig.elbow[s][2]); quat(w, pig.foQ[s])
            emit(out, 7 + s, w); emit(out, 9 + s, w)
        }
        // gun: turns about the right-hand grip by the clip's gun rotation and moves by its displacement (the renderer then adds GunHold.OX / OY / oz)
        val gf = p.gunFrame
        System.arraycopy(fr, 0, gf, 0, 16)
        Matrix.translateM(gf, 0, pig.pivot[0] + pig.d[0], pig.pivot[1] + pig.d[1], pig.pivot[2] + pig.d[2]); quat(gf, pig.qD); Matrix.translateM(gf, 0, -pig.pivot[0], -pig.pivot[1], -pig.pivot[2])
        p.hasGun = true; p.outLean = 0f; p.outDrop = pig.drop
    }
}

/** one arm solution per gun (2-bone IK, baked by tools/): [0] = left arm (upperL / foreL), [1] = right arm. Hand centres land on the right-hand grip and under the handguard. */
class GunArms(val uq: Array<FloatArray>, val fq: Array<FloatArray>, val e1: Array<FloatArray>)

/**
 * Where the gun sits relative to the shoulder pivot (gunFrame): stock tucked into the right shoulder, right hand on the pistol grip, left hand under the handguard.
 * Every gun has its own origin depth (oz) and its own baked arm pose (GunArms), so the hands really touch the grip / handguard of THAT gun.
 */
object GunHold {
    const val OX = 0.15f; const val OY = -0.19f
    private val OZ = hashMapOf(
        "mp5" to -0.3113f,
        "ump" to -0.3537f,
        "mp40" to -0.3495f,
        "vector" to -0.2560f,
        "bizon" to -0.3580f,
        "p90" to -0.2390f,
        "vss" to -0.4387f,
        "vsk94" to -0.3877f,
        "m590" to -0.3750f,
        "kar98k" to -0.4260f,
        "m24" to -0.4600f,
        "awm" to -0.4813f,
        "m82" to -0.5663f,
        // v55 pistols (tools/make_guns.py): the right wrist sits 0.10 behind the grip, the left wrist beside it (two-hand pistol grip)
        "m1911" to -0.2667f,
        "g18" to -0.2659f,
        "deagle" to -0.2731f
    )
    fun oz(id: String) = OZ[id] ?: -0.34f
    private val ARMS = hashMapOf<String, GunArms>(
        "mp5" to GunArms(
            arrayOf(floatArrayOf(0.298503f, 0.279536f, 0.324246f, 0.853006f), floatArrayOf(-0.056072f, 0.025088f, 0.175555f, 0.982551f)),
            arrayOf(floatArrayOf(0.357417f, 0.073666f, 0.707456f, 0.605254f), floatArrayOf(0.665862f, 0.361031f, -0.292679f, 0.583629f)),
            arrayOf(floatArrayOf(0.118590f, -0.272225f, -0.220749f), floatArrayOf(0.182178f, -0.317369f, 0.054666f))),
        "ump" to GunArms(
            arrayOf(floatArrayOf(0.338457f, 0.282322f, 0.375893f, 0.815136f), floatArrayOf(-0.029211f, 0.017146f, 0.157108f, 0.987000f)),
            arrayOf(floatArrayOf(0.369875f, 0.106159f, 0.665061f, 0.640012f), floatArrayOf(0.690542f, 0.352309f, -0.269925f, 0.571114f)),
            arrayOf(floatArrayOf(0.136878f, -0.232432f, -0.253258f), floatArrayOf(0.169453f, -0.326668f, 0.038387f))),
        "mp40" to GunArms(
            arrayOf(floatArrayOf(0.323838f, 0.282885f, 0.355729f, 0.829796f), floatArrayOf(0.047944f, 0.000422f, 0.115958f, 0.992096f)),
            arrayOf(floatArrayOf(0.366316f, 0.093433f, 0.681667f, 0.626429f), floatArrayOf(0.740476f, 0.330639f, -0.224160f, 0.540486f)),
            arrayOf(floatArrayOf(0.129561f, -0.248412f, -0.241672f), floatArrayOf(0.140946f, -0.341880f, -0.012349f))),
        "vector" to GunArms(
            arrayOf(floatArrayOf(0.262863f, 0.266509f, 0.286004f, 0.882087f), floatArrayOf(-0.055730f, 0.024987f, 0.175377f, 0.982605f)),
            arrayOf(floatArrayOf(0.340061f, 0.050071f, 0.738765f, 0.579723f), floatArrayOf(0.646304f, 0.350820f, -0.304561f, 0.605358f)),
            arrayOf(floatArrayOf(0.105981f, -0.299077f, -0.190317f), floatArrayOf(0.182054f, -0.317475f, 0.054461f))),
        "bizon" to GunArms(
            arrayOf(floatArrayOf(0.339907f, 0.281167f, 0.379733f, 0.813149f), floatArrayOf(0.051460f, -0.000188f, 0.113160f, 0.992243f)),
            arrayOf(floatArrayOf(0.367178f, 0.112319f, 0.656348f, 0.649439f), floatArrayOf(0.707886f, 0.313724f, -0.242696f, 0.584442f)),
            arrayOf(floatArrayOf(0.138877f, -0.229871f, -0.254504f), floatArrayOf(0.139015f, -0.342574f, -0.014761f))),
        "p90" to GunArms(
            arrayOf(floatArrayOf(0.226177f, 0.244930f, 0.250592f, 0.908877f), floatArrayOf(-0.044132f, 0.021379f, 0.166968f, 0.984742f)),
            arrayOf(floatArrayOf(0.314081f, 0.038077f, 0.754834f, 0.574569f), floatArrayOf(0.673304f, 0.354948f, -0.284576f, 0.582830f)),
            arrayOf(floatArrayOf(0.093923f, -0.320951f, -0.158332f), floatArrayOf(0.176267f, -0.321820f, 0.047557f))),
        "vss" to GunArms(
            arrayOf(floatArrayOf(0.370367f, 0.273548f, 0.426754f, 0.778384f), floatArrayOf(0.161560f, -0.013231f, 0.064105f, 0.984690f)),
            arrayOf(floatArrayOf(0.372433f, 0.138087f, 0.622632f, 0.674206f), floatArrayOf(0.709048f, 0.268262f, -0.221407f, 0.613405f)),
            arrayOf(floatArrayOf(0.156269f, -0.189914f, -0.276428f), floatArrayOf(0.105340f, -0.342559f, -0.091961f))),
        "vsk94" to GunArms(
            arrayOf(floatArrayOf(0.370367f, 0.273548f, 0.426754f, 0.778384f), floatArrayOf(0.089078f, -0.005834f, 0.095305f, 0.991437f)),
            arrayOf(floatArrayOf(0.372433f, 0.138087f, 0.622632f, 0.674206f), floatArrayOf(0.705644f, 0.295423f, -0.236564f, 0.599024f)),
            arrayOf(floatArrayOf(0.156269f, -0.189914f, -0.276428f), floatArrayOf(0.126710f, -0.345223f, -0.040814f))),
        "m590" to GunArms(
            arrayOf(floatArrayOf(0.295736f, 0.278861f, 0.321041f, 0.855400f), floatArrayOf(0.047944f, 0.000422f, 0.115958f, 0.992096f)),
            arrayOf(floatArrayOf(0.356259f, 0.071667f, 0.710077f, 0.603104f), floatArrayOf(0.740476f, 0.330639f, -0.224160f, 0.540486f)),
            arrayOf(floatArrayOf(0.117504f, -0.274566f, -0.218417f), floatArrayOf(0.140946f, -0.341880f, -0.012349f))),
        "kar98k" to GunArms(
            arrayOf(floatArrayOf(0.368197f, 0.276073f, 0.420254f, 0.782049f), floatArrayOf(0.047983f, 0.000418f, 0.116230f, 0.992063f)),
            arrayOf(floatArrayOf(0.376112f, 0.130565f, 0.633509f, 0.663444f), floatArrayOf(0.756956f, 0.338075f, -0.213218f, 0.516974f)),
            arrayOf(floatArrayOf(0.152962f, -0.194744f, -0.274914f), floatArrayOf(0.141133f, -0.341802f, -0.012374f))),
        "m24" to GunArms(
            arrayOf(floatArrayOf(0.375081f, 0.272955f, 0.432311f, 0.773251f), floatArrayOf(0.089256f, -0.005922f, 0.097268f, 0.991230f)),
            arrayOf(floatArrayOf(0.375841f, 0.138169f, 0.623311f, 0.671667f), floatArrayOf(0.737943f, 0.310573f, -0.219668f, 0.557431f)),
            arrayOf(floatArrayOf(0.157645f, -0.184260f, -0.279457f), floatArrayOf(0.128082f, -0.344708f, -0.040884f))),
        "awm" to GunArms(
            arrayOf(floatArrayOf(0.362284f, 0.275373f, 0.415403f, 0.787629f), floatArrayOf(0.188799f, -0.014887f, 0.052740f, 0.980485f)),
            arrayOf(floatArrayOf(0.369043f, 0.134433f, 0.626761f, 0.672983f), floatArrayOf(0.707843f, 0.257902f, -0.216838f, 0.620827f)),
            arrayOf(floatArrayOf(0.152600f, -0.200417f, -0.271010f), floatArrayOf(0.097570f, -0.339113f, -0.111276f))),
        "m82" to GunArms(
            arrayOf(floatArrayOf(0.352288f, 0.274202f, 0.406633f, 0.797093f), floatArrayOf(0.220255f, -0.015591f, 0.037022f, 0.974615f)),
            arrayOf(floatArrayOf(0.358047f, 0.138898f, 0.617699f, 0.686264f), floatArrayOf(0.689645f, 0.238144f, -0.216596f, 0.648662f)),
            arrayOf(floatArrayOf(0.151517f, -0.210087f, -0.264208f), floatArrayOf(0.086682f, -0.333937f, -0.133687f))),
        // v55 pistols: both hands on the grip (2-bone IK of both arms onto the wrists of the grip, baked with the maths of PistolRig)
        "m1911" to GunArms(
            arrayOf(floatArrayOf(0.295985f, 0.300220f, 0.149203f, 0.894427f), floatArrayOf(-0.034901f, 0.029638f, 0.151462f, 0.987402f)),
            arrayOf(floatArrayOf(0.140178f, -0.321993f, 0.759468f, 0.547612f), floatArrayOf(0.685712f, 0.327921f, -0.328334f, 0.560771f)),
            arrayOf(floatArrayOf(0.000974f, -0.320144f, -0.185491f), floatArrayOf(0.166467f, -0.328053f, 0.039621f))),
        "g18" to GunArms(
            arrayOf(floatArrayOf(0.295985f, 0.300220f, 0.149203f, 0.894427f), floatArrayOf(-0.034901f, 0.029638f, 0.151462f, 0.987402f)),
            arrayOf(floatArrayOf(0.140178f, -0.321993f, 0.759468f, 0.547612f), floatArrayOf(0.685712f, 0.327921f, -0.328334f, 0.560771f)),
            arrayOf(floatArrayOf(0.000974f, -0.320144f, -0.185491f), floatArrayOf(0.166467f, -0.328053f, 0.039621f))),
        "deagle" to GunArms(
            arrayOf(floatArrayOf(0.297515f, 0.300758f, 0.147058f, 0.894094f), floatArrayOf(-0.037771f, 0.028982f, 0.154615f, 0.986827f)),
            arrayOf(floatArrayOf(0.136006f, -0.322337f, 0.756654f, 0.552337f), floatArrayOf(0.681242f, 0.330567f, -0.325520f, 0.566279f)),
            arrayOf(floatArrayOf(-0.000951f, -0.319868f, -0.185966f), floatArrayOf(0.168555f, -0.326728f, 0.041684f)))
    )
    fun arms(id: String?): GunArms? = if (id == null) null else ARMS[id]
}

/** GPU skinning shader + draw call for CharModel. Lighting / fog are the same formulas as the map shader. */
class CharRenderer(private val ctx: Context) {
    private val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor; layout(location=2) in vec2 aUV; layout(location=3) in vec4 aSI; layout(location=4) in vec4 aSW;
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uBones[12]; uniform vec3 uEye; uniform float uBlink;
out vec3 vN; out vec2 vUV; out float vD; out vec3 vW;
// BLINK (FaceTune): the eyelids are part of the skin / lash meshes. The upper lid margin (about +4.5 mm above the eye centre) slides down to the lower lid margin
// (about -8.5 mm), the lower lid rises a little, the skin above follows with a fall-off. Weights are functions of the rest-pose 3D position (model space,
// mirror symmetric), so it works on the same vertices on both eyes. A lid vertex never sinks into the eyeball: it is pushed out to the eyeball ellipsoid.
// Only the skin and lash meshes get uBlink > 0 (CharRenderer.draw); everything is done before skinning, so it follows the head bone exactly.
const vec3 EC = vec3(0.0328, 1.9724, -0.1501); const vec3 ER = vec3(0.0165, 0.0180, 0.0158);
vec3 lidBlink(vec3 p, float b){
  float sg = p.x < 0.0 ? -1.0 : 1.0;
  vec3 c = vec3(sg*EC.x, EC.y, EC.z);
  float r0 = length((p - c)/ER);
  if(r0 < 0.98) return p;                                   // socket interior behind the eyeball stays put
  float qx = abs(p.x) - EC.x; float qy = p.y - EC.y;
  float wf = 1.0 - smoothstep(-0.12, -0.09, p.z);           // front of the face only
  float wx = 1.0 - smoothstep(0.009, 0.019, abs(qx));       // the corners of the eye move less
  float w = wx*wf; if(w <= 0.0) return p;
  float wU = smoothstep(-0.004, 0.001, qy) * (1.0 - smoothstep(0.006, 0.024, qy));
  float wL = (1.0 - smoothstep(-0.0065, -0.0010, qy)) * (1.0 - smoothstep(0.0095, 0.020, -qy));
  vec3 q = p; q.y += (-0.0115*wU + 0.0015*wL) * b * w;
  vec3 d = (q - c)/ER; float r = length(d); float rt = min(r0, 1.06);
  if(r < rt) q = c + (d/max(r, 1e-4))*rt*ER;                // lid slides over the eyeball, never through it
  return q;
}
void main(){
  mat4 sk = aSW.x*uBones[int(aSI.x+0.5)] + aSW.y*uBones[int(aSI.y+0.5)] + aSW.z*uBones[int(aSI.z+0.5)] + aSW.w*uBones[int(aSI.w+0.5)];
  vec3 P0 = aPos; if(uBlink > 0.0) P0 = lidBlink(aPos, uBlink);
  vec4 lp = sk*vec4(P0,1.0); vec3 ln = mat3(sk)*aNor;
  vec4 w = uModel*lp; gl_Position = uVP*w; vN = mat3(uModel)*ln; vUV = aUV; vD = distance(w.xyz,uEye); vW = w.xyz;
}"""
    private val FS = """#version 300 es
precision highp float;
in vec3 vN; in vec2 vUV; in float vD; in vec3 vW;
uniform sampler2D uTex; uniform float uFog; uniform vec3 uTint; uniform vec3 uEye; uniform vec4 uMat; uniform float uBump;
const vec3 SUN=vec3(-0.5652,0.6935,0.3596);
out vec4 o;
void main(){
  vec3 tex = texture(uTex,vUV).rgb;
  vec3 a = tex*uTint;
  vec3 n = normalize(vN); if(!gl_FrontFacing) n=-n;
  vec3 V = normalize(uEye - vW);
  if(uBump > 0.0){
    float h = dot(tex, vec3(0.299,0.587,0.114));
    vec3 dpx = dFdx(vW); vec3 dpy = dFdy(vW);
    vec3 r1 = cross(dpy, n); vec3 r2 = cross(n, dpx);
    float det = dot(dpx, r1);
    vec3 grad = sign(det)*(dFdx(h)*r1 + dFdy(h)*r2);
    n = normalize(abs(det)*n - uBump*grad);
  }
  float ndl = dot(n,SUN);
  float wr = uMat.z;
  float dif = max((ndl+wr)/(1.0+wr),0.0);
  vec3 hemi = mix(vec3(0.49,0.54,0.31), vec3(0.81,0.89,1.0), 0.5+0.5*n.y);
  vec3 lit = a*(hemi*0.62 + vec3(1.0,0.91,0.78)*dif*0.95) + vec3(0.12,0.115,0.10)*a;
  // soft subsurface glow at the shadow terminator (warm red), only where wrap > 0
  float term = smoothstep(-0.35,0.05,ndl)*(1.0-smoothstep(0.05,0.55,ndl));
  lit += a*vec3(0.55,0.16,0.07)*term*wr*0.9;
  // specular (sun) + sky rim
  vec3 Hh = normalize(SUN+V);
  float nh = max(dot(n,Hh),0.0);
  float sp = pow(nh,uMat.y)*uMat.x*smoothstep(0.0,0.25,ndl);
  lit += vec3(1.0,0.93,0.82)*sp;
  // v11 realism: broad second lobe (skin oil / hair sheen), Fresnel sky reflection (wet look at grazing angles), light passing through thin skin (ears, nose, fingers)
  float ndv = max(dot(n,V),0.0);
  float fre = 0.04 + 0.96*pow(1.0-ndv,5.0);
  float sp2 = pow(nh, max(uMat.y*0.22,4.0))*uMat.x*0.30*smoothstep(-0.05,0.35,ndl);
  if(uMat.y > 150.0) sp2 = 0.0;
  lit += vec3(1.0,0.90,0.78)*sp2;
  lit += hemi*fre*uMat.x*0.55*(0.3+0.7*(n.y*0.5+0.5));
  float back = max(dot(-n,SUN),0.0)*pow(max(dot(V,-SUN),0.0),2.5);
  lit += a*vec3(0.95,0.28,0.12)*back*wr*0.9;
  float fr = pow(1.0-max(dot(n,V),0.0),3.0);
  lit += hemi*fr*uMat.w*0.30*(0.35+0.65*max(ndl+0.4,0.0));
  if(uMat.y > 150.0){  // eyes: a second, camera-side catch-light
    float g2 = pow(max(dot(n,normalize(V+vec3(-0.30,0.55,0.15))),0.0),420.0);
    lit += vec3(1.0)*g2*1.4;
  }
  float f = clamp((vD-160.0*uFog)/(740.0*uFog),0.0,1.0);
  o = vec4(mix(lit, vec3(0.74,0.84,0.93), f*f), 1.0);
}
"""

    /**
     * REALISTIC MATERIALS per mesh part of the survivor (index = prim): { specular strength, shininess, wrap / sub-surface, rim, micro-bump from the albedo }.
     * 0 skin, 1 eyes (wet, sharp glint), 2 brows, 3 lashes, 4 mouth, 5 hair (sheen), 6 jacket, 7 trousers, 8 gloves, 9 sneakers.
     */
    private val MATS = arrayOf(
        floatArrayOf(0.26f, 30f, 0.42f, 0.32f, 0.0024f),
        floatArrayOf(1.60f, 260f, 0f, 0f, 0f),
        floatArrayOf(0.10f, 20f, 0.10f, 0f, 0f),
        floatArrayOf(0f, 10f, 0f, 0f, 0f),
        floatArrayOf(0.50f, 40f, 0.20f, 0f, 0f),
        floatArrayOf(0.50f, 30f, 0.25f, 0.35f, 0.0012f),
        floatArrayOf(0.10f, 14f, 0.15f, 0.30f, 0.0004f),
        floatArrayOf(0.06f, 10f, 0.15f, 0.25f, 0.0004f),
        floatArrayOf(0.20f, 24f, 0.18f, 0.20f, 0.0010f),
        floatArrayOf(0.45f, 48f, 0f, 0.25f, 0f)
    )
    private var prog = 0
    private var uVP = 0; private var uModel = 0; private var uBones = 0; private var uEye = 0; private var uFog = 0; private var uTint = 0; private var uTex = 0
    private var uMat = 0; private var uBump = 0; private var uBlink = 0
    private val models = HashMap<String, CharModel?>()
    private val bones = FloatArray(CharRig.NB * 16)
    private val cloth = ClothRenderer(ctx)

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Char shader: " + GL.glGetShaderInfoLog(s))
        return s
    }

    /** call on the GL thread (onSurfaceCreated). A model that fails to load is simply not drawn (callers fall back to boxes). */
    fun init() {
        models.clear(); cloth.init()
        try {
            prog = GL.glCreateProgram()
            GL.glAttachShader(prog, compile(GL.GL_VERTEX_SHADER, VS)); GL.glAttachShader(prog, compile(GL.GL_FRAGMENT_SHADER, FS))
            GL.glLinkProgram(prog)
            uVP = GL.glGetUniformLocation(prog, "uVP"); uModel = GL.glGetUniformLocation(prog, "uModel"); uBones = GL.glGetUniformLocation(prog, "uBones")
            uEye = GL.glGetUniformLocation(prog, "uEye"); uFog = GL.glGetUniformLocation(prog, "uFog"); uTint = GL.glGetUniformLocation(prog, "uTint"); uTex = GL.glGetUniformLocation(prog, "uTex")
            uMat = GL.glGetUniformLocation(prog, "uMat"); uBump = GL.glGetUniformLocation(prog, "uBump"); uBlink = GL.glGetUniformLocation(prog, "uBlink")
        } catch (e: Throwable) { prog = 0; return }
        for (id in arrayOf("survivor")) models[id] = try { CharModel(ctx, id) } catch (e: Throwable) { null }
    }

    // ---------------------------------------------------------------- BLINK timing (human: every 2 - 6 s, ~0.2 s long, now and then a double blink)
    private fun hash01(n: Int): Float { var x = n * 374761393 + 668265263; x = (x xor (x ushr 13)) * 1274126177; return ((x xor (x ushr 16)) and 0xFFFF) / 65535f }
    private fun blinkProfile(x: Float): Float {
        if (x < 0f || x > 0.20f) return 0f
        if (x < 0.07f) { val u = x / 0.07f; return u * u * (3f - 2f * u) }          // fast close
        if (x < 0.09f) return 1f                                                       // closed
        val u = (x - 0.09f) / 0.11f; return 1f - u * u * (3f - 2f * u)                // slower open
    }
    /** 0 = eyes open .. 1 = closed, at time t (s). `seed` (per character) gives every character its own rhythm. */
    private fun blinkAmt(t: Float, seed: Int): Float {
        val cyc = 3.4f
        val s = t + ((seed ushr 4) and 0xFFFF) * 0.0137f
        val k = Math.floor((s / cyc).toDouble()).toInt(); val u = s - k * cyc
        val hs = hash01(k * 7 + seed)
        // sometimes a cycle has no blink -> irregular timing; never two empty cycles in a row, so the longest gap between two blinks stays below ~9.5 s
        if (hs < 0.12f && hash01((k - 1) * 7 + seed) >= 0.12f) return 0f
        val start = 0.15f + hash01(k + seed * 3) * (cyc - 0.9f)
        var b = blinkProfile(u - start)
        if (hs > 0.80f) b = max(b, blinkProfile(u - start - 0.30f))                    // double blink
        return b
    }

    val ready get() = prog != 0
    fun has(charId: String) = models[fileOf(charId)] != null
    /** Characters.ALL ids -> asset ids */
    fun fileOf(charId: String) = charId
    fun vertices(charId: String) = models[fileOf(charId)]?.vertexCount ?: 0

    /**
     * Draw one character. `model` = world matrix of the character root (position + yaw + scale already applied by the caller).
     * Leaves the skinning program bound: callers must glUseProgram(their own program) afterwards.
     */
    fun draw(charId: String, vp: FloatArray, model: FloatArray, ex: Float, ey: Float, ez: Float, fog: Float, pose: CharPose, tr: Float = 1f, tg: Float = 1f, tb: Float = 1f, wear: String? = null, pants: String? = Wear.pants, blinkSeed: Int = 0) {
        val fid = fileOf(charId)
        val m = models[fid] ?: return
        m.skin(bones, pose)
        // store top: hide / trim the character's own jacket, draw the top + bare arms afterwards
        val wcfg = if (wear != null && cloth.has(wear)) Cloth.CFG[fid] else null
        val pcfg = if (pants != null && cloth.has(pants)) Cloth.CFG[fid] else null   // store pants: the character's own trousers are hidden
        GL.glUseProgram(prog)
        GL.glUniformMatrix4fv(uVP, 1, false, vp, 0); GL.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GL.glUniformMatrix4fv(uBones, CharRig.NB, false, bones, 0)
        GL.glUniform3f(uEye, ex, ey, ez); GL.glUniform1f(uFog, fog); GL.glUniform3f(uTint, tr, tg, tb); GL.glUniform1i(uTex, 0)
        // blink only while the face can be seen (not prone-face-down / back view is cheap anyway) - one value per character, applied to skin (prim 0) + lashes (prim 3). The rhythm is seeded by blinkSeed (callers that share ONE CharPose between several characters - the bots - pass their own), else by the pose object
        val blink = if (FaceTune.ENABLED) blinkAmt(((System.nanoTime() / 1_000_000L) % 10_000_000L) / 1000f, if (blinkSeed != 0) blinkSeed else System.identityHashCode(pose)) else 0f
        GL.glActiveTexture(GL.GL_TEXTURE0)
        for ((pi, p) in m.prims.withIndex()) {
            var vao = p.vao; var cnt = p.count
            if (pcfg != null && pcfg.pantsHide.contains(pi)) continue
            if (wcfg != null) {
                if (wcfg.hide.contains(pi)) continue
                val kind = wcfg.cut[pi]
                if (kind != null) { val a = m.ensureCut(p, kind); if (a != null) { vao = a[0]; cnt = a[1] } }
            }
            val mt = MATS[if (pi < MATS.size) pi else MATS.size - 1]
            GL.glUniform4f(uMat, mt[0], mt[1], mt[2], mt[3]); GL.glUniform1f(uBump, mt[4])
            GL.glUniform1f(uBlink, if (pi == 0 || pi == 3) blink else 0f)
            GL.glBindTexture(GL.GL_TEXTURE_2D, m.texIds.getOrElse(p.mat) { 0 })
            GL.glBindVertexArray(vao); GL.glDrawElements(GL.GL_TRIANGLES, cnt, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glBindVertexArray(0)
        if (wcfg != null || pcfg != null) cloth.draw(if (wcfg != null) wear else null, if (pcfg != null) pants else null, fid, bones, vp, model, ex, ey, ez, fog)
    }
}
