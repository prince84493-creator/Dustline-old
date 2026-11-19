import math
import numpy as np
from kit import *


def roof_face(m, A, B, C, D, col, rows=8, jit=0.07, hole=None):
    """A,B = eave edge, D above A, C above B (ridge edge). Draws `rows` bands with alternating tone.
    hole = plan rect (x0, x1, z0, z1): the cells of the roof inside it are left out (the roof hatch)."""
    A, B, C, D = [np.array(p, float) for p in (A, B, C, D)]
    for r in range(rows):
        t0, t1 = r / rows, (r + 1) / rows
        a0 = A + (D - A) * t0; b0 = B + (C - B) * t0
        a1 = A + (D - A) * t1; b1 = B + (C - B) * t1
        k = 1.0 + (0.07 if r % 2 else -0.05) + (m.rnd.random() - .5) * jit
        out = np.cross(B - A, D - A)
        if out[1] < 0: out = -out
        if hole is not None:
            xs = [a0[0], b0[0], a1[0], b1[0]]; zs = [a0[2], b0[2], a1[2], b1[2]]
            if max(xs) > hole[0] and min(xs) < hole[1] and max(zs) > hole[2] and min(zs) < hole[3]:
                nc = max(1, int(math.ceil(float(np.linalg.norm(b0 - a0)) / 0.25)))
                for c in range(nc):
                    u0, u1 = c / nc, (c + 1) / nc
                    p00 = a0 + (b0 - a0) * u0; p10 = a0 + (b0 - a0) * u1; p01 = a1 + (b1 - a1) * u0; p11 = a1 + (b1 - a1) * u1
                    cx_ = (p00[0] + p10[0] + p01[0] + p11[0]) / 4; cz_ = (p00[2] + p10[2] + p01[2] + p11[2]) / 4
                    if hole[0] < cx_ < hole[1] and hole[2] < cz_ < hole[3]: continue
                    m.quad(p00, p10, p11, p01, mul(col, k), out)
                continue
        m.quad(a0, b0, b1, a1, mul(col, k), out)


def roof_surface_def(sp, y, n):
    """(rect, planes) of the standable pitched roof (same numbers add_roof uses), or None for a flat roof."""
    r = sp['roof']; t = r['type']
    if t == 'flat': return None
    W, D = sp['W'], sp['D']; yt = y[n]
    ov = r.get('ov', 0.45); s = r.get('slope', 0.55)
    hx, hz = W / 2 + ov, D / 2 + ov
    if t in ('gable_x', 'gable_z'):
        swap = (t == 'gable_z')
        if swap: hx, hz = hz, hx
        e = yt - s * ov; yr = e + s * hz
        return ((-hz, hz, -hx, hx), [(s, 0, e + s * hz), (-s, 0, e + s * hz)]) if swap else ((-hx, hx, -hz, hz), [(0, s, e + s * hz), (0, -s, e + s * hz)])
    e = yt - s * ov
    return ((-hx, hx, -hz, hz), [(0, -s, e + s * hz), (0, s, e + s * hz), (-s, 0, e + s * hx), (s, 0, e + s * hx)])


def roof_h(sd, x, z):
    rect, pl = sd
    if not (rect[0] <= x <= rect[1] and rect[2] <= z <= rect[3]): return -1e9
    return min(a * x + b * z + c for (a, b, c) in pl)


