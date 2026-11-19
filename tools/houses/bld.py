"""v42: ENTERABLE town buildings (shop, dhaba, school, hospital, bank, godown, mandir, masjid, petrol pump, clock tower, water tower).
Every building is made with the same builder as the 7 houses (build.py): hollow walls, floors with stair holes, INSIDE stairs floor -> floor -> roof,
real door leaves on every floor, breakable window panes, furniture, loot on every floor.  Local frame: x right, z toward the FRONT (door), y up.
make_template(kind, rng) -> dict(m, sp, info, bbox, door)   (furniture is rebuilt until every loot point and the roof can be reached)"""
import math, random
import numpy as np
from kit import *
from specs import H
from build import build
from reach import unreachable
import fillers

WALLS = [H(c) for c in fillers.WALLS]
SIGNS = [H(c) for c in fillers.SIGNS]
DOORS = [H(c) for c in fillers.DOORS]
ROOFC = [H(c) for c in fillers.ROOFS]
FLOORC = [(0.62, 0.58, 0.52), (0.70, 0.66, 0.58), (0.56, 0.52, 0.48), (0.66, 0.60, 0.50)]
INT = [(0.94, 0.91, 0.84), (0.92, 0.93, 0.90), (0.95, 0.90, 0.80), (0.90, 0.92, 0.94)]


def dark(c, k=0.78): return tuple(v * k for v in c)


def _sign(col, frac=0.8, y0=2.58, y1=3.04):
    def f(m, sp, y, info):
        W, D = sp['W'], sp['D']
        x0 = sp.get('door_x', 0.0) - W * frac / 2; x1 = sp.get('door_x', 0.0) + W * frac / 2
        x0 = max(x0, -W / 2 + 0.25); x1 = min(x1, W / 2 - 0.25)
        m.box(x0, x1, y0, y1, D / 2 + 0.0, D / 2 + 0.13, col, jit=0.02)
        k = 0
        xx = x0 + 0.3
        while xx < x1 - 0.5:      # pseudo lettering
            ww = 0.18 + 0.12 * ((k * 7) % 3)
            m.box(xx, xx + ww, y0 + 0.1, y1 - 0.1, D / 2 + 0.13, D / 2 + 0.16, (0.96, 0.95, 0.88), jit=0)
            xx += ww + 0.16; k += 1
    return f


def _roof_tank(m, sp, y, info):
    rs = info.get('roofstair')
    if not rs: return
    W, D = sp['W'], sp['D']; yt = y[info['n']]
    sx = -1 if rs['x'] > 0 else 1
    cx = sx * min(W * 0.22, W / 2 - 1.2); cz = -D * 0.22
    for lx in (-0.5, 0.5):
        for lz in (-0.5, 0.5): m.box(cx + lx - .05, cx + lx + .05, yt, yt + .5, cz + lz - .05, cz + lz + .05, (0.35, 0.35, 0.37), jit=0)
    m.box(cx - .62, cx + .62, yt + .5, yt + 1.5, cz - .62, cz + .62, (0.16, 0.19, 0.24), coll=True, jit=0.02)
    m.cbox(cx - .62, cx + .62, yt, yt + .5, cz - .62, cz + .62)


def _stairs(n):
    """one stair per floor transition (alternating E / W); n == 1: only the roof stair"""
    if n == 1: return [dict(side='E', z0=0.6)]
    return [dict(side='E' if i % 2 == 0 else 'W', z0=0.6) for i in range(n - 1)]


def _wins(W, floors, ground_shop=True, big=False):
    ww, wh = (1.5, 1.4)
    win = {'+z': [], '+x': [], '-x': [], '-z': []}
    for f in range(floors):
        if f == 0 and ground_shop:
            fr = [(-W * 0.32, ww, wh), (W * 0.32, ww, wh)] if W >= 9 else []
        else:
            fr = [(-W * 0.28, ww, wh), (W * 0.28, ww, wh)] if W >= 9 else [(0.0, 1.3, wh)]
        win['+z'].append(fr)
        win['+x'].append([0.0]); win['-x'].append([0.0]); win['-z'].append([(0.0, 1.4, 1.4)] if W < 12 else [(-W * 0.25, 1.4, 1.4), (W * 0.25, 1.4, 1.4)])
    return win


