package com.bijnor.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Animation state of ONE character (the player or one bot) for the pistol animation pack (PistolAnim).
 * It picks the clip (idle / walk / run / crouch / aim / reload / turn / idle gestures), cross-fades between clips and plays the recoil kick.
 *
 * Usage, once per drawn frame:
 *     state.update(clock, speed, crouch, aimHold, ads, shooting, reloadProgress, bodyYaw, longGun)
 *     then read state.cur (K floats, layout in PistolAnim) - legs / torso / head come from the "lower" channel, gun + arms from the "upper" channel.
 * aimComp (0..1) = how much the upper body is in an aim pose (the renderer keeps the gun level against the torso lean with it).
 * leftMin (0..1) = minimum weight of the left hand staying on the gun.
 *
 * v55 TWO MODES. update(..., pistol = true): the one-hand pistol pack above (only the pistols use it). update(..., pistol = false) = RIFLE mode for every other gun:
 * legs / torso / head still come from the lower channel, but the gun + arms are made by code (rifleUpper) - BOTH hands always on the gun:
 *   - walking / standing: low ready (muzzle down, gun against the chest, both hands on it); running: the gun is carried across the body with both hands, muzzle down-left, bobbing with the steps
 *   - aiming / firing (hip fire too): the gun is shouldered and held with both hands, a recoil kick on every shot
 *   - reload (PUBG / BGMI / Free Fire style): the right hand keeps the grip, the gun is canted, the LEFT hand leaves the hand-guard, pulls the magazine, takes a new one
 *     from the pouch on the hip, pushes it in, slaps it and goes back to the hand-guard.
 */