def add_roof(m, sp, y, info):
    W, D = sp['W'], sp['D']
    n = info['n']
    r = sp['roof']
    t = r['type']
    col = r['col']
    yt = y[n]
    cut = info.get('roofcut'); slabhole = info.get('slabhole')
    if t == 'flat':
        ph = r.get('parapet', 0.5)
        pc = r.get('pc', col)
        for (x0, x1, z0, z1) in ((-W / 2, W / 2, D / 2 - .25, D / 2), (-W / 2, W / 2, -D / 2, -D / 2 + .25), (-W / 2, -W / 2 + .25, -D / 2 + .25, D / 2 - .25), (W / 2 - .25, W / 2, -D / 2 + .25, D / 2 - .25)):
            m.box(x0, x1, yt, yt + ph, z0, z1, pc, fc={'+y': mul(pc, 1.1)}, coll=True, jit=0.03)
        for pc_ in (rect_minus((-W / 2, W / 2, -D / 2, D / 2), slabhole) if slabhole else [(-W / 2, W / 2, -D / 2, D / 2)]):
            m.surface(pc_, [(0, 0, yt)], True, SLAB)
        return
    ov = r.get('ov', 0.45); s = r.get('slope', 0.55)
    hx, hz = W / 2 + ov, D / 2 + ov
    wc = sp['ext'][-1] if isinstance(sp['ext'], list) else sp['ext']
    fas = r.get('fascia', sp.get('trim', (.95, .95, .93)))
    if t in ('gable_x', 'gable_z'):
        swap = (t == 'gable_z')
        if swap:
            hx, hz = hz, hx        # hz = half span across the ridge, hx = half length along the ridge (in 'local roof axes')
        e = yt - s * ov
        yr = e + s * hz
        def P(u, yy, v):           # u along ridge, v across ridge
            return (v, yy, u) if swap else (u, yy, v)
        roof_face(m, P(-hx, e, hz), P(hx, e, hz), P(hx, yr, 0), P(-hx, yr, 0), col, rows=r.get('rows', 9), hole=cut)
        roof_face(m, P(hx, e, -hz), P(-hx, e, -hz), P(-hx, yr, 0), P(hx, yr, 0), col, rows=r.get('rows', 9), hole=cut)
        # ridge cap
        if not swap:
            m.box(-hx, hx, yr - .02, yr + .1, -.1, .1, mul(col, .8), jit=0)
        else:
            m.box(-.1, .1, yr - .02, yr + .1, -hx, hx, mul(col, .8), jit=0)
        # fascia boards along the two eaves
        for sg in (-1, 1):
            if not swap:
                m.box(-hx, hx, e - .14, e + .08, sg * hz - .05 if sg > 0 else -hz - .03, sg * hz + .03 if sg > 0 else -hz + .05, fas, jit=0)
            else:
                m.box(sg * hz - .05 if sg > 0 else -hz - .03, sg * hz + .03 if sg > 0 else -hz + .05, e - .14, e + .08, -hx, hx, fas, jit=0)
        # gable walls (triangles) at both ends
        for sg in (-1, 1):
            if not swap:
                xw = sg * W / 2
                hh = e + s * (hz - D / 2)  # = yt
                m.tri((xw, yt, -D / 2), (xw, yt, D / 2), (xw, yr, 0), wc, (sg, 0, 0))
                m.tri((xw - sg * T, yt, -D / 2), (xw - sg * T, yt, D / 2), (xw - sg * T, yr - 0.0, 0), sp.get('intc', (.94, .91, .84)), (-sg, 0, 0))
            else:
                zw = sg * D / 2
                m.tri((-W / 2, yt, zw), (W / 2, yt, zw), (0, yr, zw), wc, (0, 0, sg))
                m.tri((-W / 2, yt, zw - sg * T), (W / 2, yt, zw - sg * T), (0, yr, zw - sg * T), sp.get('intc', (.94, .91, .84)), (0, 0, -sg))
        sd = roof_surface_def(sp, y, n)
        for pc_ in (rect_minus(sd[0], cut) if cut else [sd[0]]):
            m.surface(pc_, sd[1], True, 0)
        info['ridge'] = yr
    elif t == 'hip':
        assert W >= D
        e = yt - s * ov
        yr = e + s * hz
        rx = hx - hz
        roof_face(m, (-hx, e, hz), (hx, e, hz), (rx, yr, 0), (-rx, yr, 0), col, rows=r.get('rows', 9), hole=cut)
        roof_face(m, (hx, e, -hz), (-hx, e, -hz), (-rx, yr, 0), (rx, yr, 0), col, rows=r.get('rows', 9), hole=cut)
        roof_face(m, (-hx, e, -hz), (-hx, e, hz), (-rx, yr, 0), (-rx, yr, 0), col, rows=r.get('rows', 9), hole=cut)
        roof_face(m, (hx, e, hz), (hx, e, -hz), (rx, yr, 0), (rx, yr, 0), col, rows=r.get('rows', 9), hole=cut)
        m.box(-rx, rx, yr - .02, yr + .12, -.1, .1, mul(col, .8), jit=0)
        for sg in (-1, 1):
            m.box(-hx, hx, e - .14, e + .08, sg * hz - .04, sg * hz + .04, fas, jit=0)
            m.box(sg * hx - .04, sg * hx + .04, e - .14, e + .08, -hz, hz, fas, jit=0)
        sd = roof_surface_def(sp, y, n)
        for pc_ in (rect_minus(sd[0], cut) if cut else [sd[0]]):
            m.surface(pc_, sd[1], True, 0)
        info['ridge'] = yr
