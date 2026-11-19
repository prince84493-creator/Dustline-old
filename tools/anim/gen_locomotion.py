"""v50 walk / run key poses -> Locomotion.kt.
WALK = matched to the walk reference video (cycle 1.04 s, 48 samples). RUN = measured from the "running animation" video (cycle 15 frames = 0.625 s, 60 samples).
usage: python3 tools/anim/gen_locomotion.py   (needs numpy + scipy). Edit the key lists below (10 keys per cycle, degrees) and run again.
"""
import numpy as np
from scipy.interpolate import CubicSpline

def per(vals):
    """vals at u=0,.1,...,.9 (10 keys) -> periodic spline"""
    u = np.linspace(0,1,len(vals)+1)
    v = list(vals)+[vals[0]]
    return CubicSpline(u, v, bc_type='periodic')

# ---- WALK (left side, u=0 = left heel strike). degrees. hip + = leg forward, knee = flexion (+), arm + = forward, elbow = flexion (+)
WALK = dict(
  hip   = per([28, 24, 13, 1, -11, -21, -19, -4, 15, 26]),
  knee  = per([ 4, 14,  8, 5,   8,  12,  30, 50, 44, 16]),
  arm   = per([-36,-31,-17,-1,  13,  25,  22,  6,-19,-34]),
  elbow = per([ 10, 13, 18, 24,  29,  31,  29, 25, 17, 11]),
)
# ---- RUN (v50): measured from the reference video "running animation" (720x1280, 24 fps, runner coming towards the camera).
# One full cycle (left + right step) = 15 video frames = 0.625 s = 3.2 steps/s (knee-up peaks at frames 104, 111-112, 119-120 of the 2nd run segment, arm pattern repeats every 15 frames in the 1st).
# Timeline of ONE leg inside the 15 frames, k = frame after foot contact: contact k0, loading k1-2, push-off / toe-off k5-6, heel kick peak k9, HIGH KNEE peak k12, reach k13-14.
# Style of the video: high knee drive (thigh ~70 deg), heel kick (knee ~120 deg), elbows ~90-110 deg with the fist at chin height, the other arm swings back and OUT with an open hand, small forward lean.
# degrees; hip + = leg forward, knee = flexion (+), arm + = forward, elbow = flexion (+), abd = arm swung outward (+)
RUN_FRAMES = 15
def per15(vals):
    assert len(vals) == RUN_FRAMES
    u = np.linspace(0, 1, RUN_FRAMES + 1)
    return CubicSpline(u, list(vals) + [vals[0]], bc_type='periodic')
RUN_HIP_KEYS  = [ 36,  22,   6,  -8, -20, -28, -26, -14,  -6,   8,  30,  55,  72,  62,  46]
RUN_KNEE_KEYS = [ 14,  34,  42,  34,  24,  32,  62,  92, 112, 122, 118, 105,  88,  50,  24]
def harm(mean, amp, peak_u, second=0.0):
    """arm curves: one main swing per cycle, peak (max forward) at u = peak_u, plus a small 2nd harmonic for the snappy pump"""
    return lambda u: mean + amp * np.cos(2*np.pi*(u - peak_u)) + second * np.cos(4*np.pi*(u - peak_u))
RUN = dict(
  hip   = per15(RUN_HIP_KEYS),
  knee  = per15(RUN_KNEE_KEYS),
  # arm on the SAME side as the leg: fist at the chin (max forward, 70 deg) while its leg is back (toe-off, u~0.40); back-most (-42) when its leg drives forward (u~0.90)
  arm   = harm(14, 56, 0.40, 3.0),
  elbow = harm(94, 16, 0.40),
  # the back-swinging arm opens outward (open hand of the video), the forward fist stays at the body
  abd   = harm(12, 14, 0.90),
)

import numpy as np

# ---- v50 STRIDE EXTENSION (anti foot-skating). The game moves much faster than the clips were captured at (walk 4.5 m/s, sprint 10 m/s), so the stance foot would slide
# over the ground. STR(u) is a unit shape: +1 at foot contact, falls linearly to -1 at toe-off (the stance), 0 in the swing (so heel kick / high knee stay as in the video),
# rising back to +1 over the last part of the swing (the reach). Loco.sample adds  kS(speed) * STR(u)  degrees to the hip, kS = 0 at the clip's own speed.
def _stride_shape(st, dec_end, rise_start):
    def f(x):
        x = x % 1.0
        if x < st: return 1 - 2 * x / st
        if x < dec_end: return -(1 - (x - st) / (dec_end - st))
        if x < rise_start: return 0.0
        return (x - rise_start) / (1 - rise_start)
    return f
