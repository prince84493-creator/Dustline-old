"""Geometry kit for the Bijnor houses (hollow shells, door cut, stairs, floors).  Local frame: x right, z toward the FRONT (door side), y up, y=0 = terrain."""
import math, random
import numpy as np

T = 0.3          # wall thickness
SLAB = 0.25      # floor slab thickness
GF = 0.03        # ground floor top (above terrain)
DOOR_W = 1.2
DOOR_H = 2.3
R_PLAYER = 0.42


def mul(c, k):
    return tuple(max(0.0, min(1.0, v * k)) for v in c)


class Model:
    def __init__(s, seed):
        s.V = []      # [px,py,pz,nx,ny,nz,u,v,r,g,b]
        s.I = []
        s.boxes = []  # collision AABB (x0,x1,y0,y1,z0,z1)
        s.surf = []   # dict(rect=(x0,x1,z0,z1), planes=[(a,b,c)...], flag, thick)
        s.loot = []
        s.rnd = random.Random(seed)
        s.free = []   # info
        s.dyn = []    # v30 moving parts: ('w', box.., glass rgb) breakable pane | ('d', box.., hx, hz, cdx, cdz, odx, odz, rgb) door leaf
        s.nofurn = [] # rects (x0,x1,z0,z1,floor) where nothing may stand

    # ---------------------------------------------------------------- primitives
    def quad(s, p0, p1, p2, p3, col, out=None):
        p = [np.array(q, float) for q in (p0, p1, p2, p3)]
        n = np.cross(p[1] - p[0], p[2] - p[0])
        if np.linalg.norm(n) < 1e-9:
            n = np.cross(p[2] - p[0], p[3] - p[0])
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            return
        n = n / ln
        if out is not None and np.dot(n, out) < 0:
            p = [p[0], p[3], p[2], p[1]]
            n = -n
        b = len(s.V)
        for q in p:
            s.V.append([q[0], q[1], q[2], n[0], n[1], n[2], 0.0, 0.0, col[0], col[1], col[2]])
        s.I += [b, b + 1, b + 2, b, b + 2, b + 3]

    def tri(s, p0, p1, p2, col, out=None):
        s.quad(p0, p1, p2, p2, col, out)

    def box(s, x0, x1, y0, y1, z0, z1, col, fc=None, skip=(), coll=False, jit=0.04):
        if x1 < x0: x0, x1 = x1, x0
        if y1 < y0: y0, y1 = y1, y0
        if z1 < z0: z0, z1 = z1, z0
        if x1 - x0 < 1e-4 or y1 - y0 < 1e-4 or z1 - z0 < 1e-4:
            return
        k = 1.0 + (s.rnd.random() - .5) * jit
        fc = fc or {}
        def C(f):
            return mul(fc.get(f, col), k)
        if y0 <= 0.05 and '-y' not in skip: skip = tuple(skip) + ('-y',)
        if '+x' not in skip: s.quad((x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1), C('+x'), (1, 0, 0))
        if '-x' not in skip: s.quad((x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), C('-x'), (-1, 0, 0))
        if '+y' not in skip: s.quad((x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), mul(C('+y'), 1.0), (0, 1, 0))
        if '-y' not in skip: s.quad((x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), mul(C('-y'), .8), (0, -1, 0))
        if '+z' not in skip: s.quad((x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), C('+z'), (0, 0, 1))
        if '-z' not in skip: s.quad((x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0), C('-z'), (0, 0, -1))
        if coll:
            s.boxes.append((x0, x1, y0, y1, z0, z1))

    def cbox(s, x0, x1, y0, y1, z0, z1):
        s.boxes.append((min(x0, x1), max(x0, x1), min(y0, y1), max(y0, y1), min(z0, z1), max(z0, z1)))

    def surface(s, rect, planes, standable=True, ceil_thick=0.0):
        pl = list(planes)[:4]
        while len(pl) < 4:
            pl.append((0.0, 0.0, 1e4))
        s.surf.append(dict(rect=tuple(rect), planes=pl, flag=(1 if standable else 0) | (2 if ceil_thick > 0 else 0), thick=ceil_thick))

    # ---------------------------------------------------------------- walls
    def wall_run(s, axis, fixed, lo, hi, y0, y1, ext, intc, outer=None, gaps=(), coll=True, t=T, fcx=None, wins=()):
        """axis 'x': wall runs along x, occupies z in [fixed, fixed+t].  axis 'z': runs along z, x in [fixed, fixed+t].
        outer: '+z','-z','+x','-x' = the face painted with ext (others intc, edges ext).  gaps: (a,b,height) openings from y0 up."""
        segs = []
        cur = lo
        for (a, b, gh) in sorted(gaps):
            if a > cur: segs.append((cur, a, None))
            segs.append((a, b, gh))
            cur = b
        if cur < hi: segs.append((cur, hi, None))
        for (a, b, gh) in segs:
            if gh is None:
                ya, yb = y0, y1
            else:
                ya, yb = y0 + gh, y1
            if yb - ya < 1e-3: continue
            fc = {}
            for f in ('+x', '-x', '+z', '-z'):
                fc[f] = intc
            if outer: fc[outer] = ext
            if outer is None:
                fc = {f: intc for f in ('+x', '-x', '+z', '-z')}
            if outer:
                for f2 in (('+x', '-x') if axis == 'x' else ('+z', '-z')): fc[f2] = ext
            # v30: real window holes (sill below, lintel above, glass is a separate breakable pane)
            ws_ = sorted(w for w in wins if gh is None and w[0] >= a - 1e-6 and w[1] <= b + 1e-6)
            pcs = []; c2 = a
            for (wa, wb, ylo, yhi) in ws_:
                if wa > c2: pcs.append((c2, wa, ya, yb, coll, 0))
                pcs.append((wa, wb, ya, ylo, False, 1)); pcs.append((wa, wb, yhi, yb, False, 2)); c2 = wb
            if c2 < b: pcs.append((c2, b, ya, yb, coll, 0))
            for (p0, p1, q0, q1, cl, kd) in pcs:
                if q1 - q0 < 1e-3: continue
                if axis == 'x': s.box(p0, p1, q0, q1, fixed, fixed + t, ext, fc, coll=cl)
                else: s.box(fixed, fixed + t, q0, q1, p0, p1, ext, fc, coll=cl)
                if kd and coll:   # sill: collision 0.3 lower (a hop clears it) / lintel: collision 0.5 higher (the head passes)
                    q0c, q1c = (q0, q1 - 0.3) if kd == 1 else (q0 + 0.5, q1)
                    if q1c > q0c:
                        if axis == 'x': s.cbox(p0, p1, q0c, q1c, fixed, fixed + t)
                        else: s.cbox(fixed, fixed + t, q0c, q1c, p0, p1)

    # ---------------------------------------------------------------- windows (centred at u along the wall, y centre)
    def fbars(s, ax, u, yc, w, h, ra, rb, col):
        """v30: 4 frame bars around a window HOLE (ax 'x': wall along x, z range ra..rb | 'z': wall along z, x range ra..rb)"""
        hw, hh = w / 2, h / 2
        for (u0, u1, y0, y1) in ((u - hw - .09, u - hw, yc - hh - .09, yc + hh + .09), (u + hw, u + hw + .09, yc - hh - .09, yc + hh + .09), (u - hw, u + hw, yc + hh, yc + hh + .09), (u - hw, u + hw, yc - hh - .09, yc - hh)):
            if ax == 'x': s.box(u0, u1, y0, y1, ra, rb, col, jit=0)
            else: s.box(ra, rb, y0, y1, u0, u1, col, jit=0)

    def window(s, W, D, side, u, yc, w, h, frame, glass, shut=None, sill=True):
        tt = T
        if side in ('+z', '-z'):
            sg = 1 if side == '+z' else -1
            zo = sg * D / 2; zi = sg * (D / 2 - tt)
            za, zb = sorted((zi - sg * 0.03, zo + sg * 0.03))
            s.fbars('x', u, yc, w, h, za, zb, frame)
            za, zb = sorted((zi - sg * 0.05, zo + sg * 0.05))
            s.dyn.append(('w', u - w / 2, u + w / 2, yc - h / 2, yc + h / 2, za, zb, glass))
            if sill:
                za, zb = sorted((zo - sg * 0.05, zo + sg * 0.16))
                s.box(u - w / 2 - .2, u + w / 2 + .2, yc - h / 2 - .14, yc - h / 2 - .07, za, zb, frame, jit=0)
            if shut:
                za, zb = sorted((zo, zo + sg * 0.07))
                for q in (-1, 1):
                    xs = u + q * (w / 2 + .09 + .25)
                    s.box(xs - .25, xs + .25, yc - h / 2 - .1, yc + h / 2 + .1, za, zb, shut, jit=0.02)
        else:
            sg = 1 if side == '+x' else -1
            xo = sg * W / 2; xi = sg * (W / 2 - tt)
            xa, xb = sorted((xi - sg * 0.03, xo + sg * 0.03))
            s.fbars('z', u, yc, w, h, xa, xb, frame)
            xa, xb = sorted((xi - sg * 0.05, xo + sg * 0.05))
            s.dyn.append(('w', xa, xb, yc - h / 2, yc + h / 2, u - w / 2, u + w / 2, glass))
            if sill:
                xa, xb = sorted((xo - sg * 0.05, xo + sg * 0.16))
                s.box(xa, xb, yc - h / 2 - .14, yc - h / 2 - .07, u - w / 2 - .2, u + w / 2 + .2, frame, jit=0)
            if shut:
                xa, xb = sorted((xo, xo + sg * 0.07))
                for q in (-1, 1):
                    zs = u + q * (w / 2 + .09 + .25)
                    s.box(xa, xb, yc - h / 2 - .1, yc + h / 2 + .1, zs - .25, zs + .25, shut, jit=0.02)


def rect_minus(rect, hole):
    """rect minus hole -> list of rects (x0,x1,z0,z1)"""
    x0, x1, z0, z1 = rect
    hx0, hx1, hz0, hz1 = hole
    hx0 = max(hx0, x0); hx1 = min(hx1, x1); hz0 = max(hz0, z0); hz1 = min(hz1, z1)
    if hx0 >= hx1 or hz0 >= hz1:
        return [rect]
    out = []
    if hz0 > z0: out.append((x0, x1, z0, hz0))
    if hz1 < z1: out.append((x0, x1, hz1, z1))
    if hx0 > x0: out.append((x0, hx0, hz0, hz1))
    if hx1 < x1: out.append((hx1, x1, hz0, hz1))
    return out
