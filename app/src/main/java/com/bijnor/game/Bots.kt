package com.bijnor.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Bot(val name: String, val shirt: Int, val gun: Gun) {
    var x = 0f; var y = 0f; var z = 0f
    var hp = 200f; var alive = false
    var yaw = 0f; var walk = 0f; var speed = 0f
    var runW = 0f   // v49: 0 = walk clip, 1 = run clip (a bot runs only when it hurries: speed > 4.2 m/s = urgent / taking cover)
    var cooldown = 1f; var think = 0f; var strafe = 1f
    var visible = false; var deadAt = -100f; var barTime = 0f
    var wanderX = 0f; var wanderZ = 0f; var wanderT = 0f
    val avoid = Random.nextFloat() * 6.28f
    var grenades = 1; var nadeCd = 4f + Random.nextFloat() * 6f
    // air drop: 0 in plane, 1 freefall, 2 parachute, 3 on the ground (normal AI)
    var air = 3; var jumpAt = 0f; var tx = 0f; var tz = 0f
    var vx = 0f; var vy = 0f; var vz = 0f; var dive = true; var chuteT = 0f
    var reported = false   // kill-feed entry already written
    // smart cover AI
    var coverX = 0f; var coverZ = 0f; var coverT = 0f; var coverCd = 0f; var hurtT = 0f; var lastHp = 200f
    // bot-vs-bot fights
    var foe: Bot? = null; var kills = 0
    // Phase 28: air visuals (skate stance / dive / canopy pose = descend() + dskAirPose() of the HTML), sway only after the first shot
    // (HTML: e._pr exists from the first shot on)
    val av = AirVis(); var fired = false
    // pistol animation pack (PistolState): own animation state per bot + game clock of its last shot (drives the aim pose + recoil kick)
    val pa = PistolState(); var shotAt = -100f
    // v29 realism: hearing + memory (it follows a seen / heard sign of an enemy for a while), falling, footsteps
    var alertT = 0f; var lastX = 0f; var lastZ = 0f
    var fallAir = false; var fallVy = 0f; var fallTop = 0f
    var stepAcc = 0f; var diedBy = ""
}

class Tracer(val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float, var life: Float)

/** v32: a flying bullet of a bot. target = the bot it was aimed at (bot-vs-bot fights) or null = it can only hit the player. */
class BotSlug(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val owner: Bot, val gun: Gun, val target: Bot?) {
    var trav = 0f; var age = 0f
    var dmgK = 1f; var walls = 0   // v36: share of the damage left after the walls it went through
    var whizzed = false            // v44: already reported as a near miss (only once per bullet)
}

/**
 * Port of botUpdate()/botShoot() from the HTML (normal difficulty): picks the player as target, sees 53 m with
 * line of sight, strafes inside 17 m and backs off inside 8 m, walks 3.65 m/s (2.1 when fighting), fires every
 * 0.45-0.9 s with spread 0.028 * gun factor for 9 damage.
 */
class BotSystem(private val map: MapData, private val rnd: Random, count: Int) {
    @Volatile var noDamage = false   // warm-up: nobody gets hurt
    companion object {
        val NAMES = listOf("KESTREL", "MICA", "ROOK", "SABLE", "FLINT", "NOVA", "DUNE", "COBALT", "WREN", "EMBER", "ASH", "ECHO", "SLATE", "HAZE", "QUILL")
        val SHIRTS = intArrayOf(0xFF7A2F2F.toInt(), 0xFF2F5A7A.toInt(), 0xFF5A7A2F.toInt(), 0xFF7A5A2F.toInt(), 0xFF5A2F7A.toInt(), 0xFF2F7A6A.toInt())
        const val HP = 200f
        const val DAMAGE = 9f
        const val EYE = 1.55f * MapData.PLAYER_K
        const val BODY_H = 1.78f * MapData.PLAYER_K   // v48: the hit-box is as tall as the model (1.78 m)
        const val HEAD_Y = 1.50f * MapData.PLAYER_K   // v48: head = top 28 cm
    }

