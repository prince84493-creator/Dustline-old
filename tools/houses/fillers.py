"""v26: simple town buildings (shops / flats, 1-4 floors, flat roof you can land on) that fill the towns between the enterable houses.
They use the same BJH1 block format as a house (mesh + collision box + one standable roof surface, no loot, no stairs), so the Kotlin loader is unchanged.
Several neighbouring buildings are written into ONE block (= one draw call)."""
import math
import numpy as np

WALLS = ['#e9dfc8', '#d9c9a3', '#f0e6d2', '#c9d6dc', '#e3c7b8', '#d8d2c4', '#efe9df', '#b9c7a8', '#e8d3a2', '#cfc3d6', '#dcb59a', '#f2f0ea']
SIGNS = ['#b3392f', '#2d5f8a', '#2e7d4f', '#c78a1c', '#6b3f86', '#222a33', '#0f6f78']
DOORS = ['#3f6f4a', '#7a2f2f', '#34506e', '#6b6b6b', '#8a6a3a']
ROOFS = ['#8d8a84', '#9a948a', '#7d7a74', '#a39b8d']


def H(h):
    h = h.lstrip('#'); return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


class M:
    def __init__(self): self.V = []; self.I = []
    def quad(self, p, n, col):
        b = len(self.V)
        for (x, y, z) in p: self.V.append([x, y, z, n[0], n[1], n[2], 0.0, 0.0, col[0], col[1], col[2]])
        self.I += [b, b + 1, b + 2, b, b + 2, b + 3]
    def tri(self, p, n, col):
        b = len(self.V)
        for (x, y, z) in p: self.V.append([x, y, z, n[0], n[1], n[2], 0.0, 0.0, col[0], col[1], col[2]])
        self.I += [b, b + 1, b + 2]
    def prism(self, cx, cz, r, y0, y1, n, col, topcol=None):
        for i in range(n):
            a0 = 6.2832 * i / n; a1 = 6.2832 * (i + 1) / n; am = (a0 + a1) / 2
            p0 = (cx + r * math.cos(a0), cz + r * math.sin(a0)); p1 = (cx + r * math.cos(a1), cz + r * math.sin(a1))
            self.quad([(p0[0], y0, p0[1]), (p1[0], y0, p1[1]), (p1[0], y1, p1[1]), (p0[0], y1, p0[1])], (math.cos(am), 0, math.sin(am)), col)
            self.tri([(cx, y1, cz), (p1[0], y1, p1[1]), (p0[0], y1, p0[1])], (0, 1, 0), topcol or col)
    def dome(self, cx, cy, cz, r, col, nlat=4, nlon=10):
        for a in range(nlat):
            t0 = 1.5708 * a / nlat; t1 = 1.5708 * (a + 1) / nlat
            for i in range(nlon):
                f0 = 6.2832 * i / nlon; f1 = 6.2832 * (i + 1) / nlon
                def P(t, f): return (cx + r * math.cos(t) * math.cos(f), cy + r * math.sin(t), cz + r * math.cos(t) * math.sin(f))
                pm = P((t0 + t1) / 2, (f0 + f1) / 2); nn = ((pm[0] - cx) / r, (pm[1] - cy) / r, (pm[2] - cz) / r)
                if a == nlat - 1: self.tri([P(t0, f0), P(t0, f1), (cx, cy + r, cz)], nn, col)
                else: self.quad([P(t0, f0), P(t0, f1), P(t1, f1), P(t1, f0)], nn, col)
    def cone(self, cx, cz, r, y0, y1, n, col):
        for i in range(n):
            a0 = 6.2832 * i / n; a1 = 6.2832 * (i + 1) / n; am = (a0 + a1) / 2
            self.tri([(cx + r * math.cos(a0), y0, cz + r * math.sin(a0)), (cx + r * math.cos(a1), y0, cz + r * math.sin(a1)), (cx, y1, cz)], (math.cos(am) * .7, .7, math.sin(am) * .7), col)
    def box(self, x0, x1, y0, y1, z0, z1, col, top=None):
        self.quad([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1), col)
        self.quad([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (0, 0, -1), col)
        self.quad([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (1, 0, 0), col)
        self.quad([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (-1, 0, 0), col)
        self.quad([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], (0, 1, 0), top or col)


def _gwall(m, boxes, dyn, ax, fx, t, lo, hi, ytop, col, glass, holes):
    """v30b ground-floor wall (ax 'x': runs along x, z in fx..fx+t | 'z': runs along z, x in fx..fx+t). holes = (a, b, ylo, yhi); ylo = 0 is a doorway.
    window = sill + lintel pieces + a separate breakable glass pane (dyn); collision: sill 0.3 lower (a hop clears it), lintel 0.5 higher (the head passes)"""
    def put(a, b, y0, y1, c0, c1):
        if b - a < 1e-3 or y1 - y0 < 1e-3: return
        if ax == 'x': m.box(a, b, y0, y1, fx, fx + t, col); r = (a, b, c0, c1, fx, fx + t)
        else: m.box(fx, fx + t, y0, y1, a, b, col); r = (fx, fx + t, c0, c1, a, b)
        if c1 - c0 > 1e-3: boxes.append(r)
    cur = lo
    for (a, b, ylo, yhi) in sorted(holes):
        put(cur, a, 0.0, ytop, 0.0, ytop)
        if ylo > 0.01:
            put(a, b, 0.0, ylo, 0.0, ylo - 0.3)
            dyn.append(('w',) + ((a, b, ylo, yhi, fx - .04, fx + t + .04) if ax == 'x' else (fx - .04, fx + t + .04, ylo, yhi, a, b)) + (glass,))
        put(a, b, yhi, ytop, yhi + 0.5, ytop)
        cur = b
    put(cur, hi, 0.0, ytop, 0.0, ytop)


def make_building(rng, big_town, W=None, D=None, floors=None, wall=None, sign=None, extras=None):
    """local frame: x right, z toward the FRONT, y up, origin = footprint centre on the ground. Returns (mesh, W, D, Hh)."""
    if W is None: W = float(rng.choice([7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 14.0]))
    if D is None: D = float(rng.choice([7.0, 8.0, 9.0, 10.0]))
    r = rng.random()
    if floors is None: floors = 1 if r < 0.28 else 2 if r < 0.68 else 3 if r < 0.92 else (4 if big_town else 3)
    fh = 3.2
    Hh = floors * fh + (0.4 if floors > 1 else 0.0)
    wall = H(WALLS[rng.integers(len(WALLS))]) if wall is None else wall; roof = H(ROOFS[rng.integers(len(ROOFS))]); sign = H(SIGNS[rng.integers(len(SIGNS))]) if sign is None else sign
    door = H(DOORS[rng.integers(len(DOORS))]); glass = H('#5d7f95'); trim = tuple(c * 0.78 for c in wall)
    m = M(); hx, hz = W / 2, D / 2
    # v30b: the ground floor is a real room (door + windows that open / break); the upper floors stay one solid block
    t0 = 0.25; gh = fh if floors > 1 else Hh; cy = gh - 0.25; DW = 1.6; DH = 2.3
    cbx = [(-hx, hx, cy, Hh, -hz, hz)]; dyn = []
    m.box(-hx, hx, cy, Hh, -hz, hz, wall, top=roof)
    m.box(-hx + t0, hx - t0, 0, 0.12, -hz + t0, hz - t0, (0.62, 0.58, 0.52))
    m.quad([(-hx + t0, cy, -hz + t0), (hx - t0, cy, -hz + t0), (hx - t0, cy, hz - t0), (-hx + t0, cy, hz - t0)], (0, -1, 0), (0.86, 0.84, 0.8))
    fw = [(-DW / 2, DW / 2, 0.0, DH)]
    if W >= 9: fw += [(u - .8, u + .8, 0.9, 2.3) for u in (-hx * 0.62, hx * 0.62)]
    _gwall(m, cbx, dyn, 'x', hz - t0, t0, -hx, hx, cy, wall, glass, fw)
    _gwall(m, cbx, dyn, 'x', -hz, t0, -hx, hx, cy, wall, glass, [(-.55, .55, 1.0, 2.35)] if W >= 7 else [])
    for sx in (-1, 1): _gwall(m, cbx, dyn, 'z', hx - t0 if sx > 0 else -hx, t0, -hz + t0, hz - t0, cy, wall, glass, [(-.55, .55, 1.0, 2.35)])
    dyn.append(('d', -DW / 2, DW / 2, 0.14, DH - .02, hz - t0, hz, DW / 2, hz - t0 / 2, -1.0, 0.0, 0.0, -1.0, door))   # the shop door: hinge on the right jamb, swings IN
    for k in range(1, floors):                                                           # floor bands
        y = k * fh + 0.2
        m.box(-hx - 0.06, hx + 0.06, y - 0.12, y + 0.12, -hz - 0.06, hz + 0.06, trim)
    t = 0.22; ph = 0.55                                                                  # parapet (visual only)
    m.box(-hx, hx, Hh, Hh + ph, hz - t, hz, trim); m.box(-hx, hx, Hh, Hh + ph, -hz, -hz + t, trim)
    m.box(-hx, -hx + t, Hh, Hh + ph, -hz + t, hz - t, trim); m.box(hx - t, hx, Hh, Hh + ph, -hz + t, hz - t, trim)
    # front: shop door + sign band on the ground floor, windows above
    # (the shop door is a real door now)
    m.quad([(-hx * 0.85, 2.65, hz + 0.07), (hx * 0.85, 2.65, hz + 0.07), (hx * 0.85, 3.2 if floors > 1 else 3.05, hz + 0.07), (-hx * 0.85, 3.2 if floors > 1 else 3.05, hz + 0.07)], (0, 0, 1), sign)
    def windows(face, length, f0):
        cols = max(1, int(length // 3.2)); step = length / cols
        for f in range(f0, floors):
            for c in range(cols):
                u = -length / 2 + step * (c + 0.5); y0 = f * fh + 1.0 + (0.2 if f else 0); y1 = y0 + 1.35
                if face == 'F': m.quad([(u - .55, y0, hz + 0.04), (u + .55, y0, hz + 0.04), (u + .55, y1, hz + 0.04), (u - .55, y1, hz + 0.04)], (0, 0, 1), glass)
                elif face == 'B': m.quad([(u + .55, y0, -hz - 0.04), (u - .55, y0, -hz - 0.04), (u - .55, y1, -hz - 0.04), (u + .55, y1, -hz - 0.04)], (0, 0, -1), glass)
                elif face == 'R': m.quad([(hx + 0.04, y0, u + .55), (hx + 0.04, y0, u - .55), (hx + 0.04, y1, u - .55), (hx + 0.04, y1, u + .55)], (1, 0, 0), glass)
                else: m.quad([(-hx - 0.04, y0, u - .55), (-hx - 0.04, y0, u + .55), (-hx - 0.04, y1, u + .55), (-hx - 0.04, y1, u - .55)], (-1, 0, 0), glass)
    windows('F', W, 1); windows('B', W, 1); windows('R', D, 1); windows('L', D, 1)   # upper floors only: painted
    if floors >= 2 and rng.random() < 0.7:                                               # water tank on the roof
        m.box(hx * 0.3, hx * 0.3 + 1.3, Hh + ph - 0.05, Hh + ph + 1.3, -hz * 0.5, -hz * 0.5 + 1.3, H('#2a2f36'))
    if extras: extras(m, W, D, Hh)
    return dict(m=m, W=W, D=D, boxes=cbx, surfs=[(-hx, hx, -hz, hz, [(0, 0, Hh)]), (-hx + t0, hx - t0, -hz + t0, hz - t0, [(0, 0, 0.12)])], dyn=dyn)


def F(c, k=1.0): return tuple(min(1.0, v * k) for v in H(c))


def make_mandir(rng, big):
    m = M(); stone = H('#cfc6b4'); cream = H(['#f1e3c0', '#f4d9a8', '#efe2d2'][rng.integers(3)]); org = H('#d9792b'); gold = H('#d7a62b')
    m.box(-4, 4, 0, 0.6, -4, 4, stone); m.box(-3, 3, 0.6, 5.0, -3, 3, cream, top=cream)
    m.box(-0.9, 0.9, 0.6, 3.0, 3.02, 3.06, H('#3b2a20'))
    for z in (-1.8, 1.8):
        for x in (-1.8, 1.8): m.box(x - .3, x + .3, 0.6, 4.6, 3.0, 3.3, stone) if False else None
    for i in range(6):
        h = 2.8 - i * 0.44; m.box(-h, h, 5.0 + i * 0.95, 5.0 + (i + 1) * 0.95, -h, h, org if i % 2 == 0 else cream)
    m.box(-.35, .35, 10.7, 11.5, -.35, .35, gold); m.box(-.04, .04, 11.5, 14.0, -.04, .04, H('#444444'))
    m.quad([(0, 13.9, 0), (1.6, 13.4, 0), (0, 12.9, 0)], (0, 0, 1), H('#e8731a'))
    m.quad([(0, 13.9, 0), (0, 12.9, 0), (1.6, 13.4, 0)], (0, 0, -1), H('#e8731a'))
    for x in (-3.9, 3.9): m.box(x - .15, x + .15, 0.6, 1.3, -3.9, 3.9, H('#b8ad98'))
    return dict(m=m, W=8.0, D=8.0, boxes=[(-4, 4, 0, 5.0, -4, 4)], surfs=[(-3, 3, -3, 3, [(0, 0, 5.0)])])


def make_masjid(rng, big):
    m = M(); wall = H('#f4f1e8'); green = H(['#2f8a57', '#2a7a8a', '#3a9a6a'][rng.integers(3)]); gold = H('#d7a62b')
    m.box(-6.5, 6.5, 0, 6.0, -5.5, 5.5, wall, top=wall); m.box(-6.55, 6.55, 5.6, 6.1, -5.55, 5.55, H('#d9d3c4'))
    for u in (-3.5, 0, 3.5): m.quad([(u - 1.0, 0.1, 5.56), (u + 1.0, 0.1, 5.56), (u + 1.0, 3.6, 5.56), (u - 1.0, 3.6, 5.56)], (0, 0, 1), H('#3b6e5a'))
    for u in (-4.5, 4.5):
        for f in (0, 1): m.quad([(u - .6, 2.2, 5.57), (u + .6, 2.2, 5.57), (u + .6, 4.0, 5.57), (u - .6, 4.0, 5.57)], (0, 0, 1), H('#5d7f95'))
    m.box(-2.5, 2.5, 6.0, 7.2, -2.5, 2.5, wall); m.dome(0, 7.2, 0, 3.0, green, 4, 12); m.box(-.08, .08, 10.2, 11.4, -.08, .08, gold)
    for sx in (-5.9, 5.9):
        m.box(sx - .6, sx + .6, 0, 9.0, 4.2, 5.4, wall); m.box(sx - .85, sx + .85, 9.0, 9.4, 4.0, 5.6, H('#d9d3c4'))
        m.box(sx - .5, sx + .5, 9.4, 12.5, 4.3, 5.3, wall); m.dome(sx, 12.5, 4.8, 0.75, green, 3, 8); m.box(sx - .04, sx + .04, 13.2, 14.4, 4.76, 4.84, gold)
    return dict(m=m, W=13.0, D=11.0, boxes=[(-6.5, 6.5, 0, 6.0, -5.5, 5.5), (-6.5, -5.3, 0, 9.4, 4.0, 5.6), (5.3, 6.5, 0, 9.4, 4.0, 5.6)],
                surfs=[(-4.9, 4.9, -5.5, 5.5, [(0, 0, 6.0)])])


def make_tower(rng, big):
    m = M(); leg = H('#8f8f8a'); tank = H(['#8fb3cf', '#c9d0d6', '#7fa7a0'][rng.integers(3)])
    for sx in (-2.2, 2.2):
        for sz in (-2.2, 2.2): m.box(sx - .25, sx + .25, 0, 9.0, sz - .25, sz + .25, leg)
    for y in (3.0, 6.0): m.box(-2.2, 2.2, y, y + .15, -2.25, -2.15, leg); m.box(-2.2, 2.2, y, y + .15, 2.15, 2.25, leg); m.box(-2.25, -2.15, y, y + .15, -2.2, 2.2, leg); m.box(2.15, 2.25, y, y + .15, -2.2, 2.2, leg)
    m.prism(0, 0, 2.6, 9.0, 13.0, 12, tank, topcol=H('#6d7b86')); m.cone(0, 0, 2.7, 13.0, 14.2, 12, H('#5f6b75'))
    m.box(-.4, .4, 9.0, 13.0, 2.58, 2.7, H('#555555')) if False else None
    return dict(m=m, W=6.0, D=6.0, boxes=[(-2.45, -1.95, 0, 9.0, -2.45, -1.95), (1.95, 2.45, 0, 9.0, -2.45, -1.95), (-2.45, -1.95, 0, 9.0, 1.95, 2.45), (1.95, 2.45, 0, 9.0, 1.95, 2.45)],
                surfs=[(-2.0, 2.0, -2.0, 2.0, [(0, 0, 13.0)])])


def make_petrol(rng, big):
    m = M(); red = H(['#c62f2f', '#1f5fa8', '#2b8a3e'][rng.integers(3)]); wh = H('#f0f0ec')
    m.box(-6, 6, 4.9, 5.5, -4, 4, wh, top=wh); m.box(-6.05, 6.05, 5.0, 5.4, -4.05, 4.05, red)
    for sx in (-5.2, 5.2):
        for sz in (-3.2, 3.2): m.box(sx - .25, sx + .25, 0, 4.9, sz - .25, sz + .25, H('#c9c9c4'))
    for x in (-2.2, 2.2): m.box(x - .4, x + .4, 0, 1.6, -.3, .3, red); m.box(x - .3, x + .3, 1.6, 1.9, -.25, .25, H('#222222'))
    m.box(-5.6, -2.2, 0, 3.2, -3.6, -0.6, H('#e8e2d2'), top=H('#8d8a84')); m.quad([(-5.2, 0.05, -0.55), (-3.2, 0.05, -0.55), (-3.2, 2.3, -0.55), (-5.2, 2.3, -0.55)], (0, 0, 1), H('#5d7f95'))
    return dict(m=m, W=12.4, D=8.4, boxes=[(-5.6, -2.2, 0, 3.2, -3.6, -0.6)] + [(sx - .3, sx + .3, 0, 4.9, sz - .3, sz + .3) for sx in (-5.2, 5.2) for sz in (-3.2, 3.2)],
                surfs=[(-6, 6, -4, 4, [(0, 0, 5.5)]), (-5.6, -2.2, -3.6, -0.6, [(0, 0, 3.2)])])


def make_godown(rng, big):
    m = M(); wall = H(['#bfb9aa', '#a9b4b8', '#c8b79a'][rng.integers(3)]); roof = H(['#6e7b86', '#8a5a44', '#59666f'][rng.integers(3)]); hx, hz = 10.0, 6.0; he = 5.0; rise = 2.2
    m.box(-hx, hx, 0, he, -hz, hz, wall, top=wall)
    s = rise / hz
    m.quad([(-hx - .3, he, hz + .3), (hx + .3, he, hz + .3), (hx + .3, he + rise + .1, 0), (-hx - .3, he + rise + .1, 0)], (0, .8, .6), roof)
    m.quad([(hx + .3, he, -hz - .3), (-hx - .3, he, -hz - .3), (-hx - .3, he + rise + .1, 0), (hx + .3, he + rise + .1, 0)], (0, .8, -.6), roof)
    m.tri([(-hx, he, hz + .02), (hx, he, hz + .02), (0, he + rise, hz + .02)], (0, 0, 1), wall) if False else None
    m.tri([(-hx, he, hz + .02), (-hx, he, hz + .02), (-hx, he, hz + .02)], (0, 0, 1), wall) if False else None
    m.quad([(-3, 0.05, hz + 0.05), (3, 0.05, hz + 0.05), (3, 4.0, hz + 0.05), (-3, 4.0, hz + 0.05)], (0, 0, 1), H('#4b5a63'))
    for x in (-7.5, 7.5): m.quad([(x - 1, 2.2, hz + 0.05), (x + 1, 2.2, hz + 0.05), (x + 1, 3.6, hz + 0.05), (x - 1, 3.6, hz + 0.05)], (0, 0, 1), H('#5d7f95'))
    m.quad([(-hx * .6, 4.1, hz + .07), (hx * .6, 4.1, hz + .07), (hx * .6, 4.8, hz + .07), (-hx * .6, 4.8, hz + .07)], (0, 0, 1), H(SIGNS[rng.integers(len(SIGNS))]))
    return dict(m=m, W=20.0, D=12.0, boxes=[(-hx, hx, 0, he, -hz, hz)], surfs=[(-hx, hx, -hz, hz, [(0, s, he + s * hz), (0, -s, he + s * hz)])])


def make_clock(rng, big):
    m = M(); br = H(['#a9593f', '#b57a4f', '#9a6b50'][rng.integers(3)]); wh = H('#f4f1e8')
    m.box(-2.5, 2.5, 0, 1.2, -2.5, 2.5, H('#8a8378')); m.box(-2.0, 2.0, 1.2, 16.0, -2.0, 2.0, br)
    for y in (6.0, 11.0): m.box(-2.15, 2.15, y, y + .3, -2.15, 2.15, H('#d9d3c4'))
    for (nx, nz) in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        if nz: z = 2.03 * nz; m.quad([(-1.1 * nz, 12.2, z), (1.1 * nz, 12.2, z), (1.1 * nz, 14.4, z), (-1.1 * nz, 14.4, z)], (0, 0, nz), wh)
        else: x = 2.03 * nx; m.quad([(x, 12.2, 1.1 * nx), (x, 12.2, -1.1 * nx), (x, 14.4, -1.1 * nx), (x, 14.4, 1.1 * nx)], (nx, 0, 0), wh)
    m.box(-2.3, 2.3, 16.0, 16.4, -2.3, 2.3, H('#d9d3c4')); m.box(-1.2, 1.2, 16.4, 18.4, -1.2, 1.2, br); m.cone(0, 0, 1.7, 18.4, 21.0, 4, H('#7a2f2f'))
    return dict(m=m, W=5.0, D=5.0, boxes=[(-2.5, 2.5, 0, 16.4, -2.5, 2.5)], surfs=[(-2.3, 2.3, -2.3, 2.3, [(0, 0, 16.4)])])


def _school_extra(m, W, D, Hh):
    hz = D / 2; m.box(-.05, .05, 0, 7.5, hz - 1.0, hz - .9, H('#aaaaaa')); m.quad([(.05, 7.5, hz - .95), (1.7, 7.2, hz - .95), (1.7, 6.6, hz - .95), (.05, 6.6, hz - .95)], (0, 0, 1), H('#ff9933'))
    m.quad([(.05, 6.6, hz - .95), (1.7, 6.6, hz - .95), (1.7, 6.0, hz - .95), (.05, 6.0, hz - .95)], (0, 0, 1), H('#f5f5f5')); m.quad([(.05, 6.0, hz - .95), (1.7, 6.0, hz - .95), (1.7, 5.4, hz - .95), (.05, 5.4, hz - .95)], (0, 0, 1), H('#138808'))


def _hospital_extra(m, W, D, Hh):
    hz = D / 2; red = H('#c62f2f'); m.box(-.3, .3, Hh - 3.3, Hh - 1.3, hz, hz + .08, red); m.box(-1.3, 1.3, Hh - 2.6, Hh - 2.0, hz, hz + .09, red)
    m.box(-W / 2 - .02, W / 2 + .02, 2.2, 2.45, -hz - .02, hz + .02, H('#2d6fb3'))


def _bank_extra(m, W, D, Hh):
    hz = D / 2
    for x in np.linspace(-W / 2 + 1.5, W / 2 - 1.5, 4): m.box(x - .3, x + .3, 0.4, Hh, hz, hz + .55, H('#efe9df'))
    m.box(-W / 2, W / 2, Hh, Hh + .5, hz - .2, hz + .8, H('#d9d3c4'))


def make_special(kind, rng, big):
    if kind == 'mandir': return make_mandir(rng, big)
    if kind == 'masjid': return make_masjid(rng, big)
    if kind == 'tower': return make_tower(rng, big)
    if kind == 'petrol': return make_petrol(rng, big)
    if kind == 'godown': return make_godown(rng, big)
    if kind == 'clock': return make_clock(rng, big)
    if kind == 'school': return make_building(rng, big, W=22.0, D=9.0, floors=2, wall=H('#f1dc9a'), sign=H('#2d5f8a'), extras=_school_extra)
    if kind == 'hospital': return make_building(rng, big, W=16.0, D=11.0, floors=3, wall=H('#f3f5f6'), sign=H('#2d6fb3'), extras=_hospital_extra)
    if kind == 'bank': return make_building(rng, big, W=12.0, D=9.0, floors=2, wall=H('#e6d9bf'), sign=H('#0f6f78'), extras=_bank_extra)
    if kind == 'dhaba': return make_building(rng, big, W=6.0, D=5.0, floors=1, sign=H('#c78a1c'))
    return make_building(rng, big)