def _smoothed(f, n=1200, sigma=12.0):
    u = np.linspace(0, 1, n, endpoint=False); v = np.array([f(x) for x in u])
    k = np.exp(-0.5 * (np.arange(-40, 41) / sigma) ** 2); k /= k.sum()
    vv = np.convolve(np.tile(v, 3), k, mode='same')[n:2 * n]
    return lambda x: np.interp(np.asarray(x) % 1.0, np.append(u, 1.0), np.append(vv, vv[0]))
WALK_STR = _smoothed(_stride_shape(0.55, 0.80, 0.90))   # walk: foot on the ground for ~55 % of the cycle
RUN_STR  = _smoothed(_stride_shape(0.37, 0.60, 0.80))   # run: ~37 % stance, then flight

NW=48      # walk tables: 48 samples / cycle
NR=60      # run tables: 4 samples per video frame
def arr(name, f, N=48):
    u=np.arange(N)/N
    v=f(u)
    return "    private val %s = floatArrayOf(%s)\n" % (name, ", ".join("%.2ff"%x for x in v))
out = '''package com.bijnor.game

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * v50 LOCOMOTION - the ONE walk / run animation of the game (replaces the old procedural sin() gait and the pistol-pack WALK / RUN clips).
 *
 *  WALK = taken from the reference video "Male Walk Cycle Animation Reference (front + side views)": cycle length 1.04 s (1.9 steps/s), heel strike -> loading -> mid-stance ->
 *         toe-off -> swing, hip swing about +28 / -21 deg, knee 4 deg at heel strike, ~50 deg in mid swing, free opposite arm swing (-36 / +25 deg), elbow 10-31 deg,
 *         head bob 2 x per cycle, the stance foot stays on the ground.
 *  RUN  = taken from the reference video "running animation" (v50; the old authored run of v49 is gone): ONE full cycle = 15 video frames = 0.625 s (3.2 steps/s),
 *         foot contact -> loading -> toe-off -> HEEL KICK (knee ~122 deg) -> HIGH KNEE (thigh ~72 deg) -> reach; stance ~37 % of the cycle, so there is a real flight phase.
 *         Arms: elbows bent 78-110 deg, the fist comes up to chin height (shoulder +70 deg) while the other arm swings back (-42 deg) and OUT with an open hand (abduction up to 26 deg);
 *         small forward lean (~7 deg), shoulders counter-rotate against the hips. Walk and run are different clips; they are cross-faded with runW (0 = walk, 1 = run).
 *
 * WHEN IS IT A RUN?  Only when runW > 0, and runW only goes up while the RUN button is on (player) / when a bot hurries (Bots.kt). The joystick alone is ALWAYS the walk clip,
 * whatever the speed is (the speed only changes the cadence a little, never the clip).
 *
 * Phase: u = walk / (2 pi), u = 0 is the heel strike (foot contact) of the LEFT foot. The right side is the same curve shifted by half a cycle.
 * Angles are in radians in the convention of CharModel.skinOld: hip + = leg forward, knee value is NEGATIVE when bent, arm + = forward, forearm + = elbow bent.
 * The tables are in degrees (walk 48 samples / cycle, run 60 samples / cycle), generated by tools/anim/gen_locomotion.py from the key poses.
 */
object Loco {
    class Out {
        var hipL = 0f; var kneeL = 0f; var hipR = 0f; var kneeR = 0f
        var armL = 0f; var armR = 0f; var foreL = 0f; var foreR = 0f
        /** torso lean (rad, < 0 = forward), shoulder twist about the vertical (rad, + = left shoulder back) and side roll (rad, + = to the left) */
        var lean = 0f; var twist = 0f; var roll = 0f
        /** v50: arm swung OUT sideways (rad, + = away from the body, same sign for both arms; the caller mirrors it). Run only (the open hand of the video), 0 in the walk */
        var abdL = 0f; var abdR = 0f
    }

    private const val D2R = 0.017453292f
    private const val TWO_PI = 6.2831855f

'''
for k,key in (('HIP','hip'),('KNEE','knee'),('ARM','arm'),('ELB','elbow')):
    out += arr("W_%s"%k, WALK[key], NW)