    val bots = ArrayList<Bot>()
    /** game clock of the current update() - shoot() stores it as the shot time of the bot even when no clock is passed in (drives the pistol-pack aim pose + recoil) */
    private var now = 0f
    val tracers = ArrayList<Tracer>()
    /** v32: bullets in flight (no more instant hits) */
    val slugs = ArrayList<BotSlug>()
    /** v32: player velocity (m/s, smoothed) so a bot can lead a moving target */
    private var lpx = 0f; private var lpz = 0f; private var pvx = 0f; private var pvz = 0f; private var lpInit = false
    /** called when a bot fires (for the shot sound) */
    var onShot: ((Bot) -> Unit)? = null
    /** v44 BULLET WHIZ: a bot bullet flew past the player without hitting: closest distance (m), x, y, z of the closest point, bullet speed (m/s) */
    var onWhiz: ((Float, Float, Float, Float, Float) -> Unit)? = null
    private val whizTmp = FloatArray(3)
    /** v36: a bot bullet hit a surface: x, y, z, material (Impact.*), surface normal x, y, z - the renderer shows the dust / splinters / splash */
    var onImpact: ((Float, Float, Float, Int, Float, Float, Float) -> Unit)? = null
    /** called when a bot kills another bot: (killer, victim, weapon name) - renderer writes the kill feed and drops loot */
    var onBotKill: ((Bot, Bot, String) -> Unit)? = null
    /** v29: a bot hit the ground after a fall: (bot, fall height m, damage) */
    var onBotLand: ((Bot, Float, Float) -> Unit)? = null
    /** v29: how far the player can be SEEN: 1 standing, 0.8 crouched, 0.6 prone (the renderer sets it every frame) */
    @Volatile var sightK = 1f
    /** v63: height of the player's hit-box (m): 1.78 standing, lower crouched, lowest prone (the renderer sets it every frame). Before, a crouching / prone player was still hit at full standing height. */
    @Volatile var playerH = MapData.PERSON_H
    private val angles = floatArrayOf(0f, .5f, -.5f, 1f, -1f, 1.55f, -1.55f, 2.2f, -2.2f, 3.1416f)
    private val tmp = FloatArray(3)

    init {
        for (i in 0 until count) {
            val g = Weapons.ALL[rnd.nextInt(Weapons.ALL.size)]
            bots.add(Bot(NAMES[i % NAMES.size] + (if (i >= NAMES.size) "${i / NAMES.size + 1}" else ""), SHIRTS[i % SHIRTS.size], g))
        }
    }

    fun spawnAll(px: Float, pz: Float, clock: Float) { for (b in bots) respawn(b, px, pz, clock) }

    private fun respawn(b: Bot, px: Float, pz: Float, clock: Float) {
        var p: FloatArray? = null
        for (n in 0 until 40) { // spread over the whole 4561 m map, at least 70 m from the player
            val c = map.randomLandAny(rnd) ?: continue
            if (hypot(c[0] - px, c[2] - pz) > 70f) { p = c; break }
        }
        if (p == null) { b.alive = false; return }
        b.x = p[0]; b.y = p[1]; b.z = p[2]; b.hp = HP; b.alive = true; b.cooldown = 1f + rnd.nextFloat() * 1.5f
        b.think = 0f; b.visible = false; b.barTime = 0f; b.speed = 0f; b.runW = 0f; b.shotAt = -100f; b.pa.reset()
        b.alertT = 0f; b.fallAir = false; b.fallVy = 0f; b.diedBy = ""
    }