# --------------------------------------------------------------------------------------------- specs
def spec_shop(rng, W, D, floors, wall=None, sign=None):
    wall = wall or WALLS[rng.randrange(len(WALLS))]; sign = sign or SIGNS[rng.randrange(len(SIGNS))]
    n = floors
    sp = dict(name='shop%dx%dx%d' % (W, D, n), kind='shop', W=float(W), D=float(D), h=[3.1] * n, ext=wall, trim=dark(wall), intc=INT[rng.randrange(4)],
              floorc=[FLOORC[rng.randrange(4)] for _ in range(n)], door_x=0.0, door_c=dark(wall, .6), door_leaf=DOORS[rng.randrange(len(DOORS))],
              stairs=_stairs(n), win=_wins(W, n), ws=(1.1, 1.3), sill=1.45, glass=(0.36, 0.52, 0.62),
              roof=dict(type='flat', col=ROOFC[rng.randrange(4)], parapet=0.55, pc=dark(wall, .85)),
              band=dark(wall, .72) if n > 1 else None,
              kinds=lambda f, n_: ['counter', 'shelf', 'shelf', 'table'] if f == 0 else ['bed', 'wardrobe', 'table', 'sofa'],
              loot_cnt=lambda area, f, n_: max(2, min(5, int(area / 22) + 1)),
              extras=lambda m, sp, y, info, s=sign: (_sign(s)(m, sp, y, info), _roof_tank(m, sp, y, info)))
    return sp


def spec_dhaba(rng, W, D):
    wall = WALLS[rng.randrange(len(WALLS))]; sign = H('#c78a1c')
    sp = spec_shop(rng, W, D, 1, wall, sign)
    sp.update(name='dhaba', kind='dhaba', win={'+z': [[(-W * 0.3, 1.3, 1.3), (W * 0.3, 1.3, 1.3)]], '+x': [[0.0]], '-x': [[0.0]], '-z': [[(0.0, 1.4, 1.3)]]},
              porch=dict(depth=2.0, x0=-W / 2 + 0.4, x1=W / 2 - 0.4, floorc=(0.66, 0.65, 0.62)),
              kinds=lambda f, n_: ['counter', 'table', 'table', 'shelf'], furn_gap=0.7)
    return sp


def spec_school(rng, W=22, D=10):
    wall = H('#f1dc9a'); n = 2
    sp = spec_shop(rng, W, D, n, wall, H('#2d5f8a'))
    sp.update(name='school', kind='school', h=[3.4, 3.4], stairs=[dict(side='E', z0=0.6), dict(side='W', z0=0.6)],
              win={'+z': [[(-8.0, 1.6, 1.4), (-4.0, 1.6, 1.4), (4.0, 1.6, 1.4), (8.0, 1.6, 1.4)]] * 2, '+x': [[0.0]] * 2, '-x': [[0.0]] * 2,
                   '-z': [[(-8.0, 1.6, 1.4), (-4.0, 1.6, 1.4), (4.0, 1.6, 1.4), (8.0, 1.6, 1.4)]] * 2},
              kinds=lambda f, n_: ['desk', 'desk', 'desk', 'desk', 'shelf'], furn_gap=0.5, band=dark(wall, .8),
              loot_cnt=lambda area, f, n_: 5,
              extras=lambda m, sp, y, info: (_sign(H('#2d5f8a'), 0.5)(m, sp, y, info), _roof_tank(m, sp, y, info), _flag(m, sp, y, info)))
    return sp


def _flag(m, sp, y, info):
    W, D = sp['W'], sp['D']
    x = W / 2 + 2.0; z = D / 2 + 1.5
    m.box(x - .04, x + .04, 0, 6.5, z - .04, z + .04, (0.67, 0.67, 0.67), coll=True, jit=0)
    for k, c in enumerate(((1.0, 0.6, 0.2), (0.96, 0.96, 0.96), (0.07, 0.53, 0.03))):
        m.box(x + .04, x + 1.5, 6.4 - k * .5 - .5, 6.4 - k * .5, z - .02, z + .02, c, jit=0)


