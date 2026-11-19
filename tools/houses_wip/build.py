"""Generic hollow-house builder on top of kit.Model."""
import math
from kit import *

WALLC_IN = (0.94, 0.91, 0.84)
CEILC = (0.97, 0.97, 0.95)
GLASS = (0.30, 0.46, 0.60)
WHITE = (0.95, 0.95, 0.93)
WOOD_D = (0.36, 0.23, 0.14)


def build(sp, seed):
    m = Model(seed)
    W, D = sp['W'], sp['D']
    hs = sp['h']
    n = len(hs)
    y = [GF]
    for h in hs: y.append(y[-1] + h)
    iw, idp = W / 2 - T, D / 2 - T
    ext = sp['ext']; trim = sp.get('trim', WHITE); intc = sp.get('intc', WALLC_IN)
    floorc = sp.get('floorc', [(0.55, 0.38, 0.25)] * n)
    dx = sp.get('door_x', 0.0)
    info = dict(y=y, W=W, D=D, n=n, dx=dx, keep=[[] for _ in range(n)], gaps=[[] for _ in range(n)])

    # ---------------------------------------------------------------- stairs (floor i -> i+1)
    holes = {}
    stairs = []
    for i, st in enumerate(sp['stairs']):
        side = st['side']; slope = st.get('slope', 0.62); rise = y[i + 1] - y[i]; L = rise / slope
        zA = -idp + st.get('z0', 0.6)
        sx0, sx1 = (iw - 1.65, iw - 0.05) if side == 'E' else (-iw + 0.05, -iw + 1.65)
        stairs.append(dict(i=i, side=side, x0=sx0, x1=sx1, zA=zA, zB=zA + L, slope=slope))
        holes[i + 1] = (sx0 - 0.02, sx1 + 0.02, zA - 0.05, zA + L + 0.5)
        # visual steps + walkable ramp
        k = max(2, int(round(rise / 0.18)))
        col = sp.get('stair_c', (0.62, 0.50, 0.38))
        for q in range(k):
            za = zA + L * q / k; zb = zA + L * (q + 1) / k
            top = y[i] + rise * (q + 1) / k
            m.box(sx0, sx1, y[i] - (SLAB if i > 0 else 0), top, za, zb, col, fc={'+y': mul(col, 1.12)}, jit=0.02)
        m.surface((sx0, sx1, zA, zA + L), [(0.0, slope, y[i] - slope * zA)], True, 0.0)
        # keepouts: area occupied on floor i and landing on floor i+1
        info['keep'][i].append((sx0 - 0.3, sx1 + 0.3, zA - 1.0, zA + L + 0.3))
        info['keep'][i + 1].append((sx0 - 0.3, sx1 + 0.3, zA - 0.3, zA + L + 1.9))
        # rails on the upper level
        ry = y[i + 1]
        rc = sp.get('rail_c', WOOD_D)
        if side == 'E':
            m.box(sx0 - 0.05, sx0 + 0.01, ry, ry + 0.95, zA - 0.05, zA + L + 0.5, rc, coll=True, jit=0)
        else:
            m.box(sx1 - 0.01, sx1 + 0.05, ry, ry + 0.95, zA - 0.05, zA + L + 0.5, rc, coll=True, jit=0)
        m.box(sx0, sx1, ry, ry + 0.95, zA - 0.07, zA - 0.01, rc, coll=True, jit=0)

    # ---------------------------------------------------------------- floors (slabs)
    for f in range(n):
        rect = (-iw, iw, -idp, idp)
        pieces = rect_minus(rect, holes[f]) if f in holes else [rect]
        for (a0, a1, b0, b1) in pieces:
            if f == 0:
                m.box(a0, a1, 0, GF, b0, b1, floorc[0], fc={'+y': floorc[0]}, jit=0.02)
            else:
                m.box(a0, a1, y[f] - SLAB, y[f], b0, b1, floorc[f], fc={'+y': floorc[f], '-y': CEILC}, coll=True, jit=0.02)
            m.surface((a0, a1, b0, b1), [(0, 0, y[f])], True, SLAB if f > 0 else 0.0)

    # ---------------------------------------------------------------- walls
    bd = sp.get('balc')
    for f in range(n):
        ya = 0 if f == 0 else y[f] - SLAB
        yb = (y[f + 1] - SLAB) if f < n - 1 else y[n]
        e = ext[f] if isinstance(ext, list) else ext
        fgaps = []
        if f == 0:
            fgaps.append((dx - DOOR_W / 2, dx + DOOR_W / 2, DOOR_H)); info['gaps'][0].append(('+z', dx))
        if bd and f in bd['floors']:
            bx = bd.get('door_x', 0.0)
            fgaps.append((bx - 0.8, bx + 0.8, DOOR_H)); info['gaps'][f].append(('+z', bx))
        m.wall_run('x', D / 2 - T, -W / 2, W / 2, ya, yb, e, intc, '+z', fgaps)
        m.wall_run('x', -D / 2, -W / 2, W / 2, ya, yb, e, intc, '-z')
        m.wall_run('z', -W / 2, -D / 2 + T, D / 2 - T, ya, yb, e, intc, '-x')
        m.wall_run('z', W / 2 - T, -D / 2 + T, D / 2 - T, ya, yb, e, intc, '+x')
        if sp.get('band') and f > 0:
            bc = sp['band']
            yy = y[f] - SLAB - 0.04
            m.box(-W / 2 - .06, W / 2 + .06, yy, yy + .2, D / 2 - .0, D / 2 + .06, bc, jit=0)
            m.box(-W / 2 - .06, W / 2 + .06, yy, yy + .2, -D / 2 - .06, -D / 2 + .0, bc, jit=0)
            m.box(-W / 2 - .06, -W / 2 + .0, yy, yy + .2, -D / 2, D / 2, bc, jit=0)
            m.box(W / 2 - .0, W / 2 + .06, yy, yy + .2, -D / 2, D / 2, bc, jit=0)
        # partition
        pt = sp.get('part', {}).get(f)
        if pt:
            pz, plo, phi, pdx = pt
            pg = [(pdx - 0.62, pdx + 0.62, DOOR_H)]
            m.wall_run('x', pz - 0.1, plo, phi, ya if f else 0, yb, intc, intc, None, pg, t=0.2)
            info['keep'][f].append((pdx - 1.3, pdx + 1.3, pz - 1.3, pz + 1.3))
            info.setdefault('part', {})[f] = pt
    if sp.get('corner'):
        for sx in (-1, 1):
            for sz in (-1, 1):
                m.box(sx * W / 2 - (0.1 if sx > 0 else 0), sx * W / 2 + (0 if sx > 0 else 0.1) if False else sx * W / 2, 0, y[n], sz * D / 2, sz * D / 2, sp['corner'], jit=0)

    # ---------------------------------------------------------------- door
    z1 = D / 2
    j = sp.get('door_c', trim)
    m.box(dx - DOOR_W / 2 - .12, dx - DOOR_W / 2, 0, DOOR_H + .12, z1 - T - .02, z1 + .08, j, jit=0)
    m.box(dx + DOOR_W / 2, dx + DOOR_W / 2 + .12, 0, DOOR_H + .12, z1 - T - .02, z1 + .08, j, jit=0)
    m.box(dx - DOOR_W / 2 - .12, dx + DOOR_W / 2 + .12, DOOR_H, DOOR_H + .14, z1 - T - .02, z1 + .08, j, jit=0)
    m.box(dx + DOOR_W / 2 - 0.02, dx + DOOR_W / 2 + 0.9, 0.03, DOOR_H - .02, z1 - T - 0.07, z1 - T - 0.02, sp.get('door_leaf', WOOD_D), jit=0.03)   # the door, swung open against the inside wall
    # a balcony-door frame
    if bd:
        for f in bd['floors']:
            bx = bd.get('door_x', 0.0)
            m.box(bx - 0.8, bx + 0.8, y[f] + DOOR_H, y[f] + DOOR_H + .12, z1 - T - .02, z1 + .06, trim, jit=0)

    # ---------------------------------------------------------------- windows
    ws = sp.get('ws', (1.0, 1.3))
    for side, lst in sp.get('win', {}).items():
        for f, us in enumerate(lst):
            for u in us:
                w, h = ws if not isinstance(u, tuple) else (u[1], u[2])
                uu = u if not isinstance(u, tuple) else u[0]
                yc = y[f] + (sp.get('sill', 1.55) if h < 1.8 else 1.15 + h / 2)
                if h >= 1.8: yc = y[f] + h / 2 + 0.25
                m.window(W, D, side, uu, yc, w, h, trim, sp.get('glass', GLASS), sp.get('shut') if side == '+z' else None)

    # ---------------------------------------------------------------- ceiling / roof slab
    topc = sp['roof'].get('slabc', CEILC)
    pieces = [(-W / 2, W / 2, -D / 2, D / 2)]
    rt = sp['roof']['type']
    for (a0, a1, b0, b1) in pieces:
        m.box(a0, a1, y[n] - SLAB, y[n], b0, b1, rt == 'flat' and sp['roof']['col'] or CEILC, fc={'-y': CEILC, '+y': sp['roof']['col'] if rt == 'flat' else mul(CEILC, .9)}, coll=True, jit=0.02)
    from roofs import add_roof
    add_roof(m, sp, y, info)

    # ---------------------------------------------------------------- porch / balcony / extras
    if sp.get('porch'):
        pc = sp['porch']
        pd = pc['depth']
        x0, x1 = pc['x0'], pc['x1']
        m.box(x0, x1, 0, GF, D / 2, D / 2 + pd, pc.get('floorc', (0.66, 0.65, 0.62)), jit=0.02)
        m.surface((x0, x1, D / 2, D / 2 + pd), [(0, 0, GF)], True, 0)
        info['porch'] = (x0, x1, D / 2, D / 2 + pd)
        if pc.get('roof'):
            ry = pc['roof']
            m.box(x0 - .1, x1 + .1, ry, ry + .22, D / 2 - .0, D / 2 + pd + .15, pc.get('roofc', trim), coll=True, jit=0.02)
            m.surface((x0 - .1, x1 + .1, D / 2, D / 2 + pd + .15), [(0, 0, ry + .22)], True, .22)
            for cx in pc['cols']:
                cc = pc.get('colc', trim)
                m.box(cx - .15, cx + .15, GF, ry, D / 2 + pd - .35, D / 2 + pd - .05, cc, coll=True, jit=0.02)
                m.box(cx - .22, cx + .22, GF, GF + .25, D / 2 + pd - .42, D / 2 + pd + .02, mul(cc, .92), jit=0)
        if pc.get('rail'):
            rc = pc.get('railc', trim)
            for (xa, xb) in pc['rail']:
                m.box(xa, xb, GF + .88, GF + .98, D / 2 + pd - .18, D / 2 + pd - .08, rc, coll=True, jit=0)
                nb = max(2, int((xb - xa) / .28))
                for q in range(nb + 1):
                    xx = xa + (xb - xa) * q / nb
                    m.box(xx - .025, xx + .025, GF, GF + .88, D / 2 + pd - .15, D / 2 + pd - .1, rc, jit=0)
    if bd:
        bdp = bd['depth']
        x0, x1 = bd['x0'], bd['x1']
        for f in bd['floors']:
            m.box(x0, x1, y[f] - SLAB, y[f], D / 2, D / 2 + bdp, bd.get('slabc', trim), fc={'+y': bd.get('floorc', (0.7, 0.68, 0.64)), '-y': CEILC}, coll=True, jit=0.02)
            m.surface((x0, x1, D / 2, D / 2 + bdp), [(0, 0, y[f])], True, SLAB)
            rc = bd.get('railc', trim)
            glassr = bd.get('glass_rail')
            if glassr:
                gc = (0.62, 0.78, 0.84)
                m.box(x0, x1, y[f] + .1, y[f] + 1.0, D / 2 + bdp - .06, D / 2 + bdp - .02, gc, coll=True, jit=0)
                m.box(x0, x0 + .04, y[f] + .1, y[f] + 1.0, D / 2, D / 2 + bdp, gc, coll=True, jit=0)
                m.box(x1 - .04, x1, y[f] + .1, y[f] + 1.0, D / 2, D / 2 + bdp, gc, coll=True, jit=0)
                m.box(x0, x1, y[f] + 1.0, y[f] + 1.06, D / 2 + bdp - .1, D / 2 + bdp, rc, jit=0)
            else:
                m.box(x0, x1, y[f] + .92, y[f] + 1.0, D / 2 + bdp - .14, D / 2 + bdp - .04, rc, coll=True, jit=0)
                m.box(x0, x0 + .08, y[f] + .0, y[f] + 1.0, D / 2, D / 2 + bdp, rc, coll=True, jit=0)
                m.box(x1 - .08, x1, y[f] + .0, y[f] + 1.0, D / 2, D / 2 + bdp, rc, coll=True, jit=0)
                nb = int((x1 - x0) / .3)
                for q in range(nb + 1):
                    xx = x0 + (x1 - x0) * q / nb
                    m.box(xx - .03, xx + .03, y[f] + .05, y[f] + .92, D / 2 + bdp - .12, D / 2 + bdp - .06, rc, jit=0)
            if bd.get('pillars') and f == bd['floors'][0]:
                for px in bd['pillars']:
                    m.box(px - .17, px + .17, GF, y[f] - SLAB, D / 2 + bdp - .4, D / 2 + bdp - .06, bd.get('pillc', trim), coll=True, jit=0.02)
        info['balc'] = (x0, x1, D / 2, D / 2 + bdp)
    if sp.get('extras'):
        sp['extras'](m, sp, y, info)

    # ---------------------------------------------------------------- furniture + loot
    from interior import furnish
    furnish(m, sp, y, info)
    return m, info