    private fun between(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private fun free(b: Bot, x: Float, z: Float) = !map.terrainBlocked(x, z, 0.45f, b.y) && !map.boxBlocked(x, z, 0.45f, b.y) && !map.circleBlocked(x, z, 0.42f) &&
        (map.waterDepth(x, z) < 0.6f || map.waterDepth(b.x, b.z) >= 0.6f)   // bots do not wade into deep lake water (but are never trapped if they already stand in it)

    private fun hasLos(b: Bot, px: Float, py: Float, pz: Float): Boolean {
        val ox = b.x; val oy = b.y + 1.4f * MapData.PLAYER_K; val oz = b.z
        var dx = px - ox; var dy = py + 0.73f * playerH - oy; var dz = pz - oz
        val d = sqrt(dx * dx + dy * dy + dz * dz); if (d < 0.01f) return true
        dx /= d; dy /= d; dz /= d
        val t = map.rayWorld(ox, oy, oz, dx, dy, dz, d, true)
        return t < 0f || t > d - 0.5f
    }

    /** onHit(damage, botName) is called when a bot bullet hits the player. */
    fun update(dt: Float, clock: Float, px: Float, py: Float, pz: Float, playerAlive: Boolean, zone: Float, zx: Float, zz: Float, zoneDmg: Float, onHit: (Float, String) -> Unit, onThrow: (Bot, Float, Float, Float) -> Unit) {
        now = clock
        val it = tracers.iterator(); while (it.hasNext()) { val t = it.next(); t.life -= dt; if (t.life <= 0f) it.remove() }
        if (!lpInit || dt <= 1e-4f) { lpx = px; lpz = pz; lpInit = true }
        else {
            val k = minOf(1f, dt * 8f)
            val ivx = ((px - lpx) / dt).coerceIn(-12f, 12f); val ivz = ((pz - lpz) / dt).coerceIn(-12f, 12f)
            pvx += (ivx - pvx) * k; pvz += (ivz - pvz) * k
            lpx = px; lpz = pz
        }
        stepSlugs(dt, px, py, pz, playerAlive, onHit)
        for (b in bots) {
            if (!b.alive) continue
            if (b.air != 3) continue
            b.nadeCd -= dt; b.cooldown -= dt; b.think -= dt; b.barTime = max(0f, b.barTime - dt); b.wanderT -= dt
            b.coverCd -= dt; b.hurtT = max(0f, b.hurtT - dt)
            val dx0 = px - b.x; val dz0 = pz - b.z; val dist = hypot(dx0, dz0)
            if (b.think <= 0f) {
                b.think = between(.4f, .85f)
                b.visible = playerAlive && dist < 53f * sightK && hasLos(b, px, py, pz)
                // bot-vs-bot: nearest visible enemy bot (closer than the player if the player is visible too)
                b.foe = null
                run {
                    var fd = if (b.visible) dist else 53f
                    for (o in bots) {
                        if (o === b || !o.alive || o.air != 3) continue
                        val dd = hypot(o.x - b.x, o.z - b.z)
                        if (dd < fd && hasLos(b, o.x, o.y, o.z)) { fd = dd; b.foe = o }
                    }
                }
            }
            b.alertT = max(0f, b.alertT - dt)
            if (b.visible && playerAlive) { b.alertT = 9f; b.lastX = px; b.lastZ = pz }   // v29: it saw you - it remembers where you were for 9 s
            val rad = hypot(b.x - zx, b.z - zz); val urgent = rad > zone - 5f
            if (rad > zone && zoneDmg > 0f) { b.hp -= zoneDmg; if (b.hp <= 0f) { b.alive = false; b.deadAt = clock; continue } }
            // v29: no more wall-hack. A bot goes for you only while it SEES you; after that (or after it HEARD a shot / steps) it walks to the last known spot
            val chase = !urgent && playerAlive && b.visible
            val investigate = !urgent && !chase && b.alertT > 0f
            var foe = b.foe
            if (foe != null && (!foe.alive || foe.air != 3 || hypot(foe.x - b.x, foe.z - b.z) > 60f)) { b.foe = null; foe = null }
            if (b.hp < b.lastHp - 0.01f) b.hurtT = 3f
            b.lastHp = b.hp
            run {
                if (b.coverT > 0f) b.coverT -= dt
                else if (chase && b.visible && b.hurtT > 0f && dist > 6f && b.coverCd <= 0f) { // smart cover: hurt -> run behind a wall, peek again later
                    val cv = findCover(b, px, py, pz)
                    if (cv != null) { b.coverX = cv[0]; b.coverZ = cv[1]; b.coverT = between(2.5f, 4.5f); b.coverCd = 9f } else b.coverCd = 2f
                }
            }
            var gx: Float; var gz: Float
            if (urgent) { gx = zx; gz = zz }
            else if (b.coverT > 0f) { gx = b.coverX; gz = b.coverZ }
            else if (foe != null) { gx = foe.x; gz = foe.z }
            else if (chase) { gx = px; gz = pz }
            else if (investigate) { gx = b.lastX; gz = b.lastZ }
            else {
                if (b.wanderT <= 0f) {
                    b.wanderT = between(6f, 11f)
                    val w = map.randomLand(rnd, b.x, b.z, 25f, 70f); if (w != null) { b.wanderX = w[0]; b.wanderZ = w[2] } else { b.wanderX = b.x; b.wanderZ = b.z }
                }
                gx = b.wanderX; gz = b.wanderZ
            }
            var dx = gx - b.x; var dz = gz - b.z
            val ex = if (foe != null) foe.x else px; val ez = if (foe != null) foe.z else pz
            val edist = if (foe != null) hypot(foe.x - b.x, foe.z - b.z) else dist
            val engaged = foe != null || (chase && b.visible)
            if (engaged && !urgent && edist < 17f && b.coverT <= 0f) {
                val f = if (sin(clock * .65f + b.avoid) > 0) 1f else -1f
                val old = dx; dx = dz * f; dz = -old * f
                if (edist < 8f) { dx += (b.x - ex) * 1.5f; dz += (b.z - ez) * 1.5f }
            }
            val len = hypot(dx, dz)
            if (len > 0.5f) {
                dx /= len; dz /= len
                var best = -1e9f; var bx = 0f; var bz = 0f
                for (a in angles) {
                    val x = dx * cos(a) - dz * sin(a); val z = dx * sin(a) + dz * cos(a)
                    if (free(b, b.x + x * .75f, b.z + z * .75f)) {
                        val score = x * dx + z * dz - abs(a) * .035f
                        if (score > best) { best = score; bx = x; bz = z }
                    }
                }
                val sp = if (urgent || b.coverT > 0f) 4.7f else if (engaged && edist < 20f) 2.1f else 3.65f
                val nx = b.x + bx * sp * dt; val nz = b.z + bz * sp * dt
                if (free(b, nx, b.z)) b.x = nx
                if (free(b, b.x, nz)) b.z = nz
                tmp[0] = b.x; tmp[1] = b.y; tmp[2] = b.z; map.resolve(tmp, 0.42f); b.x = tmp[0]; b.z = tmp[2]
                b.speed = if (best > -1e8f) sp else 0f
            } else b.speed = 0f
            if (investigate && hypot(b.lastX - b.x, b.lastZ - b.z) < 3f) b.alertT = minOf(b.alertT, 1f)   // reached the sound: look around a moment, then give up
            val g = map.footY(b.x, b.z, b.y)
            if (!b.fallAir && b.y - g > Realism.FALL_START) { b.fallAir = true; b.fallVy = 0f; b.fallTop = b.y }   // v29: stepping off a roof / balcony edge is a real fall
            if (b.fallAir) {
                b.fallVy -= 20f * dt; b.y += b.fallVy * dt
                if (b.y <= g) {
                    val h = b.fallTop - g; b.y = g; b.fallAir = false; b.fallVy = 0f
                    val wet = map.waterDepth(b.x, b.z)
                    var dmg = Realism.fallDamage(h); if (wet > 1.25f) dmg = 0f else if (wet > 0.45f) dmg *= 0.4f
                    if (dmg > 0f && !noDamage) {
                        b.hp -= dmg; b.barTime = 3f
                        if (b.hp <= 0f) { b.alive = false; b.deadAt = clock; b.diedBy = "died from a fall"; onBotLand?.invoke(b, h, dmg); continue }
                    }
                    onBotLand?.invoke(b, h, dmg)
                }
            } else b.y += (g - b.y) * minOf(1f, dt * 14f)
            b.runW += ((if (b.speed > 4.2f) 1f else 0f) - b.runW) * minOf(1f, dt * 8f)
            b.walk += Gait.omega(b.speed, false, false, b.runW) * dt
            b.yaw = if (foe != null && !urgent && edist < 55f) atan2(b.x - ex, b.z - ez) else if (chase && dist < 50f) atan2(b.x - px, b.z - pz) else atan2(-dx, -dz)
            if (foe != null) {
                if (b.grenades > 0 && b.nadeCd <= 0f && edist > 10f && edist < 28f) { b.grenades--; b.nadeCd = between(6f, 12f); onThrow(b, foe.x, foe.y, foe.z) }
                if (b.cooldown <= 0f && edist < 64f) { b.cooldown = between(.45f, .9f); shootBot(b, foe, clock) }
            } else
            if (playerAlive && b.visible && b.grenades > 0 && b.nadeCd <= 0f && dist > 10f && dist < 28f) {
                b.grenades--; b.nadeCd = between(6f, 12f); onThrow(b, px, py, pz)
            }
            if (playerAlive && b.visible && b.cooldown <= 0f && dist < 64f) {
                b.cooldown = between(.45f, .9f)
                shoot(b, px, py, pz, onHit, 1f, clock)
            }
        }
    }

    // ---------------------------------------------------------------- hearing (v29)
    /**
     * A noise at (x, y, z) that carries `radius` m in the open air (footsteps, shots, grenades, landings). Every bot on the ground inside that range turns
     * toward it and walks to the spot. Solid walls / floors between cut the range by 40 % each, the closer the sure-er the bot is, and the place it
     * remembers is never exact (error grows with distance). The bot that made the noise does not hear itself.
     */
    fun hear(x: Float, y: Float, z: Float, radius: Float, src: Bot?) {
        for (b in bots) {
            if (!b.alive || b.air != 3 || b === src) continue
            val dx = b.x - x; val dz = b.z - z
            if (dx > radius || dx < -radius || dz > radius || dz < -radius) continue
            val dy = b.y - y
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > radius) continue
            val e = 1.2f * MapData.PLAYER_K
            val w = map.soundWalls(x, y + e, z, b.x, b.y + e, b.z)
            var eff = radius; for (i in 0 until w) eff *= 0.6f
            if (d > eff) continue
            if (rnd.nextFloat() > 1f - 0.6f * d / eff) continue   // a faint noise at the edge of hearing is often missed
            b.alertT = max(b.alertT, 8f)
            if (!b.visible) { val err = 1.5f + d * 0.12f; b.lastX = x + between(-err, err); b.lastZ = z + between(-err, err) }
            b.think = minOf(b.think, 0.15f)   // look for the source right away
        }
    }