def spec_hospital(rng):
    W, D, n = 16.0, 11.0, 3
    wall = H('#f3f5f6'); sp = spec_shop(rng, W, D, n, wall, H('#2d6fb3'))
    sp.update(name='hospital', kind='hospital', h=[3.2] * 3, kinds=lambda f, n_: ['bed', 'bed', 'bed', 'counter', 'shelf'], furn_gap=0.6,
              win={'+z': [[(-6.0, 1.5, 1.4), (-2.5, 1.5, 1.4), (2.5, 1.5, 1.4), (6.0, 1.5, 1.4)]] * 3, '+x': [[0.0]] * 3, '-x': [[0.0]] * 3, '-z': [[(-5.0, 1.5, 1.4), (5.0, 1.5, 1.4)]] * 3},
              loot_cnt=lambda area, f, n_: 5,
              extras=lambda m, sp, y, info: (_sign(H('#2d6fb3'), 0.5)(m, sp, y, info), _roof_tank(m, sp, y, info), _redcross(m, sp, y, info)))
    return sp


def _redcross(m, sp, y, info):
    D = sp['D']; yt = y[info['n']]; red = (0.78, 0.18, 0.18)
    m.box(-.3, .3, yt - 3.0, yt - 1.0, D / 2, D / 2 + .08, red, jit=0); m.box(-1.0, 1.0, yt - 2.3, yt - 1.7, D / 2, D / 2 + .09, red, jit=0)


def spec_bank(rng):
    W, D, n = 12.0, 9.0, 2
    wall = H('#e6d9bf'); sp = spec_shop(rng, W, D, n, wall, H('#0f6f78'))
    sp.update(name='bank', kind='bank', kinds=lambda f, n_: ['counter', 'counter', 'desk', 'shelf'] if f == 0 else ['desk', 'desk', 'shelf', 'table'], loot_cnt=lambda area, f, n_: 4,
              extras=lambda m, sp, y, info: (_sign(H('#0f6f78'), 0.5)(m, sp, y, info), _roof_tank(m, sp, y, info), _columns(m, sp, y, info)))
    return sp


def _columns(m, sp, y, info):
    W, D = sp['W'], sp['D']
    for x in (-W / 2 + 1.2, W / 2 - 1.2):
        m.box(x - .28, x + .28, 0.03, y[info['n']] - 0.3, D / 2 + 0.08, D / 2 + 0.64, (0.94, 0.92, 0.87), coll=True, jit=0.01)


def spec_godown(rng):
    W, D = 20.0, 12.0
    wall = H(['#bfb9aa', '#a9b4b8', '#c8b79a'][rng.randrange(3)])
    sp = dict(name='godown', kind='godown', W=W, D=D, h=[5.0], ext=wall, trim=dark(wall), intc=(0.86, 0.86, 0.82), floorc=[(0.55, 0.54, 0.52)], door_x=0.0, door_c=dark(wall, .6),
              door_leaf=(0.30, 0.36, 0.40), stairs=[dict(side='E', z0=0.6)], rooms=True, ws=(1.4, 0.9), sill=3.4,
              win={'+z': [[(-7.0, 1.6, 0.9), (7.0, 1.6, 0.9)]], '+x': [[]], '-x': [[]], '-z': [[(-6.0, 1.6, 0.9), (0.0, 1.6, 0.9), (6.0, 1.6, 0.9)]]}, glass=(0.40, 0.52, 0.58),
              roof=dict(type='gable_x', col=H(['#6e7b86', '#8a5a44', '#59666f'][rng.randrange(3)]), ov=0.3, slope=0.37, fascia=dark(wall)),
              kinds=lambda f, n_: ['crate', 'crate', 'sacks', 'sacks', 'shelf'], furn_gap=1.0, loot_cnt=lambda area, f, n_: 8,
              extras=lambda m, sp, y, info: _gate(m, sp, y, info))
    return sp


def _gate(m, sp, y, info):
    D = sp['D']
    m.box(-3.0, 3.0, 0.03, 4.1, D / 2 + 0.0, D / 2 + 0.07, (0.30, 0.36, 0.40), jit=0)
    for u in (-2.0, 0.0, 2.0): m.box(u - .03, u + .03, 0.03, 4.1, D / 2 + .07, D / 2 + .1, (0.22, 0.27, 0.30), jit=0)


