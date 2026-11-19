package com.bijnor.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** Round-start aircraft intro (v45: only the FIRST animation is kept): the plane flies in from behind past a fixed side camera (PLANE_FLY_IN s), then a short blend (PLANE_BLEND s) into the normal chase camera. The old second animation (camera swinging round the plane) is gone. */
private const val PLANE_FLY_IN = 6f
/** The Bijnor land (about 10 km2) is ~6x the old island, so every ground-loot count is x LOOT_MULT. */
private const val LOOT_MULT = 6
private const val PLANE_BLEND = 0.9f

/** OpenGL ES 3.0 renderer: island map, third-person walking, 3D guns, shooting, reload, training targets. */
class GameRenderer(private val ctx: Context, private val map: MapData, private val sfx: SoundFx? = null) : GLSurfaceView.Renderer {
    // ---- input (written by UI thread)
    @Volatile var moveX = 0f
    @Volatile var moveY = 0f
    @Volatile var run = false
    @Volatile var firing = false
    @Volatile private var jumpReq = false
    @Volatile private var reloadReq = false
    @Volatile private var switchDir = 0
    @Volatile private var dYaw = 0f
    @Volatile private var dPitch = 0f
    fun jump() { jumpReq = true }
    fun reload() { reloadReq = true }
    fun switchGun(dir: Int) { switchDir = dir }
    @Synchronized fun look(dx: Float, dy: Float) { dYaw += dx; dPitch += dy }
    @Synchronized fun clearLook() { dYaw = 0f; dPitch = 0f }
    @Synchronized private fun takeLook(): FloatArray { val r = floatArrayOf(dYaw, dPitch); dYaw = 0f; dPitch = 0f; return r }