    // ---------------------------------------------------------------- smart cover
    private fun blockedLine(ox: Float, oy: Float, oz: Float, tx: Float, ty: Float, tz: Float): Boolean {
        var dx = tx - ox; var dy = ty - oy; var dz = tz - oz
        val d = sqrt(dx * dx + dy * dy + dz * dz); if (d < 0.01f) return false
        dx /= d; dy /= d; dz /= d
        val t = map.rayWorld(ox, oy, oz, dx, dy, dz, d, true)
        return t >= 0f && t < d - 0.3f
    }

    /** A free spot 3-11 m away that the player cannot see (head and chest height both blocked), nearest first. */
    private fun findCover(b: Bot, px: Float, py: Float, pz: Float): FloatArray? {
        var best: FloatArray? = null; var bd = 1e9f
        for (n in 0 until 14) {
            val a = rnd.nextFloat() * 6.2831855f; val r = between(3f, 11f)
            val x = b.x + sin(a) * r; val z = b.z + cos(a) * r
            if (!free(b, x, z)) continue
            val y = map.footY(x, z, b.y)
            if (abs(y - b.y) > 1.2f) continue
            if (hypot(x - px, z - pz) < 5f) continue
            if (!blockedLine(px, py + 1.5f, pz, x, y + 1.4f, z)) continue
            if (!blockedLine(px, py + 1.5f, pz, x, y + 0.6f, z)) continue
            val d = hypot(x - b.x, z - b.z)
            if (d < bd) { bd = d; best = floatArrayOf(x, z) }
        }
        return best
    }