def spec_mandir(rng):
    W = D = 9.0
    cream = H(['#f1e3c0', '#f4d9a8', '#efe2d2'][rng.randrange(3)])
    sp = dict(name='mandir', kind='mandir', W=W, D=D, h=[4.4], ext=cream, trim=H('#cfc6b4'), intc=(0.96, 0.92, 0.82), floorc=[(0.78, 0.74, 0.66)], door_x=0.0, door_c=H('#b8ad98'),
              door_leaf=H('#3b2a20'), stairs=[dict(side='E', z0=0.6)], rooms=False, ws=(0.8, 1.2), sill=1.9,
              win={'+z': [[(-3.0, 0.8, 1.2), (3.0, 0.8, 1.2)]], '+x': [[]], '-x': [[]], '-z': [[]]}, glass=(0.80, 0.62, 0.30),
              porch=dict(depth=2.2, x0=-2.0, x1=2.0, floorc=H('#b8ad98')),
              roof=dict(type='flat', col=H('#d9cdb4'), parapet=0.45, pc=H('#cfc6b4')),
              kinds=lambda f, n_: ['altar', 'mat', 'mat', 'bench'], furn_gap=0.5, loot_cnt=lambda area, f, n_: 4,
              extras=lambda m, sp, y, info: _shikhara(m, sp, y, info))
    return sp


def _shikhara(m, sp, y, info):
    yt = y[info['n']]; org = H('#d9792b'); cream = H('#f1e3c0'); gold = H('#d7a62b')
    for i in range(6):
        h = 2.3 - i * 0.36; y0 = yt + i * 0.95
        m.box(-h, h, y0, y0 + 0.95, -h, h, org if i % 2 == 0 else cream, coll=(i < 2), jit=0.02)
    m.box(-.35, .35, yt + 5.7, yt + 6.5, -.35, .35, gold, jit=0)
    m.box(-.04, .04, yt + 6.5, yt + 9.0, -.04, .04, (0.27, 0.27, 0.27), jit=0)
    m.quad((0, yt + 8.9, 0), (1.6, yt + 8.4, 0), (0, yt + 7.9, 0), (0, yt + 7.9, 0.001), (0.91, 0.45, 0.10)); m.quad((0, yt + 8.9, 0), (0, yt + 7.9, 0), (1.6, yt + 8.4, 0), (1.6, yt + 8.4, 0.001), (0.91, 0.45, 0.10))


def spec_masjid(rng):
    W, D = 13.0, 11.0
    green = [(0.18, 0.54, 0.34), (0.16, 0.48, 0.54), (0.23, 0.60, 0.42)][rng.randrange(3)]
    wall = H('#f4f1e8')
    sp = dict(name='masjid', kind='masjid', W=W, D=D, h=[4.6], ext=wall, trim=H('#d9d3c4'), intc=(0.96, 0.95, 0.90), floorc=[(0.82, 0.80, 0.74)], door_x=0.0, door_c=H('#3b6e5a'),
              door_leaf=H('#3b6e5a'), stairs=[dict(side='E', z0=0.6)], rooms=False, ws=(1.0, 1.8), sill=2.2,
              win={'+z': [[(-4.5, 1.0, 1.8), (4.5, 1.0, 1.8)]], '+x': [[(-2.0, 1.0, 1.8), (2.0, 1.0, 1.8)]], '-x': [[(-2.0, 1.0, 1.8), (2.0, 1.0, 1.8)]], '-z': [[(-3.0, 1.0, 1.8), (3.0, 1.0, 1.8)]]},
              glass=(0.45, 0.62, 0.68), porch=dict(depth=1.8, x0=-2.0, x1=2.0, floorc=H('#d9d3c4')),
              roof=dict(type='flat', col=H('#e6e1d3'), parapet=0.5, pc=H('#d9d3c4')),
              kinds=lambda f, n_: ['mat', 'mat', 'mat', 'mat', 'shelf'], furn_gap=0.3, loot_cnt=lambda area, f, n_: 5,
              extras=lambda m, sp, y, info, g=green: _domes(m, sp, y, info, g))
    return sp