    // ---- state shown on the HUD
    @Volatile var fps = 0
    @Volatile var posText = ""
    @Volatile var gunName = ""
    @Volatile var ammoNow = 0
    @Volatile var ammoMax = 0
    @Volatile var reloadProgress = -1f
    @Volatile var hitTime = 0L
    @Volatile var hitDamage = 0
    @Volatile var hitHead = false
    @Volatile var hitKill = false
    @Volatile var playerHp = 200f
    @Volatile var playerAlive = true
    @Volatile var kills = 0
    @Volatile var botsAlive = 0
    @Volatile var damageTime = 0L
    @Volatile var killedBy = ""
    @Volatile var respawnIn = 0f
    @Volatile var matchState = 0          // 0 playing, 1 won, 2 lost
    @Volatile var matchTime = 0f
    @Volatile var zoneRadius = 0f
    @Volatile var zoneText = ""
    @Volatile var zoneDist = 0f           // metres inside (+) / outside (-) the zone edge
    @Volatile var result: MatchResult? = null
    @Volatile var alivePlace = 0
    @Volatile var damageDealt = 0          // total damage the player did this match (shown on the result screen)
    fun playSfx(name: String, gain: Float = 1f) { sfx?.play(name, gain) }
    @Volatile var winT = 0f                // seconds since the player won (WINNER celebration)
    val totalPlayers: Int get() = botSys.bots.size + 1
    /** WINNER celebration: the camera swings in front of the player's own body  */
    private val winCam: Boolean get() = matchState == 1 && playerAlive && !spec && airState == 3
    /** numbers for the result screen (null while the match is still running) */
    fun summary(): MatchSummary? = if (matchState == 0) null else MatchSummary(matchState == 1, alivePlace, totalPlayers, kills, damageDealt, matchTime, result?.xp ?: 0, result?.coins ?: 0)
    @Volatile var aiming = false
    @Volatile var grenadeCount = 2
    // ---- phase 10: mushrooms / EP, level-4 gear, airdrops, zone v39, kill feed, mini-map data
    @Volatile var ep = 0f
    @Volatile var mushrooms = 0
    @Volatile private var eatReq = false
    fun eat() { eatReq = true }
    private var epTimer = 0f
    private val EP_MAX = 250f
    @Volatile var helmLvl = 0
    @Volatile var helmHp = 0f
    @Volatile var paused = false
    // ---- v45 REAL LOADING: the loading screen shows exactly this. The world stands still (hold) until the loading screen has reached 100 %, so the 30 s warm-up and the plane intro start AFTER it.
    @Volatile var loadProgress = 0f
    @Volatile var glReady = false
    @Volatile var hold = true
    @Volatile private var framesShown = 0
    /** everything uploaded to the GPU and a few frames really drawn (shaders compiled) */
    val loadDone: Boolean get() = glReady && framesShown >= 4
    @Volatile var vibMs = 0
    @Volatile var airdropText = ""
    @Volatile var posX = 0f
    @Volatile var posZ = 0f
    @Volatile var yawNow = 0f
    @Volatile var zoneCx = 0f
    @Volatile var zoneCz = 0f
    @Volatile var nextX = 0f
    @Volatile var nextZ = 0f
    @Volatile var nextR = 0f                      // <= 0: no next circle announced yet
    @Volatile var dropMarks = FloatArray(0)       // x,z pairs of crates that are falling or still hold loot
    class FeedItem(val text: String, val time: Long)
    val feed = java.util.concurrent.CopyOnWriteArrayList<FeedItem>()
    private fun addFeed(t: String) { feed.add(FeedItem(t, System.nanoTime())); while (feed.size > 6) feed.removeAt(0) }
    private val post = PostFx()
    private var postLevel = 2          // 2 = bloom + AO, 1 = bloom only, 0 = off (auto performance)
    private var frameAvg = 0.016f; private var slowT = 0f
    /** Called when the settings close: a bloom / AO / graphics change gets a fresh start (auto-performance may have shed them earlier). */
    fun resetPerf() { postLevel = 2; slowT = 0f; frameAvg = 0.016f }
    private val zone = ZoneSystem(map, Random(System.nanoTime() xor 0x1234567L))
    private var zoneTickT = 0f
    private var stepDist = 0f
    private var fallTopY = 0f   // v29: highest feet height of the current air time (fall damage = drop height)
    private var limpT = 0f      // v29: seconds of limp left after a hard landing
    private class Drop(val x: Float, val z: Float, val g: Float, val dir: Int) {
        var state = 0; var t = 0f; var ty = 0f; var h = 210f; var landT = 0f; var life = 0f; var beam = 0.3f
        val items = ArrayList<Loot>()
    }
    private val drops = ArrayList<Drop>()
    private val AD_TIMES = floatArrayOf(45f, 150f, 270f)   // AD_TIMES of the HTML
    private var adN = 0
    private var adNext = 75f
    fun silence() { sfx?.stopAll() }
    @Volatile private var nadeReq = false
    fun throwGrenade() { nadeReq = true }
    @Volatile var adsAmount = 0f
    @Volatile var scopeOverlay = false
    /** scope open / close animation 0..1 (iris + reticle), driven every frame; independent of the ADS zoom so it can ease on its own curve */
    @Volatile var scopeAnim = 0f
    /** v58 SCOPE LENS: radius of the scope lens as a fraction of the screen height (already animated, 0 = no scope). The HUD draws the scope body around it. */
    @Volatile var scopeLensFrac = 0f
    @Volatile var scopeLensMag = 2
    private var lensSel = 2
    /** v59: the view-only tremble of suppression (look target offset) - the bullet gets the same offset, so it goes where the crosshair really is */
    private var shkX = 0f; private var shkY = 0f; private var shkZ = 0f
    private var lensFbo = 0; private var lensTex = 0; private var lensDepth = 0; private var lensSize = 0
    private var lensProg = 0; private var lensVao = 0; private var lensFailed = false
    private val fbSaved = IntArray(1); private val projSave = FloatArray(16); private val vpSave = FloatArray(16)
    /** lens radius / screen height at full open: the bigger the scope, the more of the screen it covers (2X small, 4X medium, 8X big) */
    private fun scopeBaseFrac(z: Int) = if (z >= 8) 0.46f else if (z >= 4) 0.37f else 0.28f
    private fun releaseLens() {
        try {
            if (lensFbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(lensFbo), 0)
            if (lensTex != 0) GL.glDeleteTextures(1, intArrayOf(lensTex), 0)
            if (lensDepth != 0) GL.glDeleteRenderbuffers(1, intArrayOf(lensDepth), 0)
        } catch (_: Throwable) { }
        lensFbo = 0; lensTex = 0; lensDepth = 0; lensSize = 0
    }
    /** off-screen square target the zoomed scope picture is drawn into (the rest of the screen is never zoomed) */
    private fun ensureLens(size: Int): Boolean {
        if (lensFailed) return false
        try {
            if (lensProg == 0) {
                val vs = "#version 300 es\nuniform vec4 uBox; out vec2 vP;\nvoid main(){ vec2 q = vec2((gl_VertexID & 1) == 1 ? 1.0 : -1.0, (gl_VertexID & 2) == 2 ? 1.0 : -1.0); vP = q; gl_Position = vec4(uBox.xy + q * uBox.zw, 0.0, 1.0); }"
                val fs = "#version 300 es\nprecision highp float; uniform sampler2D uT; uniform float uScale; uniform float uAlpha; in vec2 vP; out vec4 o;\n" +
                    "void main(){ float r = length(vP); float a = 1.0 - smoothstep(0.985, 1.0, r); if (a <= 0.0) discard; vec3 c = texture(uT, (vP * 0.5 + 0.5) * uScale).rgb; c *= 1.0 - 0.38 * smoothstep(0.70, 1.0, r); o = vec4(c, a * uAlpha); }"
                val pr = GL.glCreateProgram()
                GL.glAttachShader(pr, compile(GL.GL_VERTEX_SHADER, vs)); GL.glAttachShader(pr, compile(GL.GL_FRAGMENT_SHADER, fs))
                GL.glLinkProgram(pr)
                val ok = IntArray(1); GL.glGetProgramiv(pr, GL.GL_LINK_STATUS, ok, 0)
                if (ok[0] == 0) { lensFailed = true; return false }
                lensProg = pr
                val va = IntArray(1); GL.glGenVertexArrays(1, va, 0); lensVao = va[0]
            }
            if (lensFbo != 0 && lensSize == size) return true
            releaseLens()
            GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, fbSaved, 0)
            val t = IntArray(1); GL.glGenTextures(1, t, 0); lensTex = t[0]
            GL.glBindTexture(GL.GL_TEXTURE_2D, lensTex)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, size, size, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR); GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE); GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            val rb = IntArray(1); GL.glGenRenderbuffers(1, rb, 0); lensDepth = rb[0]
            GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, lensDepth); GL.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_DEPTH_COMPONENT24, size, size)
            val f = IntArray(1); GL.glGenFramebuffers(1, f, 0); lensFbo = f[0]
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, lensFbo)
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, lensTex, 0)
            GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, lensDepth)
            GL.glBindTexture(GL.GL_TEXTURE_2D, 0)   // never leave the render target bound as a texture (feedback loop)
            val st = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbSaved[0])
            if (st != GL.GL_FRAMEBUFFER_COMPLETE) { releaseLens(); lensFailed = true; return false }
            lensSize = size
            return true
        } catch (e: Throwable) { lensFailed = true; try { GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbSaved[0]) } catch (_: Throwable) { }; return false }
    }
    /** nanoTime of the last AIM-button toggle (HUD ripple / pop) and the direction of it (true = opened) */
    @Volatile var aimToggleAt = 0L
    @Volatile var aimToggleOpen = false

    // ---------------------------------------------------------------- v33: loot pick-up animation + gun-change animation (PickupAnim.kt)
    /** 0..1 progress of the running pick-up animation, -1 = none */
    @Volatile var pickU = -1f
    private var pickItems: List<Loot> = emptyList()
    private var pickDur = 1f
    private var pickCommitted = false
    private val pickOrg = FloatArray(3)
    private val pkLoc = FloatArray(3); private val pkW = FloatArray(3); private val pkW4 = FloatArray(4); private val pkC = FloatArray(4); private val pkG = FloatArray(4)
    private val pkInv = FloatArray(16); private val pkMat = FloatArray(16)
    /** how far the player is bent over (0..1); drives the camera dip, the viewmodel and the body */
    val pickBend: Float get() = if (pickU < 0f || !playerAlive) 0f else PickupAnim.bend(pickU)
    /** remaining seconds of the gun-change animation; swapFrom = gun index that is put away (-1 = fists) */
    @Volatile var swapT = 0f
    private var swapFrom = -1
    private fun swapProg(): Float = if (swapT > 0f) (1f - swapT / PickupAnim.SWAP_DUR).coerceIn(0f, 1f) else -1f
    private fun startSwap(from: Int) { swapFrom = from; swapT = PickupAnim.SWAP_DUR }

    /** player-space point (right, up, forward) -> world, for a body that looks along yw */
    private fun pickWorld(l: FloatArray, o: FloatArray, yw: Float) {
        val fx = sin(yw); val fz = -cos(yw); val rx = cos(yw); val rz = sin(yw)
        o[0] = pos[0] + rx * l[0] + fx * l[2]; o[1] = pos[1] + l[1]; o[2] = pos[2] + rz * l[0] + fz * l[2]
    }

    private var pickAuto = false
    private fun sm01(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    private val hpOut = FloatArray(4)
    /** where the item that is being picked up is drawn right now (world x, y, z) and how big (hpOut[3]): it slides from where it lay to the hand, then rides in the hand to the backpack */
    private fun heldPose(l: Loot, t: Float) {
        val u = pickU.coerceIn(0f, 1f)
        val uh = maxOf(u, PickupAnim.GRAB)
        val hx: Float; val hy: Float; val hz: Float
        PickupAnim.hand(uh, pkLoc); pickWorld(pkLoc, pkW, bodyYaw); hx = pkW[0]; hy = pkW[1]; hz = pkW[2]   // the item travels along the real hand of the 3rd-person body
        val e = sm01((u - PickupAnim.GLIDE0) / (PickupAnim.GRAB - PickupAnim.GLIDE0))
        // the item hovers 0.55 m over the ground while it lies there (drawLoot bob): start from exactly that height so it does not jump
        val sy = pickOrg[1] + 0.55f + sin(t) * 0.08f
        hpOut[0] = pickOrg[0] + (hx - pickOrg[0]) * e
        hpOut[1] = sy + (hy - sy) * e + sin(3.1415927f * e) * 0.10f
        hpOut[2] = pickOrg[2] + (hz - pickOrg[2]) * e
        val small = 0.65f
        hpOut[3] = (1f + (small - 1f) * e) * (1f - 0.55f * sm01((u - PickupAnim.LIFT) / (PickupAnim.COMMIT - PickupAnim.LIFT)))
    }

    /** the pick-up animation is only for a standing player on the ground; everything else (sprinting past ammo, swimming, prone ...) takes the item at once */
    private fun startPickup(items: List<Loot>, auto: Boolean): Boolean {
        val first = items.firstOrNull { it.active } ?: return false
        if (pickU >= 0f) return false
        val why = blockedReason(first); if (why.isNotEmpty()) { if (!auto) toast(why); return false }
        if (first.type == LootType.WEAPON && owned[guns[first.sub].slot] == first.sub) { if (!auto) toast("ALREADY CARRYING " + guns[first.sub].name); return false }
        val quick = !playerAlive || airState != 3 || swimming || diving || underwater || healLeft > 0f || crouching || prone || !onGround || (auto && run)
        if (quick) { collectGroup(items); return true }
        pickItems = ArrayList(items); pickU = 0f; pickCommitted = false; pickAuto = auto
        pickDur = if (auto) 0.7f else if (first.type == LootType.WEAPON) 1.2f else 1.0f
        pickOrg[0] = first.x; pickOrg[1] = first.y; pickOrg[2] = first.z
        first.held = true
        aiming = false; reloadLeft = 0f; reloadProgress = -1f; run = false; firing = false
        return true
    }

    private fun cancelPickup() {
        for (l in pickItems) l.held = false      // the item was never moved: it simply shows on the ground again
        pickU = -1f; pickItems = emptyList(); pickCommitted = false
    }

    private fun stepPickup(dt: Float) {
        if (swapT > 0f) swapT = maxOf(0f, swapT - dt)
        if (pickU < 0f) return
        val first = pickItems.firstOrNull { it.active }
        if (!playerAlive || airState != 3 || swimming || diving || underwater || healLeft > 0f || firing || run || vy > 1.5f || crouching || prone || (first == null && !pickCommitted)) { cancelPickup(); return }
        pickU += dt / pickDur
        if (!pickCommitted) {
            if (first != null) first.held = true
            if (pickU >= PickupAnim.COMMIT) {   // the hand is at the backpack: the item goes into the bag
                pickCommitted = true
                for (l in pickItems) l.held = false
                collectGroup(pickItems)
            }
        }
        if (pickU >= 1f) { pickU = -1f; pickItems = emptyList(); pickCommitted = false }
    }
    @Volatile var scopeLabel = "AIM"
    private val scopesOwned = java.util.TreeSet<Int>()
    /** Phase 64: the SCOPE chip of the HUD exists only once at least one scope has been looted */
    val hasScopes: Boolean get() = scopesOwned.isNotEmpty()
    private var scopeSel = 0
    private var surfW = 1; private var surfH = 1
    /** ONE button, two states: first tap opens the scope / sights, the second tap on the SAME button closes it again */
    fun toggleAim() {
        if (!aiming) { if (reloadLeft > 0f || healLeft > 0f || swapT > 0f || pickU >= 0f) return; run = false }
        aiming = !aiming
        aimToggleOpen = aiming; aimToggleAt = System.nanoTime()
    }
    fun cycleScope() {
        val list = listOf(0) + scopesOwned.toList()
        if (list.size < 2) { toast("NO SCOPES YET - LOOT A 1X-8X SCOPE"); return }
        scopeSel = list[(list.indexOf(scopeSel) + 1) % list.size]
        scopeLabel = if (scopeSel > 0) scopeSel.toString() + "X" else "AIM"
        toast(if (scopeSel > 0) "SCOPE / " + scopeSel + "X" else "IRON SIGHTS")
    }
    // aim FOV / scope FOV / sensitivity scale
    private fun aimFov(z: Int) = if (z >= 3) 25f else if (z == 2) 34f else if (z == 1) 44f else 48f
    private fun scopeFov(z: Int) = if (z >= 8) 8f else if (z >= 4) 16f else if (z >= 2) 34f else 54f
    private fun sensScale(z: Int) = if (z >= 8) 0.12f else if (z >= 4) 0.24f else if (z == 3) 0.37f else if (z == 2) 0.5f else if (z == 1) 0.8f else 1f
    private fun isScoped(g: Gun) = g.cls == "dmr" || g.cls == "sniper" || g.cls == "amr"
    fun quitResult(): MatchResult? = if (matchState != 0 || warm) null else Rewards.compute(kills, botsAlive + 1, false, matchTime, true)
    @Volatile var ammoReserve = 0
    @Volatile var shield = 0f
    @Volatile var vestLvl = 0
    @Volatile var medkits = 0
    /** v31 BLEEDING: bandages in the bag, are you bleeding, HP lost per second while you bleed */
    @Volatile var bandages = 0
    @Volatile var bleeding = false
    @Volatile var bleedRate = 0f
    private var healDur = 2.2f
    private var healIsBandage = false
    private var bleedDropT = 0f
    /** the HEAL slot of the HUD shows a bandage (true) or a medkit (false) */
    val healShowsBandage: Boolean get() = if (healLeft > 0f) healIsBandage else (bandages > 0 && (bleeding || medkits < 1))
    @Volatile var healProgress = -1f
    @Volatile var promptLabel = ""
    @Volatile var toastText = ""
    @Volatile var toastTime = 0L
    @Volatile private var pickupReq = false
    @Volatile private var healReq = false
    fun pickup() { pickupReq = true }
    /** Phase 55 LOOT LIST: one row per kind of item within 3 m (identical items are stacked into ONE row with a count). The HUD only reads it. */
    class LootRow(val key: Int, val type: Int, val sub: Int, val label: String, val count: Int, val blocked: Boolean)
    @Volatile var lootRows: List<LootRow> = emptyList()
    @Volatile private var pickKey = -1
    /** tap on a row of the loot list: takes that item (a stack of the same supply is taken all at once, as far as the bag has room) */
    fun pickupKey(key: Int) { pickKey = key }
    fun heal() { healReq = true }
    @Volatile private var cancelHealReq = false
    /** CANCEL button: stop a medkit heal that is in progress (nothing is used up) */
    fun cancelHeal() { cancelHealReq = true }
    private fun toast(t: String) { toastText = t; toastTime = System.nanoTime() }

    private class GlMesh(val vao: Int, val count: Int)
    private class Layer(val mesh: GlMesh, val mat: String)
    private val layers = ArrayList<Layer>()
    /** v54: GPU meshes of the building templates (MapData.tpls) + a scratch matrix for placing each instance */
    private val tplMeshes = ArrayList<GlMesh>()
    private val bm = FloatArray(16)
    private val gunGl = HashMap<String, Pair<GlMesh, GunModel>>()
    // real aircraft model (PlaneModel.kt) - replaces the old box plane
    private class PlanePartGl(val mesh: GlMesh, val prop: Boolean, val px: Float, val py: Float, val pz: Float)
    private val planeGl = ArrayList<PlanePartGl>()
    private var planeReady = false
    // real 3D parachute (ParachuteModel.kt, from parachute_simple.glb) - replaces the old sphere canopy + box line everywhere
    private var chuteMesh: GlMesh? = null
    private var chuteTex = 0

    private var seaMesh: GlMesh? = null
    private var cube: GlMesh? = null
    private var wall: GlMesh? = null
    private var sphere: GlMesh? = null
    private var prog = 0
    // real-time sun shadows (ShadowMap.kt)
    private val shadow = ShadowMap()
    private var shadowDraw = false
    private val shm = FloatArray(16)
    // swimming effects (DSW of the HTML): ripple rings + splash drops
    private class Rip(val x: Float, val y: Float, val z: Float, val big: Float) { var life = 1f }
    private class Drop2(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val wy: Float) { var life = 1f }
    private val rips = ArrayList<Rip>()
    private val splashDrops = ArrayList<Drop2>()
    private var swimIn = false
    private var swimAcc = 0f
    /** true while the player floats in deep water (HUD can show it) */
    @Volatile var swimming = false
    /** DIVE (button only in deep water): head under water, HP drains faster the deeper you go. diveDepth = m under the surface, underwater = camera below it */
    @Volatile var diving = false
    @Volatile var diveDepth = 0f
    @Volatile var underwater = false
    private var diveAcc = 0f
    private var diveSink = 0f
    private var swimAmt = 0f; private var swimPh = 0f   // swimming pose blend 0..1 + front-crawl stroke phase
    fun toggleWaterDive() { if (!playerAlive) return; if (diving) diving = false else if (swimming) { diving = true; diveSink = 0.9f; sfx?.play("splash") } }
    private fun drownHit() {
        if (!playerAlive || warm) return
        if (healLeft > 0f) { healLeft = 0f; healProgress = -1f }
        playerHp -= 1f; damageTime = System.nanoTime(); vibMs = 20
        if (playerHp <= 0f) { playerHp = 0f; playerAlive = false; deadAt = clock; killedBy = "DROWNING"; addFeed("DROWNING eliminated YOU"); endMatch(false); beginSpectate("DROWNING") }
    }
    private val tex = HashMap<String, Int>()
    private val vp = FloatArray(16); private val proj = FloatArray(16); private val view = FloatArray(16)
    private val inv = FloatArray(16); private val mm = FloatArray(16); private val ident = FloatArray(16)
    private val nm = FloatArray(9); private val nmIdent = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var uMode = 0; private var uColor = 0; private var uModel = 0; private var uNM = 0
    // trees + weather (TreeSystem.kt): sunny -> rain -> storm -> sunny, wind sways the trees
    private val weather = WeatherSystem()
    private var trees: TreeSystem? = null
    /** v57: tree positions (x, z pairs) for the satellite map; empty until the trees are placed. */
    fun treeXZ(): FloatArray = trees?.positionsXZ() ?: FloatArray(0)
    private val rainFx = RainFx()
    private var rainOk = false
    private var uTreeOn = 0; private var uTimeL = 0; private var uWindL = 0; private var uSkyC = 0; private var uSkyAmt = 0
    private var uWetL = 0; private var uSunDimL = 0; private var uHemiDimL = 0; private var uFlashL = 0
    private var uPudL = 0; private var uRainL = 0   // v57: puddle level + rain strength for the ground shader
    // rigged 3D characters (Survivor) - GPU skinned, see CharacterModel.kt
    private val chars = CharRenderer(ctx)
    private val cpose = CharPose()
    private val paPlayer = PistolState()   // pistol-pack animation state of the player's own body (third person)
    private val paCorpse = PistolState()   // ... and of the player's corpse (death pose)
    private val tmpM = FloatArray(16)
    private val jumpAnim = JumpAnimState()   // JUMP animation of the player's body (third person), see JumpAnim.kt
    private val cbase = FloatArray(16)
    private var camX = 0f; private var camY = 0f; private var camZ = 0f
    private var footSpeed = 0f
    private var sprintNow = false   // Phase 28: sprinting on the ground at > 1.5 m/s -> the gun refuses to fire (HTML playerShoot() guard)
    /** character the player picked in the lobby (Characters.ALL id): bots use it too */
    @Volatile var playerChar = "survivor"
    private val botChars = arrayOf("survivor")

    private val pos = floatArrayOf(map.spawn[0], map.spawn[1], map.spawn[2])
    private var yaw = 0f; private var pitch = 0f; private var vy = 0f; private var onGround = true
    /** mobile BR style: `yaw` is only the CAMERA direction. `bodyYaw` is where the character itself faces (turns toward the stick direction, or toward the crosshair while shooting / aiming). */
    private var bodyYaw = 0f; private var faceHold = 0f
    private var last = 0L; private var frames = 0; private var fpsT = 0L
    private var walkPhase = 0f
    private val WALK_SPEED = 3.6f   // v54: was 4.5 - at 4.5 m/s the walk clip's feet slid (2.6x); with the faster cadence of Gait.cadence() 3.6 keeps the feet nearly planted. v49: joystick walk speed (m/s). Was 6.2 (HTML value) = a jog; 4.5 keeps the walk clip's feet from skating. Change here if you want it faster.
    private var runBlend = 0f   // v49: 0 = WALK clip, 1 = RUN clip. Follows the RUN button only (sprintNow); the joystick alone is always the walk clip

    // ---- weapons
    private val guns = Weapons.ALL
    private var gi = 0
    private var fists = false; private var punchT = 0f; private var punchHand = 1f   // FISTS slot of the HTML (weapons[4], melee)
    // BOXING: the punches come as a combo jab -> cross -> hook -> uppercut (BoxAnim); the hit lands when the fist reaches the target (BoxAnim.PEAK), not at the button press
    private var punchKind = 0; private var punchDur = 0.3f; private var comboKind = 0; private var comboClock = -10f; private var punchPending = false
    private val fm = FloatArray(16)
    private val ammo = IntArray(guns.size) { guns[it].mag }
    private val owned = intArrayOf(0, -1, -1, -1) // gun index per slot (0 long, 1 short, 2 sniper); start with MP5
    private val pool = intArrayOf(90, 16, 20)  // reserve ammo: smg, sniper, sg  (index = AmmoInfo type)
    private var healLeft = 0f
    private val loot = ArrayList<Loot>()
    private var nearLoot: Loot? = null
    private val nearTmp = ArrayList<Loot>()
    private var lootGroups: List<ArrayList<Loot>> = emptyList()
    private var cooldown = 0f; private var reloadLeft = 0f; private var kick = 0f; private var flash = 0f

    // ---------------------------------------------------------------- v44 COMBAT REALISM (numbers in Combat.kt)
    /** 13 LEAN / PEEK: -1 = left, 0 = none, 1 = right. Set by the two HUD buttons while they are held. */
    @Volatile var leanReq = 0
    private var leanAmt = 0f                      // smoothed -1..1
    private var leanOffX = 0f; private var leanOffZ = 0f   // head / gun offset in the world (m), already cut short by a wall
    private var leanRatio = 1f                    // 1 = full lean, less when a wall stops the head
    /** 14 RECOIL PATTERN: number of the shot inside the current spray, and when the last shot was */
    private var sprayN = 0; private var lastShotAt = -99f
    /** 15 SUPPRESSION 0..1: grows when bullets pass close, fades by itself. HUD reads it (dark edges), the camera trembles, the aim gets worse. */
    @Volatile var suppress = 0f
    private var whizCd = 0f
    /** 16 WEIGHT: carried kg, speed factor */
    @Volatile var loadKg = 0f
    private var loadK = 1f; private var loadWarned = false
    /** 16 DURABILITY / JAM: wear 0..100 of every gun, is it jammed, seconds left of clearing the jam */
    private val dur = FloatArray(Weapons.ALL.size) { GunCondition.MAX }
    private val jammed = BooleanArray(Weapons.ALL.size)
    private val wornWarned = BooleanArray(Weapons.ALL.size)
    private var clearLeft = 0f
    @Volatile var gunJammed = false
    @Volatile var gunDur = 100
    private var triggerHeld = false

    // ---- bots + bullet marks
    private class Mark(val x: Float, val y: Float, val z: Float, var life: Float, val mat: Int = 0, val nx: Float = 0f, val ny: Float = 1f, val nz: Float = 0f)   // v36: mat = Impact.CONCRETE / WOOD / DIRT (water leaves no mark)
    private val marks = ArrayList<Mark>()
    private var clock = 0f
    private val rnd = Random(System.nanoTime())
    private val frnd = Random(System.nanoTime() xor 0x5DEECE66DL)  // flight route + bot landing spots (new every round)
    private val botSys = BotSystem(map, rnd, 35)
    private var deadAt = 0f

    // ---- air drop state (declared before init{} so initFlight() values are not reset)
    @Volatile var airState = 0            // 0 plane, 1 freefall, 2 parachute, 3 ground
    @Volatile var airText = ""
    @Volatile var chuteReady = false
    private var flightTime = 0f
    private var autoJumpAt = 36f
    /** flight time (s) after which the aircraft is gone (it keeps flying 20 s after the auto-jump) */
    private var flightEnd = 52f
    private val LAT_MAX = 90f
    private var fdx = 0f; private var fdz = -1f; private var fsx = 0f; private var fsz = 0f; private var flightYaw = 0f
    private val FSPEED = 35.5f * 4f / 3f      // 47.3 m/s, HTML FLIGHT.speed
    private val AIR_Y = 520f
    private var pvx = 0f; private var pvz = 0f
    private var paraT = 0f; private var chuteT = 0f
    private var fogK = 8f; private var planeShown = true

    // ---- PRE-MATCH warm-up (flow 'preMatch' of the HTML): 30 s on the ground, no damage, then a fresh round starts in the plane
    @Volatile var warm = false
    @Volatile var warmLeft = 30f
    @Volatile var warmEnded = false
    private val spawn0 = pos.copyOf()

    // ---- Phase 28 state: skate-stance freefall, loot crates, dropped magazines, breathing sway, contact shadows
    @Volatile var skateMode = false          // freefall: false = head-first dive (default), true = skate stance (HTML toggleDive / V key)
    private val playerAv = AirVis()
    private val crates = ArrayList<LootCrate>()
    private val mags = ArrayList<MagDrop>()
    private val swayTmp = FloatArray(3)
    private var magDropped = false
    private var blobMesh: GlMesh? = null
    private var ringMesh: GlMesh? = null
    private var wheelMesh: GlMesh? = null

    /** SKATE / DIVE button (HTML toggleDive, V key): switches the freefall stance. Only works in freefall. */
    fun toggleDive() {
        if (airState != 1) return
        skateMode = !skateMode
        sfx?.play("jump")
        toast(if (skateMode) "SKATE STANCE - STICK = FORWARD / BACK / SIDEWAYS" else "DIVE - LOOK DOWN = FASTER")
    }

    fun startWarmup() {
        warm = true; warmLeft = 30f; warmEnded = false
        planeShown = false; airState = 3; airText = ""; onGround = true
        pos[0] = spawn0[0]; pos[1] = spawn0[1]; pos[2] = spawn0[2]; vy = 0f; yaw = 0f; bodyYaw = 0f; faceHold = 0f; pitch = -0.12f
        for (b in botSys.bots) {
            if (!b.alive) continue
            var q: FloatArray? = null
            for (k in 0 until 40) { val t = map.randomLand(frnd, pos[0], pos[2], 8f, 70f); if (t != null) { q = t; break } }
            if (q != null) { b.x = q[0]; b.y = q[1]; b.z = q[2] }
            b.air = 3; b.dive = false; b.vx = 0f; b.vy = 0f; b.vz = 0f
        }
        botSys.noDamage = true
        toast("WARM-UP: no damage. Move, aim and shoot freely for 30 seconds.")
    }

    init {
        Matrix.setIdentityM(ident, 0)
        botSys.spawnAll(pos[0], pos[2], 0f)
        // v29: every bot shot sounds like ITS gun, comes through distance / walls / roof like in real life, and the other bots hear it too
        botSys.onShot = { b ->
            val rg = Realism.gunRadius(b.gun)
            hearAt(Realism.shotSound(b.gun), b.x, b.y, b.z, rg * 1.6f)
            botSys.hear(b.x, b.y, b.z, rg * 0.8f, b)
        }
        botSys.onWhiz = { d, cx, cy, cz, spd -> whiz(d, cx, cy, cz, spd) }   // v44: near misses crack past your ear
        botSys.onImpact = { x, y, z, mat, nx, ny, nz -> impactAt(x, y, z, mat, nx, ny, nz) }   // v36: bot bullets also leave material marks + dust / splinters / splash
        botSys.onBotLand = { b, h, dmg ->
            if (h > 0.7f) { hearAt("land", b.x, b.y, b.z, 22f + h * 6f, 0.8f + h * 0.08f); botSys.hear(b.x, b.y, b.z, 10f + h * 6f, b) }
            if (dmg > 0f) hearAt("hurt", b.x, b.y, b.z, 30f, 0.8f)
        }
        botSys.onBotKill = { killer, victim, weapon -> addFeed(killer.name + " eliminated " + victim.name + "  [" + weapon + "]"); dropFromBot(victim) }
        spawnLoot()
        publishGun()
        initFlight()
    }

    // ---- air drop (plane -> freefall -> parachute -> ground), ported from eject()/deployChute()/descend() of the HTML

    private fun planeX(t: Float) = fsx + fdx * FSPEED * t
    private fun planeZ(t: Float) = fsz + fdz * FSPEED * t

    /** New random route every round: flies over the island in any direction, offset sideways like FLIGHT in the HTML. */
    private fun initFlight() {
        val a = frnd.nextFloat() * 6.2831855f
        fdx = sin(a); fdz = -cos(a); flightYaw = a
        val half = MapData.ISLAND_R + 40f; val lat = (frnd.nextFloat() * 2f - 1f) * LAT_MAX   // small sideways offset: the route ALWAYS passes (almost) through the middle, so the plane crosses the WHOLE island (never just a corner)
        fsx = -fdx * half - fdz * lat; fsz = -fdz * half + fdx * lat
        // time window in which the plane is over the Bijnor map (circle r = ISLAND_R + 40 m: the land reaches ISLAND_R from the origin)
        val v = FSPEED; val t0 = -(fsx * fdx + fsz * fdz) / v
        val perp = kotlin.math.abs(fsx * fdz - fsz * fdx)
        val R = MapData.ISLAND_R + 40f; val hc = if (perp < R) sqrt(R * R - perp * perp) / v else 8f
        val tStart = max(PLANE_FLY_IN + 0.5f, t0 - hc); val tEnd = max(tStart + 6f, t0 + hc)
        autoJumpAt = max(36f, tEnd + 1f)      // the auto-jump only comes after the plane has flown over the whole map
        flightEnd = autoJumpAt + 20f
        val n = botSys.bots.size
        for ((i, b) in botSys.bots.withIndex()) {
            if (!b.alive) continue
            b.air = 0; b.dive = true; b.vx = 0f; b.vy = 0f; b.vz = 0f; b.av.reset()
            b.jumpAt = tStart + (tEnd - tStart) * i / max(1, n - 1)
            val cx0 = planeX(b.jumpAt); val cz0 = planeZ(b.jumpAt)
            var tgt: FloatArray? = null
            for (k in 0 until 25) {
                val across = (frnd.nextFloat() * 2f - 1f) * 300f; val along = (frnd.nextFloat() * 2f - 1f) * 170f
                tgt = map.randomLand(frnd, cx0 - fdz * across + fdx * along, cz0 + fdx * across + fdz * along, 0f, 100f)
                if (tgt != null && hypot(tgt[0], tgt[2]) < MapData.ISLAND_R) break else tgt = null
            }
            if (tgt == null) tgt = map.randomLand(frnd, cx0, cz0, 0f, 350f)
            if (tgt == null) tgt = floatArrayOf(b.x, b.y, b.z)
            b.tx = tgt[0]; b.tz = tgt[2]
            b.x = cx0; b.y = AIR_Y - 1.7f; b.z = cz0
        }
        airState = 0; flightTime = 0f; planeShown = true; skateMode = false; playerAv.reset()
        pos[0] = planeX(0f); pos[1] = AIR_Y - 1.7f; pos[2] = planeZ(0f); onGround = false
        yaw = flightYaw; pitch = -0.15f
        toast("TAP DROP TO JUMP - AUTO-JUMP AT ${autoJumpAt.toInt()} S")
    }

    private fun eject() {
        if (airState != 0) return
        airState = 1; paraT = 0f; chuteReady = false; skateMode = false; playerAv.reset()
        pos[0] = planeX(flightTime); pos[1] = AIR_Y - 2.2f; pos[2] = planeZ(flightTime) + 3f
        vy = -3f; pvx = fdx * FSPEED; pvz = fdz * FSPEED
        pitch = -0.9f      // head-first dive, look up/down to change the angle
        sfx?.play("jump")
        toast("DIVE - LOOK DOWN = FASTER, STICK = SPEED, SKATE BUTTON = SKATE STANCE, CHUTE NEAR THE GROUND")
    }

    private fun deployChute() {
        if (airState != 1) return
        airState = 2; chuteT = 0f; sfx?.play("chute"); toast("PARACHUTE OPEN - STEER TO YOUR LANDING SPOT")
    }

    private fun landPlayer() {
        val p = floatArrayOf(pos[0], pos[1], pos[2])
        if (map.terrainBlocked(p[0], p[2], 0.5f, 0f, true) || p[0] * p[0] + p[2] * p[2] > MapData.LAND_LIMIT * MapData.LAND_LIMIT) {   // water near the coast is fine (you swim); cliffs and the open sea far from the land are moved to land
            val q = map.randomLand(frnd, p[0], p[2], 0f, 60f) ?: map.randomLand(frnd, p[0], p[2], 0f, 300f) ?: map.randomLand(frnd, p[0], p[2], 0f, 1500f)
            if (q != null) { p[0] = q[0]; p[2] = q[2] }
        }
        val ly = pos[1]
        p[1] = map.footY(p[0], p[2], ly); map.resolve(p, 0.42f)
        pos[0] = p[0]; pos[2] = p[2]; pos[1] = max(map.footY(p[0], p[2], ly), map.swimFloor(p[0], p[2], clock))
        vy = 0f; pvx = 0f; pvz = 0f; onGround = true; airState = 3; chuteReady = false; airText = ""; skateMode = false
        sfx?.play("land"); limpT = 0f; fallTopY = pos[1]
        matchTime = 0f; cooldown = 0.3f; pitch = -0.05f
        paPlayer.reset()   // fresh pistol-pack animation state after landing
        if (pos[1] > map.ground(pos[0], pos[2]) + 2.5f && map.houses.isNotEmpty()) toast("ROOF LANDING - WEAPONS READY, TAKE THE ROOF STAIR DOWN INTO THE HOUSE")
        else toast("LANDED - WEAPONS READY, SEARCH THE OPEN GROUND FOR LOOT")
    }

    private fun stepAirPlayer(dt: Float) {
        val l = takeLook(); yaw += l[0] * 0.0045f * Prefs.lookK(); pitch = (pitch - l[1] * 0.0045f * Prefs.lookK() * Prefs.vertK()).coerceIn(-1.45f, 1.45f)
        firing = false; aiming = false; reloadReq = false; healReq = false; nadeReq = false; pickupReq = false
        val jump = jumpReq; jumpReq = false
        val kk = { v: Float -> 1f - exp(-dt * v) }
        if (airState == 0) {
            pos[0] = planeX(flightTime); pos[1] = AIR_Y - 1.7f; pos[2] = planeZ(flightTime)
            if (jump) { if (flightTime >= PLANE_FLY_IN) eject() else toast("WAIT UNTIL THE AIRCRAFT REACHES THE ISLAND") }
            if (airState == 0 && flightTime >= autoJumpAt) eject()
            val aboard = 1 + botSys.bots.count { it.alive && it.air == 0 }
            airText = "AIRCRAFT  ·  ALT ${AIR_Y.toInt()} m  ·  $aboard ABOARD  ·  AUTO-JUMP ${max(0, ceil(autoJumpAt - flightTime).toInt())}s"
            return
        }
        val gnd = map.ground(pos[0], pos[2]).coerceAtLeast(0f)
        val agl = pos[1] - max(gnd, map.landSurface(pos[0], pos[2], pos[1], -1e9f))   // v24: height above the ground OR the roof / deck under you (the flare starts above the roof)
        if (airState == 1) {
            chuteReady = agl <= 300f
            if (jump) { if (chuteReady) deployChute() else toast("PARACHUTE OPTION APPEARS NEAR THE GROUND") }
            if (airState == 1) { if (chuteReady) { paraT += dt; if (paraT >= 5f || agl < 90f) deployChute() } else paraT = 0f }
        }
        if (airState == 1 && skateMode) { // skate-stance freefall (skate branch of descend() in the HTML): slow fall, long glide, stick = forward / back / sideways
            val f = (-moveY).coerceIn(-1f, 1f); val r = moveX.coerceIn(-1f, 1f)
            val hx = sin(yaw); val hz = -cos(yaw)
            val sp = if (f >= 0f) 10f + 20f * f else 10f * (1f + f)
            val vT = if (f >= 0f) -(9f + 4f * f) else -(9f + 5.5f * f)
            val k = kk(2.4f)
            pvx += (hx * sp - hz * 12f * r - pvx) * k; pvz += (hz * sp + hx * 12f * r - pvz) * k
            vy += (vT - vy) * kk(2.2f)
        } else if (airState == 1) { // head-first dive freefall
            val pd = (-pitch).coerceIn(0f, 1.35f); val s = sin(pd); val c = cos(pd)
            val f = (-moveY).coerceIn(-1f, 1f)
            var hT = 3f + 32f * c.pow(1.6f); hT *= if (f > 0f) 1f + 0.15f * f else 1f + 0.4f * f
            val vT = -(9f + 56f * s.pow(1.1f))
            pvx += (sin(yaw) * hT - pvx) * kk(1.6f); pvz += (-cos(yaw) * hT - pvz) * kk(1.6f)
            vy += (vT - vy) * kk(1.4f)
        } else { // parachute: descend() with fl = flare near the ground
            chuteT += dt
            val fl = max(0f, 1f - agl / 16f)
            vy = min(-5.5f + 3.6f * fl, vy + dt * 32f)
            val fx = sin(yaw); val fz = -cos(yaw); val rx = cos(yaw); val rz = sin(yaw)
            var mx = fx * -moveY + rx * moveX; var mz = fz * -moveY + rz * moveX
            val len = hypot(mx, mz); if (len > 1f) { mx /= len; mz /= len }
            val sp = 17f * (1f - 0.55f * fl)
            pvx += (mx * sp - pvx) * kk(3f); pvz += (mz * sp - pvz) * kk(3f)
        }
        pos[0] += pvx * dt; pos[2] += pvz * dt
        map.resolve(pos, 0.42f)   // v24: drifting into a wall / parapet / rail slides along it instead of passing through
        val g2 = map.ground(pos[0], pos[2]).coerceAtLeast(0f)
        // v24: ROOF LANDING. Before, only the terrain stopped the fall, so the body dropped through the roof and touched down inside the house.
        // Now the first roof / balcony / porch-roof surface it crosses stops it, and it stands ON the roof (the roof stair leads down into the house).
        val ny = pos[1] + vy * dt
        val roofY = map.landSurface(pos[0], pos[2], pos[1], ny)
        if (roofY > -1e8f || ny <= g2) {
            if (roofY > g2) pos[1] = map.freeLandingSpot(pos, roofY, 0.42f) else pos[1] = g2
            landPlayer(); return
        }
        pos[1] += vy * dt
        val kmh = sqrt(pvx * pvx + vy * vy + pvz * pvz) * 3.6f
        val alt = max(0f, pos[1] - g2)
        playerAv.step(dt, airState == 1 && skateMode, airState == 1 && !skateMode, airState == 2,
            if (airState == 2) max(0f, 1f - alt / 16f) else 0f, true, pvx, vy, pvz, clock)
        airText = (if (airState == 2) "PARACHUTE" else "FREEFALL") + "  ·  ALT ${alt.toInt()} m  ·  ${kmh.toInt()} km/h" +
            (if (airState == 1 && chuteReady) "  ·  CHUTE IN ${max(0, ceil(5f - paraT).toInt())}s" else "")
    }

    private fun stepBotsAir(dt: Float) {
        val kk = { v: Float -> 1f - exp(-dt * v) }
        for (b in botSys.bots) {
            if (!b.alive || b.air == 3) continue
            if (b.air == 0) {
                b.x = planeX(flightTime); b.y = AIR_Y - 1.7f; b.z = planeZ(flightTime)
                if (flightTime >= b.jumpAt) { b.air = 1; b.y = AIR_Y - 2.2f; b.vy = -3f; b.vx = fdx * FSPEED; b.vz = fdz * FSPEED; b.dive = true }
                continue
            }
            val dx = b.tx - b.x; val dz = b.tz - b.z; val dist = hypot(dx, dz); val dv = max(8f, dist)
            val ux = dx / dv; val uz = dz / dv
            val gnd = map.ground(b.x, b.z).coerceAtLeast(0f); val agl = b.y - gnd
            if (b.air == 1) {
                b.dive = dist > 90f
                if (agl < 48f + (if (b.dive) 42f else 0f)) { b.air = 2; b.chuteT = 0f }
            }
            if (b.air == 1) {
                if (b.dive) { b.vy = max(-44f, b.vy - dt * 30f); b.vx += (ux * 34f - b.vx) * kk(2f); b.vz += (uz * 34f - b.vz) * kk(2f) }
                else { b.vy += (-13f - b.vy) * kk(2.2f); b.vx += (ux * 30f - b.vx) * kk(2.4f); b.vz += (uz * 30f - b.vz) * kk(2.4f) }
            } else {
                b.chuteT += dt
                val fl = max(0f, 1f - agl / 16f)
                b.vy = min(-5.5f + 3.6f * fl, b.vy + dt * 32f)
                val sp = 17f * (1f - 0.55f * fl)
                b.vx += (ux * sp - b.vx) * kk(3f); b.vz += (uz * sp - b.vz) * kk(3f)
            }
            b.x += b.vx * dt; b.z += b.vz * dt
            val g2 = map.ground(b.x, b.z).coerceAtLeast(0f)
            val roofHit = map.landSurface(b.x, b.z, b.y, b.y + b.vy * dt)   // v24: a bot coming down on a roof touches down beside the house (it cannot use the roof stair), not inside it
            if (roofHit > -1e8f || b.y + b.vy * dt <= g2) {
                b.y = g2
                val esc = map.houseEscape(b.x, b.z)
                if (esc != null) { b.x = esc[0]; b.z = esc[1]; b.y = map.ground(b.x, b.z).coerceAtLeast(0f) }
                if (map.terrainBlocked(b.x, b.z, 0.45f) || map.boxBlocked(b.x, b.z, 0.45f, b.y)) {
                    val q = map.randomLand(frnd, b.x, b.z, 0f, 60f) ?: map.randomLand(frnd, b.tx, b.tz, 0f, 60f)
                    if (q != null) { b.x = q[0]; b.y = q[1]; b.z = q[2] }
                }
                b.air = 3; b.vx = 0f; b.vy = 0f; b.vz = 0f; b.speed = 0f; b.think = 0f; b.cooldown = 1.5f + frnd.nextFloat(); b.av.reset()
            } else {
                b.y += b.vy * dt
                if (kotlin.math.abs(b.vx) + kotlin.math.abs(b.vz) > 0.5f) b.yaw = atan2(-b.vx, -b.vz)
                b.av.step(dt, b.air == 1 && !b.dive, b.air == 1 && b.dive, b.air == 2, if (b.air == 2) max(0f, 1f - (b.y - g2) / 16f) else 0f, false, b.vx, b.vy, b.vz, clock)
            }
        }
    }

    /** v44 WEIGHT: everything in the bag in kg (guns + magazines, reserve ammo, supplies, armour, scopes). */
    private fun computeLoad(): Float {
        var kg = 0f
        for (s in owned.indices) { val k = owned[s]; if (k >= 0) { val gg = guns[k]; kg += Burden.gunKg(gg) + ammo[k] * Burden.ammoKg(AmmoInfo.typeOf(gg)) } }
        for (t in 0 until 3) kg += pool[t] * Burden.ammoKg(t)
        kg += medkits * Burden.MEDKIT + bandages * Burden.BANDAGE + mushrooms * Burden.SHROOM + grenadeCount * Burden.GRENADE + scopesOwned.size * Burden.SCOPE
        kg += (if (shield > 0f) Burden.vestKg(vestLvl) else 0f) + (if (helmHp > 0f) Burden.helmKg(helmLvl) else 0f)
        return kg
    }

    /** v44 LEAN: how far the head really moves this frame; a wall beside you stops it (the gap stays free). */
    private fun updateLeanOffset() {
        val a = leanAmt
        if (abs(a) < 0.004f) { leanOffX = 0f; leanOffZ = 0f; leanRatio = 1f; return }
        val sg = if (a > 0f) 1f else -1f
        val rx = cos(yaw) * sg; val rz = sin(yaw) * sg
        val want = Lean.DIST * abs(a)
        val eyeY = pos[1] + MapData.EYE_H - eyeDrop
        val reach = Lean.DIST + Lean.WALL_GAP
        val t = map.rayWorld(pos[0], eyeY, pos[2], rx, 0f, rz, reach + 0.1f)
        val got = if (t >= 0f && t <= reach) min(want, max(0f, t - Lean.WALL_GAP)) else want
        leanOffX = rx * got; leanOffZ = rz * got
        leanRatio = if (want > 1e-4f) got / want else 1f
    }

    /** v44 BULLET WHIZ: a bot bullet missed you by d metres (closest point cx, cy, cz) at speed spd. Crack from the side it passed, screen tremble, worse aim. */
    private fun whiz(d: Float, cx: Float, cy: Float, cz: Float, spd: Float) {
        if (!playerAlive || warm || airState != 3) return
        val k = Suppression.amount(d, spd)
        suppress = min(1f, suppress + 0.30f * k + 0.08f)
        if (whizCd > 0f) return
        whizCd = 0.06f
        val dx = cx - pos[0]; val dz = cz - pos[2]; val h = hypot(dx, dz)
        val pan = if (h < 0.3f) 0f else ((dx * cos(yaw) + dz * sin(yaw)) / h).coerceIn(-0.9f, 0.9f)
        sfx?.play(if (spd >= Suppression.SOUND) "whiz" else "whizS", (0.35f + 0.65f * k).coerceAtMost(1f), pan)
        vibMs = max(vibMs, 18)
    }

    /** Backpack panel (ffBag of the HTML): weapons per slot, reserve ammo, medkits, mushrooms, armor, grenades. */
    fun backpackLines(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val slotName = arrayOf("LONG GUN", "SHORT GUN", "SNIPER", "PISTOL")
        for (s in owned.indices) { val g = owned[s]; out.add(slotName[s] to (if (g >= 0) guns[g].name + "  " + ammo[g] + "/" + guns[g].mag + "  " + dur[g].toInt() + "%" else "EMPTY")) }
        out.add("FISTS" to "ALWAYS")
        for (t in 0 until 3) out.add(("Reserve ammo " + AmmoInfo.NAMES[t]) to pool[t].toString())
        out.add("Medkits" to medkits.toString()); out.add("Bandages" to bandages.toString()); out.add("Mushrooms" to mushrooms.toString())
        out.add("Armor" to (shield.toInt().toString() + (if (vestLvl > 0) "  (Lv." + vestLvl + ")" else "")))
        out.add("Grenades" to grenadeCount.toString())
        val kgNow = computeLoad()
        out.add("Weight" to (String.format(java.util.Locale.US, "%.1f kg", kgNow) + (if (Burden.heavy(kgNow)) "  SLOW " + Math.round((1f - Burden.speedK(kgNow)) * 100f) + "%" else "")))
        return out
    }

    private fun publishGun() { if (fists) { gunName = "FISTS"; ammoMax = 0; ammoNow = 0; ammoReserve = 0; gunJammed = false; gunDur = 100; return }; val g = guns[gi]; gunName = g.name; ammoMax = g.mag; ammoNow = ammo[gi]; ammoReserve = pool[AmmoInfo.typeOf(g)]; gunJammed = jammed[gi]; gunDur = dur[gi].toInt() }

    private val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor; layout(location=2) in vec2 aUV; layout(location=3) in vec3 aCol;
layout(location=4) in float aFlex; layout(location=5) in vec4 iPos; layout(location=6) in vec4 iPar;   // trees only: x,y,z,yaw / scale,phase
uniform mat4 uVP; uniform mat4 uModel; uniform mat3 uNM; uniform vec3 uEye;
uniform float uTreeOn; uniform float uT; uniform float uW;   // trees: 1 = instanced tree, uT = time, uW = wind strength (weather)
out vec3 vN; out vec2 vUV; out vec3 vCol; out float vD; out vec3 vW;
void main(){
  vec4 w;
  if(uTreeOn>0.5){
    float cy=cos(iPos.w), sy=sin(iPos.w);
    vec3 lp=aPos*iPar.x;
    vec3 rel=vec3(cy*lp.x+sy*lp.z, lp.y, -sy*lp.x+cy*lp.z);
    float hh=max(rel.y,0.0);
    float gust=0.65+0.35*sin(uT*0.7+iPar.y*0.3)+0.25*sin(uT*1.9+1.0);
    float k=(hh*hh*0.009+length(rel.xz)*0.05*min(hh,1.0))*uW*gust;
    vec3 sw=vec3(k*0.55+sin(uT*1.4+rel.y*0.35+iPar.y)*k*0.35, 0.0, sin(uT*1.1+rel.x*0.4+iPar.y)*k*0.25);
    float fl=aFlex*uW*0.12*(0.4+gust);
    sw+=vec3(sin(uT*7.0+rel.x*3.0+rel.y), cos(uT*6.0+rel.z*4.0), sin(uT*8.0+rel.y*5.0))*fl;
    w=vec4(iPos.xyz+rel+sw,1.0);
    vN=normalize(vec3(cy*aNor.x+sy*aNor.z, aNor.y, -sy*aNor.x+cy*aNor.z));
  } else { w=uModel*vec4(aPos,1.0); vN=uNM*aNor; }
  gl_Position=uVP*w; vUV=aUV; vCol=aCol; vD=distance(w.xyz,uEye); vW=w.xyz;
}"""
    private val FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vN; in vec2 vUV; in vec3 vCol; in float vD; in vec3 vW;
uniform sampler2DShadow uShMap; uniform mat4 uShVP; uniform float uShOn; uniform float uShTex;
float shadowAt(vec3 n, vec3 wp){
  if(uShOn<0.5) return 1.0;
  vec4 sc = uShVP*vec4(wp + n*0.12, 1.0);
  vec3 p = sc.xyz/sc.w*0.5+0.5;
  if(p.x<0.0||p.x>1.0||p.y<0.0||p.y>1.0||p.z>1.0) return 1.0;
  float b = 0.0003; float t = uShTex;
  float s = texture(uShMap, vec3(p.xy, p.z-b)) + texture(uShMap, vec3(p.xy+vec2(t,0.0), p.z-b)) + texture(uShMap, vec3(p.xy-vec2(t,0.0), p.z-b))
          + texture(uShMap, vec3(p.xy+vec2(0.0,t), p.z-b)) + texture(uShMap, vec3(p.xy-vec2(0.0,t), p.z-b));
  s *= 0.2;
  float e = smoothstep(0.0, 0.08, min(min(p.x,1.0-p.x), min(p.y,1.0-p.y)));
  return mix(1.0, s, e);
}
uniform sampler2D uTex; uniform int uMode; uniform vec3 uColor; uniform float uAlpha; uniform float uFog;
uniform vec3 uSkyC; uniform float uSkyAmt; uniform float uWet; uniform float uSunDim; uniform float uHemiDim; uniform float uFlash;   // weather (all 0 = clear sun)
uniform float uDeckOn;   // PHASE 67: arch-viaduct deck (bridge group) = black asphalt + white dashed centre line
uniform sampler2D uRoad; uniform vec4 uRoadXf; uniform float uRoadOn;   // ROAD PAINT: black asphalt + white dashed centre line (assets/maps/roadpaint.bin), terrain only
const vec3 ROADC=vec3(0.045,0.045,0.05);
const vec3 SUN=vec3(-0.5652,0.6935,0.3596);
uniform float uPud; uniform float uRainI; uniform float uT; uniform vec3 uEye;   // v57: puddle level 0..1 (fills while it rains, dries slowly), rain strength, time, eye (same uniforms as the vertex shader)
float hash21(vec2 p){ p=fract(p*vec2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }
float vnoise(vec2 p){
  vec2 i=floor(p); vec2 f=fract(p); f=f*f*(3.0-2.0*f);
  return mix(mix(hash21(i),hash21(i+vec2(1.0,0.0)),f.x), mix(hash21(i+vec2(0.0,1.0)),hash21(i+vec2(1.0,1.0)),f.x), f.y);
}
out vec4 o;
void main(){
  if(uMode==3){ o=vec4(uColor,uAlpha); return; }
  if(uMode==5){ o=vec4(uColor,uAlpha*vCol.r); return; }
  vec4 tx = (uMode==0 || uMode==4 || uMode==6 || uMode==7) ? texture(uTex,vUV) : vec4(1.0);
  if((uMode==4 || uMode==7) && tx.a<0.5) discard;
  vec3 a = (uMode==0 || uMode==4) ? tx.rgb : ((uMode==1 || uMode==7 || uMode==8) ? vCol : uColor);
  if(uMode==7) a = clamp(vCol * tx.rgb * 1.1, 0.0, 1.0);   // tree leaf card: texture shape x per-leaf green
  if(uMode==6) a = clamp(vCol * tx.rgb * 2.0, 0.0, 1.0);   // Bijnor ground: grass detail tile x per-vertex green
  if(uRoadOn>0.5){
    vec2 pm = texture(uRoad, (vW.xz-uRoadXf.xy)*uRoadXf.zw).rg;
    a = mix(a, ROADC, smoothstep(0.2,0.8,pm.r));
    a = mix(a, vec3(0.93), smoothstep(0.1,0.5,pm.g));
  }
  if(uDeckOn>0.5){
    // viaduct raster of the game: deck x 269.5..414, z 184.5..194.5, top ~20.55 m, centre line z = 189.5 (rails sit at the edges)
    float fy = abs(normalize(cross(dFdx(vW), dFdy(vW))).y);
    if(fy>0.9 && vW.y>19.8 && vW.y<20.8 && vW.x>269.5 && vW.x<414.0 && abs(vW.z-189.5)<4.3){
      float dz = abs(vW.z-189.5);
      float dash = (1.0-smoothstep(0.10,0.16,dz)) * (1.0-smoothstep(2.9,3.1,mod(vW.x-269.5,8.0)));
      a = mix(ROADC, vec3(0.93), dash);
    }
  }
  if(uMode==6 || uMode==7 || uMode==8) a *= mix(1.0, 0.6, uWet);   // rain makes ground and trees darker
  // ---- v57 PUDDLES + MUD: after rain, water stands in the hollows / pits and in small dips of the level ground; wet earth turns to dark mud round it.
  float pudW = 0.0; float mudW = 0.0;
  bool groundish = (uMode==6) || (uMode==2 && abs(uColor.r-0.045)<0.002 && abs(uColor.b-0.05)<0.002);   // terrain or the black asphalt
  if(groundish && uPud>0.02){
    vec2 pq = vW.xz;
    float nA = vnoise(pq*0.045);
    float nB = vnoise(pq*0.16+vec2(17.0,5.0));
    float nC = vnoise(pq*0.55+vec2(3.7,9.1));
    float low = clamp((2.35-vW.y)*1.4, 0.0, 1.0);                       // hollows hold water (the level plain is 2.40 m)
    float flatK = smoothstep(0.975, 0.995, normalize(vN).y);            // only on (nearly) level ground
    float fld = 0.50*nB + 0.20*nC + 0.30*nA + 0.45*low;
    float thr = 0.90 - 0.22*uPud + ((uMode==2) ? 0.07 : 0.0);          // roads: fewer puddles (only the odd pothole)
    pudW = smoothstep(thr, thr+0.05, fld) * flatK;
    if(uMode==6){
      mudW = smoothstep(thr-0.17, thr, fld) * (1.0-pudW) * smoothstep(0.0, 0.45, uPud) * smoothstep(0.93, 0.99, normalize(vN).y);
      a = mix(a, vec3(0.19,0.12,0.07)*(0.7+0.6*nC), mudW*0.88);          // dark brown mud
    }
  }
  vec3 n = normalize(vN); if(!gl_FrontFacing) n=-n;
  float sh = shadowAt(n, vW);
  vec3 hemi = mix(vec3(0.49,0.54,0.31), vec3(0.81,0.89,1.0), 0.5+0.5*n.y);
  vec3 lit = a*(hemi*0.62*(1.0-uHemiDim) + vec3(1.0,0.91,0.78)*max(dot(n,SUN),0.0)*0.95*sh*(1.0-uSunDim)) + vec3(0.12,0.115,0.10)*a;
  if(pudW>0.001 || mudW>0.001){
    vec3 V = normalize(uEye - vW);
    vec3 nw = vec3(0.0,1.0,0.0);
    float ringA = 0.0;
    if(pudW>0.001 && uRainI>0.05){                                       // rain drops: expanding rings on the puddles
      vec2 rq = vW.xz*2.3; vec2 ci = floor(rq); vec2 fc = fract(rq)-vec2(0.5);
      float ph = fract(uT*0.85 + hash21(ci)*5.0);
      vec2 po = (vec2(hash21(ci+vec2(3.1,1.3)), hash21(ci+vec2(7.7,2.9)))-vec2(0.5))*0.5;
      vec2 dv = fc-po; float rd = length(dv);
      ringA = (1.0-ph)*(1.0-ph)*smoothstep(0.07, 0.0, abs(rd-ph*0.45))*uRainI;
      vec2 rg = dv/(rd+0.001)*ringA*0.7;
      nw = normalize(vec3(rg.x, 1.0, rg.y));
    }
    vec3 R = reflect(-V, nw);
    float spec = pow(max(dot(R, normalize(SUN)), 0.0), 90.0)*sh*(1.0-uSunDim);
    float fres = clamp(0.18 + 0.75*pow(1.0-clamp(dot(V,nw),0.0,1.0), 4.0), 0.0, 1.0);
    vec3 pc = mix(a*0.30, uSkyC*0.95, fres);
    pc = pc*(0.72+0.28*sh) + vec3(spec*1.8) + uSkyC*ringA*0.30;
    lit = mix(lit, pc, pudW*0.93);
    lit += vec3(pow(max(dot(reflect(-V, n), normalize(SUN)), 0.0), 24.0)*0.10*sh*(1.0-uSunDim))*mudW;   // wet sheen on the mud
  }
  lit += a*uFlash*0.55;   // lightning
  float f = clamp((vD-160.0*uFog)/(740.0*uFog),0.0,1.0);
  o = vec4(mix(lit, mix(vec3(0.74,0.84,0.93), uSkyC, uSkyAmt), f*f), 1.0);
}"""

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + GL.glGetShaderInfoLog(s))
        return s
    }

    private fun texFromBmp(bmp: Bitmap): Int {
        val id = IntArray(1); GL.glGenTextures(1, id, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bmp, 0)
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT)
        return id[0]
    }

    // ---- ROAD PAINT: every road of the map black with a white dashed centre line. One RG8 texture over the whole island
    // (R = asphalt coverage, G = dash coverage, 0.5 m per texel), sampled in world XZ by the terrain shader.
    private var roadTex = 0
    private val roadXf = floatArrayOf(0f, 0f, 1f, 1f)

    private fun loadTex(path: String): Int {
        val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        return uploadTex(bmp)
    }

    /** Ground texture of the map: assets/textures/ground.jpg (slot for the new map). No file = a plain grey pixel, the game still runs. */
    private fun loadTerrainTex(): Int {
        var bmp: Bitmap? = try { ctx.assets.open("textures/ground.jpg").use { BitmapFactory.decodeStream(it) } } catch (e: Throwable) { null }
        if (bmp == null) { bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); bmp.eraseColor(0xFF808080.toInt()) }
        return uploadTex(bmp)
    }

    private fun uploadTex(bmp: Bitmap): Int {
        val id = IntArray(1); GL.glGenTextures(1, id, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT)
        return id[0]
    }

    private fun upload(verts: FloatArray, idx: IntArray): GlMesh {
        val vao = IntArray(1); GL.glGenVertexArrays(1, vao, 0); GL.glBindVertexArray(vao[0])
        val b = IntArray(2); GL.glGenBuffers(2, b, 0)
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()); vb.asFloatBuffer().put(verts)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, b[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, verts.size * 4, vb, GL.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()); ib.asIntBuffer().put(idx)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, b[1]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GL.GL_STATIC_DRAW)
        val st = MapData.STRIDE * 4
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 3, GL.GL_FLOAT, false, st, 32)
        GL.glBindVertexArray(0)
        return GlMesh(vao[0], idx.size)
    }

    /** unit cylinder (radius 1, height 1, axis Y, with caps) - the skateboard wheels (CylinderGeometry .09 / .07 of the HTML) */
    private fun unitCylinder(seg: Int): GlMesh {
        val st = MapData.STRIDE
        val nSide = (seg + 1) * 2; val nCap = seg + 2
        val v = FloatArray((nSide + 2 * nCap) * st); val idx = IntArray(seg * 6 + seg * 6)
        fun put(i: Int, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            val o = i * st
            v[o] = x; v[o + 1] = y; v[o + 2] = z; v[o + 3] = nx; v[o + 4] = ny; v[o + 5] = nz
            v[o + 8] = 1f; v[o + 9] = 1f; v[o + 10] = 1f
        }
        var ii = 0
        for (k in 0..seg) {
            val a = k * 6.2831855f / seg; val cx = cos(a); val sz = sin(a)
            put(k * 2, cx, -0.5f, sz, cx, 0f, sz); put(k * 2 + 1, cx, 0.5f, sz, cx, 0f, sz)
            if (k < seg) { val b = k * 2; idx[ii++] = b; idx[ii++] = b + 1; idx[ii++] = b + 2; idx[ii++] = b + 1; idx[ii++] = b + 3; idx[ii++] = b + 2 }
        }
        for (cap in 0..1) {
            val y = if (cap == 0) 0.5f else -0.5f; val ny = if (cap == 0) 1f else -1f
            val b = nSide + cap * nCap
            put(b, 0f, y, 0f, 0f, ny, 0f)
            for (k in 0..seg) { val a = k * 6.2831855f / seg; put(b + 1 + k, cos(a), y, sin(a), 0f, ny, 0f) }
            for (k in 0 until seg) { idx[ii++] = b; idx[ii++] = b + 1 + k; idx[ii++] = b + 2 + k }
        }
        return upload(v, idx)
    }

    /** flat ring in the XZ plane (RingGeometry of the loot-box marker) */
    private fun unitRing(seg: Int, r0: Float, r1: Float): GlMesh {
        val st = MapData.STRIDE
        val v = FloatArray((seg + 1) * 2 * st); val idx = IntArray(seg * 6)
        var ii = 0
        for (k in 0..seg) {
            val a = k * 6.2831855f / seg; val c = cos(a); val s = sin(a)
            for (j in 0..1) { val r = if (j == 0) r0 else r1; val o = (k * 2 + j) * st; v[o] = c * r; v[o + 2] = s * r; v[o + 4] = 1f }
            if (k < seg) { val b = k * 2; idx[ii++] = b; idx[ii++] = b + 1; idx[ii++] = b + 2; idx[ii++] = b + 1; idx[ii++] = b + 3; idx[ii++] = b + 2 }
        }
        return upload(v, idx)
    }

    /** soft round blob: radial alpha 0.62 -> 0.28 (at 55 %) -> 0 exactly like the gradient texture of contact() in the reference pack. Alpha sits in the vertex colour red channel (shader mode 5). */
    private fun blobDisc(seg: Int): GlMesh {
        val st = MapData.STRIDE
        val rr = floatArrayOf(0f, 0.065f, 0.55f, 1f); val aa = floatArrayOf(0.62f, 0.62f, 0.28f, 0f)
        val nv = 1 + 3 * (seg + 1); val v = FloatArray(nv * st); val idx = IntArray(seg * 3 + 2 * seg * 6)
        v[4] = 1f; v[8] = 0.62f
        for (r in 1..3) for (k in 0..seg) {
            val a = k * 6.2831855f / seg; val o = (1 + (r - 1) * (seg + 1) + k) * st
            v[o] = cos(a) * rr[r]; v[o + 2] = sin(a) * rr[r]; v[o + 4] = 1f; v[o + 8] = aa[r]
        }
        var ii = 0
        for (k in 0 until seg) { idx[ii++] = 0; idx[ii++] = 1 + k; idx[ii++] = 2 + k }
        for (r in 1..2) for (k in 0 until seg) {
            val a0 = 1 + (r - 1) * (seg + 1) + k; val b0 = 1 + r * (seg + 1) + k
            idx[ii++] = a0; idx[ii++] = b0; idx[ii++] = a0 + 1; idx[ii++] = a0 + 1; idx[ii++] = b0; idx[ii++] = b0 + 1
        }
        return upload(v, idx)
    }

    private fun unitCube(): GlMesh {
        val n = arrayOf(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f))
        val u = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f))
        val v = FloatArray(24 * MapData.STRIDE); val idx = IntArray(36)
        for (f in 0 until 6) {
            val nn = n[f]; val uu = u[f]
            val vv = floatArrayOf(nn[1] * uu[2] - nn[2] * uu[1], nn[2] * uu[0] - nn[0] * uu[2], nn[0] * uu[1] - nn[1] * uu[0])
            val sg = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
            for (k in 0 until 4) {
                val o = (f * 4 + k) * MapData.STRIDE
                for (a in 0..2) v[o + a] = 0.5f * (nn[a] + sg[k][0] * uu[a] + sg[k][1] * vv[a])
                v[o + 3] = nn[0]; v[o + 4] = nn[1]; v[o + 5] = nn[2]
            }
            val b = f * 4; val j = f * 6
            idx[j] = b; idx[j + 1] = b + 1; idx[j + 2] = b + 2; idx[j + 3] = b; idx[j + 4] = b + 2; idx[j + 5] = b + 3
        }
        return upload(v, idx)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glReady = false; loadProgress = 0.02f
        layers.clear(); tplMeshes.clear(); tex.clear(); gunGl.clear(); planeGl.clear(); planeReady = false; chuteMesh = null; post.lost(); lensFbo = 0; lensTex = 0; lensDepth = 0; lensSize = 0; lensProg = 0; lensVao = 0; lensFailed = false
        prog = GL.glCreateProgram()
        GL.glAttachShader(prog, compile(GL.GL_VERTEX_SHADER, VS)); GL.glAttachShader(prog, compile(GL.GL_FRAGMENT_SHADER, FS))
        GL.glLinkProgram(prog)
        uMode = GL.glGetUniformLocation(prog, "uMode"); uColor = GL.glGetUniformLocation(prog, "uColor")
        uModel = GL.glGetUniformLocation(prog, "uModel"); uNM = GL.glGetUniformLocation(prog, "uNM")
        tex["terrain"] = loadTerrainTex()
        tex["atlas"] = tex["terrain"]!!   // placeholder id for the draw loop
        for (g in map.groups) layers.add(Layer(upload(g.verts, g.indices), g.mat))
        for (t in map.tpls) tplMeshes.add(upload(t.verts, t.idx))   // v54: every building model is uploaded once
        loadProgress = 0.30f
        uTreeOn = GL.glGetUniformLocation(prog, "uTreeOn"); uTimeL = GL.glGetUniformLocation(prog, "uT"); uWindL = GL.glGetUniformLocation(prog, "uW")
        uSkyC = GL.glGetUniformLocation(prog, "uSkyC"); uSkyAmt = GL.glGetUniformLocation(prog, "uSkyAmt"); uWetL = GL.glGetUniformLocation(prog, "uWet")
        uSunDimL = GL.glGetUniformLocation(prog, "uSunDim"); uHemiDimL = GL.glGetUniformLocation(prog, "uHemiDim"); uFlashL = GL.glGetUniformLocation(prog, "uFlash")
        uPudL = GL.glGetUniformLocation(prog, "uPud"); uRainL = GL.glGetUniformLocation(prog, "uRainI")
        try { if (trees == null) trees = TreeSystem(map); trees?.initGl() } catch (e: Exception) { trees = null }
        try { rainFx.init(); rainOk = true } catch (e: Exception) { rainOk = false }
        loadProgress = 0.42f
        val s = 4000f
        val sea = floatArrayOf(-s, 0f, -s, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, s, 0f, -s, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f,
            s, 0f, s, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, -s, 0f, s, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
        seaMesh = upload(sea, intArrayOf(0, 2, 1, 0, 3, 2))
        cube = unitCube()
        wheelMesh = unitCylinder(14); ringMesh = unitRing(24, 0.85f, 0.97f); blobMesh = blobDisc(20)
        run { // unit sphere for explosions
            val la = 12; val lo = 16; val v = FloatArray((la + 1) * (lo + 1) * MapData.STRIDE); val idx = IntArray(la * lo * 6)
            for (i in 0..la) for (j in 0..lo) {
                val th = i * Math.PI.toFloat() / la; val ph = j * 6.2831855f / lo; val o = (i * (lo + 1) + j) * MapData.STRIDE
                v[o] = sin(th) * cos(ph); v[o + 1] = cos(th); v[o + 2] = sin(th) * sin(ph); v[o + 3] = v[o]; v[o + 4] = v[o + 1]; v[o + 5] = v[o + 2]
            }
            var k = 0
            for (i in 0 until la) for (j in 0 until lo) {
                val a = i * (lo + 1) + j; val b = a + lo + 1
                idx[k++] = a; idx[k++] = b; idx[k++] = a + 1; idx[k++] = a + 1; idx[k++] = b; idx[k++] = b + 1
            }
            sphere = upload(v, idx)
        }
        run { // zone wall: unit cylinder, 96 segments, y 0..1
            val seg = 96; val v = FloatArray((seg + 1) * 2 * MapData.STRIDE); val idx = IntArray(seg * 6)
            for (i in 0..seg) {
                val a = i * 6.2831855f / seg
                for (k in 0..1) { val o = (i * 2 + k) * MapData.STRIDE; v[o] = cos(a); v[o + 1] = k.toFloat(); v[o + 2] = sin(a); v[o + 4] = 1f }
            }
            for (i in 0 until seg) { val b = i * 2; val j = i * 6; idx[j] = b; idx[j + 1] = b + 1; idx[j + 2] = b + 2; idx[j + 3] = b + 1; idx[j + 4] = b + 3; idx[j + 5] = b + 2 }
            wall = upload(v, idx)
        }
        loadProgress = 0.50f
        for ((gk, g) in guns.withIndex()) {
            loadProgress = 0.50f + 0.25f * gk / guns.size
            try {
                val m = GunModel(ctx, g.id)
                gunGl[g.id] = Pair(upload(m.verts, m.indices), m)
                if (m.textured && !tex.containsKey(g.id)) tex[g.id] = loadTex("models/guns/${g.id}_tex.jpg")
            } catch (e: Exception) { /* gun without model: only the HUD name is shown */ }
        }
        loadProgress = 0.78f
        try {
            val plm = PlaneModel(ctx)
            planeGl.clear()
            for (pt in plm.parts) planeGl.add(PlanePartGl(upload(pt.verts, pt.indices), pt.prop, pt.px, pt.py, pt.pz))
            planeReady = true
        } catch (e: Exception) { planeReady = false }
        loadProgress = 0.86f
        try {
            val pm3 = ParachuteModel(ctx)
            chuteTex = loadTex("models/parachute/parachute_tex.jpg")
            chuteMesh = upload(pm3.verts, pm3.indices)
        } catch (e: Exception) { chuteMesh = null }
        loadProgress = 0.92f
        PistolAnim.load(ctx)   // pistol animation pack (assets/models/anim/pistol.anim); if it is missing the old animation stays in use
        chars.init()
        loadProgress = 0.97f
        shadow.init()
        GL.glClearColor(0.74f, 0.84f, 0.93f, 1f)
        GL.glEnable(GL.GL_DEPTH_TEST)
        last = System.nanoTime()
        loadProgress = 1f; glReady = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GL.glViewport(0, 0, w, h); surfW = w; surfH = h
        Matrix.perspectiveM(proj, 0, 70f, w.toFloat() / maxOf(h, 1), 0.05f, 2500f)
    }

    // ---------------------------------------------------------------- gameplay
    private fun endMatch(won: Boolean) {
        if (matchState != 0) return
        matchState = if (won) 1 else 2
        alivePlace = if (won) 1 else botSys.bots.count { it.alive } + 1
        firing = false; healLeft = 0f; healProgress = -1f
        aiming = false; adsAmount = 0f; scopeOverlay = false; scopeAnim = 0f; cancelPickup(); swapT = 0f; run = false; footSpeed = 0f; moveX = 0f; moveY = 0f; winT = 0f
        if (won) { crouching = false; prone = false; sfx?.play("win", 1f) }
        result = Rewards.compute(kills, alivePlace, won, matchTime, false)
    }

    // ---------------------------------------------------------------- brass casings + muzzle smoke
    private class Casing(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val shell: Boolean)
    private class Puff(var x: Float, var y: Float, var z: Float, var life: Float)
    private val casings = ArrayList<Casing>()
    private val puffs = ArrayList<Puff>()
    /** every shot: a brass casing (red shell for shotguns) flies out to the right and bounces on the ground, plus a short puff of smoke at the muzzle. */
    private fun shotFx(ox: Float, oy: Float, oz: Float, fwd: FloatArray, right: FloatArray, up: FloatArray, shell: Boolean) {
        val px = ox + right[0] * 0.17f + fwd[0] * 0.42f - up[0] * 0.14f; val py = oy + right[1] * 0.17f + fwd[1] * 0.42f - up[1] * 0.14f; val pz = oz + right[2] * 0.17f + fwd[2] * 0.42f - up[2] * 0.14f
        val r = 1.6f + rnd.nextFloat() * 1.4f; val u = 1.2f + rnd.nextFloat() * 1.4f; val f = (rnd.nextFloat() - 0.3f) * 0.8f
        casings.add(Casing(px, py, pz, right[0] * r + up[0] * u + fwd[0] * f, right[1] * r + up[1] * u + fwd[1] * f, right[2] * r + up[2] * u + fwd[2] * f, 1.8f, shell))
        while (casings.size > 40) casings.removeAt(0)
        puffs.add(Puff(ox + fwd[0] * 0.9f - up[0] * 0.1f, oy + fwd[1] * 0.9f - up[1] * 0.1f, oz + fwd[2] * 0.9f - up[2] * 0.1f, 0.55f))
        while (puffs.size > 14) puffs.removeAt(0)
    }
    private fun stepShotFx(dt: Float) {
        stepMags(dt)
        var i = casings.size - 1
        while (i >= 0) {
            val c = casings[i]; c.life -= dt
            c.vy -= 9.8f * dt; c.x += c.vx * dt; c.y += c.vy * dt; c.z += c.vz * dt
            val g = map.ground(c.x, c.z) + 0.02f
            if (c.y < g) { c.y = g; c.vy = if (c.vy < -0.9f) -c.vy * 0.3f else 0f; c.vx *= 0.55f; c.vz *= 0.55f }
            if (c.life <= 0f) casings.removeAt(i); i--
        }
        i = puffs.size - 1
        while (i >= 0) { val p = puffs[i]; p.life -= dt; p.y += 0.7f * dt; if (p.life <= 0f) puffs.removeAt(i); i-- }
        stepImpactFx(dt)
    }
    private fun drawShotFx() {
        drawMags()
        drawImpactFx()
        for (c in casings) {
            if (c.shell) drawCasing(c.x, c.y, c.z, 0.05f, 0.011f, 0.72f, 0.1f, 0.08f) else drawCasing(c.x, c.y, c.z, 0.034f, 0.0065f, 0.88f, 0.68f, 0.22f)   // v63: round brass
        }
        val sp = sphere ?: return
        if (puffs.isEmpty()) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        GL.glBindVertexArray(sp.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 0.78f, 0.78f, 0.8f)
        for (p in puffs) {
            val k = (p.life / 0.55f).coerceIn(0f, 1f)
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, p.x, p.y, p.z); val sc = 0.08f + (1f - k) * 0.22f; Matrix.scaleM(mm, 0, sc, sc, sc)
            setModel(mm)
            GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.38f * k)
            GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    // ---------------------------------------------------------------- v36 MATERIAL-WISE IMPACT FX
    private class Debris(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val s: Float, val r: Float, val g: Float, val b: Float, val grav: Float)
    private class Dust(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val life0: Float, val s0: Float, val s1: Float, val r: Float, val g: Float, val b: Float, val a: Float)
    private val debris = ArrayList<Debris>()
    private val dusts = ArrayList<Dust>()

    /** a bullet hits something made of mat (Impact.*) at (x,y,z); (nx,ny,nz) = the way the surface faces (debris flies out along it). */
    private fun impactFx(x: Float, y: Float, z: Float, mat: Int, nx: Float, ny: Float, nz: Float) {
        // far hits are not worth the particles
        val ex = x - pos[0]; val ez = z - pos[2]; if (ex * ex + ez * ez > 6400f) return
        fun spray(n: Int, spd: Float, lift: Float, life: Float, size: Float, r: Float, g: Float, b: Float, grav: Float) {
            for (i in 0 until n) {
                val sx = nx * (0.5f + rnd.nextFloat()) + (rnd.nextFloat() - 0.5f) * 1.3f
                val sy = ny * (0.5f + rnd.nextFloat()) + (rnd.nextFloat() - 0.5f) * 1.3f + lift
                val sz = nz * (0.5f + rnd.nextFloat()) + (rnd.nextFloat() - 0.5f) * 1.3f
                val v = spd * (0.4f + rnd.nextFloat() * 0.8f); val k = 0.85f + rnd.nextFloat() * 0.3f
                debris.add(Debris(x + nx * 0.03f, y + ny * 0.03f, z + nz * 0.03f, sx * v, sy * v, sz * v, life * (0.7f + rnd.nextFloat() * 0.6f), size * (0.6f + rnd.nextFloat() * 0.8f), r * k, g * k, b * k, grav))
            }
        }
        fun puff(n: Int, spd: Float, life: Float, s0: Float, s1: Float, r: Float, g: Float, b: Float, a: Float, rise: Float) {
            for (i in 0 until n) dusts.add(Dust(x + nx * 0.05f, y + ny * 0.05f, z + nz * 0.05f,
                (nx + (rnd.nextFloat() - 0.5f)) * spd * rnd.nextFloat(), (ny + (rnd.nextFloat() - 0.5f)) * spd * rnd.nextFloat() + rise, (nz + (rnd.nextFloat() - 0.5f)) * spd * rnd.nextFloat(),
                life * (0.8f + rnd.nextFloat() * 0.4f), life, s0, s1, r, g, b, a))
        }
        when (mat) {
            Impact.WOOD -> {      // light splinters + a small tan puff
                spray(8, 3.4f, 0.5f, 0.55f, 0.05f, 0.66f, 0.46f, 0.24f, 9f)
                spray(3, 2.2f, 0.3f, 0.45f, 0.03f, 0.42f, 0.27f, 0.13f, 9f)
                puff(1, 0.6f, 0.45f, 0.06f, 0.22f, 0.58f, 0.44f, 0.27f, 0.35f, 0.1f)
            }
            Impact.WATER -> {     // droplets thrown up + a white foam puff on the surface
                spray(12, 3.6f, 2.4f, 0.75f, 0.035f, 0.82f, 0.92f, 1f, 9.8f)
                puff(2, 0.5f, 0.5f, 0.1f, 0.38f, 0.92f, 0.96f, 1f, 0.5f, 0.25f)
            }
            Impact.DIRT -> {      // brown dust cloud + clods
                spray(5, 2.8f, 1.2f, 0.6f, 0.045f, 0.36f, 0.26f, 0.15f, 9.8f)
                puff(4, 1.2f, 0.85f, 0.1f, 0.5f, 0.52f, 0.42f, 0.29f, 0.42f, 0.35f)
            }
            else -> {             // concrete: grey dust cloud + pale stone chips
                spray(6, 3.2f, 0.6f, 0.5f, 0.035f, 0.66f, 0.65f, 0.62f, 9.8f)
                puff(3, 1.0f, 0.75f, 0.08f, 0.42f, 0.74f, 0.73f, 0.7f, 0.45f, 0.2f)
            }
        }
        while (debris.size > 140) debris.removeAt(0)
        while (dusts.size > 40) dusts.removeAt(0)
    }
    private fun stepImpactFx(dt: Float) {
        var i = debris.size - 1
        while (i >= 0) {
            val d = debris[i]; d.life -= dt
            d.vy -= d.grav * dt; d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt
            if (d.life <= 0f || d.y < map.ground(d.x, d.z) - 0.05f) debris.removeAt(i)
            i--
        }
        i = dusts.size - 1
        while (i >= 0) { val d = dusts[i]; d.life -= dt; d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt; d.vx *= 0.94f; d.vz *= 0.94f; d.vy *= 0.96f; if (d.life <= 0f) dusts.removeAt(i); i-- }
    }
    private fun drawImpactFx() {
        for (d in debris) drawBall(d.x, d.y, d.z, d.s * 0.5f, d.r, d.g, d.b)   // v63: round chips
        val sp = sphere ?: return
        if (dusts.isEmpty()) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        GL.glBindVertexArray(sp.vao); GL.glUniform1i(uMode, 3)
        val uA = GL.glGetUniformLocation(prog, "uAlpha")
        for (d in dusts) {
            val k = (d.life / d.life0).coerceIn(0f, 1f)
            GL.glUniform3f(uColor, d.r, d.g, d.b)
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, d.x, d.y, d.z); val sc = d.s0 + (1f - k) * (d.s1 - d.s0); Matrix.scaleM(mm, 0, sc, sc, sc)
            setModel(mm)
            GL.glUniform1f(uA, d.a * k)
            GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glUniform1f(uA, 1f)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    /** a bullet stopped at (or went through) a surface: mark (not on water) + material FX. Call with the nx.. of map.impactNormal. */
    private fun impactAt(x: Float, y: Float, z: Float, mat: Int, nx: Float, ny: Float, nz: Float, mark: Boolean = true) {
        val ex = x - pos[0]; val ez = z - pos[2]; if (ex * ex + ez * ez > 6400f) return   // farther than 80 m: nobody sees it
        if (mark && mat != Impact.WATER) { marks.add(Mark(x, y, z, 8f, mat, nx, ny, nz)); if (marks.size > 60) marks.removeAt(0) }
        impactFx(x, y, z, mat, nx, ny, nz)
    }

    // ---------------------------------------------------------------- blood / bone hit FX (dark-red blobs + a few bone-white chips, shorter lived)
    private class Blood(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val s: Float, val bone: Boolean)
    private val blood = ArrayList<Blood>()
    private fun bloodBurst(x: Float, y: Float, z: Float, head: Boolean) {
        val nb = if (head) 9 else 6; val ns = if (head) 6 else 4
        for (i in 0 until nb) { val a = rnd.nextFloat() * 6.283f; val sp = 1.2f + rnd.nextFloat() * 2.6f
            blood.add(Blood(x, y, z, cos(a) * sp, 0.8f + rnd.nextFloat() * 2.2f, sin(a) * sp, 0.3f + rnd.nextFloat() * 0.25f, 0.035f + rnd.nextFloat() * 0.05f, false)) }
        for (i in 0 until ns) { val a = rnd.nextFloat() * 6.283f; val sp = 2.2f + rnd.nextFloat() * 2.4f
            blood.add(Blood(x, y, z, cos(a) * sp, 0.5f + rnd.nextFloat() * 2f, sin(a) * sp, 0.22f + rnd.nextFloat() * 0.16f, 0.03f, true)) }
        while (blood.size > 160) blood.removeAt(0)
    }
    private fun stepBlood(dt: Float) {
        var i = blood.size - 1
        while (i >= 0) { val b = blood[i]; b.life -= dt; b.vy -= 9f * dt; b.x += b.vx * dt; b.y += b.vy * dt; b.z += b.vz * dt; if (b.life <= 0f) blood.removeAt(i); i-- }
    }
    // ---------------------------------------------------------------- v31 BLEEDING
    /** a drop of your blood lying on the floor (x, y, z, seconds left) */
    private class Stain(val x: Float, val y: Float, val z: Float, var life: Float, val s: Float)
    private val stains = ArrayList<Stain>()

    private fun startBleed(hpLost: Float) {
        val was = bleeding
        bleeding = true
        bleedRate = minOf(Realism.BLEED_MAX, bleedRate + Realism.bleedAdd(hpLost))
        bleedDropT = 0.2f
        if (!was) toast("BLEEDING! USE A BANDAGE OR A MEDKIT")
    }

    private fun stopBleed() { bleeding = false; bleedRate = 0f }

    /** HP drains every second while you bleed; blood drops fall on the floor under you (more wounds = faster). Nothing but a bandage / medkit stops it. */
    private fun stepBleed(dt: Float) {
        var i = stains.size - 1
        while (i >= 0) { val st = stains[i]; st.life -= dt; if (st.life <= 0f) stains.removeAt(i); i-- }
        if (!bleeding) return
        if (!playerAlive || matchState != 0) { stopBleed(); return }
        if (warm) return
        playerHp -= bleedRate * dt
        bleedDropT -= dt
        if (bleedDropT <= 0f) {
            bleedDropT = (1.0f - bleedRate * 0.16f).coerceAtLeast(0.3f)
            if (!swimming) {
                stains.add(Stain(pos[0] + (rnd.nextFloat() - 0.5f) * 0.3f, pos[1] + 0.03f, pos[2] + (rnd.nextFloat() - 0.5f) * 0.3f, 40f, 0.10f + rnd.nextFloat() * 0.08f))
                while (stains.size > 80) stains.removeAt(0)
            }
        }
        if (playerHp <= 0f) {
            playerHp = 0f; playerAlive = false; deadAt = clock; killedBy = "BLEEDING"; addFeed("BLEEDING eliminated YOU"); stopBleed()
            endMatch(false); beginSpectate("BLEEDING")
        }
    }

    private fun drawBleedStains() {
        if (stains.isEmpty()) return
        for (st in stains) { val f = minOf(1f, st.life / 8f); drawFlatDisc(st.x, st.y, st.z, st.s * (0.6f + 0.4f * f) * 0.55f, 0.42f, 0.02f, 0.04f) }   // v63: round stain
    }

    private fun drawBlood() {
        if (blood.isEmpty()) return
        for (b in blood) if (b.bone) drawBall(b.x, b.y, b.z, b.s * 0.55f, 0.93f, 0.9f, 0.8f) else drawBall(b.x, b.y, b.z, b.s * 0.55f, 0.45f, 0.02f, 0.04f)   // v63: round drops
    }

    // ---------------------------------------------------------------- crouch / SIT (crouching + crouchAmt of the HTML)
    @Volatile var crouching = false
    @Volatile var crouchAmt = 0f
    @Volatile private var crouchReq = false
    fun toggleCrouch() { crouchReq = true }
    /** Phase 55 PRONE: lie flat. Lower eye (0.45 m below the crouch height), slowest walk, steadiest aim. Third person still shows the crouch pose. */
    @Volatile var prone = false
    @Volatile var proneAmt = 0f
    /** lying prone and looking UP (pitch > 0.55) turns the body onto its back (face up, feet first); looking down again turns it back */
    @Volatile var supine = false
    @Volatile var supineLock = false   // PRONE button: 1st tap face down, 2nd tap on the back, 3rd tap crouch
    @Volatile var supineAmt = 0f
    @Volatile private var proneReq = false
    fun toggleProne() { proneReq = true }
    /** how far the eye / head is lowered (m): crouch 0.5 + prone 0.45 */
    private val eyeDrop: Float get() = (0.5f * crouchAmt + 0.45f * proneAmt + 0.38f * pickBend) * MapData.PLAYER_K
    private fun canStandUp(): Boolean = map.ceilAt(pos[0], pos[2], pos[1] + 0.9f, pos[1] + MapData.BODY_H) >= 1e8f
    private fun stepCrouch(dt: Float) {
        if (crouchReq) {
            crouchReq = false
            if (playerAlive && airState == 3 && matchState == 0) {
                if (crouching) { if (canStandUp()) { crouching = false; toast("STANDING") } else toast("Not enough room to stand up.") }
                else { crouching = true; run = false; aiming = false; toast("CROUCHING / slower, steadier aim, harder to spot") }
            }
        }
        if (proneReq) {
            proneReq = false
            if (playerAlive && airState == 3 && matchState == 0 && !swimming) {
                if (prone && !supineLock) { supineLock = true; toast("ON BACK / lying face up") }
                else if (prone) { prone = false; supineLock = false; toast("CROUCHING") }
                else { prone = true; supineLock = false; crouching = true; run = false; aiming = false; toast("PRONE / slowest, lowest, steadiest aim") }
            }
        }
        if (crouching && (airState != 3 || !playerAlive || swimming)) crouching = false
        if (crouching && run) { if (canStandUp()) crouching = false else run = false }   // RUN stands you up
        if (!crouching) prone = false
        val target = if (crouching) 1f else 0f
        crouchAmt += (target - crouchAmt) * (1f - exp(-dt * 12f)); if (abs(target - crouchAmt) < 0.002f) crouchAmt = target
        val pTarget = if (prone) 1f else 0f
        proneAmt += (pTarget - proneAmt) * (1f - exp(-dt * 12f)); if (abs(pTarget - proneAmt) < 0.002f) proneAmt = pTarget
        supine = prone && supineLock
        if (!prone) supineLock = false
        val sT = if (supine) 1f else 0f
        supineAmt += (sT - supineAmt) * (1f - exp(-dt * 6f)); if (abs(sT - supineAmt) < 0.002f) supineAmt = sT
    }

    // ---------------------------------------------------------------- spectate after death (SPEC of the HTML: mobile BR style, no debrief card)
    @Volatile var spec = false
    @Volatile var specOver = false
    @Volatile var specTitle = ""
    @Volatile var specSub = ""
    @Volatile var specLabel = "SPECTATING"
    @Volatile var specName = "-"
    @Volatile var specLeft = 10f          // the BACK (10) countdown of the HTML
    @Volatile var specBannerT = 0f        // seconds since the banner text changed
    private var specT = 0f
    private var specTarget: Bot? = null
    private var specKiller: Bot? = null
    private var specLost = 0f
    private var specUserT = -1e9f
    private var specOffX = 0f; private var specOffY = 0f; private var specOffZ = 0f
    private var deathYaw = 0f; private var fallDir = 1f
    @Volatile private var specCycleDir = 0
    private val specTmp = FloatArray(3)
    private val specCam = FloatArray(6)
    fun specCycle(dir: Int) { specCycleDir = dir }
    private fun easeS(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun specAlive(): List<Bot> = botSys.bots.filter { it.alive }
    private fun specBanner(title: String, sub: String) { specTitle = title; specSub = sub; specBannerT = 0f }
    private fun specSetTarget(b: Bot?) { specTarget = b; specLost = 0f; specName = b?.name?.uppercase() ?: "-" }
    private fun specPick() {
        val alive = specAlive(); if (alive.isEmpty()) { specSetTarget(null); return }
        val k = specKiller
        if (k != null && k.alive) { specSetTarget(k); return }
        val t = specTarget; val rx = t?.x ?: pos[0]; val rz = t?.z ?: pos[2]
        specSetTarget(alive.minByOrNull { (it.x - rx) * (it.x - rx) + (it.z - rz) * (it.z - rz) })
    }
    private fun specFinish(alive: List<Bot>) {
        if (specOver) return
        specOver = true
        specBanner("MATCH OVER", if (alive.size == 1) "WINNER / " + alive[0].name.uppercase() else "NO SURVIVORS")
        specLabel = if (alive.isNotEmpty()) "WINNER" else "MATCH OVER"
        if (alive.isNotEmpty()) specSetTarget(alive[0])
    }
    /** specBegin(): the body falls, the camera lifts up and back, then glides to the player who eliminated you. */
    private fun beginSpectate(by: String) {
        paCorpse.reset()   // death pose starts from a fresh state
        if (spec) return
        spec = true; specOver = false; specT = 0f; specLost = 0f; specUserT = -1e9f; specLeft = 10f; specLabel = "SPECTATING"
        specKiller = botSys.bots.firstOrNull { it.alive && it.name == by }
        specOffX = camX - pos[0]; specOffY = camY - (pos[1] + 0.55f); specOffZ = camZ - pos[2]
        deathYaw = bodyYaw
        val k = specKiller
        fallDir = if (k != null) { val fx = sin(yaw); val fz = -cos(yaw); if (fx * (k.x - pos[0]) + fz * (k.z - pos[2]) >= 0f) 1f else -1f } else if (rnd.nextBoolean()) 1f else -1f
        aiming = false; diving = false; diveDepth = 0f; diveSink = 0f; diveAcc = 0f; underwater = false; swimAmt = 0f; adsAmount = 0f; scopeOverlay = false; scopeAnim = 0f; cancelPickup(); swapT = 0f; moveX = 0f; moveY = 0f; run = false; firing = false; reloadLeft = 0f; reloadProgress = -1f
        specBanner(if (k != null) "ELIMINATED BY " + k.name.uppercase() else "YOU WERE ELIMINATED",
            "#" + alivePlace + "  /  " + kills + " ELIM  /  " + String.format(java.util.Locale.US, "%d:%02d", matchTime.toInt() / 60, matchTime.toInt() % 60))
        specPick()
    }
    private fun specStep(dt: Float) {
        val l = takeLook()
        if (l[0] != 0f || l[1] != 0f) specUserT = clock
        yaw += l[0] * 0.0045f * Prefs.lookK(); pitch = (pitch - l[1] * 0.0045f * Prefs.lookK() * Prefs.vertK()).coerceIn(-1.45f, 1.45f)
        specT += dt; specBannerT += dt; specLeft = max(0f, specLeft - dt)
        val alive = specAlive()
        val cd = specCycleDir; specCycleDir = 0
        if (cd != 0 && !specOver && alive.isNotEmpty()) {
            var i = alive.indexOf(specTarget); i = if (i < 0) 0 else (i + cd + alive.size) % alive.size
            specSetTarget(alive[i]); specT = max(specT, 3.4f)
        }
        if (specOver) return
        if (alive.size <= 1) { specFinish(alive); return }
        val t = specTarget
        if (t == null || !t.alive) { specLost += dt; if (t == null || specLost > 1.4f) specPick() } else specLost = 0f
    }
    /** specClamp(): pull the camera in front of walls / terrain on the way from [from] to the wanted point. */
    private fun specClamp(fx: Float, fy: Float, fz: Float, tx: Float, ty: Float, tz: Float) {
        var x = tx; var y = ty; var z = tz
        val dx = tx - fx; val dy = ty - fy; val dz = tz - fz; val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len > 0.01f) {
            val th = map.rayWorld(fx, fy, fz, dx / len, dy / len, dz / len, len + 0.25f)
            if (th >= 0f) { val a = max(0.35f, th - 0.3f); if (a < len) { x = fx + dx / len * a; y = fy + dy / len * a; z = fz + dz / len * a } }
        }
        specTmp[0] = x; specTmp[1] = max(map.ground(x, z) + 0.3f, y); specTmp[2] = z
    }
    /** specCamera(): fills specCam with eye (0..2) and look-at point (3..5). */
    private fun specCamera(dt: Float) {
        val t = specT
        val bx = pos[0]; val by = pos[1] + 0.55f; val bz = pos[2]
        val rise = easeS(t / 1.7f)
        var hx = specOffX; var hz = specOffZ; var hl = hypot(hx, hz)
        if (hl < 0.3f) { hx = sin(deathYaw); hz = -cos(deathYaw); hl = 1f }
        hx /= hl; hz /= hl
        val dist = hl + (max(hl, 3.4f) - hl) * rise + 1f * rise
        val height = max(0.9f, specOffY) + 2.8f * rise
        specClamp(bx, by, bz, bx + hx * dist, by + height, bz + hz * dist)
        var px = specTmp[0]; var py = specTmp[1]; var pz = specTmp[2]
        var lx = bx; var ly = by; var lz = bz
        val tg = specTarget
        if (tg != null) {
            val h = -tg.yaw
            val free = clock - specUserT > 2.8f
            if (t < 2f) yaw = h else if (free) { val d = atan2(sin(h - yaw), cos(h - yaw)); yaw += d * (1f - exp(-dt * 1.8f)) }
            if (t < 2f || free) pitch += (-0.2f - pitch) * (1f - exp(-dt * 2.2f))
            val b = easeS((t - 2f) / 1.5f)
            if (b > 0f) {
                val tx = tg.x; val ty = tg.y + 1.55f; val tz = tg.z
                val cpp = cos(pitch); val fx = sin(yaw) * cpp; val fy = sin(pitch); val fz = -cos(yaw) * cpp
                specClamp(tx, ty, tz, tx - fx * 4.9f + cos(yaw) * 0.55f, ty - fy * 4.9f, tz - fz * 4.9f + sin(yaw) * 0.55f)
                px += (specTmp[0] - px) * b; py += (specTmp[1] - py) * b; pz += (specTmp[2] - pz) * b
                lx += (tx - lx) * b; ly += (ty - ly) * b; lz += (tz - lz) * b
            }
        }
        specCam[0] = px; specCam[1] = py; specCam[2] = pz; specCam[3] = lx; specCam[4] = ly; specCam[5] = lz
    }
    /** your own body stays where you fell (tickFall of the HTML: falls over in 0.85 s, slides 0.8 m, never disappears). */
    private fun drawPlayerCorpse() {
        if (!(chars.ready && chars.has(playerChar))) return
        val p = easeS((clock - deadAt) / 0.85f)
        Matrix.setIdentityM(cbase, 0); Matrix.translateM(cbase, 0, pos[0], pos[1], pos[2])
        Matrix.rotateM(cbase, 0, Math.toDegrees((-deathYaw).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.translateM(cbase, 0, 0f, 0.22f * p, 0.8f * p * fallDir)
        Matrix.rotateM(cbase, 0, Math.toDegrees((1.5f * p * fallDir).toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.scaleM(cbase, 0, CharRig.SCALE, CharRig.SCALE, CharRig.SCALE)
        cpose.mode = CharPose.RIFLE; cpose.walk = 0f; cpose.speed = 0f; cpose.sprint = false; cpose.runW = 0f; cpose.gunId = null
        cpose.crouch = 0f; cpose.prone = 0f; cpose.supine = 0f; cpose.aimPitch = 0f; cpose.headYaw = 0f; cpose.headPitch = 0f
        // death pose of the pistol pack: relaxed standing idle (gun lowered, no recoil / reload) while the body falls over
        paCorpse.update(clock, 0f, 0f, false, false, false, -1f, deathYaw, false, true)
        cpose.pa = paCorpse
        chars.draw(playerChar, vp, cbase, camX, camY, camZ, fogK, cpose, wear = Wear.id)
        cpose.pa = null   // the pose object is shared with the bots: never leave the pistol state on
        GL.glUseProgram(prog)
        GL.glBindVertexArray(cube!!.vao)
    }

    // ---------------------------------------------------------------- v29 REALISM: noise, real sound path, fall damage
    /** Every bot on the ground within hearing of this noise (radius m in the open air, walls cut it) turns to it and walks to the spot. */
    private fun noise(x: Float, y: Float, z: Float, radius: Float, src: Bot? = null) { botSys.hear(x, y, z, radius, src) }

    /** Plays a world sound the way the ears get it: distance, walls in between, roof over the head, speed of sound, left / right. (sy = feet height of the source) */
    private fun hearAt(name: String, sx: Float, sy: Float, sz: Float, range: Float, gain: Float = 1f) {
        val s = sfx ?: return
        val ex = pos[0]; val ey = pos[1] + MapData.EYE_H - eyeDrop; val ez = pos[2]
        val dx = sx - ex; val dz = sz - ez
        if (dx > range || dx < -range || dz > range || dz < -range) return
        val e = 1.2f * MapData.PLAYER_K
        val dy = sy + e - ey
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d >= range) return
        val walls = map.soundWalls(sx, sy + e, sz, ex, ey, ez)
        val pan = ((dx * cos(yaw) + dz * sin(yaw)) / max(4f, d)).coerceIn(-0.9f, 0.9f)
        s.playWorld(name, d, pan, walls, map.covered(sx, sy, sz), map.covered(ex, pos[1], ez), range, gain)
    }

    /** One footstep of the player: prone is almost silent, crouching quiet, walking normal, sprinting loud - for the ears AND for the bots. */
    private fun playerStep(sprinting: Boolean) {
        val gain = if (prone) 0.22f else if (crouching) 0.45f else if (sprinting) 1.25f else 0.85f
        sfx?.playOwn("step", map.covered(pos[0], pos[1], pos[2]), gain)
        noise(pos[0], pos[1], pos[2], if (prone) 2.5f else if (crouching) 4.5f else if (sprinting) 30f else 14f)
    }

    /** The bots walk with real footsteps: you hear them (muffled through walls), they are louder when they run. */
    private fun stepBotFoot(dt: Float) {
        for (b in botSys.bots) {
            if (!b.alive || b.air != 3 || b.speed < 0.5f) { b.stepAcc = 0f; continue }
            b.stepAcc += b.speed * dt
            if (b.stepAcc >= (if (b.speed > 4f) 2.7f else 1.9f)) {
                b.stepAcc = 0f
                if (map.waterDepth(b.x, b.z) > 0.45f) continue
                hearAt("step", b.x, b.y, b.z, 16f + b.speed * 4f, if (b.speed > 4f) 1.2f else 0.85f)
            }
        }
    }

    /** Feet touch the ground after h metres of air time (vHit = vertical speed on impact). Fall damage ignores armour; water breaks the fall. */
    private fun landed(h: Float, vHit: Float) {
        val wet = map.waterDepth(pos[0], pos[2])
        if (wet > 1.25f) { if (vHit < -5f) { sfx?.play("splash", 0.9f); noise(pos[0], pos[1], pos[2], 14f) }; return }
        val dmg = Realism.fallDamage(h) * (if (wet > 0.45f) 0.4f else 1f)
        if (vHit < -5f || dmg > 0f) {
            sfx?.playOwn("land", map.covered(pos[0], pos[1], pos[2]), (0.8f + h * 0.1f).coerceAtMost(1.6f))
            noise(pos[0], pos[1], pos[2], 10f + h * 6f)
        }
        if (dmg <= 0f || warm || !playerAlive) return
        playerHp -= dmg; damageTime = System.nanoTime(); sfx?.play("hurt", 0.9f); vibMs = (40f + dmg).toInt().coerceAtMost(160)
        if (healLeft > 0f) { healLeft = 0f; healProgress = -1f; toast("HEALING INTERRUPTED") }
        limpT = minOf(6f, 1.2f + dmg / 10f)   // a hard landing leaves you limping: slower, no sprint
        if (playerHp <= 0f) { playerHp = 0f; playerAlive = false; deadAt = clock; killedBy = "THE FALL"; addFeed("THE FALL eliminated YOU"); endMatch(false); beginSpectate("THE FALL") }
        else toast("HARD LANDING / -" + dmg.toInt() + " HP from a " + (Math.round(h * 10f) / 10f) + " m fall")
    }

    private fun hurtPlayer(dmg: Float, by: String) {
        if (!playerAlive || warm) return
        var d = dmg
        // airdrop gear (HTML damage() wrapper): Lv4 helmet takes 22 %, Lv4 vest 40 % before the normal armor rule
        if (helmLvl >= 4 && helmHp > 0f) { val r = minOf(helmHp, d * 0.22f); helmHp -= r; d -= r }
        if (vestLvl >= 4 && shield > 0f) { val x = minOf(shield, d * 0.4f); shield -= x; d -= x }
        val absorb = minOf(shield, d * 0.65f); shield -= absorb; d -= absorb
        sfx?.play("hurt"); vibMs = 45
        if (shield <= 0f && vestLvl > 0 && absorb > 0f) { vestLvl = 0; toast("VEST DESTROYED") }
        if (healLeft > 0f) { healLeft = 0f; healProgress = -1f; toast("HEALING INTERRUPTED") }
        playerHp -= d; damageTime = System.nanoTime()
        if (d >= Realism.BLEED_MIN_HIT && rnd.nextFloat() < Realism.bleedChance(d)) startBleed(d)   // v31: a wound that keeps bleeding
        if (playerHp <= 0f) { playerHp = 0f; playerAlive = false; deadAt = clock; killedBy = by; addFeed(by + " eliminated YOU"); endMatch(false); beginSpectate(by) }
    }

    private fun zoneHurt(dmg: Float) {
        if (warm) return
        if (!playerAlive) return
        playerHp -= dmg; damageTime = System.nanoTime(); sfx?.play("hurt", 0.7f); vibMs = 30
        if (healLeft > 0f) { healLeft = 0f; healProgress = -1f }
        if (playerHp <= 0f) { playerHp = 0f; playerAlive = false; deadAt = clock; killedBy = "THE ZONE"; addFeed("THE ZONE eliminated YOU"); endMatch(false); beginSpectate("THE ZONE") }
    }

    /** v39 zone: updates circle / text / distance; returns the damage to apply now (non-zero once every 2 s while landed). */
    private fun updateZone(dt: Float): Float {
        val ps = ArrayList<FloatArray>()
        if (playerAlive) ps.add(floatArrayOf(pos[0], pos[2]))
        for (b in botSys.bots) if (b.alive) ps.add(floatArrayOf(b.x, b.z))
        val m = zone.update(matchTime, ps)
        if (m != null && airState == 3) toast(m)
        zoneRadius = zone.radius; zoneCx = zone.cx; zoneCz = zone.cz; zoneText = zone.text
        zoneDist = zoneRadius - hypot(pos[0] - zoneCx, pos[2] - zoneCz)
        val n = zone.next; if (n != null) { nextX = n.x; nextZ = n.z; nextR = n.r } else nextR = 0f
        if (airState != 3 || matchState != 0 || warm) return 0f
        zoneTickT += dt
        if (zoneTickT >= ZP.TICK) { zoneTickT -= ZP.TICK; return zone.dmgNow }
        return 0f
    }

    private fun ambience() {
        if (paused || hold || matchState != 0) { sfx?.ambience(0f, 0f); return }
        val wind = when (airState) { 1 -> 0.11f; 2 -> 0.035f; 0 -> 0.018f; else -> 0f }
        val motor = if (airState == 0) 0.07f else if (flightTime < flightEnd) {
            val dx = planeX(flightTime) - pos[0]; val dz = planeZ(flightTime) - pos[2]; val dy = AIR_Y - pos[1]
            0.04f / (1f + sqrt(dx * dx + dy * dy + dz * dz) * 0.1f)
        } else 0f
        sfx?.ambience(wind, motor)
    }

    private fun step(dt: Float) {
        if (paused || hold) { takeLook(); jumpReq = false; sfx?.ambience(0f, 0f); return }
        clock += dt
        suppress = max(0f, suppress - dt * Suppression.DECAY); whizCd = max(0f, whizCd - dt)
        if (!playerAlive || airState != 3) { suppress = 0f; leanAmt = 0f; leanOffX = 0f; leanOffZ = 0f; leanRatio = 1f }
        posX = pos[0]; posZ = pos[2]; yawNow = yaw
        ambience()
        stepDyn(dt)
        stepCrouch(dt)
        stepBlood(dt)
        stepShotFx(dt)
        if (warm && !warmEnded) { warmLeft -= dt; if (warmLeft <= 0f) { warmLeft = 0f; warmEnded = true } }
        if (matchState == 0 && airState == 3 && !warm) matchTime += dt
        if (airState != 3 || flightTime < flightEnd + 8f) flightTime += dt
        val zdmg = updateZone(dt)
        if (matchState != 0) {
            botSys.update(dt, clock, pos[0], pos[1], pos[2], false, zoneRadius, zoneCx, zoneCz, 0f, { _, _ -> }, { _, _, _, _ -> })
            botsAlive = botSys.bots.count { it.alive }
            if (matchState == 1) winT += dt
            if (spec) specStep(dt) else takeLook()
            return
        }
        stepBotsAir(dt)
        if (airState != 3) { bodyYaw = yaw; faceHold = 0f; jumpAnim.reset() }
        if (airState != 3) {
            scopeLensFrac = 0f; scopeAnim = 0f; adsAmount = 0f; aiming = false
            stepAirPlayer(dt)
            botSys.update(dt, clock, pos[0], pos[1], pos[2], false, zoneRadius, zoneCx, zoneCz, 0f, { _, _ -> }, { _, _, _, _ -> })
            botsAlive = botSys.bots.count { it.alive }
            return
        }
        val gNow = guns[gi]
        loadKg = computeLoad(); loadK = Burden.speedK(loadKg)   // v44 WEIGHT
        if (Burden.heavy(loadKg) && !loadWarned) { loadWarned = true; toast("HEAVY LOAD / slower, lower jumps - drop what you do not need") } else if (loadKg < Burden.FREE_KG - 1f) loadWarned = false
        if (aiming && (reloadLeft > 0f || healLeft > 0f || run || swapT > 0f || pickU >= 0f)) aiming = false   // v60: gun change / pick-up also closes the scope
        adsAmount += ((if (aiming) 1f else 0f) - adsAmount) * minOf(1f, dt * 12f)
        val scopedView = !fists && scopeSel >= 2
        scopeOverlay = adsAmount > 0.85f && scopedView
        val scopeTgt = if (aiming && scopedView) 1f else 0f
        scopeAnim += (scopeTgt - scopeAnim) * minOf(1f, dt * (if (scopeTgt > scopeAnim) 16f else 20f))
        if (Math.abs(scopeTgt - scopeAnim) < 0.01f) scopeAnim = scopeTgt
        if (scopedView) lensSel = scopeSel
        run { val k = 1f - scopeAnim.coerceIn(0f, 1f); val sk = 1f - k * k * k; scopeLensFrac = if (scopeAnim > 0.003f) scopeBaseFrac(lensSel) * (0.55f + 0.45f * sk) else 0f; scopeLensMag = if (lensSel >= 8) 8 else if (lensSel >= 4) 4 else 2 }
        val sens = (1f + (sensScale(scopeSel) - 1f) * adsAmount) * (1f + (Prefs.adsK(scopeSel) - 1f) * adsAmount) * Prefs.lookK() * (if (firing) Prefs.freeK() else 1f)
        val l = takeLook(); yaw += l[0] * 0.0045f * sens; pitch = (pitch - l[1] * 0.0045f * sens * Prefs.vertK()).coerceIn(-1.45f, 1.45f)
        val swimDepth = map.waterDepth(pos[0], pos[2])
        swimming = swimDepth > 1.25f
        limpT = max(0f, limpT - dt)
        // v44 LEAN / PEEK (hold the LEAN buttons): the head goes sideways (not into a wall), the view rolls, the gun and the hit-box follow. No lean while sprinting, swimming, lying down or healing.
        val leanWant = if (leanReq != 0 && !swimming && !diving && !prone && healLeft <= 0f && pickU < 0f && onGround) leanReq else 0
        if (leanWant != 0) run = false
        leanAmt += (leanWant - leanAmt) * min(1f, dt * Lean.RATE); if (abs(leanAmt - leanWant) < 0.01f) leanAmt = leanWant.toFloat()
        updateLeanOffset()
        val sprinting = run && !swimming && !crouching && !aiming && healLeft <= 0f && pickU < 0f && limpT <= 1f   // v29: limping (after a hard fall) = no sprint
        val limpK = if (limpT > 0f) 1f - 0.4f * minOf(1f, limpT / 2f) else 1f
        val sp = (if (healLeft > 0f) 2.8f else if (pickU >= 0f) (if (pickAuto) 3.6f else 2.4f) else if (sprinting) 10f else if (prone) 1.7f else if (crouching) 3.4f else if (aiming) 3.2f else WALK_SPEED) * map.swimSpeed(pos[0], pos[2]) * limpK * loadK   // v44: carried weight slows you   // DLM of the HTML: walk 6.2 / sprint 10 / crouch 3.4 / prone 1.7 / aim 4.2 / heal 2.8   // wading 0.8, swimming 0.55 and no sprint (BERM.swimSpeed)
        val fx = sin(yaw); val fz = -cos(yaw); val rx = cos(yaw); val rz = sin(yaw)
        var mx = (fx * -moveY + rx * moveX); var mz = (fz * -moveY + rz * moveX)
        var len = hypot(mx, mz); if (len > 1f) { mx /= len; mz /= len }
        if (doorAssistT > 0f) {   // v37 walk-in: after OPEN from outside the body steps through the doorway by itself; any stick input / water / jump cancels it
            doorAssistT -= dt
            if (len > 0.2f || swimming || jumpReq) doorAssistT = 0f
            else if (doorAssistT < 0.7f) { mx = doorAx; mz = doorAz; len = 1f }
        }
        // camera is free (drag); the body turns to the stick direction. While firing / aiming (and 0.6 s after) it turns to the crosshair.
        run {
            if (firing || aiming || adsAmount > 0.1f) faceHold = 0.6f else faceHold = max(0f, faceHold - dt)
            var tgt = bodyYaw; var rate = 13f
            if (faceHold > 0f) { tgt = yaw; rate = 22f } else if (len > 0.12f) tgt = atan2(mx, -mz)
            bodyYaw += atan2(sin(tgt - bodyYaw), cos(tgt - bodyYaw)) * (1f - exp(-dt * rate))
        }
        // v63: move in small steps (max 0.2 m). At 10 m/s and a slow frame one step was up to 1 m, longer than a 0.25 m wall: the body jumped to the far side of a thin wall / tree / stone.
        run {
            val stepN = ceil(hypot(mx, mz) * sp * dt / 0.2f).toInt().coerceIn(1, 12); val sdt = dt / stepN
            for (si in 0 until stepN) {
                val nx = pos[0] + mx * sp * sdt; val nz = pos[2] + mz * sp * sdt
                if (!map.terrainBlocked(nx, pos[2], 0.5f, pos[1], true)) pos[0] = nx
                if (!map.terrainBlocked(pos[0], nz, 0.5f, pos[1], true)) pos[2] = nz
                map.resolve(pos, 0.42f)
            }
        }
        run { val dFar = hypot(pos[0], pos[2]); if (dFar > MapData.FAR_LIMIT) { val kf = MapData.FAR_LIMIT / dFar; pos[0] *= kf; pos[2] *= kf; toast("OPEN SEA LIMIT / far from the land, turn back") } }   // the only limit of the sea: 3 km, far outside the map
        var g = max(map.footY(pos[0], pos[2], pos[1]), map.swimFloor(pos[0], pos[2], clock))
        if (diving && !swimming) diving = false
        if (diving) {   // DIVE: stick forward + look down = deeper, look up = back to the surface; with no input buoyancy lifts you slowly. No shooting under water.
            firing = false; aiming = false
            val surfY = map.waterLevelAt(pos[0], pos[2]) - 1.25f; val bedY = map.ground(pos[0], pos[2]) + 0.1f
            val fwd = -moveY
            var ny = pos[1] + (sin(pitch) * fwd * 3.4f + (if (kotlin.math.abs(fwd) < 0.1f) 0.6f else 0f)) * dt
            if (diveSink > 0f) { diveSink -= dt; ny -= 1.6f * dt }
            ny = ny.coerceIn(minOf(bedY, surfY), surfY)
            pos[1] = ny; g = ny; vy = 0f; onGround = true; jumpReq = false
            diveDepth = (surfY - ny).coerceAtLeast(0f)
            if (diveSink <= 0f && ny >= surfY - 0.001f && kotlin.math.abs(fwd) < 0.1f) diving = false
            if (diveDepth > 0.45f) { diveAcc += (1.2f + 3f * (diveDepth - 0.45f)) * dt; while (diveAcc >= 1f) { diveAcc -= 1f; drownHit() } } else diveAcc = 0f
        } else { diveDepth = 0f; diveAcc = 0f }   // deep water: you float with your head above the surface
        if (jumpReq && onGround && crouching) { if (canStandUp()) crouching = false else jumpReq = false }
        if (jumpReq && onGround) { vy = 6.2f * Burden.jumpK(loadKg); onGround = false; fallTopY = pos[1]; sfx?.play("jump"); noise(pos[0], pos[1], pos[2], 9f) }; jumpReq = false
        // v29: stepping off a roof / balcony / floor edge is a real FALL now (before, the body slid down in a tenth of a second and nothing happened)
        if (onGround && !swimming && !diving && pos[1] - g > Realism.FALL_START) { onGround = false; vy = 0f; fallTopY = pos[1] }
        if (!onGround) { vy -= 20f * dt; var ny = pos[1] + vy * dt
            if (vy > 0f) { val cl = map.ceilAt(pos[0], pos[2], pos[1] - 0.01f, ny + MapData.BODY_H); if (cl < 1e8f) { ny = minOf(ny, cl - MapData.BODY_H - 0.012f); vy = 0f } }   // head bump under a viaduct arch
            pos[1] = ny; if (pos[1] > fallTopY) fallTopY = pos[1]
            if (pos[1] <= g) { val drop = fallTopY - g; val vHit = vy; pos[1] = g; vy = 0f; onGround = true; landed(drop, vHit) } }
        else pos[1] += (g - pos[1]) * minOf(1f, dt * 18f)
        footSpeed = hypot(mx, mz) * sp
        sprintNow = sprinting && onGround && footSpeed > 1.5f
        jumpAnim.update(dt, onGround, vy, footSpeed, !swimming && !prone && playerAlive)
        runBlend += ((if (sprintNow && !crouching && !prone) 1f else 0f) - runBlend) * minOf(1f, dt * 8f)
        if (onGround) { walkPhase += Gait.omega(hypot(mx, mz) * sp, crouching, prone, runBlend) * dt; stepDist += hypot(mx, mz) * sp * dt; if (stepDist >= (if (sprinting) 2.7f else if (crouching) 1.4f else 1.9f)) { stepDist = 0f; if (!swimming) playerStep(sprinting) } }
        stepSwim(dt)
        swimAmt += ((if (swimming && playerAlive && airState == 3) 1f else 0f) - swimAmt) * minOf(1f, dt * 7f)
        swimPh = (swimPh + dt * (2.2f + 3.0f * minOf(1f, footSpeed / 3f))) % 6.2831855f
        if (swimAmt > 0.5f) { firing = false; aiming = false }   // gun is slung while swimming: no shooting / aiming

        stepWeapon(dt)
        stepLoot(dt)
        stepBleed(dt)
        stepNades(dt)
        stepTail(dt, zdmg)
    }

    /** Bots, zone damage, airdrops, win check, bullet marks */
    private fun stepTail(dt: Float, zdmg: Float) {
        botSys.playerH = max(0.6f, MapData.PERSON_H - (0.5f * crouchAmt + 0.45f * proneAmt) * MapData.PLAYER_K)   // v63: the hit-box follows crouch / prone
        botSys.sightK = if (prone) 0.6f else if (crouching) 0.8f else 1f   // v29: crouched / prone you are harder to spot (as the toast always said)
        botSys.update(dt, clock, pos[0] + leanOffX, pos[1], pos[2] + leanOffZ, true, zoneRadius, zoneCx, zoneCz, zdmg, { dmg, name -> hurtPlayer(dmg, name) }, { b, x, y, z -> botThrow(b, x, y, z) })
        stepBotFoot(dt)
        stepSlugs(dt)
        botsAlive = botSys.bots.count { it.alive }
        for (b in botSys.bots) if (!b.alive && !b.reported && b.deadAt >= 0f && b.air == 3) { b.reported = true; addFeed(b.name + (if (b.diedBy.isEmpty()) " died in the zone" else " " + b.diedBy)); dropFromBot(b) }
        if (zoneDist < 0f && zdmg > 0f) { zoneHurt(zdmg); toast("OUTSIDE THE SAFE ZONE / -" + zdmg.toInt() + " HP every 2 seconds · move into the blue") }
        stepAirdrops(dt)
        if (matchState == 0 && botsAlive == 0) endMatch(true)
        val it = marks.iterator(); while (it.hasNext()) { val m = it.next(); m.life -= dt; if (m.life <= 0f) it.remove() }
    }

    private fun stepWeapon(dt: Float) {
        val g = guns[gi]
        cooldown -= dt; kick = maxOf(0f, kick - dt * 9f); flash = maxOf(0f, flash - dt)
        val sw = switchDir; switchDir = 0
        if (sw != 0) {
            val list = owned.filter { it >= 0 } + (-1)          // the last entry is FISTS
            val at = (if (fists) list.size - 1 else list.indexOf(gi)).coerceAtLeast(0)
            val nx = list[((at + sw) % list.size + list.size) % list.size]
            val prevIdx = if (fists) -1 else gi
            if (nx < 0) fists = true else { fists = false; gi = nx }
            if (nx != prevIdx) startSwap(prevIdx)
            reloadLeft = 0f; reloadProgress = -1f; clearLeft = 0f; sprayN = 0; cooldown = 0.6f; aiming = false; punchT = 0f; punchPending = false; publishGun(); sfx?.play("ready")
            return
        }
        if (fists) {
            aiming = false; reloadReq = false; reloadLeft = 0f; reloadProgress = -1f; clearLeft = 0f
            if (punchT > 0f) {
                punchT -= dt
                if (punchPending && 1f - punchT / punchDur >= BoxAnim.PEAK[punchKind]) { punchPending = false; punchLand() }   // the fist has arrived: now it hits
            } else punchPending = false
            if (firing && cooldown <= 0f) punch()
            triggerHeld = firing
            return
        }
        if (reloadReq) {
            reloadReq = false
            if (jammed[gi]) {   // v44 JAM: the RELOAD button (UNJAM) pulls the charging handle and clears the stoppage
                if (clearLeft <= 0f && healLeft <= 0f) { clearLeft = GunCondition.CLEAR_TIME; reloadProgress = 0f; aiming = false; sfx?.play("unjam") }
            } else
            if (reloadLeft <= 0f && healLeft <= 0f && ammo[gi] < g.mag) {
                if (pool[AmmoInfo.typeOf(g)] <= 0) toast("NO RESERVE AMMO - FIND " + AmmoInfo.NAMES[AmmoInfo.typeOf(g)] + " AMMO") else { reloadLeft = g.reload; sfx?.play("reload") }
            }
        }
        if (clearLeft > 0f) {   // v44: clearing a jam (the shot is not possible meanwhile)
            clearLeft -= dt
            reloadProgress = 1f - (clearLeft / GunCondition.CLEAR_TIME).coerceIn(0f, 1f)
            if (clearLeft <= 0f) { clearLeft = 0f; jammed[gi] = false; dur[gi] = min(GunCondition.MAX, dur[gi] + GunCondition.CLEAR_RESTORE); reloadProgress = -1f; publishGun(); toast("JAM CLEARED") }
            return
        }
        if (reloadLeft > 0f) {
            reloadLeft -= dt
            reloadProgress = 1f - (reloadLeft / g.reload).coerceIn(0f, 1f)
            if (!magDropped && reloadProgress > 0.33f && g.pellets <= 1) { magDropped = true; dropMag() }
            if (reloadLeft <= 0f) {
                val t = AmmoInfo.typeOf(g); val n = minOf(g.mag - ammo[gi], pool[t])
                ammo[gi] += n; pool[t] -= n; reloadProgress = -1f; publishGun()
            }
            return
        }
        reloadProgress = -1f; magDropped = false
        if (firing && healLeft > 0f) { healLeft = 0f; healProgress = -1f; toast("HEALING INTERRUPTED") }
        val down = firing
        if (down && sprintNow) { // pressing FIRE while sprinting stops the sprint (the HUD drops auto-run too) and the gun fires right away
            run = false; sprintNow = false
        }
        if (down && cooldown <= 0f && !(g.semi && triggerHeld)) {
            if (jammed[gi]) { cooldown = 0.3f; if (!triggerHeld) { sfx?.play("jam"); toast("WEAPON JAMMED / tap UNJAM (the reload button)") } }
            else if (ammo[gi] > 0) {
                if (rnd.nextFloat() < GunCondition.jamChance(dur[gi])) { jammed[gi] = true; cooldown = 0.4f; sfx?.play("jam"); toast("WEAPON JAMMED / tap UNJAM (the reload button)"); publishGun() }   // v44: a worn gun jams, the shot does not leave
                else shoot(g)
            } else if (pool[AmmoInfo.typeOf(g)] > 0) reloadReq = true
        }
        triggerHeld = down
    }

    /**
     * BOXING punch: starts the next punch of the combo (jab -> cross -> hook -> uppercut, restarts with a jab after 1.1 s without punching).
     * Every punch has its own damage / cool-down / length (BoxAnim). A punch stops the sprint and cancels healing. The hit itself lands in punchLand().
     */
    private fun punch() {
        if (clock - comboClock > 1.1f) comboKind = 0
        punchKind = comboKind; comboKind = (comboKind + 1) % 4; comboClock = clock
        cooldown = BoxAnim.COOLDOWN[punchKind]; punchDur = BoxAnim.DURATION[punchKind]; punchT = punchDur; punchPending = true
        punchHand = if (BoxAnim.leftArm(punchKind)) -1f else 1f
        healLeft = 0f; run = false; sprintNow = false; sfx?.play("throw")
    }

    /**
     * The fist lands (HTML punch(): reach about 3 m, x1.5 on the head). First the exact crosshair ray (head shots); if it misses, the nearest enemy inside a
     * ~33 degree cone in front of the player and within reach is hit in the body (aim assist - a thumb cannot aim a fist exactly), if no wall is in between.
     */
    private fun punchLand() {
        val cp = cos(pitch)
        val dx = sin(yaw) * cp; val dy = sin(pitch); val dz = -cos(yaw) * cp
        val ox = pos[0]; val oy = pos[1] + MapData.EYE_H - eyeDrop; val oz = pos[2]
        val reach = 2.2f   // a real punch: arm + lunge (was 3 m)
        val tw = map.rayWorld(ox, oy, oz, dx, dy, dz, reach)
        var tb = -1f; var best: Bot? = null; var bh = false
        for (d in botSys.bots) {
            if (!d.alive || d.air != 3) continue   // only enemies standing on the ground (not in the plane / sky)
            val t = MapData.rayBody(ox, oy, oz, dx, dy, dz, d.x, d.z, 0.3f, 0.25f, d.y, d.y + BotSystem.BODY_H, reach)
            if (t >= 0f && (tb < 0f || t < tb)) { tb = t; best = d; bh = (oy + dy * t) >= d.y + BotSystem.HEAD_Y }
        }
        var hx = ox + dx * tb; var hy = oy + dy * tb; var hz = oz + dz * tb
        if (best != null && tw >= 0f && tb >= tw) best = null   // a wall is in front of him
        if (best == null) {   // aim assist cone
            var bs = -1f
            for (d in botSys.bots) {
                if (!d.alive || d.air != 3) continue
                val cx = d.x; val cy = d.y + 1.0f; val cz = d.z
                val vx = cx - ox; val vy = cy - oy; val vz = cz - oz
                val dist = sqrt(vx * vx + vy * vy + vz * vz)
                if (dist < 0.05f || dist - 0.3f > reach - 0.2f) continue
                val dot = (vx * dx + vy * dy + vz * dz) / dist
                if (dot < 0.84f) continue
                val tww = map.rayWorld(ox, oy, oz, vx / dist, vy / dist, vz / dist, dist)
                if (tww >= 0f && tww < dist - 0.4f) continue
                val sc = dot - dist * 0.05f
                if (sc > bs) { bs = sc; best = d; hx = cx; hy = cy; hz = cz; bh = false }
            }
        }
        val b = best ?: return
        val dmg = BoxAnim.DAMAGE[punchKind] * (if (bh) 1.5f else 1f)
        if (!warm) { damageDealt += min(dmg, max(0f, b.hp)).toInt(); b.hp -= dmg }; b.barTime = 3f
        bloodBurst(hx, hy, hz, bh)
        var kill = false
        if (b.hp <= 0f) { b.alive = false; b.deadAt = clock; kill = true; kills++; b.reported = true; addFeed("YOU eliminated " + b.name + "  [FISTS]"); dropFromBot(b) }
        hitDamage = dmg.toInt(); hitHead = bh; hitTime = System.nanoTime(); hitKill = kill; sfx?.play("hit")
    }

    // ---------------------------------------------------------------- v30: real doors (swing, shut = wall) + windows (glass breaks, hole = way in)
    private class Shard(@JvmField var x: Float, @JvmField var y: Float, @JvmField var z: Float, @JvmField var vx: Float, @JvmField var vy: Float, @JvmField var vz: Float, @JvmField var life: Float, @JvmField val s: Float)
    private val shards = ArrayList<Shard>()
    private val doorBodies = FloatArray(520)
    private var doorHooked = false

    // ---- v37: DOOR button (the player opens / shuts doors by hand; walking up to a door no longer opens it)
    /** 0 = no door in reach (button hidden), 1 = button shows OPEN, 2 = button shows CLOSE. Written by the GL thread, read by the HUD. */
    @Volatile var doorPrompt = 0
    @Volatile private var doorReq = false
    /** walk-in assist after OPEN from outside: the body steps through the doorway by itself (stick untouched). Total time left; it starts moving 0.3 s in, when the leaf has swung past 30 %. */
    private var doorAssistT = 0f
    private var doorAx = 0f
    private var doorAz = 0f
    /** DOOR button tapped (UI thread): handled on the next frame by stepDyn */
    fun toggleDoor() { doorReq = true }

    private fun doorStep() {
        val onFoot = airState == 3 && !swimming && !spec && matchState == 0 && !paused
        val d = if (onFoot) map.doorNear(pos[0], pos[1], pos[2], 2.6f) else null
        if (!onFoot) { doorReq = false; doorAssistT = 0f }   // v39: a tap / walk-in left over from before a respawn, swim, pause or end screen never fires later
        if (doorReq) {
            doorReq = false
            if (d != null) {
                val b = d.box; val cx = (b.minX + b.maxX) * .5f; val cz = (b.minZ + b.maxZ) * .5f
                val ex = cx - pos[0]; val ez = cz - pos[2]
                if (d.tgt > 0.5f) {   // CLOSE (never on yourself: not while standing in the doorway)
                    if (ex * ex + ez * ez < 1.21f) toast("STEP AWAY FROM THE DOOR TO CLOSE IT") else { d.pm = 2; doorMates(d) }
                } else {              // OPEN
                    d.pm = 1; doorMates(d)
                    // coming in from outside: the door normal pointing from the player through the door; assist only if the far side is under a roof and the player is not
                    var nx = -d.cz; var nz = d.cx
                    if (nx * ex + nz * ez < 0f) { nx = -nx; nz = -nz }
                    val farIn = map.covered(cx + nx * 2.2f, pos[1], cz + nz * 2.2f)
                    val here = map.covered(pos[0], pos[1], pos[2])
                    val along = kotlin.math.abs((pos[0] - cx) * d.cx + (pos[2] - cz) * d.cz)   // sideways offset from the door centre
                    if (farIn && !here && along < d.len * 0.5f + 0.6f) { doorAssistT = 1.0f; doorAx = nx; doorAz = nz } else doorAssistT = 0f
                }
            }
        }
        doorPrompt = if (d == null) 0 else if (d.tgt > 0.5f) 2 else 1
    }

    /** v38 double doors: the other leaf of a pair (centres < 1.3 m apart, same floor) follows the player's choice, so both leaves open / shut together */
    private fun doorMates(d: MapData.Dyn) {
        val b = d.box; val cx = (b.minX + b.maxX) * .5f; val cz = (b.minZ + b.maxZ) * .5f
        for (e in map.dyn) {
            if (e === d || !e.door) continue
            val eb = e.box
            if (kotlin.math.abs(eb.minY - b.minY) > 0.3f) continue
            val dx = (eb.minX + eb.maxX) * .5f - cx; val dz = (eb.minZ + eb.maxZ) * .5f - cz
            if (dx * dx + dz * dz < 1.69f) e.pm = d.pm
        }
    }

    private fun stepDyn(dt: Float) {
        if (map.dyn.isEmpty()) { doorPrompt = 0; return }
        if (!doorHooked) {
            doorHooked = true
            map.onGlass = { d -> glassFx(d) }   // glass broken by a bot's bullet
            map.onDoor = { d -> val b = d.box; val ex = (b.minX + b.maxX) * .5f - pos[0]; val ez = (b.minZ + b.maxZ) * .5f - pos[2]; val q = ex * ex + ez * ez; if (q < 900f) sfx?.play(if (d.tgt > 0.5f) "door" else "doorc", 1f / (1f + q * 0.02f)) }   // v38: opening creak / closing thud + latch
        }
        doorStep()
        var n = 0
        doorBodies[n++] = pos[0]; doorBodies[n++] = pos[2]
        for (d in botSys.bots) { if (!d.alive || n >= 518) continue; doorBodies[n++] = d.x; doorBodies[n++] = d.z }
        map.updateDoors(dt, doorBodies, n / 2)
        var i = 0
        while (i < shards.size) {
            val s = shards[i]; s.life -= dt
            if (s.life <= 0f) { shards.removeAt(i); continue }
            s.vy -= 9.8f * dt; s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt
            val gy = map.footY(s.x, s.z, s.y) + 0.02f
            if (s.y < gy) { s.y = gy; s.vy = 0f; s.vx *= 0.6f; s.vz *= 0.6f }
            i++
        }
    }

    /** the pane shatters: it stops being a wall (= a way in), glass pieces fly, the bots hear it */
    private fun breakGlass(d: MapData.Dyn) { map.breakPane(d); glassFx(d) }

    private fun glassFx(d: MapData.Dyn) {
        val b = d.box; val wx = b.maxX - b.minX; val wz = b.maxZ - b.minZ
        val cx = (b.minX + b.maxX) * .5f; val cy = (b.minY + b.maxY) * .5f; val cz = (b.minZ + b.maxZ) * .5f
        for (i in 0 until 30) {
            val u = rnd.nextFloat() - .5f; val v = rnd.nextFloat() - .5f
            shards.add(Shard(cx + (if (wx > wz) u * wx else 0f), cy + v * (b.maxY - b.minY), cz + (if (wx > wz) 0f else u * wz),
                (rnd.nextFloat() - .5f) * 2.4f, rnd.nextFloat() * 1.6f, (rnd.nextFloat() - .5f) * 2.4f, 1.6f + rnd.nextFloat(), 0.03f + rnd.nextFloat() * 0.07f))
        }
        val ex = cx - pos[0]; val ez = cz - pos[2]
        sfx?.play("glass", (1f / (1f + (ex * ex + ez * ez) * 0.004f)).coerceIn(0.15f, 1f))
        noise(cx, cy, cz, 28f)
    }

    /** v54: places instance [k] (one quarter-turn face about +y, local (x,z) -> world (x,z): 0 (x,z) | 1 (z,-x) | 2 (-x,-z) | 3 (-z,x)) into [bm]. */
    private fun instMatrix(k: MapData.Inst) {
        Matrix.setIdentityM(bm, 0); Matrix.translateM(bm, 0, k.x, k.y, k.z)
        if (k.face != 0) Matrix.rotateM(bm, 0, k.face * 90f, 0f, 1f, 0f)
    }

    /** v54: draw the instanced buildings near the eye (distance by graphics level, plus a cheap behind-the-camera test for the far ones). */
    private fun drawBuildings(ex: Float, ez: Float) {
        val ins = map.insts
        if (ins.isEmpty() || tplMeshes.isEmpty()) return
        val range = when (Prefs.gfx) { "high" -> 420f; "low" -> 230f; else -> 320f }
        var fx = -view[2]; var fz = -view[10]
        val fl = sqrt(fx * fx + fz * fz); if (fl > 1e-4f) { fx /= fl; fz /= fl }
        GL.glUniform1i(uMode, 1)
        for (k in ins) {
            val dx = k.x - ex; val dz = k.z - ez
            val d2 = dx * dx + dz * dz; val lim = range + k.rad
            if (d2 > lim * lim) continue
            // far buildings that are clearly behind the camera are skipped (near ones are always drawn: a wall can fill the screen from the side)
            if (d2 > 2500f && (dx * fx + dz * fz) < -k.rad) continue
            val mesh = tplMeshes[k.tpl]
            instMatrix(k); setModel(bm)
            GL.glBindVertexArray(mesh.vao); GL.glDrawElements(GL.GL_TRIANGLES, mesh.count, GL.GL_UNSIGNED_INT, 0)
        }
        setModel(ident)
    }

    private fun drawDyn() {
        if (map.dyn.isEmpty()) return
        val px = pos[0]; val pz = pos[2]
        GL.glBindVertexArray(cube!!.vao); GL.glUniform1i(uMode, 2)
        for (d in map.dyn) {   // door leaves: a box hinged on the jamb, swinging 90 degrees
            if (!d.door) continue
            val b = d.box; val ex = (b.minX + b.maxX) * .5f - px; val ez = (b.minZ + b.maxZ) * .5f - pz
            if (ex * ex + ez * ez > 22500f) continue
            val an = easeS(d.a) * 1.5708f; val c = cos(an); val sn = kotlin.math.sin(an)
            val ux = d.cx * c + d.ox * sn; val uz = d.cz * c + d.oz * sn
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, d.hx + ux * d.len * .5f, (b.minY + b.maxY) * .5f, d.hz + uz * d.len * .5f)
            Matrix.rotateM(mm, 0, Math.toDegrees(Math.atan2((-uz).toDouble(), ux.toDouble())).toFloat(), 0f, 1f, 0f)
            Matrix.scaleM(mm, 0, d.len, b.maxY - b.minY, 0.07f)
            setModel(mm); GL.glUniform3f(uColor, d.r, d.g, d.b)
            GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        val uA = GL.glGetUniformLocation(prog, "uAlpha")   // (a local in the other draw functions, so it is looked up here too)
        GL.glUniform1f(uA, 0.34f)
        for (d in map.dyn) {   // intact glass: a thin see-through sheet
            if (d.door || d.broken) continue
            val b = d.box; val wx = b.maxX - b.minX; val wz = b.maxZ - b.minZ
            val cx = (b.minX + b.maxX) * .5f; val cz = (b.minZ + b.maxZ) * .5f
            val ex = cx - px; val ez = cz - pz
            if (ex * ex + ez * ez > 14400f) continue
            drawCube(cx, (b.minY + b.maxY) * .5f, cz, if (wx < wz) 0.05f else wx, b.maxY - b.minY, if (wx < wz) wz else 0.05f,
                min(1f, d.r * 1.3f + 0.12f), min(1f, d.g * 1.3f + 0.12f), min(1f, d.b * 1.3f + 0.12f), 3)
        }
        GL.glUniform1f(uA, 0.8f)
        for (s in shards) drawCube(s.x, s.y, s.z, s.s, s.s * 0.25f, s.s, 0.82f, 0.93f, 1f, 3)
        GL.glUniform1f(uA, 1f); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    private fun shoot(g: Gun) {
        ammo[gi]--; cooldown = g.delay; kick = 1f; flash = 0.05f
        dur[gi] = max(0f, dur[gi] - GunCondition.wearPerShot(g)); gunDur = dur[gi].toInt()   // v44 DURABILITY
        if (dur[gi] < GunCondition.JAM_BELOW && !wornWarned[gi]) { wornWarned[gi] = true; toast("GUN WORN / it may jam - clear it with UNJAM, or find another gun") }
        sfx?.playOwn(Realism.shotSound(g), map.covered(pos[0], pos[1], pos[2]))   // v29: inside a house the shot rings with a room reverb
        noise(pos[0], pos[1], pos[2], Realism.gunRadius(g))                         // v29: the bots hear the shot (suppressed VSS 26 m ... M82 230 m, walls muffle it)
        val cp = cos(pitch)
        val fwd = floatArrayOf(sin(yaw) * cp + shkX, sin(pitch) + shkY, -cos(yaw) * cp + shkZ)   // v59: includes the tremble the camera shows
        run { val fl = sqrt(fwd[0] * fwd[0] + fwd[1] * fwd[1] + fwd[2] * fwd[2]); fwd[0] /= fl; fwd[1] /= fl; fwd[2] /= fl }
        val right = floatArrayOf(cos(yaw), 0f, sin(yaw))
        val up = floatArrayOf(-sin(yaw) * sin(pitch), cp, cos(yaw) * sin(pitch))
        val ox = pos[0] + leanOffX; val oy = pos[1] + MapData.EYE_H - eyeDrop; val oz = pos[2] + leanOffZ   // v44: a leaning player shoots from the leaned head
        var aimD = -1f   // v59: distance of the point under the crosshair (world or bot); the bullet is aimed to HIT that point (gravity / drag compensated)
        if (airState == 3) {   // free camera sits behind / beside the body: the bullet flies from the head to the exact point under the crosshair (like mobile BR)
            var tCam = map.rayWorld(camX, camY, camZ, fwd[0], fwd[1], fwd[2], 400f); if (tCam < 0f) tCam = 400f
            for (d in botSys.bots) {
                if (!d.alive || d.air == 0) continue
                val t = MapData.rayBody(camX, camY, camZ, fwd[0], fwd[1], fwd[2], d.x, d.z, 0.3f, 0.25f, d.y, d.y + BotSystem.BODY_H, tCam)
                if (t >= 0f && t < tCam) tCam = t
            }
            val ax = camX + fwd[0] * tCam - ox; val ay = camY + fwd[1] * tCam - oy; val az = camZ + fwd[2] * tCam - oz
            val al = sqrt(ax * ax + ay * ay + az * az)
            if (tCam > 4f && al > 1f) { fwd[0] = ax / al; fwd[1] = ay / al; fwd[2] = az / al; aimD = al } else aimD = tCam
        } else {
            aimD = aimDistance(ox, oy, oz, fwd)
        }
        shotFx(ox, oy, oz, fwd, right, up, g.pellets > 1)
        // v32 BALLISTICS: no instant hit any more. Every pellet becomes a bullet that flies (muzzle speed, gravity drop, drag) - see stepSlugs()
        val spd = Ballistics.speed(g); val drg = Ballistics.drag(g)
        val lift = aimLift(fwd, spd, drg, if (aimD > 0f) aimD else Ballistics.zero(g))   // v59: replaces the fixed zero-range lift: the bullet crosses the crosshair exactly at what you aim at
        for (p in 0 until g.pellets) {
            val sp = g.spread * (1f - 0.45f * adsAmount) * (1f - 0.65f * (if (scopeSel >= 2 && !fists) scopeAnim else 0f)) * (1f - 0.4f * crouchAmt - 0.2f * proneAmt) * GunCondition.spreadK(dur[gi]) * (1f + Suppression.SPREAD_K * suppress)
            val sx = (rnd.nextFloat() * 2 - 1) * sp; val sy = (rnd.nextFloat() * 2 - 1) * sp
            var dx = fwd[0] + right[0] * sx + up[0] * sy; var dy = fwd[1] + up[1] * sy + lift; var dz = fwd[2] + right[2] * sx + up[2] * sy
            val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
            slugs.add(Slug(ox, oy, oz, dx * spd, dy * spd, dz * spd, g, drg))
        }
        while (slugs.size > 160) slugs.removeAt(0)
        val recK = (1f - 0.25f * adsAmount) * (1f - 0.15f * crouchAmt - 0.15f * proneAmt)
        // v44 RECOIL PATTERN: shot n of a spray always kicks the same way (RecoilPattern), a pause restarts the pattern
        if (clock - lastShotAt > RecoilPattern.resetGap(g)) sprayN = 0
        lastShotAt = clock
        pitch = (pitch + RecoilPattern.pitch(g, sprayN) * recK).coerceAtMost(1.45f)
        yaw += RecoilPattern.yaw(g, sprayN) * recK
        sprayN++
        ammoNow = ammo[gi]
    }

    /** v59: distance (m) from the eye along [f] to the first thing the crosshair sees: terrain / wall or a living bot; -1 = nothing within range */
    private fun aimDistance(ox: Float, oy: Float, oz: Float, f: FloatArray): Float {
        var t = map.rayWorld(ox, oy, oz, f[0], f[1], f[2], Ballistics.MAX_RANGE)
        if (t < 0f) t = Ballistics.MAX_RANGE
        var hitSomething = t < Ballistics.MAX_RANGE
        for (d in botSys.bots) {
            if (!d.alive || d.air == 0) continue
            val tb = MapData.rayBody(ox, oy, oz, f[0], f[1], f[2], d.x, d.z, 0.3f, 0.25f, d.y, d.y + BotSystem.BODY_H, t)
            if (tb >= 0f && tb < t) { t = tb; hitSomething = true }
        }
        return if (hitSomething) t else -1f
    }

    /**
     * v59 AIM = HIT: extra upward slope (added to the direction's y, like the old fixed lift) that makes the bullet - with the same gravity + drag as stepSlugs() -
     * pass exactly through the point [dist] metres along [f]. So the bullet lands where the crosshair / scope / red dot is, at any distance.
     */
    private fun aimLift(f: FloatArray, spd: Float, drg: Float, dist: Float): Float {
        val D = dist.coerceIn(2f, Ballistics.MAX_RANGE)
        // unit vector perpendicular to f in the vertical plane (the "up" of the sight line)
        var ux = -f[0] * f[1]; var uy = 1f - f[1] * f[1]; var uz = -f[2] * f[1]
        val un = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-4f); ux /= un; uy /= un; uz /= un
        fun miss(lift: Float): Float {   // sight-line height of the bullet (m) when it has flown D along f
            var dx = f[0]; var dy = f[1] + lift; var dz = f[2]
            val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
            var vx = dx * spd; var vy = dy * spd; var vz = dz * spd
            var px = 0f; var py = 0f; var pz = 0f; val h = 0.008f
            var steps = 0
            while (steps < 1400) {
                steps++
                val sp0 = sqrt(vx * vx + vy * vy + vz * vz)
                val dk = max(0f, 1f - drg * sp0 * h)
                vx *= dk; vz *= dk; vy = vy * dk - Ballistics.G * h
                val qx = px + vx * h; val qy = py + vy * h; val qz = pz + vz * h
                val a0 = px * f[0] + py * f[1] + pz * f[2]; val a1 = qx * f[0] + qy * f[1] + qz * f[2]
                if (a1 >= D) {
                    val k = if (a1 - a0 > 1e-6f) (D - a0) / (a1 - a0) else 1f
                    val mx = px + (qx - px) * k; val my = py + (qy - py) * k; val mz = pz + (qz - pz) * k
                    return mx * ux + my * uy + mz * uz
                }
                px = qx; py = qy; pz = qz
                if (vx * vx + vy * vy + vz * vz < 25f) break
            }
            return -1000f   // never got there (too slow): needs more lift
        }
        var lo = -0.02f; var hi = 0.40f
        if (miss(hi) < 0f) return hi
        for (i in 0 until 22) { val m = (lo + hi) * 0.5f; if (miss(m) < 0f) lo = m else hi = m }
        return (lo + hi) * 0.5f
    }

    // ---------------------------------------------------------------- v32 BALLISTICS: flying bullets of the player
    private class Slug(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val gun: Gun, val drag: Float) {
        var trav = 0f; var age = 0f
        var dmgK = 1f; var walls = 0   // v36: share of the damage left after the walls it went through, number of walls it went through
    }
    private val slugs = ArrayList<Slug>()

    /**
     * Moves every bullet by dt (in sub-steps of ~8 ms so a fast bullet never skips a wall or a bot): drag + gravity change the velocity, then the
     * straight piece of the path is tested against glass (breaks it, bullet flies on), walls / terrain and the bots. The nearest thing wins.
     * Damage / headshot / kill rules are the old hitscan ones; the distance falloff now uses the distance the bullet really flew.
     */
    private fun stepSlugs(dt: Float) {
        if (slugs.isEmpty() || dt <= 0f) return
        var hit = false; var head = false; var kill = false; var total = 0
        val n = ceil(dt / 0.008f).toInt().coerceIn(1, 12); val h = dt / n
        var i = slugs.size - 1
        while (i >= 0) {
            val s = slugs[i]; var dead = false; var k = 0
            while (k < n && !dead) {
                k++
                val sp0 = sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz)
                val dk = max(0f, 1f - s.drag * sp0 * h)
                s.vx *= dk; s.vz *= dk; s.vy = s.vy * dk - Ballistics.G * h
                val ex = s.vx * h; val ey = s.vy * h; val ez = s.vz * h
                val len = sqrt(ex * ex + ey * ey + ez * ez)
                if (len < 1e-4f) { dead = true; break }
                val dx = ex / len; val dy = ey / len; val dz = ez / len
                var rem = len   // v36: a bullet can go through thin walls, so one sub-step may hold several pieces: [start..wall], [behind the wall..next wall], ...
                while (rem > 1e-3f && !dead) {
                    var tw = map.shootThrough(s.x, s.y, s.z, dx, dy, dz, rem)   // glass breaks and the bullet goes on, first wall / ground distance or -1
                    val matHit = if (tw > 0f) map.impactMaterial(s.x + dx * tw, s.z + dz * tw) else Impact.CONCRETE   // v36: what it hit (read right after the ray, before anything else uses the map)
                    val nrmHit = if (tw > 0f) map.impactNormal(s.x + dx * tw, s.y + dy * tw, s.z + dz * tw, dx, dy, dz) else null
                    val nx = nrmHit?.get(0) ?: 0f; val ny = nrmHit?.get(1) ?: 1f; val nz = nrmHit?.get(2) ?: 0f
                    var water = false
                    val twWater = map.waterCross(s.x, s.y, s.z, dx, dy, dz, rem)   // v36: the surface of a lake / river / sea stops the bullet with a splash
                    if (twWater > 0f && (tw < 0f || twWater < tw)) { tw = twWater; water = true }
                    val twBox = tw > 0f && !water && !map.lastHitTerrain          // true = a wall / door / furniture (can be punched through), false = ground / hill / water
                    var tb = -1f; var best: Bot? = null; var bh = false
                    for (d in botSys.bots) {
                        if (!d.alive || d.air == 0) continue
                        val t = MapData.rayBody(s.x, s.y, s.z, dx, dy, dz, d.x, d.z, 0.3f, 0.25f, d.y, d.y + BotSystem.BODY_H, rem)
                        if (t >= 0f && (tb < 0f || t < tb)) { tb = t; best = d; bh = (s.y + dy * t) >= d.y + BotSystem.HEAD_Y }
                    }
                    if (best != null && (tw < 0f || tb < tw)) {
                        val g = s.gun
                        var dmg = (if (bh) (g.head ?: g.damage * 2) else (g.body ?: g.damage)).toFloat()
                        if (s.trav + tb > g.eff) dmg *= 0.6f
                        dmg *= s.dmgK   // v36: weaker after a wall
                        if (!warm) { damageDealt += min(dmg, max(0f, best.hp)).toInt(); best.hp -= dmg }; best.barTime = 3f; total += dmg.toInt(); hit = true; head = head || bh
                        bloodBurst(s.x + dx * tb, s.y + dy * tb, s.z + dz * tb, bh)
                        if (best.hp <= 0f && best.alive) { best.alive = false; best.deadAt = clock; kill = true; kills++; best.reported = true; addFeed("YOU eliminated " + best.name + "  [" + g.name + "]"); dropFromBot(best) }
                        dead = true
                    } else if (tw > 0f) {
                        val g = s.gun
                        if (water) impactAt(s.x + dx * tw, s.y + dy * tw, s.z + dz * tw, Impact.WATER, 0f, 1f, 0f)
                        else impactAt(s.x + dx * tw, s.y + dy * tw, s.z + dz * tw, matHit, nx, ny, nz)
                        val th = if (twBox && s.walls < Penetration.maxWalls(g)) map.wallThickness(s.x, s.y, s.z, dx, dy, dz, tw, Penetration.limit(g)) else -1f
                        if (th > 0f) {
                            // v36 WALL PENETRATION: the bullet goes through the wall, leaves a hole on the far side, and flies on weaker + slower
                            val adv = tw + th + 0.03f
                            s.dmgK *= Penetration.damageKeep(g, th); s.walls++
                            val sk = Penetration.speedKeep(g); s.vx *= sk; s.vy *= sk; s.vz *= sk
                            s.x += dx * adv; s.y += dy * adv; s.z += dz * adv; s.trav += adv
                            impactAt(s.x - dx * 0.03f, s.y - dy * 0.03f, s.z - dz * 0.03f, matHit, dx, dy * 0.3f, dz)   // the exit hole: debris flies on out of the far side (v36)
                            rem -= adv
                        } else dead = true
                    } else {
                        s.x += dx * rem; s.y += dy * rem; s.z += dz * rem; s.trav += rem
                        rem = 0f
                    }
                }
                if (!dead) {
                    s.age += h
                    if (s.trav > Ballistics.MAX_RANGE || s.age > Ballistics.MAX_LIFE || s.y < -80f) dead = true
                }
            }
            if (dead) slugs.removeAt(i)
            i--
        }
        if (hit) { hitDamage = total; hitHead = head; hitKill = kill; hitTime = System.nanoTime(); sfx?.play("hit") }
    }

    // ---------------------------------------------------------------- loot
    private fun addLoot(type: Int, sub: Int, p: FloatArray?) { if (p != null) loot.add(Loot(type, sub, p[0], p[1], p[2])) }

    private fun gunOfSlot(slot: Int): Int {
        val list = guns.indices.filter { guns[it].slot == slot }
        return list[rnd.nextInt(list.size)]
    }

    /** v56: slot of a random ground weapon. [r] is the old 0..1 roll that picked long / short / sniper (below [t0] long, below [t1] short, else sniper); about 1 in 5 weapons is now a pistol (M1911 / G18 / DEAGLE). */
    private fun lootSlot(r: Float, t0: Float, t1: Float): Int = if (rnd.nextFloat() < 0.20f) 3 else if (r < t0) 0 else if (r < t1) 1 else 2

    /**
     * DX PATCH of the HTML (`dxStockHouses`): ground loot lies only INSIDE houses or on their porch (mobile BR style), never out in the open.
     * Up to 90 tries per item, at least 1.3 m from every other item; if the map has no house list the old open-ground spot is used.
     */
    private fun houseSpot(): FloatArray? {
        if (map.houseSpots.isEmpty()) {   // the Bijnor map has no houses: ground loot lies in the OPEN, anywhere on the 4561 m land (never on a road, never on the coast)
            for (n in 0 until 60) {
                val sp = map.randomLandAny(rnd) ?: continue
                var ok = true
                for (l in loot) { val dx = l.x - sp[0]; val dz = l.z - sp[2]; if (dx * dx + dz * dz < 1.69f) { ok = false; break } }
                if (ok) return sp
            }
            return null
        }
        for (n in 0 until 90) {
            val sp = map.houseSpotTry(rnd) ?: continue
            var ok = true
            for (l in loot) { val dx = l.x - sp[0]; val dz = l.z - sp[2]; if (dx * dx + dz * dz < 1.69f) { ok = false; break } }
            if (ok) return sp
        }
        return null
    }

    /** Loot of the replacement houses (they stand where the 4 old landmark models were): 3 items per house, now inside / next to ANY house like the HTML. */
    private fun spawnVillageLoot() {
        for (hh in map.extraHouses) {
            for (k in 0 until 3) {
                val sp = houseSpot()
                val r = rnd.nextFloat()
                when {
                    r < .30f -> addLoot(LootType.WEAPON, gunOfSlot(lootSlot(rnd.nextFloat(), .50f, .85f)), sp)
                    r < .48f -> addLoot(LootType.AMMO, rnd.nextInt(3), sp)
                    r < .58f -> addLoot(LootType.BANDAGE, 0, sp)   // v31
                    r < .65f -> addLoot(LootType.MEDKIT, 0, sp)
                    r < .75f -> addLoot(LootType.ARMOR, 0, sp)
                    r < .90f -> addLoot(LootType.SCOPE, intArrayOf(1, 2, 4, 8)[rnd.nextInt(4)], sp)
                    r < .95f -> addLoot(LootType.GRENADE, 0, sp)
                    else -> addLoot(LootType.SHROOM, 0, sp)
                }
            }
        }
    }

    private fun spawnLoot() {
        spawnVillageLoot()
        // a starter ring close to the spawn point so the first minute is playable
        for (k in 0 until 4) addLoot(LootType.WEAPON, gunOfSlot(k), houseSpot())   // v56: one of every slot, pistol included
        addLoot(LootType.AMMO, 0, houseSpot()); addLoot(LootType.AMMO, 2, houseSpot())
        addLoot(LootType.AMMO, 1, houseSpot()); addLoot(LootType.MEDKIT, 0, houseSpot()); addLoot(LootType.BANDAGE, 0, houseSpot()); addLoot(LootType.BANDAGE, 0, houseSpot())
        addLoot(LootType.ARMOR, 0, houseSpot())
        addLoot(LootType.SCOPE, 1, houseSpot()); addLoot(LootType.SCOPE, 2, houseSpot())
        addLoot(LootType.GRENADE, 0, houseSpot())
        addLoot(LootType.SHROOM, 0, houseSpot())
        for (i in 0 until 20 * LOOT_MULT) addLoot(LootType.SHROOM, 0, houseSpot())
        for (i in 0 until 15 * LOOT_MULT) addLoot(LootType.GRENADE, 0, houseSpot())
        // map-wide (same counts family as spawnOutdoor())
        for (i in 0 until 36 * LOOT_MULT) { val r = rnd.nextFloat(); addLoot(LootType.SCOPE, if (r < .34f) 1 else if (r < .64f) 2 else if (r < .86f) 4 else 8, houseSpot()) }
        for (i in 0 until 60 * LOOT_MULT) { val r = rnd.nextFloat(); addLoot(LootType.WEAPON, gunOfSlot(lootSlot(r, .45f, .82f)), houseSpot()) }
        for (i in 0 until 40 * LOOT_MULT) addLoot(LootType.AMMO, 0, houseSpot())
        for (i in 0 until 26 * LOOT_MULT) addLoot(LootType.AMMO, 1, houseSpot())
        for (i in 0 until 18 * LOOT_MULT) addLoot(LootType.AMMO, 2, houseSpot())
        for (i in 0 until 15 * LOOT_MULT) addLoot(LootType.ARMOR, 0, houseSpot())
        for (i in 0 until 15 * LOOT_MULT) addLoot(LootType.MEDKIT, 0, houseSpot())
        for (i in 0 until 24 * LOOT_MULT) addLoot(LootType.BANDAGE, 0, houseSpot())   // v31: bandages stop bleeding
        stockHomes()
        stockEveryHouse()
    }

    /** Loot INSIDE the 7 Bijnor houses (every floor): one random item on each interior loot point of the map. */
    private fun stockHomes() {
        // v54: the map now has ~7300 interior loot points (1300 enterable buildings): two of every three are stocked (keeps the loot lists and the frame time small)
        for ((q, p) in map.homeLoot.withIndex()) if (q % 3 != 2) addRandomLootAt(floatArrayOf(p[0], p[1], p[2]))
    }

    /** One random ground item (same odds as the village houses) at sp. */
    private fun addRandomLootAt(sp: FloatArray?) {
        val r = rnd.nextFloat()
        when {
            r < .30f -> addLoot(LootType.WEAPON, gunOfSlot(lootSlot(rnd.nextFloat(), .50f, .85f)), sp)
            r < .48f -> addLoot(LootType.AMMO, rnd.nextInt(3), sp)
            r < .58f -> addLoot(LootType.BANDAGE, 0, sp)   // v31
            r < .65f -> addLoot(LootType.MEDKIT, 0, sp)
            r < .75f -> addLoot(LootType.ARMOR, 0, sp)
            r < .90f -> addLoot(LootType.SCOPE, intArrayOf(1, 2, 4, 8)[rnd.nextInt(4)], sp)
            r < .95f -> addLoot(LootType.GRENADE, 0, sp)
            else -> addLoot(LootType.SHROOM, 0, sp)
        }
    }

    /** EVERY house has loot: after the random scatter each house footprint that holds fewer than 3 items is topped up to 3 (inside, or on the porch if its inside is closed). */
    private fun stockEveryHouse() {
        for (i in map.houseSpots.indices) {
            val hh = map.houseSpots[i]
            var have = 0
            for (l in loot) if (kotlin.math.abs(l.x - hh[0]) < hh[2] / 2f + 3f && kotlin.math.abs(l.z - hh[1]) < hh[3] / 2f + 3f) have++
            var tries = 0
            while (have < 3 && tries < 400) {
                tries++
                val sp = map.houseSpotAt(rnd, i) ?: continue
                var ok = true
                for (l in loot) { val dx = l.x - sp[0]; val dz = l.z - sp[2]; if (dx * dx + dz * dz < 1.69f) { ok = false; break } }
                if (!ok) continue
                addRandomLootAt(sp); have++
            }
        }
    }

    /** Like damage() + dxMakeCrate() in the HTML: a dead bot drops its gun, one supply item and sometimes an armor plate / grenade / scope. */
    private fun dropFromBot(b: Bot) {
        // Phase 28: dxMakeCrate() of the HTML - a wooden crate pops out of the ground 0.8 s after the kill (ring + light beam), the items lie INSIDE it
        // (not on the ground any more) and can be taken when you stand within 3.4 m. Same loot as before: gun, one supply item, armor plate (35 %),
        // grenade (30 %), scope (22 %, rollScope odds 34 / 30 / 22 / 14 %).
        val gIdx = guns.indexOfFirst { it.id == b.gun.id }
        val baseY = map.footY(b.x, b.z, b.y + 0.4f)
        val cr = LootCrate(b.x, baseY, b.z, rnd.nextFloat() * 6.2831855f)
        fun put(l: Loot) {
            val a = cr.items.size * 1.9f
            l.x = cr.x + cos(a) * 0.12f; l.y = cr.y; l.z = cr.z + sin(a) * 0.12f
            l.crate = cr; cr.items.add(l); loot.add(l)
        }
        put(Loot(LootType.WEAPON, gIdx, b.x, b.y, b.z))
        val r = rnd.nextFloat()
        put(if (r < .40f) Loot(LootType.AMMO, AmmoInfo.typeOf(b.gun), b.x, b.y, b.z) else if (r < .55f) Loot(LootType.SHROOM, 0, b.x, b.y, b.z) else if (r < .78f) Loot(LootType.BANDAGE, 0, b.x, b.y, b.z) else Loot(LootType.MEDKIT, 0, b.x, b.y, b.z))
        if (rnd.nextFloat() < .35f) put(Loot(LootType.ARMOR, 0, b.x, b.y, b.z))
        if (rnd.nextFloat() < .30f) put(Loot(LootType.GRENADE, 0, b.x, b.y, b.z))
        if (rnd.nextFloat() < .22f) { val q = rnd.nextFloat(); put(Loot(LootType.SCOPE, if (q < .34f) 1 else if (q < .64f) 2 else if (q < .86f) 4 else 8, b.x, b.y, b.z)) }
        crates.add(cr)
        while (crates.size > 14) { val old = crates.removeAt(0); loot.removeAll(old.items) }
    }

    /** an item inside a crate can only be taken while the crate is open (you are near and it has popped up) */
    private fun crateOk(l: Loot): Boolean { val c = l.crate; return c == null || c.open }

    /** dxCrates() of the HTML: pop-in after 0.8 s (grows with a little overshoot), items usable above grow 0.9 within 3.4 m, an empty box shrinks away. */
    private fun stepCrates(dt: Float) {
        var i = crates.size - 1
        while (i >= 0) {
            val c = crates[i]
            c.t += dt
            val u = ((c.t - 0.8f) / 0.4f).coerceIn(0f, 1f)
            c.grow = easeS(u)
            val sc = if (c.t < 0.8f) 0.001f else max(0.001f, c.grow * (1f + 0.12f * sin(3.1415927f * u)))
            var left = 0
            for (l in c.items) if (l.active) left++
            val near = playerAlive && airState == 3 && c.grow > 0.9f && hypot(pos[0] - c.x, pos[2] - c.z) < 3.4f && kotlin.math.abs(pos[1] - c.y) < 2.5f
            c.open = near && left > 0
            c.fx = c.grow > 0.5f && left > 0
            if (left > 0) {
                c.empty = 0f; c.scale = sc
                if (near && !c.hinted) { c.hinted = true; toast("LOOT BOX - TAP AN ITEM IN THE LIST TO TAKE IT") }
            } else {
                c.empty += dt
                c.scale = max(0.001f, (1f - easeS((c.empty - 1.2f) / 0.5f)) * sc)
                if (c.empty > 1.8f) { loot.removeAll(c.items); crates.removeAt(i) }
            }
            i--
        }
    }

    private fun blockedReason(l: Loot): String {
        return when (l.type) {
            LootType.AMMO -> if (pool[l.sub] >= AmmoInfo.CAP[l.sub]) AmmoInfo.NAMES[l.sub] + " AMMO FULL (" + AmmoInfo.CAP[l.sub] + ")" else ""
            LootType.ARMOR -> if (shield >= 100f) "ARMOR ALREADY FULL" else ""
            LootType.VEST -> if (vestLvl > l.sub) "YOU WEAR A BETTER VEST (Lv." + vestLvl + ")" else if (vestLvl == l.sub && shield >= 100f) "LEVEL " + l.sub + " VEST ALREADY EQUIPPED" else ""
            LootType.MEDKIT -> if (medkits >= 5) "MEDKITS FULL (5)" else ""
            LootType.BANDAGE -> if (bandages >= Realism.BANDAGE_MAX) "BANDAGES FULL (" + Realism.BANDAGE_MAX + ")" else ""
            LootType.GRENADE -> if (grenadeCount >= 6) "GRENADES FULL (6)" else ""
            LootType.SCOPE -> if (scopesOwned.contains(l.sub)) "SCOPE " + l.sub + "X ALREADY ATTACHED" else ""
            LootType.VEST4 -> if (vestLvl >= 4 && shield >= 100f) "LEVEL 4 VEST ALREADY EQUIPPED" else ""
            LootType.HELM4 -> if (helmLvl >= 4 && helmHp >= 100f) "LEVEL 4 HELMET ALREADY EQUIPPED" else ""
            LootType.SHROOM -> if (mushrooms >= 5) "MUSHROOMS FULL (5)" else ""
            else -> ""
        }
    }

    private fun collect(l: Loot) {
        val why = blockedReason(l); if (why.isNotEmpty()) { toast(why); return }
        when (l.type) {
            LootType.WEAPON -> {
                val g = guns[l.sub]
                val old = owned[g.slot]
                if (old == l.sub) { // already carrying it: take its ammo instead
                    toast("ALREADY CARRYING " + g.name); return
                }
                if (old >= 0) loot.add(Loot(LootType.WEAPON, old, pos[0] + 1.2f, pos[1], pos[2] + 0.4f).also { it.ammo = ammo[old]; it.dur = dur[old] }) // drop replaced gun (keeps its magazine)
                owned[g.slot] = l.sub; ammo[l.sub] = if (l.ammo in 0..g.mag) l.ammo else g.mag
                dur[l.sub] = if (l.dur >= 0f) l.dur else GunCondition.FOUND_MIN + rnd.nextFloat() * (GunCondition.FOUND_MAX - GunCondition.FOUND_MIN)   // v44: a found gun is a little worn
                jammed[l.sub] = false; wornWarned[l.sub] = dur[l.sub] < GunCondition.JAM_BELOW; clearLeft = 0f; sprayN = 0
                startSwap(if (fists) -1 else gi); gi = l.sub; fists = false; aiming = false; reloadLeft = 0f; reloadProgress = -1f; cooldown = 0.6f; publishGun()
                toast("EQUIPPED " + g.name)
            }
            LootType.AMMO -> { val n = minOf(AmmoInfo.BOX[l.sub], AmmoInfo.CAP[l.sub] - pool[l.sub]); pool[l.sub] += n; publishGun(); toast("+" + n + " " + AmmoInfo.NAMES[l.sub] + " AMMO") }
            LootType.VEST -> { vestLvl = l.sub; shield = 100f; toast("EQUIPPED " + AmmoInfo.VEST_NAMES[l.sub] + " (ARMOR FULL)") }
            LootType.ARMOR -> { shield = minOf(100f, shield + 40f); toast("+40 ARMOR") }
            LootType.GRENADE -> { grenadeCount++; toast("+1 GRENADE") }
            LootType.SCOPE -> { scopesOwned.add(l.sub); if (l.sub > scopeSel) { scopeSel = l.sub; scopeLabel = l.sub.toString() + "X" }; toast("SCOPE / " + l.sub + "X ATTACHED - TAP THE SCOPE TAG TO SWITCH") }
            LootType.VEST4 -> { vestLvl = 4; shield = 100f; toast("AIRDROP / LEVEL 4 VEST equipped (max armor)") }
            LootType.HELM4 -> { helmLvl = 4; helmHp = 100f; toast("AIRDROP / LEVEL 4 HELMET equipped") }
            LootType.SHROOM -> { mushrooms++; toast("SUPPLIES / +1 mushroom · tap EAT (+60 EP)") }
            LootType.BANDAGE -> { bandages++; toast("+1 BANDAGE / stops bleeding") }
            else -> { medkits++; toast("+1 MEDKIT") }
        }
        l.active = false; sfx?.play("pickup")
    }

    private fun lootPrio(t: Int): Int = when (t) {
        LootType.WEAPON -> 0; LootType.VEST4, LootType.VEST -> 1; LootType.HELM4 -> 2; LootType.SCOPE -> 3; LootType.AMMO -> 4
        LootType.MEDKIT, LootType.BANDAGE -> 5; LootType.GRENADE -> 6; LootType.SHROOM -> 7; else -> 8
    }

    /** takes one row of the loot list: supplies (ammo, medkit, grenade, mushroom, armor plate) are taken one after the other until the bag is full, everything else one piece at a time */
    private fun collectGroup(g: List<Loot>) {
        if (g.isEmpty()) return
        val t = g[0].type
        val stack = t == LootType.AMMO || t == LootType.MEDKIT || t == LootType.BANDAGE || t == LootType.GRENADE || t == LootType.SHROOM || t == LootType.ARMOR
        for (l in g) {
            if (!l.active) continue
            collect(l)
            if (!stack || l.active) break   // not a stack, or the bag is full for this kind (collect() showed the reason)
        }
    }

    private fun stepLoot(dt: Float) {
        stepCrates(dt)
        stepPickup(dt)
        // auto pick-up (settings: medkits / mushrooms / ammo / armor / grenades; radius 1.5 m, 2.6 m with FAST)
        val pr = Prefs.pickRadius(); val pr2 = pr * pr
        for (l in loot) {
            if (!l.active || !crateOk(l)) continue
            val on = when (l.type) {
                LootType.MEDKIT, LootType.BANDAGE -> Prefs.pickMedkit; LootType.SHROOM -> Prefs.pickMushroom; LootType.AMMO -> Prefs.pickAmmo
                LootType.ARMOR, LootType.VEST -> Prefs.pickArmor; LootType.GRENADE -> Prefs.pickGrenade; else -> false
            }
            if (!on) continue
            val dx = l.x - pos[0]; val dz = l.z - pos[2]
            if (dx * dx + dz * dz <= pr2 && kotlin.math.abs(l.y - pos[1]) < 2.5f && blockedReason(l).isEmpty()) { startPickup(listOf(l), true); if (pickU >= 0f) break }
        }
        // eat a mushroom: +60 EP (max 250); EP converts to health, 1 EP -> 1 HP every second (epRegen in the HTML)
        if (eatReq) {
            eatReq = false
            if (mushrooms < 1) toast("NO MUSHROOMS - FIND RED MUSHROOMS") else if (ep >= EP_MAX) toast("EP IS ALREADY FULL")
            else { mushrooms--; ep = minOf(EP_MAX, ep + 60f); sfx?.play("heal"); toast("MUSHROOM EATEN / +60 EP") }
        }
        epTimer += dt
        while (epTimer >= 1f) { epTimer -= 1f; if (playerHp < 200f && ep > 0f) { ep = maxOf(0f, ep - 1f); playerHp = minOf(200f, playerHp + 1f) } }
        // Phase 55 LOOT LIST (mobile BR style): everything within 3 m, identical items stacked into one row, tap a row to take it
        nearTmp.clear()
        for (l in loot) {
            if (!l.active || !crateOk(l)) continue
            val dx = l.x - pos[0]; val dz = l.z - pos[2]
            if (dx * dx + dz * dz <= 9f && kotlin.math.abs(l.y - pos[1]) < 2.5f) nearTmp.add(l)
        }
        if (nearTmp.isEmpty()) {
            if (lootRows.isNotEmpty()) { lootRows = emptyList(); lootGroups = emptyList() }
            nearLoot = null; promptLabel = ""
        } else {
            nearTmp.sortBy { (it.x - pos[0]) * (it.x - pos[0]) + (it.z - pos[2]) * (it.z - pos[2]) }   // nearest first inside a stack
            val groups = ArrayList<ArrayList<Loot>>()
            for (l in nearTmp) {
                var g: ArrayList<Loot>? = null
                for (e in groups) if (e[0].type == l.type && e[0].sub == l.sub) { g = e; break }
                if (g == null) { g = ArrayList(); groups.add(g) }
                g.add(l)
            }
            groups.sortWith(compareBy<ArrayList<Loot>>({ lootPrio(it[0].type) }, { it[0].sub }))   // fixed order (no jumping rows while you walk)
            val rows = ArrayList<LootRow>(groups.size)
            for (g in groups) { val f = g[0]; rows.add(LootRow(f.type * 100 + f.sub, f.type, f.sub, lootLabel(f), g.size, blockedReason(f).isNotEmpty() || (f.type == LootType.WEAPON && owned[guns[f.sub].slot] == f.sub))) }
            lootGroups = groups; lootRows = rows
            nearLoot = groups[0][0]; promptLabel = lootLabel(groups[0][0])
        }
        val pk = if (pickU < 0f) pickKey else -1   // a tap during a running pick-up waits for it (the next item is taken right after)
        if (pk >= 0) {
            pickKey = -1
            val rs = lootRows
            for (i in rs.indices) if (rs[i].key == pk && i < lootGroups.size) { startPickup(lootGroups[i], false); break }
        }
        if (pickupReq && pickU < 0f) { pickupReq = false; lootGroups.firstOrNull()?.let { startPickup(it, false) } }
        // medkit heal: 2.2 s, restores 100 HP
        if (healReq) {
            healReq = false
            if (healLeft <= 0f) {
                // v31: bleeding + a bandage -> wrap it (stops the bleeding). Otherwise a medkit (heals 100 and also stops it). No medkit but a bandage -> bandage (+15 HP)
                if (bleeding && bandages > 0) { healIsBandage = true; healDur = Realism.BANDAGE_TIME; healLeft = healDur; reloadLeft = 0f; reloadProgress = -1f }
                else if (playerHp >= 200f && !bleeding) toast("HEALTH ALREADY FULL")
                else if (medkits >= 1) { healIsBandage = false; healDur = 2.2f; healLeft = healDur; reloadLeft = 0f; reloadProgress = -1f }
                else if (bandages >= 1 && playerHp < 200f) { healIsBandage = true; healDur = Realism.BANDAGE_TIME; healLeft = healDur; reloadLeft = 0f; reloadProgress = -1f }
                else toast(if (bleeding) "BLEEDING - FIND A BANDAGE OR A MEDKIT" else "NO MEDKITS - FIND WHITE SUPPLY PACKS")
            }
        }
        if (cancelHealReq) { cancelHealReq = false; if (healLeft > 0f) { healLeft = 0f; healProgress = -1f; toast("HEALING CANCELLED") } }
        if (healLeft > 0f) {
            healLeft -= dt; healProgress = 1f - (healLeft / healDur).coerceIn(0f, 1f)
            if (healLeft <= 0f) {
                val wasBleeding = bleeding
                healProgress = -1f; sfx?.play("heal")
                if (healIsBandage) {
                    bandages = maxOf(0, bandages - 1); playerHp = minOf(200f, playerHp + Realism.BANDAGE_HP); stopBleed()
                    toast(if (wasBleeding) "BANDAGED / BLEEDING STOPPED +" + Realism.BANDAGE_HP.toInt() else "BANDAGED +" + Realism.BANDAGE_HP.toInt())
                } else {
                    medkits--; playerHp = minOf(200f, playerHp + 100f); stopBleed()
                    toast(if (wasBleeding) "HEALED +100 / BLEEDING STOPPED" else "HEALED +100")
                }
            }
        }
    }

    // ---------------------------------------------------------------- airdrops (adPickSpot / adMake / adTick of the HTML)
    private fun adPickSpot(): FloatArray? {
        val r0 = max(150f, min(zoneRadius * 0.8f, 900f))
        for (i in 0 until 600) {
            val a = rnd.nextFloat() * 6.2832f; val r = sqrt(rnd.nextFloat()) * r0
            val x = sin(a) * r; val z = cos(a) * r; val g = map.ground(x, z)
            if (g < 1.5f) continue
            if (map.terrainBlocked(x, z, 3.2f) || map.boxBlocked(x, z, 3.2f, g + 0.05f)) continue
            if (hypot(x - pos[0], z - pos[2]) < 80f) continue
            return floatArrayOf(x, z, g)
        }
        return null
    }

    private fun landDrop(d: Drop) {
        val spec = arrayOf(
            intArrayOf(LootType.VEST4, 0), intArrayOf(LootType.HELM4, 0), intArrayOf(LootType.WEAPON, gunOfSlot(2)), intArrayOf(LootType.WEAPON, gunOfSlot(1)),
            intArrayOf(LootType.AMMO, 0), intArrayOf(LootType.AMMO, 0), intArrayOf(LootType.MEDKIT, 0), intArrayOf(LootType.GRENADE, 0))
        for ((i, sp) in spec.withIndex()) {
            val a = i / 8f * 6.2832f
            val l = Loot(sp[0], sp[1], d.x + sin(a) * 2.2f, d.g + 0.05f, d.z + cos(a) * 2.2f)
            loot.add(l); d.items.add(l)
        }
        toast("AIRDROP LANDED / Lv4 Vest + Lv4 Helmet inside. Follow the gold beam")
        fxs.add(Fx(d.x, d.g + 0.3f, d.z, 0.25f))
    }

    private fun stepAirdrops(dt: Float) {
        if (adN < AD_TIMES.size && matchTime >= adNext) {
            val sp = adPickSpot()
            if (sp != null) {
                adN++; adNext = if (adN < AD_TIMES.size) AD_TIMES[adN] else 1e9f
                drops.add(Drop(sp[0], sp[1], sp[2], if (rnd.nextBoolean()) 1 else -1))
                toast("AIRDROP INCOMING / watch the sky, gold beam shows the crate"); sfx?.play("alert"); sfx?.play("chute")
            } else adNext = matchTime + 2f
        }
        for (d in drops) {
            d.t += dt
            when (d.state) {
                0 -> if (d.t >= 260f / 110f) { d.state = 1; d.ty = 0f }
                1 -> {
                    d.ty += dt
                    d.h = max(0f, 210f - (if (d.ty < 3f) d.ty * d.ty * 3f else 27f + (d.ty - 3f) * 9.5f))
                    if (d.h <= 0.01f) { d.state = 2; d.h = 0f; d.landT = 0f; landDrop(d) }
                }
                else -> { d.landT += dt; d.life += dt; if (d.items.none { it.active } || d.life > 150f) d.beam = max(0f, d.beam - dt * 0.2f) }
            }
        }
        var best: Drop? = null; var bd = 1e9f
        val marks = ArrayList<Float>()
        for (d in drops) {
            if (d.state == 2 && d.items.none { it.active }) continue
            marks.add(d.x); marks.add(d.z)
            val dd = hypot(d.x - pos[0], d.z - pos[2]); if (dd < bd) { bd = dd; best = d }
        }
        dropMarks = marks.toFloatArray()
        airdropText = if (best != null) "AIRDROP " + (if (best.state == 2) "" else "(INCOMING) ") + "· " + bd.toInt() + " m" else ""
    }

    // ---------------------------------------------------------------- grenades
    private class Nade(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val bot: Bot?) { var fuse = 2.6f; var resting = false }
    private class Fx(val x: Float, val y: Float, val z: Float, var life: Float)
    private val nades = ArrayList<Nade>()
    private val fxs = ArrayList<Fx>()

    private fun playerThrow() {
        if (cooldown > 0f) return
        if (grenadeCount <= 0) { toast("NO GRENADES - FIND GRENADE SUPPLIES"); return }
        grenadeCount--; reloadLeft = 0f; reloadProgress = -1f; healLeft = 0f; healProgress = -1f; aiming = false; cooldown = 0.85f
        val p = pitch.coerceIn(-0.8f, 0.8f); val cp = cos(p)
        var dx = sin(yaw) * cp; var dy = sin(p) + 0.3f; var dz = -cos(yaw) * cp
        val l = sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l
        nades.add(Nade(pos[0], pos[1] + 1.52f, pos[2], dx * 15f, dy * 15f + 3f, dz * 15f, null)); sfx?.play("throw")
    }

    private fun botThrow(b: Bot, tx: Float, ty: Float, tz: Float) {
        val dx = tx - b.x; val dz = tz - b.z; val r = hypot(dx, dz).coerceAtLeast(3f)
        val v = sqrt(14f * r) * (0.94f + rnd.nextFloat() * 0.12f); val c = 0.7071f
        nades.add(Nade(b.x, b.y + 1.5f, b.z, dx / r * v * c, v * c, dz / r * v * c, b))
    }

    private fun stepNades(dt: Float) {
        if (nadeReq) { nadeReq = false; faceHold = 0.6f; bodyYaw = yaw; playerThrow() }
        val it = nades.iterator()
        while (it.hasNext()) {
            val g = it.next(); g.fuse -= dt
            if (g.fuse <= 0f) { it.remove(); detonate(g); continue }
            if (!g.resting) nadePhysics(g, dt)
        }
        val fi = fxs.iterator(); while (fi.hasNext()) { val f = fi.next(); f.life -= dt; if (f.life <= 0f) fi.remove() }
    }

    /** Same constants as updateGrenades(): gravity 14, bounce 0.42, floor friction 0.67, rests below 1 m/s, radius 0.18. */
    private fun nadePhysics(g: Nade, dt: Float) {
        g.vy -= 14f * dt
        val r = 0.18f
        val spd = sqrt(g.vx * g.vx + g.vy * g.vy + g.vz * g.vz)
        val n = Math.ceil((spd * dt / 0.12f).toDouble()).toInt().coerceIn(1, 10); val h = dt / n
        for (s in 0 until n) {
            g.x += g.vx * h; g.y += g.vy * h; g.z += g.vz * h
            var nx = 0f; var ny = 0f; var nz = 0f; var hit = false
            val ncl = map.boxesAt(g.x, g.z)
            if (ncl != null) for (qi in 0 until ncl.size) {
                val b = ncl[qi]
                if (g.x > b.minX - r && g.x < b.maxX + r && g.y > b.minY - r && g.y < b.maxY + r && g.z > b.minZ - r && g.z < b.maxZ + r) {
                    val dxl = g.x - (b.minX - r); val dxr = (b.maxX + r) - g.x; val dyb = g.y - (b.minY - r); val dyt = (b.maxY + r) - g.y
                    val dzl = g.z - (b.minZ - r); val dzr = (b.maxZ + r) - g.z
                    val m = minOf(minOf(minOf(dxl, dxr), minOf(dyb, dyt)), minOf(dzl, dzr))
                    when (m) {
                        dxl -> { g.x = b.minX - r; nx = -1f }
                        dxr -> { g.x = b.maxX + r; nx = 1f }
                        dyb -> { g.y = b.minY - r; ny = -1f }
                        dyt -> { g.y = b.maxY + r; ny = 1f }
                        dzl -> { g.z = b.minZ - r; nz = -1f }
                        else -> { g.z = b.maxZ + r; nz = 1f }
                    }
                    hit = true; break
                }
            }
            val gy = map.footY(g.x, g.z, g.y) + r   // grenades also bounce on the viaduct deck / model floors
            if (!hit && g.y < gy) {
                g.y = gy
                if (gy - r > map.ground(g.x, g.z) + 0.5f) { nx = 0f; ny = 1f; nz = 0f; hit = true }   // flat deck / floor: normal straight up
                else {
                val gx = map.ground(g.x + 1f, g.z) - map.ground(g.x - 1f, g.z); val gz = map.ground(g.x, g.z + 1f) - map.ground(g.x, g.z - 1f)
                val l = sqrt(gx * gx + 4f + gz * gz); nx = -gx / l; ny = 2f / l; nz = -gz / l; hit = true }
            }
            if (hit) {
                val vn = g.vx * nx + g.vy * ny + g.vz * nz
                if (vn < 0f) { g.vx -= 1.42f * vn * nx; g.vy -= 1.42f * vn * ny; g.vz -= 1.42f * vn * nz }
                if (vn < -3f) { hearAt("bounce", g.x, g.y - 0.9f, g.z, 45f, 0.8f); noise(g.x, g.y, g.z, 16f, g.bot) }
                if (ny > 0.5f) {
                    g.vx *= 0.67f; g.vz *= 0.67f
                    if (sqrt(g.vx * g.vx + g.vy * g.vy + g.vz * g.vz) < 1f) { g.vx = 0f; g.vy = 0f; g.vz = 0f; g.resting = true; return }
                }
            }
        }
    }

    /** 125 * (1 - d/9)^1.15 within 9 m, needs a clear line from the blast (as in detonate()). */
    private fun nadeDamage(cx: Float, cy: Float, cz: Float, tx: Float, ty: Float, tz: Float): Float {
        val dx = tx - cx; val dy = ty - cy; val dz = tz - cz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d >= 9f) return 0f
        if (d > 0.05f) { val t = map.rayWorld(cx, cy, cz, dx / d, dy / d, dz / d, d); if (t in 0f..(d - 0.3f)) return 0f }
        return 125f * Math.pow((1f - d / 9f).toDouble(), 1.15).toFloat()
    }

    private fun detonate(g: Nade) {
        fxs.add(Fx(g.x, g.y + 0.2f, g.z, 0.5f)); hearAt("blast", g.x, g.y - 0.9f, g.z, 300f, 1.2f); noise(g.x, g.y, g.z, 160f, g.bot)
        if (playerAlive && matchState == 0) {
            val dmg = nadeDamage(g.x, g.y + 0.2f, g.z, pos[0], pos[1] + 1.05f, pos[2])
            if (dmg > 0f) hurtPlayer(dmg, if (g.bot == null) "YOUR GRENADE" else g.bot.name)
        }
        var dealt = 0; var killed = false
        for (b in botSys.bots) {
            if (!b.alive) continue
            val dmg = nadeDamage(g.x, g.y + 0.2f, g.z, b.x, b.y + 1.0f, b.z)
            if (dmg <= 0f) continue
            if (g.bot == null) { dealt += dmg.toInt(); if (!warm) damageDealt += min(dmg, max(0f, b.hp)).toInt() }; if (!warm) b.hp -= dmg; b.barTime = 3f
            if (b.hp <= 0f) { b.alive = false; b.deadAt = clock; b.reported = true; if (g.bot == null) { kills++; killed = true }; addFeed((g.bot?.name ?: "YOU") + " eliminated " + b.name + "  [GRENADE]"); dropFromBot(b) }
        }
        if (dealt > 0) { hitDamage = dealt; hitHead = false; hitKill = killed; hitTime = System.nanoTime() }
    }

    // ---------------------------------------------------------------- drawing
    // ================================================================ Phase 28: skateboard, air body, loot crate, contact shadows, dropped magazine
    private val bdm = FloatArray(16)

    private fun degF(r: Float): Float = Math.toDegrees(r.toDouble()).toFloat()

    /** one box relative to a parent matrix: centre px,py,pz, rotation about Z in degrees, FULL sizes. The cube VAO must already be bound. */
    private fun boxIn(par: FloatArray, px: Float, py: Float, pz: Float, rotZ: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float, mode: Int) {
        System.arraycopy(par, 0, pm, 0, 16)
        Matrix.translateM(pm, 0, px, py, pz)
        if (rotZ != 0f) Matrix.rotateM(pm, 0, rotZ, 0f, 0f, 1f)
        Matrix.scaleM(pm, 0, sx, sy, sz)
        setModel(pm)
        GL.glUniform1i(uMode, mode); GL.glUniform3f(uColor, r, g, b)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    /**
     * The skateboard under a skating fighter, drawn in the model-root frame so it scales / turns with the body:
     * deck + grip tape + orange rails + two kicktails + trucks + 4 wheels + cyan glow underneath. It spins in while boardK grows.
     */
    private fun drawSkateboard(rootM: FloatArray, av: AirVis) {
        if (av.boardK <= 0.03f) return
        val k = max(0.01f, av.boardK)
        System.arraycopy(rootM, 0, bdm, 0, 16)
        Matrix.translateM(bdm, 0, 0f, -0.06f + av.drop, 0f)
        Matrix.rotateM(bdm, 0, degF((1f - av.boardK) * 4f), 0f, 1f, 0f)
        Matrix.scaleM(bdm, 0, k, k, k)
        GL.glBindVertexArray(cube!!.vao)
        boxIn(bdm, 0f, 0f, 0f, 0f, 1.5f, 0.05f, 0.4f, 0.106f, 0.149f, 0.188f, 2)                 // deck #1b2630
        boxIn(bdm, 0f, 0.03f, 0f, 0f, 1.44f, 0.012f, 0.36f, 0.051f, 0.071f, 0.09f, 2)            // grip tape #0d1217
        boxIn(bdm, 0f, 0.004f, 0.2f, 0f, 1.5f, 0.03f, 0.025f, 1f, 0.353f, 0.165f, 2)             // orange rails #ff5a2a
        boxIn(bdm, 0f, 0.004f, -0.2f, 0f, 1.5f, 0.03f, 0.025f, 1f, 0.353f, 0.165f, 2)
        for (s in 0..1) {
            val sg = if (s == 0) -1f else 1f
            boxIn(bdm, sg * 0.8f, 0.045f, 0f, degF(sg * 0.42f), 0.24f, 0.05f, 0.4f, 0.106f, 0.149f, 0.188f, 2)   // kicktail
            boxIn(bdm, sg * 0.5f, -0.06f, 0f, 0f, 0.09f, 0.05f, 0.3f, 0.604f, 0.647f, 0.678f, 2)                // truck #9aa5ad
        }
        boxIn(bdm, 0f, -0.035f, 0f, 0f, 1.25f, 0.012f, 0.26f, 0.22f, 0.91f, 1f, 3)               // glow underside #38e8ff (unlit)
        val wm = wheelMesh
        if (wm != null) {
            GL.glBindVertexArray(wm.vao)
            for (s in 0..1) for (zz in 0..1) {
                val sg = if (s == 0) -1f else 1f; val z = if (zz == 0) -0.17f else 0.17f
                System.arraycopy(bdm, 0, pm, 0, 16)
                Matrix.translateM(pm, 0, sg * 0.5f, -0.1f, z); Matrix.rotateM(pm, 0, 90f, 1f, 0f, 0f); Matrix.scaleM(pm, 0, 0.09f, 0.07f, 0.09f)
                setModel(pm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.957f, 0.945f, 0.91f)   // wheels #f4f1e8
                GL.glDrawElements(GL.GL_TRIANGLES, wm.count, GL.GL_UNSIGNED_INT, 0)
            }
            GL.glBindVertexArray(cube!!.vao)
        }
    }

    /**
     * The 3D parachute model: canopy at y 4.5..6.4, the two riser ends (where the hands / the crate lid attach) at y = 1.1, a centre line down to y = 0.
     * So the caller puts the origin at (attach height - 1.1 * sc).
     * open = 0..1 inflates the canopy; heading = degrees around Y; sc = overall size; sway = small pendulum swing in degrees.
     */
    private fun drawChute(x: Float, y: Float, z: Float, open: Float, heading: Float, sc: Float, swayDeg: Float) {
        val m = chuteMesh ?: return
        val k = (0.3f + 0.7f * open) * sc
        Matrix.setIdentityM(pm, 0); Matrix.translateM(pm, 0, x, y, z)
        Matrix.rotateM(pm, 0, heading, 0f, 1f, 0f)
        if (swayDeg != 0f) { Matrix.rotateM(pm, 0, swayDeg, 0f, 0f, 1f); Matrix.rotateM(pm, 0, swayDeg * 0.5f, 1f, 0f, 0f) }
        Matrix.scaleM(pm, 0, k, k, k)
        setModel(pm)
        GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, chuteTex)
        GL.glBindVertexArray(m.vao); GL.glDrawElements(GL.GL_TRIANGLES, m.count, GL.GL_UNSIGNED_INT, 0)
        GL.glBindVertexArray(cube!!.vao)
    }

    /** Your own character in freefall / under the canopy, seen from behind like the HTML: skate stance + board, head-first dive, parachute hang. */
    private fun drawPlayerAir() {
        if (!(chars.ready && chars.has(playerChar))) return
        val av = playerAv
        Matrix.setIdentityM(cbase, 0)
        Matrix.translateM(cbase, 0, pos[0] + av.offX, pos[1] + av.offY, pos[2] + av.offZ)
        Matrix.rotateM(cbase, 0, degF(av.rotY), 0f, 1f, 0f)
        Matrix.rotateM(cbase, 0, degF(av.rotX), 1f, 0f, 0f)
        Matrix.rotateM(cbase, 0, degF(av.rotZ), 0f, 0f, 1f)
        Matrix.scaleM(cbase, 0, CharRig.SCALE, CharRig.SCALE, CharRig.SCALE)
        chars.draw(playerChar, vp, cbase, camX, camY, camZ, fogK, av.cp, wear = Wear.id)
        GL.glUseProgram(prog)
        GL.glBindVertexArray(cube!!.vao)
        drawSkateboard(cbase, av)
        if (airState == 2) drawChute(pos[0], pos[1] + 1.0f, pos[2], (chuteT / 0.9f).coerceIn(0f, 1f), degF(-bodyYaw), 1f, 2.5f * sin(clock * 1.7f))
    }

    /** The wooden loot box (dxMakeCrate): body, lid, orange straps, dark green side bands; ring on the ground + light beam while it still holds items. */
    private fun drawCrates(ex: Float, ez: Float) {
        if (crates.isEmpty()) return
        GL.glBindVertexArray(cube!!.vao)
        for (c in crates) {
            if (c.scale < 0.01f) continue
            val dx = c.x - ex; val dz = c.z - ez
            if (dx * dx + dz * dz > 130f * 130f) continue
            Matrix.setIdentityM(base, 0); Matrix.translateM(base, 0, c.x, c.y, c.z)
            Matrix.rotateM(base, 0, degF(c.yaw), 0f, 1f, 0f); Matrix.scaleM(base, 0, c.scale, c.scale, c.scale)
            part(0f, 0.28f, 0f, 0f, 0f, 1.0f, 0.56f, 0.68f, 0xFF6E5A38.toInt())       // body (wood)
            part(0f, 0.6f, 0f, 0f, 0f, 1.06f, 0.1f, 0.74f, 0xFF8D7549.toInt())        // lid
            part(0f, 0.3f, 0.35f, 0f, 0f, 1.02f, 0.09f, 0.02f, 0xFFFFAC78.toInt())    // orange straps
            part(0f, 0.3f, -0.35f, 0f, 0f, 1.02f, 0.09f, 0.02f, 0xFFFFAC78.toInt())
            part(-0.3f, 0.3f, 0f, 0f, 0f, 0.08f, 0.58f, 0.7f, 0xFF43594D.toInt())     // dark green side bands
            part(0.3f, 0.3f, 0f, 0f, 0f, 0.08f, 0.58f, 0.7f, 0xFF43594D.toInt())
        }
        var any = false
        for (c in crates) if (c.fx) { any = true; break }
        if (!any) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        val uA = GL.glGetUniformLocation(prog, "uAlpha")
        val rm = ringMesh; val wl = wall
        for (c in crates) {
            if (!c.fx) continue
            val dx = c.x - ex; val dz = c.z - ez
            if (dx * dx + dz * dz > 130f * 130f) continue
            if (rm != null) {   // RingGeometry(.85, .97) flat on the ground, opacity .85
                Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, c.x, c.y + 0.05f, c.z); setModel(mm)
                GL.glBindVertexArray(rm.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.675f, 0.471f); GL.glUniform1f(uA, 0.85f)
                GL.glDrawElements(GL.GL_TRIANGLES, rm.count, GL.GL_UNSIGNED_INT, 0)
            }
            if (wl != null) {   // CylinderGeometry(.05, .05, 3.2) light beam, opacity .35
                Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, c.x, c.y + 0.3f, c.z); Matrix.scaleM(mm, 0, 0.05f, 3.2f, 0.05f); setModel(mm)
                GL.glBindVertexArray(wl.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.675f, 0.471f); GL.glUniform1f(uA, 0.35f)
                GL.glDrawElements(GL.GL_TRIANGLES, wl.count, GL.GL_UNSIGNED_INT, 0)
            }
        }
        GL.glUniform1f(uA, 1f); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        GL.glBindVertexArray(cube!!.vao)
    }

    /** Soft contact shadow under every fighter on foot (contact() of the reference pack): 55 m range, fades with distance, a little smaller while crouching. */
    private fun drawContactShadows() {
        val bm = blobMesh ?: return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        GL.glEnable(GL.GL_POLYGON_OFFSET_FILL); GL.glPolygonOffset(-2f, -2f)
        GL.glBindVertexArray(bm.vao); GL.glUniform1i(uMode, 5); GL.glUniform3f(uColor, 0f, 0f, 0f)
        val uA = GL.glGetUniformLocation(prog, "uAlpha")
        if (playerAlive && airState == 3 && true) blobAt(bm, uA, pos[0], pos[1], pos[2], crouchAmt)
        for (b in botSys.bots) {
            if (!b.alive || b.air != 3) continue
            blobAt(bm, uA, b.x, b.y, b.z, 0f)
        }
        GL.glUniform1f(uA, 1f)
        GL.glDisable(GL.GL_POLYGON_OFFSET_FILL); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        GL.glBindVertexArray(cube!!.vao)
    }

    private fun blobAt(bm: GlMesh, uA: Int, x: Float, y: Float, z: Float, cr: Float) {
        val dx = x - camX; val dy = y - camY; val dz = z - camZ
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d >= 55f) return
        val fade = (1.4f - d / 55f).coerceIn(0f, 1f)
        val s = 0.605f * (1f - 0.2f * cr)
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y + 0.05f, z); Matrix.scaleM(mm, 0, s, 1f, s)
        setModel(mm)
        GL.glUniform1f(uA, 0.55f * fade)
        GL.glDrawElements(GL.GL_TRIANGLES, bm.count, GL.GL_UNSIGNED_INT, 0)
    }

    /** Reload: the empty magazine drops out of the gun (dropMag of the reference pack): falls with 0.8 m/s, tumbles, bounces, rests, shrinks away after 5 s. */
    private fun dropMag() {
        val cp = cos(pitch); val sp = sin(pitch)
        val fx = sin(yaw) * cp; val fy = sp; val fz = -cos(yaw) * cp
        val rx = cos(yaw); val rz = sin(yaw)
        val ux = -sin(yaw) * sp; val uy = cp; val uz = cos(yaw) * sp
        val ox = pos[0]; val oy = pos[1] + MapData.EYE_H - eyeDrop; val oz = pos[2]
        val px = ox + rx * 0.17f + fx * 0.4f - ux * 0.3f
        val py = oy + fy * 0.4f - uy * 0.3f
        val pz = oz + rz * 0.17f + fz * 0.4f - uz * 0.3f
        val side = (rnd.nextFloat() - 0.5f) * 0.6f
        mags.add(MagDrop(px, py, pz, -ux * 0.8f + rx * side, -uy * 0.8f, -uz * 0.8f + rz * side,
            (rnd.nextFloat() - 0.5f) * 10f, (rnd.nextFloat() - 0.5f) * 6f, (rnd.nextFloat() - 0.5f) * 10f,
            rnd.nextFloat() * 6f, rnd.nextFloat() * 6f, rnd.nextFloat() * 6f))
        while (mags.size > 8) mags.removeAt(0)
    }

    private fun stepMags(dt: Float) {
        var i = mags.size - 1
        while (i >= 0) {
            val m = mags[i]; m.life -= dt
            if (m.life <= 0f) { mags.removeAt(i); i--; continue }
            if (!m.rest) {
                m.vy -= 9.8f * dt; m.x += m.vx * dt; m.y += m.vy * dt; m.z += m.vz * dt
                m.rx += m.wx * dt; m.ry += m.wy * dt; m.rz += m.wz * dt
                val fy = map.footY(m.x, m.z, m.y + 0.35f) + 0.05f
                if (m.y < fy) {
                    m.y = fy
                    if (kotlin.math.abs(m.vy) > 0.8f) { m.vy *= -0.34f; m.vx *= 0.62f; m.vz *= 0.62f; m.wx *= 0.55f; m.wy *= 0.55f; m.wz *= 0.55f }
                    else { m.rest = true; m.vx = 0f; m.vy = 0f; m.vz = 0f }
                }
            }
            i--
        }
    }

    private fun drawMags() {
        if (mags.isEmpty()) return
        GL.glBindVertexArray(cube!!.vao)
        for (m in mags) {
            val sc = if (m.life < 0.6f) max(0.001f, m.life / 0.6f) else 1f
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, m.x, m.y, m.z)
            Matrix.rotateM(mm, 0, degF(m.rx), 1f, 0f, 0f); Matrix.rotateM(mm, 0, degF(m.ry), 0f, 1f, 0f); Matrix.rotateM(mm, 0, degF(m.rz), 0f, 0f, 1f)
            Matrix.scaleM(mm, 0, 0.03f * sc, 0.13f * sc, 0.07f * sc)
            setModel(mm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.145f, 0.157f, 0.173f)   // dark metal #25282c
            GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
        }
    }

    /** the player's own rigged character, seen from behind in third-person view (same rifle pose as the bots) */
    private fun drawPlayerBody() {
        if (!(chars.ready && chars.has(playerChar))) return
        // reference pack: idle breathing (the body rises and falls 7 mm while you stand still), breathing sway in the rifle arms, the head follows the aim pitch
        Sway.calc(clock, 0f, if (aiming) 0.4f else 1f, swayTmp)
        val idleBob = if (footSpeed < 0.3f && onGround) sin(clock * 1.8f) * 0.007f else 0f
        val sa = if (winCam || airState != 3 || !playerAlive) 0f else swimAmt   // swimming pose 0..1: body lies on the water, back just above the surface
        Matrix.setIdentityM(cbase, 0); Matrix.translateM(cbase, 0, pos[0], pos[1] + idleBob + 1.04f * sa, pos[2])
        Matrix.rotateM(cbase, 0, Math.toDegrees((-bodyYaw).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(cbase, 0, CharRig.SCALE, CharRig.SCALE, CharRig.SCALE)
        cpose.mode = if (sa > 0.05f) CharPose.RELAXED else if (fists) CharPose.BOX else CharPose.RIFLE; cpose.walk = if (sa > 0.05f) swimPh else walkPhase; cpose.speed = footSpeed; cpose.sprint = run && footSpeed > 3f; cpose.runW = runBlend
        cpose.time = clock; cpose.punchKind = punchKind; cpose.punchU = if (fists && punchT > 0f) (1f - punchT / punchDur).coerceIn(0f, 1f) else -1f   // boxing: stance + the running punch
        cpose.crouch = crouchAmt; cpose.prone = if (winCam) 0f else max(proneAmt, sa); cpose.supine = if (winCam) 0f else supineAmt * (1f - sa); cpose.swim = sa; cpose.aimPitch = (if (winCam) 0f else pitch).coerceIn(-0.8f, 0.8f) + swayTmp[0] * 0.7f; cpose.headYaw = 0f; cpose.headPitch = if (winCam) 0f else (pitch * 0.5f).coerceIn(-0.42f, 0.42f)
        cpose.jumpT = jumpAnim.t; cpose.jumpW = jumpAnim.w; cpose.jumpDrop = jumpAnim.drop   // jump / landing animation (JumpAnim.kt)
        cpose.gunId = if (fists) null else guns[gi].id   // per-gun hold: stock on the shoulder, hands on the grip + handguard (GunHold)
        // pistol animation pack: clip choice / cross-fades / recoil of the player's body. Prone, swimming, jumping and fists fall back to the old animation (baked = false fades the pack out).
        val gcls = guns[gi].cls
        val longGun = gcls == "shotgun" || gcls == "dmr" || gcls == "sniper" || gcls == "amr"
        val packOn = PistolAnim.ready && !fists && !winCam && sa < 0.05f && proneAmt < 0.05f && jumpAnim.w < 0.05f && pickU < 0f
        val reloadingNow = reloadLeft > 0f && reloadProgress >= 0f
        // v56: ONLY the pistols use the one-hand pistol pack; every other gun gets the two-hand rifle animation (both hands carry / run / fire, left hand pulls the magazine on reload)
        if (!fists) gunGl[guns[gi].id]?.let { paPlayer.setGun(guns[gi].id, it.second.zmin, it.second.my, it.second.len) }
        paPlayer.update(clock, footSpeed, crouchAmt, aiming || firing || flash > 0f || adsAmount > 0.05f, adsAmount > 0.5f || aiming, flash > 0f,
            if (reloadingNow) reloadProgress.coerceIn(0f, 1f) else -1f, bodyYaw, longGun, packOn, guns[gi].pistol && !fists, walkPhase, runBlend)
        cpose.pa = paPlayer
        cpose.leanRoll = if (winCam) 0f else -leanAmt * leanRatio * Lean.BODY_ROLL   // v44: the 3rd-person body leans into the peek
        if (pickU >= 0f && !winCam && sa < 0.05f) { cpose.pickup = pickBend; cpose.pickReach = PickupAnim.reach(pickU); cpose.pickBag = PickupAnim.bag(pickU); PickupAnim.hand(pickU, pkLoc); cpose.pickTx = pkLoc[0]; cpose.pickTy = pkLoc[1]; cpose.pickTz = pkLoc[2] }   // loot pick-up: bend, reach, backpack
        chars.draw(playerChar, vp, cbase, camX, camY, camZ, fogK, cpose, wear = Wear.id)
        cpose.pickup = 0f; cpose.pickReach = 0f; cpose.pickBag = 0f; cpose.leanRoll = 0f
        cpose.pa = null
        val gf = cpose.gunFrame
        cpose.jumpT = -1f; cpose.jumpW = 0f; cpose.jumpDrop = 0f; cpose.prone = 0f; cpose.supine = 0f; cpose.swim = 0f; cpose.punchU = -1f   // the pose object is shared with the bots: never leave jump / prone / punch on
        GL.glUseProgram(prog)
        GL.glBindVertexArray(cube!!.vao)
        if (!fists && sa < 0.05f && cpose.hasGun) { // third-person + WINNER pose: the real 3D gun, attached to the hands (gunFrame)
            val swpB = swapProg(); val outB2 = swpB >= 0f && swpB < PickupAnim.SWAP_OUT
            val shownB = if (outB2) swapFrom else gi
            val wg = guns[if (shownB >= 0) shownB else gi]; val gm = gunGl[wg.id]; val gt = tex[wg.id]
            if (shownB >= 0 && gm != null && (!gm.second.textured || gt != null)) {
                System.arraycopy(cbase, 0, pm, 0, 16)
                Matrix.multiplyMM(tmpM, 0, cbase, 0, gf, 0); System.arraycopy(tmpM, 0, pm, 0, 16)
                Matrix.translateM(pm, 0, GunHold.OX, GunHold.OY, GunHold.oz(wg.id))
                val swDr = PickupAnim.swapDrop(swpB)   // gun change: the gun is lowered / swung up
                if (swDr > 0.001f) { Matrix.translateM(pm, 0, 0.10f * swDr, -0.42f * swDr, 0.14f * swDr); Matrix.rotateM(pm, 0, -32f * swDr, 1f, 0f, 0f) }
                // old reload dip only without the pistol pack: with the pack the gun already follows the hands / clip, so the extra dip would double the motion
                // fades with the pack: bakedW = 1 -> no dip at all, bakedW = 0 (prone / swim / jump / pack missing) -> the old full dip
                val dipK = if (PistolAnim.ready) 1f - paPlayer.bakedW else 1f
                val rlP = if (reloadLeft > 0f && reloadProgress >= 0f) sin(reloadProgress * Math.PI.toFloat()) * dipK else 0f
                Matrix.rotateM(pm, 0, -rlP * 28f, 1f, 0f, 0f)   // dips while reloading
                Matrix.rotateM(pm, 0, rlP * 14f, 0f, 0f, 1f)
                Matrix.scaleM(pm, 0, 0.85f, 0.85f, 0.85f)
                setModel(pm)
                if (gm.second.textured) { GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, gt!!) } else GL.glUniform1i(uMode, 1)
                GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
                GL.glBindVertexArray(cube!!.vao)
                if (paPlayer.rifle && paPlayer.magHeld && paPlayer.bakedW > 0.9f) { // v56 rifle reload: the magazine that the left hand pulls out / pushes in (same block as the first-person view)
                    val mr = paPlayer.rig; val mc = paPlayer.magC
                    System.arraycopy(pm, 0, mm, 0, 16)
                    Matrix.translateM(mm, 0, mc[0], mc[1], mc[2])
                    if (mr.dirY > 0f) Matrix.scaleM(mm, 0, 0.03f, 0.05f, 0.2f) else Matrix.scaleM(mm, 0, 0.034f, mr.magH, 0.05f)
                    setModel(mm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.165f, 0.176f, 0.192f)
                    GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
                }
                if (flash > 0f && !winCam) { // muzzle flash at the barrel tip
                    Matrix.translateM(pm, 0, 0f, gm.second.my, gm.second.zmin - 0.07f); Matrix.scaleM(pm, 0, 0.065f, 0.065f, 0.09f)   // v63: round flash (was a square box)
                    setModel(pm); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.85f, 0.4f)
                    val fsp = sphere
                    if (fsp != null) { GL.glBindVertexArray(fsp.vao); GL.glDrawElements(GL.GL_TRIANGLES, fsp.count, GL.GL_UNSIGNED_INT, 0); GL.glBindVertexArray(cube!!.vao) }
                }
            }
        }
    }

    private var lootS = 1f; private var lootCx = 0f; private var lootCy = 0f; private var lootCz = 0f
    private val lsA = FloatArray(16); private val lsB = FloatArray(16)
    private fun setModel(m0: FloatArray) {
        var m = m0
        if (lootS != 1f) {   // an item that is carried in the hand is shrunk round its own centre
            Matrix.setIdentityM(lsA, 0); Matrix.translateM(lsA, 0, lootCx, lootCy, lootCz); Matrix.scaleM(lsA, 0, lootS, lootS, lootS); Matrix.translateM(lsA, 0, -lootCx, -lootCy, -lootCz)
            Matrix.multiplyMM(lsB, 0, lsA, 0, m0, 0); m = lsB
        }
        GL.glUniformMatrix4fv(uModel, 1, false, m, 0)
        nm[0] = m[0]; nm[1] = m[1]; nm[2] = m[2]; nm[3] = m[4]; nm[4] = m[5]; nm[5] = m[6]; nm[6] = m[8]; nm[7] = m[9]; nm[8] = m[10]
        GL.glUniformMatrix3fv(uNM, 1, false, nm, 0)
    }

    /** v63: a round flat disc (bullet hole) of [rad] m lying on the surface at the mark, facing along the surface normal; [lift] keeps it just above the surface (no flicker). */
    private fun drawDecal(k: Mark, rad: Float, r: Float, g: Float, b: Float, lift: Float) {
        val wm = wheelMesh ?: return
        var nx = k.nx; var ny = k.ny; var nz = k.nz
        val nl = sqrt(nx * nx + ny * ny + nz * nz); if (nl < 1e-4f) { nx = 0f; ny = 1f; nz = 0f } else { nx /= nl; ny /= nl; nz /= nl }
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, k.x + nx * lift, k.y + ny * lift, k.z + nz * lift)
        // turn the disc's axis (Y) into the surface normal
        if (ny < -0.9999f) Matrix.rotateM(mm, 0, 180f, 1f, 0f, 0f)
        else if (ny < 0.9999f) {
            val ax = nz; val az = -nx   // Y x N
            Matrix.rotateM(mm, 0, Math.toDegrees(kotlin.math.acos(ny.coerceIn(-1f, 1f)).toDouble()).toFloat(), ax, 0f, az)
        }
        Matrix.scaleM(mm, 0, rad, 0.004f, rad)
        setModel(mm)
        GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, r, g, b)   // flat colour: a decal must not be lit like a lump
        GL.glBindVertexArray(wm.vao); GL.glDrawElements(GL.GL_TRIANGLES, wm.count, GL.GL_UNSIGNED_INT, 0)
    }

    /** v63: a ROUND (cylinder) streak from the rotation that [pm] already holds: length [len] along local Z, radius [rad]. Bullets are round, not square beams. */
    private fun drawRoundStreak(len: Float, rad: Float, r: Float, g: Float, b: Float) {
        val wm = wheelMesh ?: return
        Matrix.rotateM(pm, 0, 90f, 1f, 0f, 0f)        // cylinder axis Y -> Z
        Matrix.scaleM(pm, 0, rad, len, rad)
        setModel(pm)
        GL.glBindVertexArray(wm.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, r, g, b)
        GL.glDrawElements(GL.GL_TRIANGLES, wm.count, GL.GL_UNSIGNED_INT, 0)
    }

    /** v63: round flat disc lying on the ground (blood stain). */
    private fun drawFlatDisc(x: Float, y: Float, z: Float, rad: Float, r: Float, g: Float, b: Float) {
        val wm = wheelMesh ?: return
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y + 0.01f, z); Matrix.scaleM(mm, 0, rad, 0.012f, rad)
        setModel(mm)
        GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, r, g, b)
        GL.glBindVertexArray(wm.vao); GL.glDrawElements(GL.GL_TRIANGLES, wm.count, GL.GL_UNSIGNED_INT, 0)
    }

    /** v63: a round brass case / shotgun shell (small cylinder lying along X). */
    private fun drawCasing(x: Float, y: Float, z: Float, len: Float, rad: Float, r: Float, g: Float, b: Float) {
        val wm = wheelMesh ?: return
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y, z); Matrix.rotateM(mm, 0, 90f, 0f, 0f, 1f); Matrix.scaleM(mm, 0, rad, len, rad)
        setModel(mm)
        GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, r, g, b)
        GL.glBindVertexArray(wm.vao); GL.glDrawElements(GL.GL_TRIANGLES, wm.count, GL.GL_UNSIGNED_INT, 0)
    }

    /** v63: tiny round chip / droplet (sphere) instead of a square cube. */
    private fun drawBall(x: Float, y: Float, z: Float, rad: Float, r: Float, g: Float, b: Float) {
        val sp = sphere ?: return
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y, z); Matrix.scaleM(mm, 0, rad, rad, rad)
        setModel(mm)
        GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, r, g, b)
        GL.glBindVertexArray(sp.vao); GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
    }

    private fun drawCube(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float, mode: Int = 2) {
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y, z); Matrix.scaleM(mm, 0, sx, sy, sz)
        setModel(mm)
        GL.glUniform1i(uMode, mode); GL.glUniform3f(uColor, r, g, b)
        GL.glBindVertexArray(cube!!.vao); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime(); val dt = ((now - last) / 1e9f).coerceAtMost(0.1f); last = now; val dt0 = dt
        step(dt)
        if (glReady && framesShown < 1000) framesShown++
        frames++; if (now - fpsT > 1_000_000_000L) { fps = frames; frames = 0; fpsT = now }
        posText = String.format(java.util.Locale.US, "X %.0f  Y %.1f  Z %.0f", pos[0], pos[1], pos[2])

        val gv = guns[gi]
        val targetFov = if (!fists && scopeSel >= 2) (if (lensFailed) scopeFov(scopeSel) else 70f) else aimFov(scopeSel)   // v58: a scope zooms only inside its lens, the rest of the screen keeps the normal view
        val airborne = airState != 3
        val aglNow = if (airborne) (if (airState == 0) AIR_Y else max(0f, pos[1] - map.ground(pos[0], pos[2]))) else 0f
        val introOn = airState == 0 && !spec && flightTime < PLANE_FLY_IN + PLANE_BLEND
        val introB = smooth((flightTime - PLANE_FLY_IN) / PLANE_BLEND)   // 0 during the fly-in, 0 -> 1 in the short blend to the chase camera
        val introF0 = 58f - 10f * smooth(1.1f)
        val introFov = if (flightTime < PLANE_FLY_IN) 58f - 10f * smooth(flightTime / PLANE_FLY_IN * 1.1f) else introF0 + (76f - introF0) * introB
        val fov = if (introOn) introFov else if (spec) 68f else if (airborne) (if (airState == 0) 76f else if (airState == 1) (if (skateMode) 77f else 83f) else 74f) else 70f + (targetFov - 70f) * adsAmount
        val nearP = if (introOn) 1f else if (airborne && aglNow > 30f) min(8f, max(0.6f, aglNow * 0.018f)) else 0.05f
        Matrix.perspectiveM(proj, 0, fov, surfW.toFloat() / surfH, nearP, if (airborne && aglNow > 30f) 7000f else 2500f)
        // clear sky in the plane / freefall, fog fades back in near the ground (like the HTML)
        val fogTarget = if (airState == 0) 8f else if (airborne) { val u = ((aglNow - 30f) / 110f).coerceIn(0f, 1f); 1f + 7f * u * u * (3f - 2f * u) } else 1f
        fogK += (fogTarget - fogK) * min(1f, dt0 * 4f)
        weather.update(dt0)
        val cp = cos(pitch)
        var ex = pos[0]; var ey = pos[1] + MapData.EYE_H - eyeDrop; var ez = pos[2]
        if (airState == 0) { // chase camera around the plane: 26 m behind the look direction, 2.2 m to the right
            val ddx = sin(yaw) * cp; val ddy = sin(pitch); val ddz = -cos(yaw) * cp
            ex = planeX(flightTime) - ddx * 26f + cos(yaw) * 2.2f; ey = AIR_Y - ddy * 26f; ez = planeZ(flightTime) - ddz * 26f + sin(yaw) * 2.2f
        }
        // Phase 28: freefall + parachute are seen from behind like the HTML (updateCamera: 10 m boom, 8 m in skate stance, 0.8 m to the right)
        val airTp = (airState == 1 || airState == 2) && playerAlive && chars.ready && chars.has(playerChar)
        if (airTp) {
            val lenA = if (airState == 1 && skateMode) 8f else 10f
            val adx = sin(yaw) * cp; val ady = sin(pitch); val adz = -cos(yaw) * cp
            ex = pos[0] - adx * lenA + cos(yaw) * 0.8f; ey = pos[1] + 1.68f - ady * lenA; ez = pos[2] - adz * lenA + sin(yaw) * 0.8f
            val gyA = map.ground(ex, ez) + 0.5f
            if (ey < gyA) ey = gyA
        }
        if (airState == 3 && playerAlive) { // camera behind the player, pulled in by walls / terrain
            val hx = pos[0]; val hy = pos[1] + MapData.EYE_H * 0.94f - eyeDrop; val hz = pos[2]
            val ddx = sin(yaw) * cp; val ddy = sin(pitch); val ddz = -cos(yaw) * cp
            // v61: ONLY third person. While aiming (red dot / scope) the camera does NOT go into the eyes any more: it moves closer, over the right shoulder
            // (boom 3.1 m -> 1.9 m, 0.55 m -> 0.72 m to the side), so the body, the gun and the reload stay visible and the crosshair sits next to the gun.
            val ka = adsAmount.coerceIn(0f, 1f)
            val boom = 3.1f - 1.2f * ka; val side = 0.55f + 0.17f * ka
            val bx = -ddx * boom + cos(yaw) * side; val by = -ddy * boom + 0.3f; val bz = -ddz * boom + sin(yaw) * side   // v48: 3.1 m behind -> the 1.78 m character fills ~41 % of the screen height (Free Fire 35 - 45 %)
            val bl = sqrt(bx * bx + by * by + bz * bz)
            val th = map.rayWorld(hx, hy, hz, bx / bl, by / bl, bz / bl, bl)
            val f = if (th >= 0f && th < bl) max(0.3f, th - 0.25f) / bl else 1f
            ex = hx + bx * f; ey = hy + by * f; ez = hz + bz * f
            val gy3 = map.ground(ex, ez) + 0.4f
            if (ey < gy3) ey = gy3
        }
        var lkx = ex + sin(yaw) * cp; var lky = ey + sin(pitch); var lkz = ez - cos(yaw) * cp
        if (winCam) { // WINNER: low camera in front of the player, slowly swinging left / right, looking up at the character
            val wt = winT; val e0 = easeS(wt / 1.2f)
            val ang = bodyYaw + 0.55f * sin(wt * 0.55f) * e0
            val dist = 4.6f - 1.2f * e0
            val hx = pos[0]; val hy = pos[1] + 1.2f; val hz = pos[2]
            val bx = sin(ang) * dist; val bz = -cos(ang) * dist; val by = -0.25f + 0.3f * (1f - e0)
            val bl = sqrt(bx * bx + by * by + bz * bz)
            val th = map.rayWorld(hx, hy, hz, bx / bl, by / bl, bz / bl, bl)
            val f = if (th >= 0f && th < bl) max(0.3f, th - 0.25f) / bl else 1f
            ex = hx + bx * f; ey = hy + by * f; ez = hz + bz * f
            val gyW = map.ground(ex, ez) + 0.35f
            if (ey < gyW) ey = gyW
            lkx = pos[0]; lky = pos[1] + 1.35f; lkz = pos[2]
        }
        if (introOn) { // aircraft intro: fixed side camera, plane flies in from behind, then a short blend into the chase camera
            val rx = -fdz; val rz = fdx
            val rpx = planeX(flightTime); val rpz = planeZ(flightTime)
            if (flightTime < PLANE_FLY_IN) {
                val x = flightTime / PLANE_FLY_IN; val e = 1f - (1f - x) * (1f - x) * (1f - x); val q = (1f - e) * (1f - e)
                val ppx = rpx - fdx * 130f * (1f - e) - rx * 6f * q; val ppz = rpz - fdz * 130f * (1f - e) - rz * 6f * q   // v57: the plane comes from just behind and passes right OVER the camera
                ex = rpx + rx * 4f - fdx * 40f; ey = AIR_Y - 16f; ez = rpz + rz * 4f - fdz * 40f
                lkx = ppx; lky = AIR_Y + 10f * q + 1f; lkz = ppz
            } else {   // v45: no second animation - the side camera simply blends into the chase camera
                val w = introB
                val ivx = rpx + rx * 4f - fdx * 40f; val ivy = AIR_Y - 16f; val ivz = rpz + rz * 4f - fdz * 40f
                ex = ivx + (ex - ivx) * w; ey = ivy + (ey - ivy) * w; ez = ivz + (ez - ivz) * w
                lkx = rpx + (lkx - rpx) * w; lky = AIR_Y + 1f + (lky - AIR_Y - 1f) * w; lkz = rpz + (lkz - rpz) * w
            }
        }
        if (spec) {
            specCamera(dt0)
            ex = specCam[0]; ey = specCam[1]; ez = specCam[2]; lkx = specCam[3]; lky = specCam[4]; lkz = specCam[5]
        }
        // v44: LEAN moves + rolls the camera, SUPPRESSION makes the view tremble (view only, the aim is not touched)
        var upx = 0f; var upy = 1f; var upz = 0f
        shkX = 0f; shkY = 0f; shkZ = 0f
        if (airState == 3 && playerAlive && !winCam && !spec && !introOn) {
            if (leanOffX != 0f || leanOffZ != 0f) { ex += leanOffX; ez += leanOffZ; lkx += leanOffX; lkz += leanOffZ }
            val rl = leanAmt * leanRatio * Lean.ROLL
            if (abs(rl) > 1e-4f) { upx = cos(yaw) * sin(rl); upy = cos(rl); upz = sin(yaw) * sin(rl) }
            val sh = suppress * 0.028f
            if (sh > 0.0005f) { val s1 = sin(clock * 53f); val s2 = sin(clock * 61f + 1.3f); lkx += cos(yaw) * s1 * sh; lkz += sin(yaw) * s1 * sh; lky += s2 * sh; shkX = cos(yaw) * s1 * sh; shkZ = sin(yaw) * s1 * sh; shkY = s2 * sh }
        }
        val wlv = map.waterLevelAt(ex, ez); underwater = airState == 3 && wlv > -900f && ey < wlv
        camX = ex; camY = ey; camZ = ez
        Matrix.setLookAtM(view, 0, ex, ey, ez, lkx, lky, lkz, upx, upy, upz)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // real-time shadows: depth pass from the sun around the camera (not in the plane / high freefall)
        shadowDraw = false
        if (Prefs.shadows && shadow.ready && (!airborne || aglNow < 120f)) {
            when (Prefs.gfx) { "high" -> shadow.ensure(2048, 115f); "low" -> shadow.ensure(1024, 80f); else -> shadow.ensure(1024, 100f) }
            if (shadow.ready) { renderShadows(ex, map.ground(ex, ez), ez); shadowDraw = true }
        }
        // post-processing (bloom + AO): the scene goes into an off-screen target, PostFx composites it to the screen at the end of the frame
        val farP = if (airborne && aglNow > 30f) 7000f else 2500f
        val useAo = Prefs.ao && Prefs.gfx == "high" && postLevel >= 2 && !airborne && scopeAnim < 0.02f
        val useBloom = Prefs.bloom && Prefs.gfx != "low" && postLevel >= 1
        val usePost = (useAo || useBloom) && post.ensure(surfW, surfH)
        if (usePost) post.begin()
        // auto performance: shed AO first, then bloom, when the frame time stays high (never comes back inside the same match)
        if (usePost && clock > 8f) {
            frameAvg += (dt0 - frameAvg) * min(1f, dt0 * 0.6f)
            if (frameAvg > 0.032f) slowT += dt0 else slowT = max(0f, slowT - dt0)
            if (slowT > 2.5f) { postLevel = if (useAo) 1 else 0; slowT = 0f; frameAvg = 0.02f }
        }
        fun scenePass(lens: Boolean) {   // v58: the whole world is drawn by this function; called a second time (lens = true) into the scope target
        GL.glClearColor(weather.skyR, weather.skyG, weather.skyB, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        GL.glUseProgram(prog)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uShOn"), if (shadowDraw) 1f else 0f)
        GL.glUniform1i(GL.glGetUniformLocation(prog, "uShMap"), 1)
        if (shadowDraw) {
            shadow.bindForSampling()
            GL.glUniformMatrix4fv(GL.glGetUniformLocation(prog, "uShVP"), 1, false, shadow.lightVP, 0)
            GL.glUniform1f(GL.glGetUniformLocation(prog, "uShTex"), 1f / shadow.size)
        }
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(prog, "uVP"), 1, false, vp, 0)
        GL.glUniform3f(GL.glGetUniformLocation(prog, "uEye"), ex, ey, ez)
        GL.glUniform1i(GL.glGetUniformLocation(prog, "uTex"), 0)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uFog"), fogK * (if (airborne) 1f else weather.fogMul))
        GL.glUniform1f(uTreeOn, 0f); GL.glUniform1f(uTimeL, weather.time); GL.glUniform1f(uWindL, weather.wind)
        GL.glUniform3f(uSkyC, weather.skyR, weather.skyG, weather.skyB); GL.glUniform1f(uSkyAmt, 1f); GL.glUniform1f(uWetL, weather.wet)
        GL.glUniform1f(uPudL, weather.pud); GL.glUniform1f(uRainL, min(1f, weather.rain))
        GL.glUniform1f(uSunDimL, weather.sunDim); GL.glUniform1f(uHemiDimL, weather.hemiDim); GL.glUniform1f(uFlashL, min(1f, weather.flash))
        GL.glDisable(GL.GL_CULL_FACE)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        setModel(ident)

        val sc = map.seaColor
        GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, ((sc shr 16) and 255) / 255f, ((sc shr 8) and 255) / 255f, (sc and 255) / 255f)
        // the flat sea follows the eye (snapped to 100 m), so there is water in every direction, however far you swim
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, Math.round(ex / 100f) * 100f, 0f, Math.round(ez / 100f) * 100f); setModel(mm)
        GL.glBindVertexArray(seaMesh!!.vao); GL.glDrawElements(GL.GL_TRIANGLES, 6, GL.GL_UNSIGNED_INT, 0)
        setModel(ident)

        val uRoadOn = GL.glGetUniformLocation(prog, "uRoadOn")
        val uDeckOn = GL.glGetUniformLocation(prog, "uDeckOn")
        GL.glUniform1i(GL.glGetUniformLocation(prog, "uRoad"), 2)
        GL.glUniform4f(GL.glGetUniformLocation(prog, "uRoadXf"), roadXf[0], roadXf[1], roadXf[2], roadXf[3])
        if (roadTex != 0) { GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, roadTex); GL.glActiveTexture(GL.GL_TEXTURE0) }
        for (m in layers) {
            GL.glUniform1f(uRoadOn, 0f); GL.glUniform1f(uDeckOn, if (m.mat == "bridge") 1f else 0f)
            when (m.mat) {
                "terrain", "atlas", "bridge" -> {
                    GL.glUniform1i(uMode, if (m.mat == "terrain") 6 else 0); GL.glActiveTexture(GL.GL_TEXTURE0)
                    GL.glBindTexture(GL.GL_TEXTURE_2D, tex[if (m.mat == "terrain") "terrain" else "atlas"]!!)
                    if (m.mat == "terrain" && roadTex != 0) {   // black asphalt + white dashed centre line painted over the terrain
                        GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, roadTex); GL.glActiveTexture(GL.GL_TEXTURE0)
                        GL.glUniform1f(uRoadOn, 1f)
                    }
                }
                "house" -> GL.glUniform1i(uMode, 1)
                "road" -> {   // PHASE 67: the road mesh itself carries the white dashed centre line (before it was flat black and hid the dashes painted on the terrain below)
                    GL.glUniform1i(uMode, 2)
                    if (roadTex != 0) { GL.glUniform3f(uColor, 0.045f, 0.045f, 0.05f); GL.glUniform1f(uRoadOn, 1f) } else GL.glUniform3f(uColor, 0.085f, 0.09f, 0.10f)   // Bijnor roads: dark asphalt
                }
                "roadb" -> { GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.20f, 0.21f, 0.22f) }
                "roadline" -> { GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.96f, 0.96f, 0.93f) }   // v57: white dashed centre line
                "water" -> { GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.14f, 0.55f, 0.92f) }   // lake / pond: clear blue (was dark teal)
                else -> GL.glUniform1i(uMode, 2)
            }
            val isRoadMesh = m.mat == "road" || m.mat == "roadb" || m.mat == "roadline"
            if (isRoadMesh) { GL.glEnable(GL.GL_POLYGON_OFFSET_FILL); if (m.mat == "roadline") GL.glPolygonOffset(-5f, -5f) else GL.glPolygonOffset(-2f, -2f) }
            GL.glBindVertexArray(m.mesh.vao); GL.glDrawElements(GL.GL_TRIANGLES, m.mesh.count, GL.GL_UNSIGNED_INT, 0)
            if (isRoadMesh) GL.glDisable(GL.GL_POLYGON_OFFSET_FILL)
        }
        GL.glUniform1f(uRoadOn, 0f); GL.glUniform1f(uDeckOn, 0f)
        drawBuildings(ex, ez)   // v54: every shop / school / hospital / bank / house is an instance of a template (enterable, stairs, roof)
        drawDyn()   // v30: door leaves + glass + shards

        trees?.let { tr ->   // all trees (never on roads / in towns), swaying with the wind
            GL.glUniform1f(uTreeOn, 1f)
            tr.draw(uMode, ex, ez, -view[2], -view[10], when (Prefs.gfx) { "high" -> 420f; "low" -> 220f; else -> 320f })
            GL.glUniform1f(uTreeOn, 0f)
        }

        drawLoot(ex, ez)
        drawCrates(ex, ez)
        drawDrops()
        // bots, tracers, bullet marks
        for (b in botSys.bots) drawBot(b)
        if (!lens && playerAlive && airState == 3) drawPlayerBody()
        if (airTp && !lens) drawPlayerAir()
        drawContactShadows()
        if (spec) drawPlayerCorpse()
        if (flightTime < flightEnd) drawPlane()
        for (t in botSys.tracers) drawTracer(t)
        for (b in slugs) drawSlug(b.x, b.y, b.z, b.vx, b.vy, b.vz, b.trav, b.gun.pellets > 1, true)
        for (b in botSys.slugs) drawSlug(b.x, b.y, b.z, b.vx, b.vy, b.vz, b.trav, false, false)
        for (k in marks) when (k.mat) {   // v63: ROUND flat hole that lies ON the surface (was a floating square cube)
            Impact.WOOD -> drawDecal(k, 0.055f, 0.22f, 0.12f, 0.05f, 0.012f)                                   // dark splintered wood
            Impact.DIRT -> drawDecal(k, 0.11f, 0.17f, 0.11f, 0.06f, 0.012f)                                     // dug-up earth
            else -> { drawDecal(k, 0.10f, 0.62f, 0.61f, 0.58f, 0.012f); drawDecal(k, 0.045f, 0.08f, 0.08f, 0.08f, 0.020f) }   // pale chipped plaster ring + round dark hole
        }

        for (g in nades) { drawCube(g.x, g.y, g.z, 0.24f, 0.28f, 0.24f, 0.4f, 0.5f, 0.42f); drawCube(g.x, g.y + 0.16f, g.z, 0.1f, 0.06f, 0.14f, 1f, 0.67f, 0.47f) }
        drawFx()
        drawBlood()
        drawBleedStains()
        drawShotFx()
        drawSwimFx()
        if (!lens) drawRain(dt0)
        drawZoneWall()
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uShOn"), 0f)   // the gun in your hands is not shadowed by the world
        GL.glBindVertexArray(0)
        }
        scenePass(false)
        // ---- v58 SCOPE LENS: second, zoomed picture of the same world, drawn into a square target and pasted as a round lens in the middle of the screen
        if (scopeAnim > 0.02f && scopeLensFrac > 0.02f && airState == 3 && playerAlive && !spec && !winCam && !fists) {
            val rPx = scopeLensFrac * surfH
            val maxSide = 0.92f * surfH
            val side = min(1024f, maxSide).toInt().coerceAtLeast(64)
            if (ensureLens(side)) {
                val kRes = side / maxSide
                val dPx = (2f * rPx * kRes).toInt().coerceIn(32, side)
                val tanMain = tan(Math.toRadians(fov.toDouble() * 0.5)).toFloat()
                val tanLens = (2f * rPx / surfH) * tanMain / scopeLensMag
                val lensFov = Math.toDegrees(2.0 * atan(tanLens.toDouble())).toFloat()
                GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, fbSaved, 0)
                System.arraycopy(proj, 0, projSave, 0, 16); System.arraycopy(vp, 0, vpSave, 0, 16)
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, lensFbo); GL.glViewport(0, 0, dPx, dPx)
                GL.glEnable(GL.GL_DEPTH_TEST); GL.glDepthMask(true)
                Matrix.perspectiveM(proj, 0, lensFov, 1f, nearP, farP); Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
                scenePass(true)
                System.arraycopy(projSave, 0, proj, 0, 16); System.arraycopy(vpSave, 0, vp, 0, 16)
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbSaved[0]); GL.glViewport(0, 0, surfW, surfH)
                // paste the round lens
                val k = 1f - scopeAnim.coerceIn(0f, 1f); val sa = 1f - k * k * k
                GL.glDisable(GL.GL_DEPTH_TEST); GL.glDepthMask(false); GL.glDisable(GL.GL_CULL_FACE)
                GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
                GL.glUseProgram(lensProg)
                GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, lensTex)
                GL.glUniform1i(GL.glGetUniformLocation(lensProg, "uT"), 0)
                GL.glUniform4f(GL.glGetUniformLocation(lensProg, "uBox"), 0f, 0f, rPx / (surfW * 0.5f), rPx / (surfH * 0.5f))
                GL.glUniform1f(GL.glGetUniformLocation(lensProg, "uScale"), dPx.toFloat() / side)
                GL.glUniform1f(GL.glGetUniformLocation(lensProg, "uAlpha"), sa)
                GL.glBindVertexArray(lensVao); GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4); GL.glBindVertexArray(0)
                GL.glBindTexture(GL.GL_TEXTURE_2D, 0)
                GL.glDisable(GL.GL_BLEND); GL.glDepthMask(true); GL.glEnable(GL.GL_DEPTH_TEST)
                GL.glUseProgram(prog)
            }
        }
        if (usePost) post.finish(nearP, farP, fov, useBloom, useAo)
    }

    private fun rgb(c: Int, f: (Float, Float, Float) -> Unit) = f(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)

    private fun drawLoot(ex: Float, ez: Float) {
        for (l in loot) {
            if (!l.active || l.crate != null) continue
            val dx = l.x - ex; val dz = l.z - ez
            if (dx * dx + dz * dz > 110f * 110f) continue
            val t = clock * 1.6f + l.spin
            var bob = if (l.held) 0f else 0.55f + sin(t) * 0.08f
            var lx = l.x; var ly = l.y; var lz = l.z
            if (l.held && pickU >= 0f) { heldPose(l, t); lx = hpOut[0]; ly = hpOut[1]; lz = hpOut[2]; bob = 0f; lootS = hpOut[3]; lootCx = lx; lootCy = ly; lootCz = lz }
            when (l.type) {
                LootType.WEAPON -> {
                    val gm = gunGl[guns[l.sub].id]
                    if (gm != null) {
                        Matrix.setIdentityM(pm, 0); Matrix.translateM(pm, 0, lx, ly + bob, lz)
                        Matrix.rotateM(pm, 0, Math.toDegrees(t.toDouble()).toFloat(), 0f, 1f, 0f); Matrix.scaleM(pm, 0, 1.1f, 1.1f, 1.1f)
                        setModel(pm)
                        if (gm.second.textured) { GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, tex[guns[l.sub].id]!!) } else GL.glUniform1i(uMode, 1)
                        GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
                    }
                    halo(l, 0xFFFFC21A.toInt())
                }
                LootType.VEST -> {
                    val gm = gunGl["vest" + l.sub]
                    if (gm != null) {
                        Matrix.setIdentityM(pm, 0); Matrix.translateM(pm, 0, lx, ly + bob + 0.25f, lz)
                        Matrix.rotateM(pm, 0, Math.toDegrees(t.toDouble()).toFloat(), 0f, 1f, 0f); Matrix.scaleM(pm, 0, 1.25f, 1.25f, 1.25f)
                        setModel(pm); GL.glUniform1i(uMode, 1)
                        GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
                    }
                    halo(l, AmmoInfo.VEST_COLORS[l.sub])
                }
                LootType.AMMO -> { rgb(AmmoInfo.COLORS[l.sub]) { r, g, b -> drawCube(lx, ly + bob, lz, 0.38f, 0.26f, 0.26f, r, g, b) }; halo(l, AmmoInfo.COLORS[l.sub]) }
                LootType.MEDKIT -> {
                    drawCube(lx, ly + bob, lz, 0.42f, 0.3f, 0.3f, 0.95f, 0.95f, 0.95f)
                    drawCube(lx, ly + bob, lz, 0.30f, 0.09f, 0.32f, 0.85f, 0.1f, 0.1f); drawCube(lx, ly + bob, lz, 0.09f, 0.22f, 0.32f, 0.85f, 0.1f, 0.1f)
                    halo(l, 0xFFFFFFFF.toInt())
                }
                LootType.BANDAGE -> {
                    drawCube(lx, ly + bob, lz, 0.34f, 0.16f, 0.2f, 0.93f, 0.9f, 0.82f)
                    drawCube(lx, ly + bob, lz, 0.1f, 0.17f, 0.21f, 0.85f, 0.1f, 0.1f); halo(l, 0xFFF2E6D0.toInt())
                }
                LootType.GRENADE -> {
                    drawCube(lx, ly + bob, lz, 0.24f, 0.28f, 0.24f, 0.4f, 0.5f, 0.42f); drawCube(lx, ly + bob + 0.17f, lz, 0.1f, 0.06f, 0.14f, 1f, 0.67f, 0.47f); halo(l, 0xFF9BE05A.toInt())
                }
                LootType.SCOPE -> {
                    drawCube(lx, ly + bob, lz, 0.12f, 0.12f, 0.34f, 0.12f, 0.12f, 0.14f)
                    drawCube(lx, ly + bob, lz + 0.17f, 0.15f, 0.15f, 0.04f, 0.3f, 0.8f, 1f); halo(l, 0xFF35D0C8.toInt())
                }
                LootType.VEST4 -> {
                    drawBlob(lx, ly + bob + 0.3f, lz, 0.42f, 0.53f, 0.24f, 0.85f, 0.70f, 0.23f)
                    drawCube(lx, ly + bob + 0.34f, lz, 0.34f, 0.06f, 0.5f, 1f, 0.95f, 0.69f); halo(l, 0xFFFFD34D.toInt())
                }
                LootType.HELM4 -> {
                    drawBlob(lx, ly + bob + 0.2f, lz, 0.34f, 0.26f, 0.36f, 0.85f, 0.70f, 0.23f)
                    drawCube(lx, ly + bob + 0.1f, lz + 0.2f, 0.36f, 0.05f, 0.2f, 0.42f, 0.35f, 0.12f); halo(l, 0xFFFFD34D.toInt())
                }
                LootType.SHROOM -> {
                    drawCube(lx, ly + bob - 0.05f, lz, 0.1f, 0.34f, 0.1f, 0.91f, 0.87f, 0.78f)
                    drawBlob(lx, ly + bob + 0.16f, lz, 0.34f, 0.2f, 0.34f, 0.84f, 0.27f, 0.24f)
                    drawCube(lx + 0.1f, ly + bob + 0.27f, lz + 0.08f, 0.06f, 0.03f, 0.06f, 0.96f, 0.94f, 0.88f)
                    drawCube(lx - 0.1f, ly + bob + 0.25f, lz - 0.04f, 0.05f, 0.03f, 0.05f, 0.96f, 0.94f, 0.88f); halo(l, 0xFFE0584F.toInt())
                }
                else -> { drawCube(lx, ly + bob, lz, 0.4f, 0.4f, 0.14f, 0.25f, 0.55f, 0.95f); halo(l, 0xFF5FB4E8.toInt()) }
            }
            lootS = 1f
        }
        GL.glBindVertexArray(0)
    }

    private fun drawBlob(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) {
        val sp = sphere ?: return
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y, z); Matrix.scaleM(mm, 0, sx, sy, sz)
        setModel(mm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, r, g, b)
        GL.glBindVertexArray(sp.vao); GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
    }

    /** Crate on a parachute with a gold beam, and the cargo plane that dropped it (adMake in the HTML). */
    private fun drawDrops() {
        if (drops.isEmpty()) return
        val pulse = 0.55f + 0.4f * sin(clock * 5.5f)
        for (d in drops) {
            // the plane keeps flying across the island for ~8.7 s
            val off = 260f - d.t * 110f
            if (kotlin.math.abs(off) <= 700f) drawPlaneAt(d.x + d.dir * off, d.g + 215f, d.z, -d.dir * 1.5708f)
            if (d.state == 0) continue
            val cy = d.g + d.h
            GL.glBindVertexArray(cube!!.vao)
            Matrix.setIdentityM(base, 0); Matrix.translateM(base, 0, d.x, cy, d.z)
            part(0f, 0.7f, 0f, 0f, 0f, 1.7f, 1.3f, 1.7f, 0xFFA8321F.toInt())
            part(0f, 0.7f, 0f, 0f, 0f, 1.74f, 0.22f, 1.74f, 0xFFF2C230.toInt())
            part(0f, 0.7f, 0f, 0f, 0f, 0.22f, 1.34f, 1.74f, 0xFFF2C230.toInt())
            // lid opens after landing (hinge at the back edge)
            System.arraycopy(base, 0, pm, 0, 16)
            Matrix.translateM(pm, 0, 0f, 1.38f, -0.85f); Matrix.rotateM(pm, 0, -Math.toDegrees(minOf(1.9f, d.landT * 2.2f).toDouble()).toFloat(), 1f, 0f, 0f)
            Matrix.translateM(pm, 0, 0f, 0.05f, 0.85f); Matrix.scaleM(pm, 0, 1.76f, 0.14f, 1.76f)
            setModel(pm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.49f, 0.14f, 0.08f)
            GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
            if (d.state == 1) { // 3D parachute above the crate (lines meet on the lid)
                drawChute(d.x, cy + 0.1f, d.z, 1f, degF(d.dir * 1.5708f), 1.25f, 3f * sin(clock * 1.3f + d.x))
            }
            // gold beam (additive) + pulsing glow
            val beamA = d.beam
            if (beamA > 0.01f) {
                val w = wall
                if (w != null) {
                    Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, d.x, cy - 4f, d.z); Matrix.scaleM(mm, 0, 1.1f, 300f, 1.1f)
                    setModel(mm)
                    GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE); GL.glDepthMask(false)
                    GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.68f, 0f); GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), beamA)
                    GL.glBindVertexArray(w.vao); GL.glDrawElements(GL.GL_TRIANGLES, w.count, GL.GL_UNSIGNED_INT, 0)
                    val sp = sphere
                    if (sp != null) {
                        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, d.x, cy + 2.5f, d.z); Matrix.scaleM(mm, 0, 1.1f, 1.1f, 1.1f)
                        setModel(mm); GL.glUniform3f(uColor, 1f, 0.94f, 0.63f); GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), pulse * 0.8f)
                        GL.glBindVertexArray(sp.vao); GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
                    }
                    GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
                    GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
                }
            }
        }
        GL.glBindVertexArray(0)
    }

    private fun halo(l: Loot, col: Int) { if (l.held) return; rgb(col) { r, g, b -> drawCube(l.x, l.y + 0.04f, l.z, 0.9f, 0.04f, 0.9f, r, g, b, 3) } }

    // ---------------------------------------------------------------- swimming effects (DSW of the HTML) + sun shadows

    private fun ripple(x: Float, y: Float, z: Float, big: Boolean) { if (rips.size >= 14) rips.removeAt(0); rips.add(Rip(x, y + 0.04f, z, if (big) 1.6f else 1f)) }

    private fun splash(x: Float, y: Float, z: Float, n: Int, power: Float) {
        for (i in 0 until n) {
            if (splashDrops.size >= 48) break
            val a = rnd.nextFloat() * 6.2831855f; val sp = (0.6f + rnd.nextFloat()) * power
            splashDrops.add(Drop2(x + (rnd.nextFloat() - 0.5f) * 0.6f, y, z + (rnd.nextFloat() - 0.5f) * 0.6f,
                cos(a) * sp * 0.6f, 2.2f + rnd.nextFloat() * 3.2f * power, sin(a) * sp * 0.6f, y - 0.05f))
        }
    }

    /** tick() of the swimming block: ripple rings, splash splashDrops, enter / leave water, wading strokes. */
    private fun stepSwim(dt: Float) {
        var i = rips.size - 1
        while (i >= 0) { val r = rips[i]; r.life -= dt * 0.85f; if (r.life <= 0f) rips.removeAt(i); i-- }
        i = splashDrops.size - 1
        while (i >= 0) {
            val d = splashDrops[i]; d.vy -= 14f * dt; d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt; d.life -= dt * 1.4f
            if (d.life <= 0f || d.y < d.wy) splashDrops.removeAt(i)
            i--
        }
        if (!playerAlive || airState != 3) { swimIn = false; swimming = false; return }
        val px = pos[0]; val pz = pos[2]; val depth = map.waterDepth(px, pz); val lvl = map.waterLevelAt(px, pz)
        val swim = depth > 1.25f; val wade = depth > 0.3f
        if (swim && !swimIn) { swimIn = true; ripple(px, lvl, pz, true); splash(px, lvl, pz, 22, 1.2f); sfx?.play("splash"); toast("SWIMMING / slower, no sprint. Swim to the shore to get out.") }
        else if (!swim && swimIn) { swimIn = false; ripple(px, lvl, pz, false); splash(px, lvl, pz, 10, 0.7f) }
        if (wade && footSpeed > 0.8f) {
            swimAcc -= dt
            if (swimAcc <= 0f) {
                swimAcc = if (swim) 0.26f else 0.34f
                ripple(px, lvl, pz, false)
                if (rnd.nextFloat() < 0.7f) splash(px, lvl, pz, if (swim) 4 else 3, 0.5f)
                if (swim) sfx?.play("swim")
            }
        }
    }

    private fun drawSwimFx() {
        if (rips.isEmpty() && splashDrops.isEmpty()) return
        val w = wall
        if (w != null && rips.isNotEmpty()) {
            GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
            GL.glUniform1i(uMode, 3); GL.glBindVertexArray(w.vao); GL.glUniform3f(uColor, 0.91f, 0.96f, 1f)
            for (r in rips) {
                val rad = (0.6f + (1f - r.life) * 3.6f * r.big) * 0.65f
                Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, r.x, r.y, r.z); Matrix.scaleM(mm, 0, rad, 0.07f, rad)
                setModel(mm)
                GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), max(0f, r.life) * 0.55f)
                GL.glDrawElements(GL.GL_TRIANGLES, w.count, GL.GL_UNSIGNED_INT, 0)
            }
            GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
            GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        }
        for (d in splashDrops) drawBall(d.x, d.y, d.z, 0.05f, 0.85f, 0.94f, 1f)   // v63: round droplets
        setModel(ident)
    }

    /** one box of a shadow caster: model = T(x,y,z) * Ry(yawDeg) * T(lx,ly,lz) * S(sx,sy,sz) */
    private fun shCube(x: Float, y: Float, z: Float, yawDeg: Float, lx: Float, ly: Float, lz: Float, sx: Float, sy: Float, sz: Float) {
        Matrix.setIdentityM(shm, 0); Matrix.translateM(shm, 0, x, y, z); Matrix.rotateM(shm, 0, yawDeg, 0f, 1f, 0f)
        Matrix.translateM(shm, 0, lx, ly, lz); Matrix.scaleM(shm, 0, sx, sy, sz)
        shadow.setModel(shm)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    private fun humanShadow(x: Float, y: Float, z: Float, yawDeg: Float) {
        shCube(x, y, z, yawDeg, 0f, 0.42f, 0f, 0.36f, 0.84f, 0.22f)    // legs
        shCube(x, y, z, yawDeg, 0f, 1.14f, 0f, 0.46f, 0.60f, 0.26f)    // torso
        shCube(x, y, z, yawDeg, 0f, 1.58f, 0f, 0.22f, 0.24f, 0.22f)    // head
    }

    /** Depth pass from the sun: houses / baked buildings / bridge, trees + boulders, bots and your own body (third person). */
    private fun renderShadows(cx: Float, cy: Float, cz: Float) {
        shadow.begin(cx, cy, cz)
        val h = shadow.half
        Matrix.setIdentityM(shm, 0); shadow.setModel(shm)
        for (m in layers) {
            if (m.mat != "atlas" && m.mat != "house" && m.mat != "bridge") continue
            GL.glBindVertexArray(m.mesh.vao); GL.glDrawElements(GL.GL_TRIANGLES, m.mesh.count, GL.GL_UNSIGNED_INT, 0)
        }
        for (k in map.insts) {   // v54: instanced buildings inside the shadow square
            val lim = h * 1.25f + k.rad
            if (kotlin.math.abs(k.x - cx) > lim || kotlin.math.abs(k.z - cz) > lim) continue
            instMatrix(k); shadow.setModel(bm)
            val mesh = tplMeshes[k.tpl]
            GL.glBindVertexArray(mesh.vao); GL.glDrawElements(GL.GL_TRIANGLES, mesh.count, GL.GL_UNSIGNED_INT, 0)
        }
        Matrix.setIdentityM(shm, 0); shadow.setModel(shm)
        shadow.useProgram()
        val cb = cube
        if (cb != null) {
            GL.glBindVertexArray(cb.vao)
            for (b in botSys.bots) {
                if (!b.alive || b.air != 3) continue
                if (hypot(b.x - cx, b.z - cz) > h * 1.2f) continue
                humanShadow(b.x, b.y, b.z, Math.toDegrees(b.yaw.toDouble()).toFloat())
            }
            if (playerAlive && airState == 3) humanShadow(pos[0], pos[1], pos[2], Math.toDegrees((-bodyYaw).toDouble()).toFloat())
        }
        GL.glBindVertexArray(0)
        shadow.end()
    }

    private fun drawFx() {
        val sp = sphere ?: return
        if (fxs.isEmpty()) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE); GL.glDepthMask(false)
        GL.glBindVertexArray(sp.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.6f, 0.25f)
        for (f in fxs) {
            val k = (f.life / 0.5f).coerceIn(0f, 1f)
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, f.x, f.y, f.z); val sc = 1.5f + (1f - k) * 3.5f; Matrix.scaleM(mm, 0, sc, sc, sc)
            setModel(mm)
            GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.8f * k)
            GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    /** rain streaks around the camera (only on the ground, not under water) */
    private fun drawRain(dt: Float) {
        if (!rainOk || weather.rain < 0.03f || underwater || airState != 3) return
        rainFx.update(camX, camY, camZ, dt, weather.wind, weather.rain, map)
        if (rainFx.vertexCount <= 0) return
        setModel(ident)
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 0.72f, 0.78f, 0.88f)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.45f * min(1f, weather.rain))
        GL.glBindVertexArray(rainFx.vao); GL.glDrawArrays(GL.GL_LINES, 0, rainFx.vertexCount); GL.glBindVertexArray(0)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    private fun drawZoneWall() {
        val w = wall ?: return
        if (zoneRadius < 1f) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        GL.glUniform1i(uMode, 3); GL.glBindVertexArray(w.vao)
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, zoneCx, -40f, zoneCz); Matrix.scaleM(mm, 0, zoneRadius, 320f, zoneRadius)
        setModel(mm); GL.glUniform3f(uColor, 0.25f, 0.62f, 1f); GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.28f)
        GL.glDrawElements(GL.GL_TRIANGLES, w.count, GL.GL_UNSIGNED_INT, 0)
        if (nextR > 1f) { // next safe zone: white wall (nextWall in the HTML)
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, nextX, -40f, nextZ); Matrix.scaleM(mm, 0, nextR, 320f, nextR)
            setModel(mm); GL.glUniform3f(uColor, 1f, 1f, 1f); GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.2f)
            GL.glDrawElements(GL.GL_TRIANGLES, w.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    private val base = FloatArray(16); private val pm = FloatArray(16)

    private fun part(px: Float, py: Float, pz: Float, rotX: Float, dropY: Float, sx: Float, sy: Float, sz: Float, col: Int) {
        System.arraycopy(base, 0, pm, 0, 16)
        Matrix.translateM(pm, 0, px, py, pz); if (rotX != 0f) Matrix.rotateM(pm, 0, rotX, 1f, 0f, 0f)
        Matrix.translateM(pm, 0, 0f, -dropY, 0f); Matrix.scaleM(pm, 0, sx, sy, sz)
        setModel(pm)
        GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, ((col shr 16) and 255) / 255f, ((col shr 8) and 255) / 255f, (col and 255) / 255f)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** The aircraft on its route. During the first PLANE_FLY_IN seconds it flies in from far behind (and above / to the left), levelling out. */
    private fun drawPlane() {
        var px = planeX(flightTime); var pz = planeZ(flightTime); var py = AIR_Y + sin(flightTime * 1.3f) * 0.12f
        var pitchD = 0f; var bankD = 0f
        if (flightTime < PLANE_FLY_IN) {
            val x = flightTime / PLANE_FLY_IN; val e = 1f - (1f - x) * (1f - x) * (1f - x); val q = (1f - e) * (1f - e)
            px -= fdx * 130f * (1f - e) + (-fdz) * 6f * q; pz -= fdz * 130f * (1f - e) + fdx * 6f * q; py += 10f * q
            pitchD = -0.05f * q * 57.29578f; bankD = 0.12f * q * 57.29578f
        }
        drawPlaneAt(px, py, pz, flightYaw, pitchD, bankD)
    }

    /** Real aircraft model (nose = -Z), 2 spinning engine fans. Also used for the cargo plane of the air drop. */
    private fun drawPlaneAt(px: Float, py: Float, pz: Float, heading: Float, pitchD: Float = 0f, bankD: Float = 0f) {
        if (!planeReady) return
        Matrix.setIdentityM(base, 0); Matrix.translateM(base, 0, px, py, pz)
        Matrix.rotateM(base, 0, Math.toDegrees((-heading).toDouble()).toFloat(), 0f, 1f, 0f)
        if (pitchD != 0f) Matrix.rotateM(base, 0, pitchD, 1f, 0f, 0f)
        Matrix.rotateM(base, 0, bankD + sin(flightTime * 0.9f) * 2f, 0f, 0f, 1f)
        GL.glUniform1i(uMode, 1)   // vertex colours (the jet has no texture)
        val spin = Math.toDegrees(((clock * 34f) % 6.2831855f).toDouble()).toFloat()
        for (p in planeGl) {
            System.arraycopy(base, 0, pm, 0, 16)
            if (p.prop) { Matrix.translateM(pm, 0, p.px, p.py, p.pz); Matrix.rotateM(pm, 0, spin, 0f, 0f, 1f) }
            setModel(pm)
            GL.glBindVertexArray(p.mesh.vao); GL.glDrawElements(GL.GL_TRIANGLES, p.mesh.count, GL.GL_UNSIGNED_INT, 0)
        }
    }

    private fun drawBot(b: Bot) {
        val dead = !b.alive
        if (dead && clock - b.deadAt > 6f) return
        if (!dead && b.air == 0) return
        GL.glBindVertexArray(cube!!.vao)
        val cid = botChars[(b.name.hashCode() and 0x7fffffff) % botChars.size]
        val cd = hypot(b.x - camX, b.z - camZ)
        val skinned = chars.ready && chars.has(cid) && cd < 45f
        // Phase 28: in freefall / under the canopy a skinned bot uses the same smoothed air pose as the player (skate stance + board, dive, chute hang)
        val airVis = !dead && skinned && (b.air == 1 || b.air == 2)
        Matrix.setIdentityM(base, 0)
        if (airVis) {
            val av = b.av
            Matrix.translateM(base, 0, b.x + av.offX, b.y + av.offY, b.z + av.offZ)
            Matrix.rotateM(base, 0, degF(av.rotY), 0f, 1f, 0f); Matrix.rotateM(base, 0, degF(av.rotX), 1f, 0f, 0f); Matrix.rotateM(base, 0, degF(av.rotZ), 0f, 0f, 1f)
        } else {
            Matrix.translateM(base, 0, b.x, b.y, b.z)
            Matrix.rotateM(base, 0, Math.toDegrees(b.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            if (!dead && b.air == 1 && b.dive) { Matrix.translateM(base, 0, 0f, 0.9f, 0f); Matrix.rotateM(base, 0, -80f, 1f, 0f, 0f) }
            if (dead) { val f = ((clock - b.deadAt) * 4f).coerceAtMost(1f); Matrix.translateM(base, 0, 0f, 0.15f * f, 0f); Matrix.rotateM(base, 0, -88f * f, 1f, 0f, 0f) }
        }
        Matrix.scaleM(base, 0, CharRig.SCALE, CharRig.SCALE, CharRig.SCALE)
        val sw = if (dead) 0f else sin(b.walk) * 35f
        val skin = 0xFFC58F6B.toInt(); val pants = 0xFF2B2B33.toInt()
        if (skinned) {
            if (airVis) {
                chars.draw(cid, vp, base, camX, camY, camZ, fogK, b.av.cp, pants = null)
            } else {
                cpose.mode = if (dead) CharPose.RIFLE else if (b.air == 1) CharPose.DIVE else if (b.air == 2) CharPose.CHUTE else CharPose.RIFLE
                cpose.walk = b.walk; cpose.speed = if (dead) 0f else b.speed; cpose.sprint = b.speed > 3.2f; cpose.runW = if (dead) 0f else b.runW   // v49: a bot only RUNS when it hurries (b.runW), else walks
                // breathing sway of the reference pack: the rifle arms drift a hair, only for fighters that already fired (HTML: e._pr exists from the first shot)
                var swayA = 0f
                if (b.fired && !dead && b.air == 3) { Sway.calc(clock, b.avoid, 1f, swayTmp); swayA = swayTmp[0] * 0.7f }
                cpose.crouch = 0f; cpose.prone = 0f; cpose.supine = 0f; cpose.aimPitch = swayA; cpose.headYaw = 0f; cpose.headPitch = 0f
                cpose.gunId = b.gun.id
                // pistol animation pack for this bot: aim pose + recoil kick after its own shots, walk / run clips from its real speed; dead bots (death pose) settle into the idle pose
                val bc = b.gun.cls
                val bLong = bc == "shotgun" || bc == "dmr" || bc == "sniper" || bc == "amr"
                val fightUp = !dead && b.air == 3 && b.fired && clock - b.shotAt < 1.2f
                b.pa.update(clock, if (dead) 0f else b.speed, 0f, fightUp, true, fightUp && clock - b.shotAt < 0.06f, -1f, -b.yaw, bLong, b.air == 3 || dead, b.gun.pistol, b.walk, if (dead) 0f else b.runW)
                cpose.pa = if (PistolAnim.ready && (b.air == 3 || dead)) b.pa else null
                chars.draw(cid, vp, base, camX, camY, camZ, fogK, cpose, pants = null, blinkSeed = System.identityHashCode(b))
                cpose.pa = null
            }
            GL.glUseProgram(prog)
            GL.glBindVertexArray(cube!!.vao)
            if (airVis) drawSkateboard(base, b.av)
        } else {
            part(0f, 1.35f, 0f, 0f, 0f, 0.5f, 0.7f, 0.28f, b.shirt)
            part(0f, 1.85f, 0f, 0f, 0f, 0.26f, 0.26f, 0.26f, skin)
            part(-0.27f, 1.67f, 0f, 72f + sw * 0.15f, 0.3f, 0.14f, 0.6f, 0.14f, b.shirt)
            part(0.27f, 1.67f, 0f, 78f - sw * 0.15f, 0.3f, 0.14f, 0.6f, 0.14f, b.shirt)
            part(-0.12f, 1.04f, 0f, sw, 0.52f, 0.18f, 1.04f, 0.18f, pants)
            part(0.12f, 1.04f, 0f, -sw, 0.52f, 0.18f, 1.04f, 0.18f, pants)
        }
        // the bot's gun (real 3D model)
        val gm = gunGl[b.gun.id]
        if (gm != null && !airVis) {
            System.arraycopy(base, 0, pm, 0, 16)
            if (skinned && cpose.hasGun) { Matrix.multiplyMM(tmpM, 0, base, 0, cpose.gunFrame, 0); System.arraycopy(tmpM, 0, pm, 0, 16); Matrix.translateM(pm, 0, GunHold.OX, GunHold.OY, GunHold.oz(b.gun.id)) }
            else Matrix.translateM(pm, 0, 0.14f, 1.45f, -0.42f)
            Matrix.scaleM(pm, 0, 0.85f, 0.85f, 0.85f)
            setModel(pm)
            if (gm.second.textured) { GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, tex[b.gun.id]!!) } else GL.glUniform1i(uMode, 1)
            GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
        }
        if (!dead && b.air == 2) { // 3D parachute (same model as the player's)
            drawChute(b.x, b.y + 0.8f, b.z, (b.chuteT / 0.9f).coerceIn(0f, 1f), Math.toDegrees(b.yaw.toDouble()).toFloat(), 0.85f, 2.5f * sin(clock * 1.7f + b.avoid))
        }
        // health bar for 3 s after a hit (always faces the camera)
        if (!dead && b.barTime > 0f) {
            val f = (b.hp / BotSystem.HP).coerceIn(0f, 1f)
            Matrix.setIdentityM(pm, 0); Matrix.translateM(pm, 0, b.x, b.y + 2.05f, b.z)
            Matrix.rotateM(pm, 0, -Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            val back = pm.copyOf()
            Matrix.scaleM(pm, 0, 1.0f, 0.09f, 0.03f); setModel(pm)
            GL.glBindVertexArray(cube!!.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 0.1f, 0.1f, 0.1f); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
            Matrix.translateM(back, 0, -(1f - f) * 0.5f, 0f, 0.02f); Matrix.scaleM(back, 0, 0.96f * f, 0.06f, 0.03f); setModel(back)
            GL.glUniform3f(uColor, 1f, 0.67f, 0.47f); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
        }
    }

    /** v32: a flying bullet as a short glowing streak behind its head (long for fast bullets). Your own bullets are hidden for the first metres so the line does not cover the crosshair. */
    private fun drawSlug(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float, trav: Float, pellet: Boolean, own: Boolean) {
        if (pellet) return
        val sp = sqrt(vx * vx + vy * vy + vz * vz); if (sp < 1f) return
        val len = min(min(sp * 0.014f, 7f), trav + 0.4f); if (len < 0.3f) return
        val nx = vx / sp; val ny = vy / sp; val nz = vz / sp
        Matrix.setIdentityM(pm, 0)
        Matrix.translateM(pm, 0, x - nx * len * 0.5f, y - ny * len * 0.5f, z - nz * len * 0.5f)
        Matrix.rotateM(pm, 0, Math.toDegrees(kotlin.math.atan2(-nx, -nz).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(pm, 0, Math.toDegrees(kotlin.math.asin(ny.coerceIn(-1f, 1f)).toDouble()).toFloat(), 1f, 0f, 0f)
        drawRoundStreak(len, 0.018f, 1f, 0.9f, 0.5f)
    }

    private fun drawTracer(t: Tracer) {
        val dx = t.x1 - t.x0; val dy = t.y1 - t.y0; val dz = t.z1 - t.z0
        val len = sqrt(dx * dx + dy * dy + dz * dz); if (len < 0.1f) return
        val ndx = dx / len; val ndy = dy / len; val ndz = dz / len
        Matrix.setIdentityM(pm, 0)
        Matrix.translateM(pm, 0, (t.x0 + t.x1) / 2, (t.y0 + t.y1) / 2, (t.z0 + t.z1) / 2)
        Matrix.rotateM(pm, 0, Math.toDegrees(kotlin.math.atan2(-ndx, -ndz).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(pm, 0, Math.toDegrees(kotlin.math.asin(ndy.coerceIn(-1f, 1f)).toDouble()).toFloat(), 1f, 0f, 0f)
        drawRoundStreak(len, 0.02f, 1f, 0.9f, 0.5f)
    }
}