class PistolState {
    private companion object {
        const val PACK_SCALE = 1.38f   // pack metres -> game character units (same as bake_pistol.js)

        // ---- v55 rifle reload timeline (u = 0..1 over the whole reload; every column is eased between its keyframes)
        //            left hand on the hand-guard / not free, left hand on the magazine, magazine offset, gun pose, hand-to-pouch
        val RL_U   = floatArrayOf(0f,   .08f, .22f, .32f, .44f, .54f, .64f, .78f, .87f, .93f, 1f)
        val RL_WH  = floatArrayOf(1f,   1f,   1f,   1f,   1f,   0f,   0f,   0f,   1f,   1f,   1f)   // 1 = the left hand is NOT free (hand-guard / magazine), 0 = free (goes to the pouch)
        val RL_WM  = floatArrayOf(0f,   0f,   1f,   1f,   1f,   0f,   0f,   1f,   1f,   1f,   0f)   // weight of the left hand on the magazine / magazine well
        val RL_OFF = floatArrayOf(0f,   0f,   0f,   0f,   .22f, .22f, .22f, .22f, 0f,   -.02f, 0f)  // how far the magazine is from the well (gun-mesh units, along the magazine axis)
        val RL_GUN = floatArrayOf(0f,   .5f,  1f,   1f,   1f,   1f,   1f,   1f,   1f,   .8f,  0f)   // 0 = ready pose .. 1 = reload pose (canted gun)
        /** the pouch on the left hip, relative to the LEFT shoulder, chest space (x right, y up, -z forward) */
        const val POUCH_X = 0.10f; const val POUCH_Y = -0.60f; const val POUCH_Z = -0.16f

        fun sst(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
        /** eased keyframe lookup in RL_U */
        fun kf(tab: FloatArray, u: Float): Float {
            val n = RL_U.size
            if (u <= RL_U[0]) return tab[0]
            if (u >= RL_U[n - 1]) return tab[n - 1]
            var i = 0
            while (i < n - 2 && u > RL_U[i + 1]) i++
            val t = (u - RL_U[i]) / (RL_U[i + 1] - RL_U[i])
            val e = t * t * (3f - 2f * t)
            return tab[i] + (tab[i + 1] - tab[i]) * e
        }
    }
    /** blended pose of this frame: [0..23] from the lower channel, [24..36] from the upper channel */
    val cur = FloatArray(PistolAnim.K).also { it[3] = 1f; it[7] = 1f; it[11] = 1f; it[15] = 1f; it[19] = 1f; it[23] = 1f; it[27] = 1f }
    var aimComp = 0f; private set
    var leftMin = 0f; private set
    /** v55: true while this state animates a non-pistol gun (RIFLE mode, code-made two-hand upper body) */
    var rifle = false; private set
    /** v55 RIFLE reload: weight of the left hand on the magazine and that point in unscaled gun-mesh space (read by CharModel.skinBaked -> PistolRig) */
    var lhMagW = 0f; private set
    val lhMagPt = FloatArray(3)
    /** v55 RIFLE reload: anchors of the gun in the hands (magazine well, hand-guard) - the renderer sets them with setGun() before update() */
    val rig = ReloadAnim.Rig()
    private var rigId = ""
    /** centre of the magazine (unscaled gun-mesh space), how far it is pulled out of the well, and true while the left hand holds it out of the gun (the renderer draws a magazine block then) */
    val magC = FloatArray(3)
    var magOff = 0f; private set
    var magHeld = false; private set
    var clipLower = PistolAnim.IDLE; private set
    var clipUpper = PistolAnim.IDLE; private set
    /** 0..1 how much of the baked pose is used by CharModel.skin() (fades to 0 while prone / swimming / jumping, where the old animation takes over) */
    var bakedW = 1f; private set

    /** one cross-fading channel: set(clip, t) every frame, a change of clip starts a fade from the last output */
    private class Chan {
        var clip = -1; var bl = 1f; var fade = 0.25f
        val prev = FloatArray(PistolAnim.K); val out = FloatArray(PistolAnim.K); val tmp = FloatArray(PistolAnim.K)
        fun apply(c: Int, t: Float, dt: Float, fadeDur: Float) {
            if (c != clip) {
                if (clip >= 0) { System.arraycopy(out, 0, prev, 0, PistolAnim.K); bl = 0f } else bl = 1f
                clip = c; fade = if (fadeDur < 0.05f) 0.05f else fadeDur
            }
            PistolAnim.sample(c, t, tmp)
            if (bl < 1f) {
                bl = minOf(1f, bl + dt / fade)
                val s = bl * bl * (3f - 2f * bl)
                PistolAnim.blend(prev, tmp, s, out)
            } else System.arraycopy(tmp, 0, out, 0, PistolAnim.K)
        }
    }
    private val lo = Chan(); private val up = Chan()

    private var lastClock = -1f; private var locoT = 0f
    private var crouched = false
    private var transClip = -1; private var transT = 0f
    private var idleTime = 0f; private var gestIdx = 0; private var gestClip = -1; private var gestT = 0f
    private var lastYaw = 0f; private var yawAcc = 0f; private var turnClip = -1; private var turnT = 0f
    private var aimLatch = 0f
    private var wasShooting = false; private var kickT = -1f; private var kickClip = PistolAnim.KICK2
    private var aimSm = 0f; private var crouchSm = 0f
    // v55 rifle mode
    private var sprintSm = 0f
    private val ru = FloatArray(PistolAnim.K); private val rb = FloatArray(PistolAnim.K)

    fun reset() { bakedW = 1f; lastClock = -1f; transClip = -1; gestClip = -1; turnClip = -1; kickT = -1f; idleTime = 0f; locoT = 0f; aimSm = 0f; crouchSm = 0f; yawAcc = 0f; sprintSm = 0f; lhMagW = 0f; magOff = 0f; magHeld = false }

    /** v55: tells the state which gun is in the hands (magazine / hand-guard anchors for the rifle reload). Cheap: only does work when the gun changes. */
    fun setGun(id: String, zmin: Float, my: Float, len: Float) {
        if (id == rigId) return
        rigId = id; ReloadAnim.rigFor(id, zmin, my, len, rig)
    }

    /**
     * @param clock       game clock (s)
     * @param speed       ground speed (m/s)
     * @param crouch      0..1 crouch amount of the game
     * @param aimHold     gun is up: aiming, firing or just fired
     * @param ads         aiming down the sights (two-hand aim); false + aimHold = hip fire
     * @param shooting    true during the frames a shot / muzzle flash is happening
     * @param reloadProg  0..1 while reloading, < 0 otherwise (drives the reload clip 1:1 with the game's reload time)
     * @param yaw         body yaw (rad) - a quick turn on the spot plays the turn clip
     * @param longGun     shotgun / DMR / sniper / AMR: always two-handed, aims with the two-hand pose
     * @param baked       false while the character is prone / swimming / jumping: the baked pose fades out ([bakedW]) and the old animation fades in
     * @param pistol      true = the gun in the hands is a PISTOL: one-hand pistol pack. false = every other gun: two-hand RIFLE animation (carry, run, fire, reload with the left hand)
     * @param walk        walk phase (rad) of the character - bobs the carried rifle with the steps (rifle mode)
     * @param runW        0..1 how hard the character runs (RUN button / hurrying bot) - the rifle is carried across the body while running (rifle mode)
     */
    fun update(clock: Float, speed: Float, crouch: Float, aimHold: Boolean, ads: Boolean, shooting: Boolean, reloadProg: Float, yaw: Float, longGun: Boolean, baked: Boolean = true,
               pistol: Boolean = true, walk: Float = 0f, runW: Float = 0f) {
        rifle = !pistol
        if (!PistolAnim.ready) return
        var dt = clock - lastClock
        if (lastClock < 0f || dt <= 0f || dt > 0.25f) { dt = if (lastClock < 0f) 0f else 0.016f; lastYaw = yaw }
        lastClock = clock
        bakedW += ((if (baked) 1f else 0f) - bakedW) * minOf(1f, dt * 10f)
        if (baked && bakedW > 0.995f) bakedW = 1f else if (!baked && bakedW < 0.005f) bakedW = 0f

        // ---- crouch state with hysteresis + transition clips
        val wasCrouched = crouched
        if (!crouched && crouch > 0.55f) crouched = true else if (crouched && crouch < 0.45f) crouched = false
        if (crouched != wasCrouched && lastClock >= 0f && dt > 0f) { transClip = if (crouched) PistolAnim.CROUCH_DOWN else PistolAnim.STAND_UP; transT = 0f; gestClip = -1; turnClip = -1 }
        if (transClip >= 0) {
            transT += dt * 2.0f
            if (transT >= PistolAnim.clipDuration(transClip) || speed > 1.2f) transClip = -1
        }

        val reloading = reloadProg >= 0f
        if (aimHold) aimLatch = 0.6f else if (aimLatch > 0f) aimLatch -= dt
        val gunUp = aimHold || aimLatch > 0f
        val still = speed < 0.25f

        // ---- idle gestures (ready / high ready / lower+look / radio): only after a few seconds of standing still with nothing else going on
        if (gestClip >= 0) {
            gestT += dt
            if (gestT >= PistolAnim.clipDuration(gestClip) || !still || gunUp || reloading || crouched || transClip >= 0) { gestClip = -1; idleTime = 0f }
        } else if (!rifle && still && !gunUp && !reloading && !crouched && transClip < 0 && turnClip < 0) {
            idleTime += dt
            if (idleTime > 7f) {
                gestClip = when (gestIdx % 4) { 0 -> PistolAnim.READY; 1 -> PistolAnim.HIGH; 2 -> PistolAnim.LOWER; else -> PistolAnim.COMMS }
                gestIdx++; gestT = 0f
            }
        } else idleTime = 0f

        // ---- turn on the spot
        var d = yaw - lastYaw; lastYaw = yaw
        while (d > Math.PI) d -= (2.0 * Math.PI).toFloat(); while (d < -Math.PI) d += (2.0 * Math.PI).toFloat()
        yawAcc = yawAcc * exp(-dt * 4f) + d
        if (turnClip >= 0) {
            turnT += dt * 1.9f
            if (turnT >= PistolAnim.clipDuration(turnClip) || !still || gunUp || reloading || crouched) turnClip = -1
        } else if (still && !gunUp && !reloading && !crouched && transClip < 0 && gestClip < 0 && abs(yawAcc) > 0.6f) {
            turnClip = if (yawAcc > 0f) PistolAnim.TURN_R else PistolAnim.TURN_L; turnT = 0f; yawAcc = 0f; idleTime = 0f
        }

        // ---- locomotion (v49): the pack's WALK / RUN clips are NOT used any more. While the character walks or runs on its feet the legs, hip bob and torso sway come
        // from the new walk / run cycle (Loco, applied in CharModel.skinBaked from CharPose.walk / speed / runW); the pack only supplies the ready / aim pose of the upper body
        // (idle clip) and, when crouched, its crouch clips.
        val gs = PACK_SCALE * CharRig.SCALE
        val loco: Int; val rate: Float
        if (crouched) {
            if (still) { loco = PistolAnim.CROUCH_IDLE; rate = 1f } else { loco = PistolAnim.CROUCH_WALK; rate = (speed / (PistolAnim.clipSpeed(PistolAnim.CROUCH_WALK) * gs)).coerceIn(0.7f, 2.4f) }
        } else { loco = PistolAnim.IDLE; rate = 1f }
        locoT += dt * rate

        // ---- choose the clip of each channel
        var lc = loco; var lt = locoT; var uc = loco; var ut = locoT; var fade = 0.25f
        if (reloading && !rifle) {
            val rc = if (crouched) PistolAnim.CROUCH_RELOAD else PistolAnim.RELOAD
            lc = rc; uc = rc; lt = reloadProg.coerceIn(0f, 1f) * PistolAnim.clipDuration(rc); ut = lt; fade = 0.2f
        } else if (transClip >= 0) { lc = transClip; uc = transClip; lt = transT; ut = transT }
        else if (turnClip >= 0) { lc = turnClip; uc = turnClip; lt = turnT; ut = turnT }
        else if (gestClip >= 0) { lc = gestClip; uc = gestClip; lt = gestT; ut = gestT }
        // rifle mode: the gun is held by code, so a crouch transition does not lower it; the upper clip is ALWAYS the two-hand aim hold (blended in by aimSm below)
        val aimUp = gunUp && !reloading && (rifle || (transClip < 0 && gestClip < 0))
        if (rifle) { uc = PistolAnim.AIM2; ut = PistolAnim.AIM2_HOLD }
        var aimTarget = 0f
        if (aimUp) {
            val oneHand = !rifle && !ads && !longGun   // rifle mode: always two hands, hip fire too
            uc = if (oneHand) PistolAnim.AIM1 else PistolAnim.AIM2
            ut = if (oneHand) PistolAnim.AIM1_HOLD else PistolAnim.AIM2_HOLD
            aimTarget = 1f
            // recoil: a new shot restarts the kick (but not faster than every 0.12 s, so full-auto does not jitter)
            if (shooting && !wasShooting && (kickT < 0f || kickT > 0.12f)) { kickT = 0f; kickClip = if (oneHand) PistolAnim.KICK1 else PistolAnim.KICK2 }
        }
        wasShooting = shooting
        if (kickT >= 0f) {
            kickT += dt * 1.6f
            if (kickT >= PistolAnim.clipDuration(kickClip) || !aimUp) kickT = -1f
            else { uc = kickClip; ut = kickT; fade = 0.06f }
        }

        lo.apply(lc, lt, dt, fade); up.apply(uc, ut, dt, if (uc == PistolAnim.KICK1 || uc == PistolAnim.KICK2) 0.06f else fade)
        clipLower = lc; clipUpper = uc
        System.arraycopy(lo.out, 0, cur, 0, 24)
        if (rifle) {
            // v55 RIFLE: the gun + arms of the carry / run / reload are made by code (buildRifle); the baked two-hand aim hold (AIM2 / KICK2) is blended over it while aiming / firing
            System.arraycopy(cur, 0, ru, 0, 24)
            buildRifle(dt, speed, reloadProg, walk, runW)
            PistolAnim.blend(ru, up.out, aimSm, rb)
            System.arraycopy(rb, 24, cur, 24, PistolAnim.K - 24)
        } else {
            lhMagW = 0f; magHeld = false; magOff = 0f
            System.arraycopy(up.out, 24, cur, 24, PistolAnim.K - 24)
        }
        // crouch + aim: the gun / arm channel (AIM1 / AIM2) is expressed relative to the STANDING aim chest, the crouch clips have a different (leaning) chest.
        // With the crouch chest the aim-line correction in PistolRig had to compensate a large pitch / yaw mismatch (clamped -> the muzzle drifted 16-30 deg off the aim line),
        // so while aiming in a crouch the chest of the aim clip is blended in (weight = aim x crouch, both smoothed). Legs stay in the crouch clip.
        crouchSm += ((if (crouched) 1f else 0f) - crouchSm) * minOf(1f, dt * 8f)
        val cw = aimSm * crouchSm
        if (cw > 0.001f) {
            val lq = lo.out; val uq = up.out
            val sg = if (lq[0] * uq[0] + lq[1] * uq[1] + lq[2] * uq[2] + lq[3] * uq[3] < 0f) -1f else 1f
            var x = lq[0] + (uq[0] * sg - lq[0]) * cw; var y = lq[1] + (uq[1] * sg - lq[1]) * cw
            var z = lq[2] + (uq[2] * sg - lq[2]) * cw; var w = lq[3] + (uq[3] * sg - lq[3]) * cw
            val l = Math.sqrt((x * x + y * y + z * z + w * w).toDouble()).toFloat()
            if (l > 1e-6f) { cur[0] = x / l; cur[1] = y / l; cur[2] = z / l; cur[3] = w / l }
        }

        aimSm += (aimTarget - aimSm) * minOf(1f, dt * 8f); aimComp = aimSm
        leftMin = if (rifle) (if (reloading) 0f else 1f) else if (longGun && gestClip != PistolAnim.COMMS) 1f else 0f
    }

    // ------------------------------------------------------------------ v55 two-hand RIFLE pose (code-made)

    /** o[at..at+3] = Ry(yaw) * Rx(pitch) * Rz(roll) as a quaternion (x, y, z, w). Muzzle direction = Ry * Rx * (0, 0, -1): pitch < 0 = muzzle down, yaw > 0 = muzzle to the left. */
    private fun eul(yaw: Float, pitch: Float, roll: Float, o: FloatArray, at: Int) {
        val sy = sin(yaw * 0.5f); val cy = cos(yaw * 0.5f)
        val sp = sin(pitch * 0.5f); val cp = cos(pitch * 0.5f)
        val sr = sin(roll * 0.5f); val cr = cos(roll * 0.5f)
        // a = Ry * Rx
        val ax = cy * sp; val ay = sy * cp; val az = -sy * sp; val aw = cy * cp
        // o = a * Rz
        o[at] = ax * cr + ay * sr
        o[at + 1] = ay * cr - ax * sr
        o[at + 2] = az * cr + aw * sr
        o[at + 3] = aw * cr - az * sr
    }

    /**
     * Fills ru[24..39] (gun rotation / displacement relative to the aim hold, free left wrist, left-hand weight) for the rifle carry and the reload.
     *  - walking / standing: LOW READY - muzzle down, gun against the chest, BOTH hands on it
     *  - running: the gun is carried ACROSS the body with both hands, muzzle down-left, bobbing with the steps
     *  - reload (PUBG / BGMI / Free Fire): the right hand keeps the grip, the gun is canted, the LEFT hand leaves the hand-guard, pulls the magazine,
     *    takes a new one from the pouch on the hip, pushes it in, slaps it and goes back to the hand-guard (RL_* tables)
     */
    private fun buildRifle(dt: Float, speed: Float, reloadProg: Float, walk: Float, runW: Float) {
        val reloading = reloadProg >= 0f
        val u = if (reloading) (if (reloadProg > 1f) 1f else reloadProg) else 0f
        val moving = sst(0.15f, 1.0f, speed)
        val sprintT = if (!reloading && speed > 1.5f) (if (runW < 0f) 0f else if (runW > 1f) 1f else runW) else 0f
        sprintSm += (sprintT - sprintSm) * minOf(1f, dt * 7f)
        val gW = if (reloading) kf(RL_GUN, u) else 0f
        val sp = sprintSm
        // ready pose -> sprint carry (across the body)
        var gy = lerp(0f, 0.50f, sp); var gp = lerp(-0.50f, -0.85f, sp); var gr = lerp(0f, 0.15f, sp)
        var dx = lerp(-0.03f, -0.10f, sp); var dy = lerp(-0.10f, -0.14f, sp); var dz = lerp(0.05f, 0.10f, sp)
        // -> reload pose (canted gun, the magazine well turned to the left hand)
        gy = lerp(gy, 0.15f, gW); gp = lerp(gp, -0.25f, gW); gr = lerp(gr, -0.55f, gW)
        dx = lerp(dx, -0.10f, gW); dy = lerp(dy, -0.12f, gW); dz = lerp(dz, 0.08f, gW)
        // step bob (stronger while running) and a little breathing while standing
        val bobA = moving * (0.5f + 0.5f * sp) * (1f - 0.6f * gW)
        gy += sin(walk) * 0.035f * bobA
        gp += sin(walk * 2f) * 0.03f * bobA * (1f + sp) + sin(lastClock * 1.8f) * 0.010f * (1f - moving)
        gr += sin(walk) * 0.05f * sp * (1f - gW)
        dy += sin(walk * 2f) * 0.012f * bobA * (1f + 1.5f * sp)
        dx += sin(walk) * 0.010f * bobA
        // the slap on the new magazine shakes the gun a hair
        if (reloading) dz += sst(0.85f, 0.90f, u) * (1f - sst(0.90f, 0.96f, u)) * 0.025f
        eul(gy, gp, gr, ru, 24)
        ru[28] = dx; ru[29] = dy; ru[30] = dz
        ru[31] = POUCH_X; ru[32] = POUCH_Y; ru[33] = POUCH_Z
        ru[34] = if (reloading) kf(RL_WH, u) else 1f
        ru[35] = 0f; ru[36] = 0f; ru[37] = 0f; ru[38] = 0f; ru[39] = 0f
        // magazine + the point of the gun the left hand grips (unscaled gun-mesh space, see PistolRig.lhMagPt)
        if (reloading) {
            lhMagW = kf(RL_WM, u)
            val offS = if (rig.magH < 0.1f) 0.55f else 1f
            val off = kf(RL_OFF, u) * offS
            magOff = off
            magC[0] = rig.magX; magC[1] = rig.magY + rig.dirY * off; magC[2] = rig.magZ
            lhMagPt[0] = rig.magX - 0.03f; lhMagPt[1] = rig.magY + rig.dirY * (off + rig.magH * 0.30f); lhMagPt[2] = rig.magZ
            magHeld = lhMagW > 0.5f && off > 0.05f
        } else { lhMagW = 0f; magOff = 0f; magHeld = false }
    }
}
