import math
import numpy as np
from kit import *


def roof_face(m, A, B, C, D, col, rows=8, jit=0.07):
    """A,B = eave edge, D above A, C above B (ridge edge). Draws `rows` bands with alternating tone."""
    A, B, C, D = [np.array(p, float) for p in (A, B, C, D)]
    for r in range(rows):
        t0, t1 = r / rows, (r + 1) / rows
        a0 = A + (D - A) * t0; b0 = B + (C - B) * t0
        a1 = A + (D - A) * t1; b1 = B + (C - B) * t1
        k = 1.0 + (0.07 if r % 2 else -0.05) + (m.rnd.random() - .5) * jit
        out = np.cross(B - A, D - A)
        if out[1] < 0: out = -out
        m.quad(a0, b0, b1, a1, mul(col, k), out)


def add_roof(m, sp, y, info):
    W, D = sp['W'], sp['D']
    n = info['n']
    r = sp['roof']
    t = r['type']
    col = r['col']
    yt = y[n]
    if t == 'flat':
        ph = r.get('parapet', 0.5)
        pc = r.get('pc', col)
        for (x0, x1, z0, z1) in ((-W / 2, W / 2, D / 2 - .25, D / 2), (-W / 2, W / 2, -D / 2, -D / 2 + .25), (-W / 2, -W / 2 + .25, -D / 2 + .25, D / 2 - .25), (W / 2 - .25, W / 2, -D / 2 + .25, D / 2 - .25)):
            m.box(x0, x1, yt, yt + ph, z0, z1, pc, fc={'+y': mul(pc, 1.1)}, coll=True, jit=0.03)
        m.surface((-W / 2, W / 2, -D / 2, D / 2), [(0, 0, yt)], True, SLAB)
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
        roof_face(m, P(-hx, e, hz), P(hx, e, hz), P(hx, yr, 0), P(-hx, yr, 0), col, rows=r.get('rows', 9))
        roof_face(m, P(hx, e, -hz), P(-hx, e, -hz), P(-hx, yr, 0), P(hx, yr, 0), col, rows=r.get('rows', 9))
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
        if not swap:
            m.surface((-hx, hx, -hz, hz), [(0, s, e + s * hz), (0, -s, e + s * hz)], True, 0)
        else:
            m.surface((-hz, hz, -hx, hx), [(s, 0, e + s * hz), (-s, 0, e + s * hz)], True, 0)
        info['ridge'] = yr
    elif t == 'hip':
        assert W >= D
        e = yt - s * ov
        yr = e + s * hz
        rx = hx - hz
        roof_face(m, (-hx, e, hz), (hx, e, hz), (rx, yr, 0), (-rx, yr, 0), col, rows=r.get('rows', 9))
        roof_face(m, (hx, e, -hz), (-hx, e, -hz), (-rx, yr, 0), (rx, yr, 0), col, rows=r.get('rows', 9))
        roof_face(m, (-hx, e, -hz), (-hx, e, hz), (-rx, yr, 0), (-rx, yr, 0), col, rows=r.get('rows', 9))
        roof_face(m, (hx, e, hz), (hx, e, -hz), (rx, yr, 0), (rx, yr, 0), col, rows=r.get('rows', 9))
        m.box(-rx, rx, yr - .02, yr + .12, -.1, .1, mul(col, .8), jit=0)
        for sg in (-1, 1):
            m.box(-hx, hx, e - .14, e + .08, sg * hz - .04, sg * hz + .04, fas, jit=0)
            m.box(sg * hx - .04, sg * hx + .04, e - .14, e + .08, -hz, hz, fas, jit=0)
        m.surface((-hx, hx, -hz, hz), [(0, -s, e + s * hz), (0, s, e + s * hz), (-s, 0, e + s * hx), (s, 0, e + s * hx)], True, 0)
        info['ridge'] = yr