def _dome(m, cx, cy, cz, r, col, nlat=4, nlon=10):
    def P(t, f): return (cx + r * math.cos(t) * math.cos(f), cy + r * math.sin(t), cz + r * math.cos(t) * math.sin(f))
    for a in range(nlat):
        t0 = 1.5708 * a / nlat; t1 = 1.5708 * (a + 1) / nlat
        for i in range(nlon):
            f0 = 6.2832 * i / nlon; f1 = 6.2832 * (i + 1) / nlon
            if a == nlat - 1: m.tri(P(t0, f0), P(t0, f1), (cx, cy + r, cz), col)
            else: m.quad(P(t0, f0), P(t0, f1), P(t1, f1), P(t1, f0), col)


def _domes(m, sp, y, info, green):
    yt = y[info['n']]; gold = H('#d7a62b'); wall = H('#f4f1e8')
    m.box(-2.2, 2.2, yt, yt + 1.3, -2.2, 2.2, wall, coll=True, jit=0.01)
    _dome(m, 0, yt + 1.3, 0, 2.8, green)
    m.box(-.06, .06, yt + 4.1, yt + 5.2, -.06, .06, gold, jit=0)
    sx = sp['W'] / 2 - 0.9; sz = sp['D'] / 2 - 0.7
    for q in (-1, 1):
        x = q * sx
        m.box(x - .6, x + .6, yt, yt + 8.0, sz - .6, sz + .6, wall, coll=True, jit=0.01)
        m.box(x - .8, x + .8, yt + 8.0, yt + 8.4, sz - .8, sz + .8, H('#d9d3c4'), jit=0)
        m.box(x - .5, x + .5, yt + 8.4, yt + 10.6, sz - .5, sz + .5, wall, jit=0)
        _dome(m, x, yt + 10.6, sz, 0.75, green, 3, 8)
        m.box(x - .04, x + .04, yt + 11.3, yt + 12.4, sz - .04, sz + .04, gold, jit=0)


def spec_petrol(rng):
    W, D = 6.0, 7.0
    col = [(0.78, 0.18, 0.18), (0.12, 0.37, 0.66), (0.17, 0.54, 0.24)][rng.randrange(3)]
    sp = spec_shop(rng, W, D, 1, H('#e8e2d2'), col)
    sp.update(name='petrol', kind='petrol', win={'+z': [[(-1.9, 1.2, 1.3), (1.9, 1.2, 1.3)]], '+x': [[0.0]], '-x': [[0.0]], '-z': [[]]}, kinds=lambda f, n_: ['counter', 'shelf', 'table'], loot_cnt=lambda area, f, n_: 3,
              extras=lambda m, sp, y, info, c=col: (_sign(c, 0.9)(m, sp, y, info), _roof_tank(m, sp, y, info), _canopy(m, sp, y, info, c)))
    return sp


def _canopy(m, sp, y, info, red):
    W, D = sp['W'], sp['D']; x0 = W / 2 + 1.0; x1 = x0 + 10.0; z0 = -3.0; z1 = 4.6
    wh = (0.94, 0.94, 0.92)
    m.box(x0, x1, 4.9, 5.5, z0, z1, wh, jit=0.01); m.box(x0 - .05, x1 + .05, 5.0, 5.4, z0 - .05, z1 + .05, red, jit=0)
    for sx in (x0 + .5, x1 - .5):
        for sz in (z0 + .6, z1 - .6): m.box(sx - .25, sx + .25, 0.03, 4.9, sz - .25, sz + .25, (0.79, 0.79, 0.77), coll=True, jit=0.01)
    for x in (x0 + 3.0, x0 + 7.0):
        m.box(x - .4, x + .4, 0.03, 1.6, 0.8, 1.6, (0.80, 0.18, 0.16), coll=True); m.box(x - .3, x + .3, 1.6, 1.9, 0.85, 1.55, (0.13, 0.13, 0.13))
        m.box(x - .6, x + .6, 0.03, 0.2, 0.6, 1.8, (0.62, 0.62, 0.60), jit=0)