    /** v32: moves the bullets of the bots; same sub-step idea as the player's bullets. A bullet stops at the first wall (glass breaks), the player or its target bot. */
    private fun stepSlugs(dt: Float, px: Float, py: Float, pz: Float, playerAlive: Boolean, onHit: (Float, String) -> Unit) {
        if (slugs.isEmpty() || dt <= 0f) return
        val n = kotlin.math.ceil(dt / 0.01f).toInt().coerceIn(1, 10); val h = dt / n
        var i = slugs.size - 1
        while (i >= 0) {
            val s = slugs[i]; var dead = false; var k = 0
            while (k < n && !dead) {
                k++
                s.vy -= Ballistics.G * h
                val ex = s.vx * h; val ey = s.vy * h; val ez = s.vz * h
                val len = sqrt(ex * ex + ey * ey + ez * ez)
                if (len < 1e-4f) { dead = true; break }
                val dx = ex / len; val dy = ey / len; val dz = ez / len
                var rem = len   // v36: thin walls can be punched through (see Penetration.kt), so one sub-step may hold several pieces
                while (rem > 1e-3f && !dead) {
                    var tw = map.shootThrough(s.x, s.y, s.z, dx, dy, dz, rem)
                    val matHit = if (tw > 0f) map.impactMaterial(s.x + dx * tw, s.z + dz * tw) else Impact.CONCRETE
                    val nrm = if (tw > 0f) map.impactNormal(s.x + dx * tw, s.y + dy * tw, s.z + dz * tw, dx, dy, dz) else null
                    val nx = nrm?.get(0) ?: 0f; val ny = nrm?.get(1) ?: 1f; val nz = nrm?.get(2) ?: 0f
                    var water = false
                    val twWater = map.waterCross(s.x, s.y, s.z, dx, dy, dz, rem)
                    if (twWater > 0f && (tw < 0f || twWater < tw)) { tw = twWater; water = true }
                    val twBox = tw > 0f && !water && !map.lastHitTerrain
                    var tp = -1f
                    val o = s.target
                    if (o == null) {
                        if (playerAlive) tp = MapData.rayBody(s.x, s.y, s.z, dx, dy, dz, px, pz, 0.3f, 0.3f, py, py + playerH, rem)
                    } else if (o.alive) {
                        tp = MapData.rayBody(s.x, s.y, s.z, dx, dy, dz, o.x, o.z, .3f, .25f, o.y, o.y + BODY_H, rem)
                    }
                    val hitsPlayer = tp >= 0f && (tw < 0f || tp < tw)
                    if (!hitsPlayer && !s.whizzed && o == null && playerAlive) {   // v44: a miss that passes close = crack + suppression
                        val seg = if (tw > 0f) minOf(tw, rem) else rem
                        val dd = Suppression.closest(s.x, s.y, s.z, dx, dy, dz, seg, px, py + 1.3f * MapData.PLAYER_K, pz, whizTmp)
                        if (dd < Suppression.NEAR) {
                            s.whizzed = true
                            onWhiz?.invoke(dd, whizTmp[0], whizTmp[1], whizTmp[2], sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz))
                        }
                    }
                    if (hitsPlayer) {
                        val dmg = Prefs.botDamage() * s.dmgK
                        if (o == null) onHit(dmg, s.owner.name)
                        else {
                            if (!noDamage) o.hp -= dmg; o.barTime = 3f
                            if (o.hp <= 0f && o.alive) { o.alive = false; o.deadAt = now; o.reported = true; s.owner.kills++; onBotKill?.invoke(s.owner, o, s.gun.name) }
                        }
                        dead = true
                    } else if (tw > 0f) {
                        if (water) onImpact?.invoke(s.x + dx * tw, s.y + dy * tw, s.z + dz * tw, Impact.WATER, 0f, 1f, 0f)
                        else onImpact?.invoke(s.x + dx * tw, s.y + dy * tw, s.z + dz * tw, matHit, nx, ny, nz)
                        val th = if (twBox && s.walls < Penetration.maxWalls(s.gun)) map.wallThickness(s.x, s.y, s.z, dx, dy, dz, tw, Penetration.limit(s.gun)) else -1f
                        if (th > 0f) {
                            val adv = tw + th + 0.03f
                            s.dmgK *= Penetration.damageKeep(s.gun, th); s.walls++
                            onImpact?.invoke(s.x + dx * (adv - 0.03f), s.y + dy * (adv - 0.03f), s.z + dz * (adv - 0.03f), matHit, dx, dy * 0.3f, dz)
                            val sk = Penetration.speedKeep(s.gun); s.vx *= sk; s.vy *= sk; s.vz *= sk
                            s.x += dx * adv; s.y += dy * adv; s.z += dz * adv; s.trav += adv
                            rem -= adv
                        } else dead = true
                    } else {
                        s.x += dx * rem; s.y += dy * rem; s.z += dz * rem; s.trav += rem
                        rem = 0f
                    }
                }
                if (!dead) {
                    s.age += h
                    if (s.trav > 120f || s.age > 3f || s.y < -80f) dead = true
                }
            }
            if (dead) slugs.removeAt(i)
            i--
        }
    }

    /** v63: the bullet starts 0.6 m in front of the bot. If a wall stands inside that 0.6 m (bot hugging a wall) the bullet used to start BEHIND the wall and fly through it untouched - now it starts at the bot and hits the wall. */
    private fun muzzleSlug(b: Bot, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, sp: Float, target: Bot?) {
        val t = map.rayWorld(ox, oy, oz, dx, dy, dz, 0.7f, true)
        val k = if (t >= 0f) 0f else 0.6f
        slugs.add(BotSlug(ox + dx * k, oy - 0.1f, oz + dz * k, dx * sp, dy * sp, dz * sp, b, b.gun, target))
    }

    /** Bot fires at another bot: same spread / damage as shoot(), blocked by walls, kill credit + loot via onBotKill. */
    private fun shootBot(b: Bot, o: Bot, clock: Float) {
        b.fired = true; b.shotAt = clock
        val spread = Prefs.botSpread() * max(.35f, b.gun.spread / .01f)
        val ox = b.x; val oy = b.y + EYE; val oz = b.z
        var dx = o.x - ox; var dy = o.y + 1.2f * MapData.PLAYER_K - oy; var dz = o.z - oz
        val d0 = sqrt(dx * dx + dy * dy + dz * dz); if (d0 < 0.01f) return
        run { val tt = d0 / Ballistics.botSpeed(b.gun); dy += 0.5f * Ballistics.G * tt * tt }   // hold over for the drop
        dx /= d0; dy /= d0; dz /= d0
        dx += between(-spread, spread); dy += between(-spread, spread); dz += between(-spread, spread)
        val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
        val sp = Ballistics.botSpeed(b.gun)
        muzzleSlug(b, ox, oy, oz, dx, dy, dz, sp, o)
        onShot?.invoke(b)
    }

    private fun shoot(b: Bot, px: Float, py: Float, pz: Float, onHit: (Float, String) -> Unit, spreadK: Float = 1f, clock: Float = 0f) {
        b.fired = true; b.shotAt = if (clock > 0f) clock else now
        val spread = Prefs.botSpread() * max(.35f, b.gun.spread / .01f) * spreadK
        val ox = b.x; val oy = b.y + EYE; val oz = b.z
        var dx = px - ox + 0f; var dy = py + 0.67f * playerH - oy; var dz = pz - oz
        val d0 = sqrt(dx * dx + dy * dy + dz * dz)
        run {   // v32: the bullet needs time to arrive - lead a moving player and hold over for the drop
            val tt = d0 / Ballistics.botSpeed(b.gun)
            dx += pvx * tt * 0.85f; dz += pvz * tt * 0.85f; dy += 0.5f * Ballistics.G * tt * tt
        }
        val d1 = sqrt(dx * dx + dy * dy + dz * dz); dx /= d1; dy /= d1; dz /= d1
        dx += between(-spread, spread); dy += between(-spread, spread); dz += between(-spread, spread)
        val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
        val sp = Ballistics.botSpeed(b.gun)
        muzzleSlug(b, ox, oy, oz, dx, dy, dz, sp, null)
        onShot?.invoke(b)
    }
}