out += arr("W_STR", WALK_STR, NW)
for k,key in (('HIP','hip'),('KNEE','knee'),('ARM','arm'),('ELB','elbow'),('ABD','abd')):
    out += arr("R_%s"%k, RUN[key], NR)
out += arr("R_STR", RUN_STR, NR)
out += '''
    /** cyclic linear lookup, u in cycles (any value) */
    private fun cyc(a: FloatArray, u: Float): Float {
        if (u.isNaN()) return a[0]
        val n = a.size
        val x = (u - floor(u)) * n
        var i = x.toInt(); if (i >= n) i = n - 1
        val f = x - i
        val j = if (i + 1 >= n) 0 else i + 1
        return a[i] + (a[j] - a[i]) * f
    }

    private fun ch(w: FloatArray, r: FloatArray, u: Float, rw: Float, kw: Float, kr: Float): Float {
        val a = cyc(w, u) * kw; val b = cyc(r, u) * kr
        return (a + (b - a) * rw) * D2R
    }

    /** stride extension (deg) of the hip at phase [u]: see gen_locomotion.py (_stride_shape). Walk: 0 at 1 m/s .. 22 deg at 3.5 m/s; run: 0 at 3 m/s .. 28 deg at 7.4 m/s. */
    private fun stride(u: Float, rw: Float, speed: Float): Float {
        val kw = 22f * ((speed - 1f) / 2.5f).coerceIn(0f, 1f)
        val kr = 28f * ((speed - 3f) / 4.4f).coerceIn(0f, 1f)
        return (cyc(W_STR, u) * kw * (1f - rw) + cyc(R_STR, u) * kr * rw) * D2R
    }

    /**
     * Fills [o] for the walk phase [walk] (rad, the value that Gait.omega() integrates), blend [run] 0..1 (0 = walk clip, 1 = run clip) and the ground speed [speed] (m/s).
     * The stride grows with the speed: hip swing walk up to +10 % / run up to +12 %, plus the stride extension of stride() (v50 anti foot-skating: walk up to 22 deg, run up to 28 deg at the stance / reach phases). The caller multiplies everything by its own "moving" weight, so a standing character stays in its idle pose.
     */
    fun sample(walk: Float, run: Float, speed: Float, o: Out) {
        val u = walk / TWO_PI; val ub = u + 0.5f
        val rw = if (run < 0f) 0f else if (run > 1f) 1f else run
        val kw = 1f + 0.10f * ((speed - 1.5f) / 4.5f).coerceIn(0f, 1f)
        val kr = 1f + 0.12f * ((speed - 6f) / 4f).coerceIn(0f, 1f)
        o.hipL = ch(W_HIP, R_HIP, u, rw, kw, kr) + stride(u, rw, speed); o.hipR = ch(W_HIP, R_HIP, ub, rw, kw, kr) + stride(ub, rw, speed)
        o.kneeL = -ch(W_KNEE, R_KNEE, u, rw, 1f, 1f); o.kneeR = -ch(W_KNEE, R_KNEE, ub, rw, 1f, 1f)
        o.armL = ch(W_ARM, R_ARM, u, rw, kw, kr); o.armR = ch(W_ARM, R_ARM, ub, rw, kw, kr)
        o.foreL = ch(W_ELB, R_ELB, u, rw, 1f, 1f); o.foreR = ch(W_ELB, R_ELB, ub, rw, 1f, 1f)
        o.abdL = cyc(R_ABD, u) * rw * D2R; o.abdR = cyc(R_ABD, ub) * rw * D2R
        o.lean = -(0.045f + (0.12f - 0.045f) * rw)
        // walk: left shoulder back at the left heel strike (u = 0); run: left shoulder back when the left leg drives forward (u = 0.9)
        o.twist = 0.07f * (1f - rw) * cos(u * TWO_PI) + 0.17f * rw * cos((u - 0.9f) * TWO_PI)
        o.roll = (0.02f + 0.015f * rw) * sin(u * TWO_PI)
    }
}
'''
import os, sys
dst = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'java', 'com', 'bijnor', 'game', 'Locomotion.kt')
open(dst,'w').write(out)
print(len(out))