def spec_clock(rng):
    W = D = 8.0; n = 5
    br = [(0.66, 0.35, 0.25), (0.71, 0.48, 0.31), (0.60, 0.42, 0.31)][rng.randrange(3)]
    sp = dict(name='clock', kind='clock', W=W, D=D, h=[3.2] * n, ext=br, trim=H('#d9d3c4'), intc=(0.92, 0.88, 0.80), floorc=[(0.55, 0.45, 0.36)] * n, door_x=0.0, door_c=H('#d9d3c4'),
              door_leaf=H('#3b2a20'), stairs=_stairs(n), rooms=False, ws=(0.9, 1.3), sill=1.5,
              win={'+z': [[], [(0.0, 0.9, 1.3)], [(0.0, 0.9, 1.3)], [(0.0, 0.9, 1.3)], [(0.0, 0.9, 1.3)]], '+x': [[0.0]] * n, '-x': [[0.0]] * n, '-z': [[0.0]] * n}, glass=(0.5, 0.6, 0.65),
              band=H('#d9d3c4'), roof=dict(type='flat', col=H('#8a8378'), parapet=0.6, pc=H('#d9d3c4')),
              kinds=lambda f, n_: ['table', 'shelf'], loot_cnt=lambda area, f, n_: 3,
              extras=lambda m, sp, y, info: _clockface(m, sp, y, info))
    return sp


def _clockface(m, sp, y, info):
    W, D = sp['W'], sp['D']; yt = y[info['n']]; wh = (0.96, 0.95, 0.90); cy = yt - 2.2
    for (nx, nz) in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        if nz: z = (D / 2 + 0.05) * nz; m.box(-1.1, 1.1, cy - 1.1, cy + 1.1, min(z, z + .05 * nz), max(z, z + .05 * nz), wh, jit=0)
        else: x = (W / 2 + 0.05) * nx; m.box(min(x, x + .05 * nx), max(x, x + .05 * nx), cy - 1.1, cy + 1.1, -1.1, 1.1, wh, jit=0)
    m.box(-1.0, 1.0, yt, yt + 2.6, -1.0, 1.0, H('#a9593f'), coll=True, jit=0.01)
    for i in range(4):
        r = 1.5 - i * 0.35; m.box(-r, r, yt + 2.6 + i * .5, yt + 3.1 + i * .5, -r, r, H('#7a2f2f'), jit=0.01)


# --------------------------------------------------------------------------------------------- water tower (outside ramp, custom)
def make_tower(rng):
    """v43: Stilt water tank 9 m up.  A 2.2 m wide ramp (slope 0.62, real steps) goes along the north side (x), turns at a landing and climbs the east side (z) to a pad
    that opens onto the deck through a 1.8 m gap in the deck rail.  Deck 7.2 m wide, tank collision radius 1.9 m: the walk ring round the tank is 0.7 m wide (player r 0.42).
    (v42 bug: no floor surface between the ramp and the deck, so the deck could not be entered.)"""
    m = Model(rng.randrange(1 << 30))
    leg = (0.56, 0.56, 0.54); tank = [(0.56, 0.70, 0.81), (0.79, 0.82, 0.84), (0.50, 0.65, 0.63)][rng.randrange(3)]; rail = (0.30, 0.30, 0.32); sl = 0.62
    E = 3.6; RW = 2.2; ZN0 = E + 0.2; ZN1 = ZN0 + RW          # ramp 1 (along x) occupies z ZN0..ZN1
    for sx in (-2.6, 2.6):
        for sz in (-2.6, 2.6): m.box(sx - .28, sx + .28, 0.03, 9.0, sz - .28, sz + .28, leg, coll=True, jit=0.01)
    for yb in (3.0, 6.0):
        m.box(-2.6, 2.6, yb, yb + .14, -2.62, -2.58, leg, jit=0); m.box(-2.6, 2.6, yb, yb + .14, 2.58, 2.62, leg, jit=0); m.box(-2.62, -2.58, yb, yb + .14, -2.6, 2.6, leg, jit=0)
    # deck
    m.box(-E, E, 8.75, 9.0, -E, E, (0.50, 0.50, 0.48), coll=True, jit=0.01)
    m.surface((-E, E, -E, E), [(0, 0, 9.0)], True, 0.25)
    # tank (drum)
    r = 2.1
    for k in range(12):
        a0 = 6.2832 * k / 12; a1 = 6.2832 * (k + 1) / 12; am = (a0 + a1) / 2
        p0 = (r * math.cos(a0), r * math.sin(a0)); p1 = (r * math.cos(a1), r * math.sin(a1))
        m.quad((p0[0], 9.0, p0[1]), (p1[0], 9.0, p1[1]), (p1[0], 13.0, p1[1]), (p0[0], 13.0, p0[1]), tank, (math.cos(am), 0, math.sin(am)))
        m.tri((0, 13.0, 0), (p1[0], 13.0, p1[1]), (p0[0], 13.0, p0[1]), (0.43, 0.48, 0.53), (0, 1, 0))
    m.cbox(-1.9, 1.9, 9.0, 13.0, -1.9, 1.9)
    m.box(-.2, .2, 13.0, 14.0, -.2, .2, (0.37, 0.42, 0.46), jit=0)
    # ramp geometry
    XA = -5.4; XB = E; ya1 = sl * (XB - XA)                 # ramp 1: x XA..XB (ground .. ya1)
    ZA = ZN0; ZB = ZA - (9.0 - ya1) / sl                    # ramp 2: z ZA..ZB (ya1 .. 9.0), x XB..XB+RW
    XR1 = XB + RW + 0.2
    # deck rail: north, south, west full; east from ZB to E (gap z -E .. ZB = the way onto the deck)
    def rl(x0, x1, z0, z1): m.box(x0, x1, 9.0, 9.95, z0, z1, rail, coll=True, jit=0)
    rl(-E, E, E - .06, E); rl(-E, E, -E, -E + .06); rl(-E, -E + .06, -E, E); rl(E - .06, E, ZB, E)
    def steps_x(xa, xb, z0, z1):
        n = int(round((xb - xa) * sl / 0.18))
        for q in range(n):
            a = xa + (xb - xa) * q / n; b = xa + (xb - xa) * (q + 1) / n
            m.box(a, b, 0.03, sl * (b - xa), z0, z1, (0.58, 0.58, 0.56), fc={'+y': (0.66, 0.66, 0.64)}, jit=0.02)
    def steps_z(za, zb, x0, x1):
        n = int(round((za - zb) * sl / 0.18))
        for q in range(n):
            a = za - (za - zb) * q / n; b = za - (za - zb) * (q + 1) / n
            m.box(x0, x1, ya1 - 0.3, ya1 + sl * (za - b), b, a, (0.58, 0.58, 0.56), fc={'+y': (0.66, 0.66, 0.64)}, jit=0.02)
    steps_x(XA, XB, ZN0, ZN1)
    m.surface((XA, XB, ZN0, ZN1), [(sl, 0, -sl * XA + 0.09), (0, 0, ya1 + 0.02)], True, 0.0)
    # landing (x XB..XR1, z ZN0..ZN1) at ya1
    m.box(XB, XR1, ya1 - .3, ya1, ZN0, ZN1, (0.58, 0.58, 0.56), jit=0.01)
    m.surface((XB - 0.02, XR1, ZN0, ZN1), [(0, 0, ya1)], True, 0.0)
    steps_z(ZA, ZB, XB, XR1)
    m.surface((XB - 0.02, XR1, ZB, ZA), [(0, -sl, ya1 + sl * ZA + 0.09), (0, 0, 9.02)], True, 0.0)
    # pad (x E..XR1, z -E..ZB) at 9.0, joined to the deck
    m.box(E, XR1, 8.75, 9.0, -E, ZB, (0.50, 0.50, 0.48), jit=0.01)
    m.surface((E - 0.02, XR1, -E, ZB + 0.05), [(0, 0, 9.0)], True, 0.0)
    # rails: outer (north z = ZN1 edge for ramp 1, east x = XR1 edge for the landing, ramp 2 and pad), inner (south edge of ramp 1)
    n = 14
    for q in range(n):
        a = XA + (XB - XA) * q / n; b = XA + (XB - XA) * (q + 1) / n
        m.cbox(a, b, sl * (a - XA) + 0.1, sl * (b - XA) + 1.0, ZN1 - .06, ZN1)
        m.box(a, b, sl * (a - XA) + 0.7, sl * (b - XA) + 0.95, ZN1 - .06, ZN1, rail, jit=0)
        m.cbox(a, b, sl * (a - XA) + 0.1, sl * (b - XA) + 1.0, ZN0, ZN0 + .06)
        m.box(a, b, sl * (a - XA) + 0.7, sl * (b - XA) + 0.95, ZN0, ZN0 + .06, rail, jit=0)
    m.cbox(XR1 - .06, XR1, ya1 + 0.1, ya1 + 1.0, ZA, ZN1); m.box(XR1 - .06, XR1, ya1 + 0.7, ya1 + 0.95, ZA, ZN1, rail, jit=0)
    m.cbox(XB, XR1, ya1 + 0.1, ya1 + 1.0, ZN1 - .06, ZN1); m.box(XB, XR1, ya1 + 0.7, ya1 + 0.95, ZN1 - .06, ZN1, rail, jit=0)
    for q in range(n):
        a = ZA - (ZA - ZB) * q / n; b = ZA - (ZA - ZB) * (q + 1) / n
        m.cbox(XR1 - .06, XR1, ya1 + sl * (ZA - a) + 0.1, ya1 + sl * (ZA - b) + 1.0, b, a)
        m.box(XR1 - .06, XR1, ya1 + sl * (ZA - a) + 0.7, ya1 + sl * (ZA - b) + 0.95, b, a, rail, jit=0)
    m.cbox(XR1 - .06, XR1, 9.0, 9.95, -E, ZB); m.box(XR1 - .06, XR1, 9.0, 9.95, -E, ZB, rail, jit=0)
    m.cbox(E, XR1, 9.0, 9.95, -E, -E + .06); m.box(E, XR1, 9.0, 9.95, -E, -E + .06, rail, jit=0)
    # loot on the deck ring (clear of the tank and of the rails)
    m.loot += [(-2.9, 9.0, 2.9), (2.9, 9.0, 2.7), (-2.9, 9.0, -2.9), (2.9, 9.0, -2.9)]
    sp = dict(name='tower', kind='tower', W=8.0, D=8.0, h=[9.0], n=1, door_x=0.0)
    info = dict(n=1, dx=0.0, bounds=(-8.0, 8.0, -6.0, 9.0), start=(-6.6, 0.5 * (ZN0 + ZN1)), roofstair=dict(x=2.9, z=0.0, y=9.0), y=[0.03, 9.0])
    return m, sp, info


# --------------------------------------------------------------------------------------------- driver
def _finish(m, sp, info):
    V = np.array(m.V, np.float32)
    bb = (float(V[:, 0].min()), float(V[:, 0].max()), float(V[:, 2].min()), float(V[:, 2].max()))
    return dict(m=m, sp=sp, info=info, bbox=bb, door=(sp.get('door_x', 0.0), sp['D'] / 2 + 0.2), kind=sp['kind'], name=sp['name'])


def make_template(kind, rng, tries=40, **kw):
    if kind == 'tower':
        for t in range(tries):
            m, sp, info = make_tower(random.Random(rng.randrange(1 << 30)))
            bad = unreachable(m, sp, info)
            if not bad: return _finish(m, sp, info)
        raise RuntimeError('tower unreachable: %s' % (bad[:3],))
    for t in range(tries):
        r2 = random.Random(rng.randrange(1 << 30))
        if kind == 'shop': sp = spec_shop(r2, kw['W'], kw['D'], kw['floors'])
        elif kind == 'dhaba': sp = spec_dhaba(r2, kw['W'], kw['D'])
        else: sp = globals()['spec_' + kind](r2)
        m, info = build(sp, r2.randrange(1 << 30))
        bad = unreachable(m, sp, info)
        ok_levels = all(any(abs(v - (info['y'][f])) < 0.12 for v in info.get('reached_y', [])) for f in range(info['n']))
        if not bad and ok_levels: return _finish(m, sp, info)
    raise RuntimeError('%s unreachable: %s' % (kind, bad[:3]))
