"""Generic hollow-house builder on top of kit.Model."""
import math
from kit import *

WALLC_IN = (0.94, 0.91, 0.84)
CEILC = (0.97, 0.97, 0.95)
GLASS = (0.30, 0.46, 0.60)
WHITE = (0.95, 0.95, 0.93)
WOOD_D = (0.36, 0.23, 0.14)


def _pick_partition_x(sp, f, iw, bd, ws0, dx):
    """v40: x of the centre partition wall of floor f that does not hit a window, the front door, a balcony door or a stair strip.
    Returns None when no clean x exists (then that floor stays one big room, as before)."""
    lim = iw - 1.65 - 1.45
    iv = []
    if f == 0: iv.append((dx - DOOR_W / 2 - 0.12, dx + DOOR_W / 2 + 0.12))
    if bd and f in bd['floors']: bx = bd.get('door_x', 0.0); iv.append((bx - 0.92, bx + 0.92))
    for side in ('+z', '-z'):
        lst = sp.get('win', {}).get(side, [])
        if f < len(lst):
            for u in lst[f]:
                w = ws0[0] if not isinstance(u, tuple) else u[1]
                uu = u if not isinstance(u, tuple) else u[0]
                iv.append((uu - w / 2 - 0.09, uu + w / 2 + 0.09))
    for M in (0.35, 0.25, 0.15):
        k = 0
        while k * 0.05 <= lim + 1e-9:
            for sg in ((1, -1) if k else (1,)):
                x = sg * k * 0.05
                if all(x + M <= a or x - M >= b for (a, b) in iv): return round(x, 3)
            k += 1
    return None


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

    # ---------------------------------------------------------------- stairs (floor i -> i+1, and the last one up to the ROOF)
    from roofs import roof_surface_def, roof_h
    holes = {}
    stairs = []
    stairs_spec = list(sp['stairs'])
    if len(stairs_spec) == n - 1:   # roof access: one more stair from the top floor, on the other side
        last = stairs_spec[-1]
        stairs_spec.append(dict(side='W' if last['side'] == 'E' else 'E', z0=last.get('z0', 0.6), slope=last.get('slope', 0.62)))
    sdef = roof_surface_def(sp, y, n)
    info['roofcut'] = None; info['slabhole'] = None; info['roofstair'] = None
    for i, st in enumerate(stairs_spec):
        side = st['side']; slope = st.get('slope', 0.62)
        zA = -idp + st.get('z0', 0.6)
        sx0, sx1 = (iw - 1.65, iw - 0.05) if side == 'E' else (-iw + 0.05, -iw + 1.65)
        roofst = (i == n - 1)
        if roofst and sdef is not None:
            # pitched roof: the stair keeps climbing through the ceiling and through a hatch cut in the roof until it meets the roof surface
            base = y[i]; xs_ = (sx0, (sx0 + sx1) / 2, sx1)
            zt = None; za = None; z = zA
            while z < idp - 0.3:
                ry_ = base + slope * (z - zA)
                if za is None and ry_ > y[n] + 0.1 and ry_ + 1.9 >= min(roof_h(sdef, x_, z) for x_ in xs_): za = z
                if ry_ > y[n] + 0.3 and ry_ >= max(roof_h(sdef, x_, z) for x_ in xs_) - 0.02: zt = z; break
                z += 0.05
            assert zt is not None, sp['name'] + ': roof stair does not reach the roof inside the house'
            if za is None: za = zt - 0.5
            rise = base + slope * (zt - zA) - base; L = zt - zA; y_top = base + rise
            info['roofcut'] = (sx0 - 0.02, sx1 + 0.02, za - 0.3, zt + 0.05)
            info['roofstair'] = dict(x=(sx0 + sx1) / 2, z=zt, y=y_top)
        else:
            rise = y[i + 1] - y[i]; L = rise / slope; y_top = y[i + 1]
            if roofst: info['roofstair'] = dict(x=(sx0 + sx1) / 2, z=zA + L, y=y_top)
        stairs.append(dict(i=i, side=side, x0=sx0, x1=sx1, zA=zA, zB=zA + L, slope=slope))
        holes[i + 1] = (sx0 - 0.02, sx1 + 0.02, zA - 0.05, zA + L + (0.05 if roofst else 0.0))
        # visual steps + walkable ramp
        k = max(2, int(round(rise / 0.18)))
        col = sp.get('stair_c', (0.62, 0.50, 0.38))
        for q in range(k):
            za_ = zA + L * q / k; zb_ = zA + L * (q + 1) / k
            top = y[i] + rise * (q + 1) / k
            m.box(sx0, sx1, y[i] - (SLAB if i > 0 else 0), top, za_, zb_, col, fc={'+y': mul(col, 1.12)}, jit=0.02)
        m.surface((sx0 - 0.02, sx1 + 0.02, zA - 0.05, zA + L), [(0.0, slope, y[i] - slope * zA + 0.5 * rise / k), (0.0, 0.0, y_top)], True, 0.0)
        # keepouts: area occupied on floor i and landing on floor i+1
        info['keep'][i].append((sx0 - 1.4, sx1 + 1.4, zA - 1.0, zA + L + 0.3))
        if i + 1 < n: info['keep'][i + 1].append((sx0 - 1.4, sx1 + 1.4, zA - 0.3, zA + L + 1.9))
        # rails on the upper level (not on a pitched roof: there the hatch itself is the edge)
        if roofst and sdef is not None: continue
        ry = y_top
        rc = sp.get('rail_c', WOOD_D)
        if side == 'E':
            m.box(sx0 - 0.05, sx0 + 0.01, ry, ry + 0.95, zA - 0.05, zA + L, rc, coll=True, jit=0)
        else:
            m.box(sx1 - 0.01, sx1 + 0.05, ry, ry + 0.95, zA - 0.05, zA + L, rc, coll=True, jit=0)
        m.box(sx0, sx1, ry, ry + 0.95, zA - 0.07, zA - 0.01, rc, coll=True, jit=0)
    info['slabhole'] = holes.get(n)

    # ---------------------------------------------------------------- v40: rooms - a centre partition with a REAL door on every floor
    xpx = {}
    xpz = {}
    if sp.get('rooms', True):
        for f in range(n):
            px_ = _pick_partition_x(sp, f, iw, sp.get('balc'), sp.get('ws', (1.0, 1.3)), dx)
            if px_ is None: continue
            xpx[f] = px_
            st_ = stairs[f] if f < len(stairs) else stairs[-1]
            xpz[f] = round(min(max((st_['zA'] + st_['zB']) / 2, -idp + 1.5), idp - 1.5), 3)
            info['keep'][f].append((px_ - 0.5, px_ + 0.5, -idp, idp))                       # nothing stands against the partition
            info['keep'][f].append((px_ - 1.4, px_ + 1.4, xpz[f] - 1.4, xpz[f] + 1.4))      # the open leaf swings here
    info['xpart'] = (xpx, xpz)

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
    ws0 = sp.get('ws', (1.0, 1.3))
    def wins(side, f):   # v30: window holes (u0, u1, ylo, yhi) of one wall on one floor
        out = []; lst = sp.get('win', {}).get(side)
        if not lst or f >= len(lst): return out
        for u in lst[f]:
            w, h = ws0 if not isinstance(u, tuple) else (u[1], u[2]); uu = u if not isinstance(u, tuple) else u[0]
            yc = y[f] + (sp.get('sill', 1.55) if h < 1.8 else 1.15 + h / 2)
            if h >= 1.8: yc = y[f] + h / 2 + 0.25
            out.append((uu - w / 2, uu + w / 2, yc - h / 2, yc + h / 2))
        return out
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
        m.wall_run('x', D / 2 - T, -W / 2, W / 2, ya, yb, e, intc, '+z', fgaps, wins=wins('+z', f))
        m.wall_run('x', -D / 2, -W / 2, W / 2, ya, yb, e, intc, '-z', wins=wins('-z', f))
        m.wall_run('z', -W / 2, -D / 2 + T, D / 2 - T, ya, yb, e, intc, '-x', wins=wins('-x', f))
        m.wall_run('z', W / 2 - T, -D / 2 + T, D / 2 - T, ya, yb, e, intc, '+x', wins=wins('+x', f))
        if sp.get('band') and f > 0:
            bc = sp['band']
            yy = y[f] - SLAB - 0.04
            m.box(-W / 2 - .06, W / 2 + .06, yy, yy + .2, D / 2 - .0, D / 2 + .06, bc, jit=0)
            m.box(-W / 2 - .06, W / 2 + .06, yy, yy + .2, -D / 2 - .06, -D / 2 + .0, bc, jit=0)
            m.box(-W / 2 - .06, -W / 2 + .0, yy, yy + .2, -D / 2, D / 2, bc, jit=0)
            m.box(W / 2 - .0, W / 2 + .06, yy, yy + .2, -D / 2, D / 2, bc, jit=0)
        if f in xpx:
            pdz = xpz[f]; px_ = xpx[f]
            m.wall_run('z', px_ - 0.1, -D / 2 + T, D / 2 - T, ya, yb, intc, intc, None, [(pdz - 0.62, pdz + 0.62, DOOR_H)], t=0.2)
            fy0 = 0.0 if f == 0 else y[f] - SLAB
            for zz in (pdz - 0.68, pdz + 0.62):      # door frame (jambs + head), visual only
                m.box(px_ - 0.13, px_ + 0.13, fy0, fy0 + DOOR_H, zz, zz + 0.06, trim, jit=0)
            m.box(px_ - 0.13, px_ + 0.13, fy0 + DOOR_H, fy0 + DOOR_H + 0.08, pdz - 0.68, pdz + 0.68, trim, jit=0)
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
    m.box(dx - DOOR_W / 2, dx + DOOR_W / 2, 0, GF, z1 - T, z1 + .02, floorc[0], jit=0)
    m.surface((dx - DOOR_W / 2, dx + DOOR_W / 2, z1 - T - 0.05, z1 + 0.05), [(0, 0, GF)], True, 0.0)
    if bd:
        for f in bd['floors']:
            bx = bd.get('door_x', 0.0)
            m.box(bx - 0.8, bx + 0.8, y[f] - SLAB, y[f], z1 - T, z1 + .02, floorc[f], jit=0)
            m.surface((bx - 0.8, bx + 0.8, z1 - T - 0.05, z1 + 0.05), [(0, 0, y[f])], True, 0.0)
    j = sp.get('door_c', trim)
    m.box(dx - DOOR_W / 2 - .12, dx - DOOR_W / 2, 0, DOOR_H + .12, z1 - T - .02, z1 + .08, j, jit=0)
    m.box(dx + DOOR_W / 2, dx + DOOR_W / 2 + .12, 0, DOOR_H + .12, z1 - T - .02, z1 + .08, j, jit=0)
    m.box(dx - DOOR_W / 2 - .12, dx + DOOR_W / 2 + .12, DOOR_H, DOOR_H + .14, z1 - T - .02, z1 + .08, j, jit=0)
    m.dyn.append(('d', dx - DOOR_W / 2, dx + DOOR_W / 2, 0.03, DOOR_H - .02, z1 - T, z1, dx + DOOR_W / 2, z1 - T / 2, -1.0, 0.0, 0.0, -1.0, sp.get('door_leaf', WOOD_D)))   # v30: real door leaf (hinge on the right jamb, swings IN)
    # v30b: balcony doors (two leaves) and the doors of the inner partition walls are real doors too
    leafc = sp.get('door_leaf', WOOD_D)
    if bd:
        for f in bd['floors']:
            bx = bd.get('door_x', 0.0); yb0 = y[f] + 0.02; yt0 = y[f] - SLAB + DOOR_H - 0.02
            m.dyn.append(('d', bx - 0.8, bx, yb0, yt0, z1 - T, z1, bx - 0.8, z1 - T / 2, 1.0, 0.0, 0.0, -1.0, leafc))
            m.dyn.append(('d', bx, bx + 0.8, yb0, yt0, z1 - T, z1, bx + 0.8, z1 - T / 2, -1.0, 0.0, 0.0, -1.0, leafc))
    for f, pt in sp.get('part', {}).items():
        pz, plo, phi, pdx = pt
        m.dyn.append(('d', pdx - 0.62, pdx + 0.62, 0.03 if f == 0 else y[f] + 0.02, (DOOR_H - 0.02) if f == 0 else (y[f] - SLAB + DOOR_H - 0.02), pz - 0.1, pz + 0.1, pdx + 0.62, pz, -1.0, 0.0, 0.0, -1.0, leafc))
    if xpx:   # v40: the leaf of every centre partition door (hinge on the front jamb, closed toward the back, swings toward the centre of the house)
        for f in sorted(xpx):
            pdz = xpz[f]; px_ = xpx[f]
            yb0 = 0.03 if f == 0 else y[f] + 0.02
            yt0 = (DOOR_H - 0.02) if f == 0 else (y[f] - SLAB + DOOR_H - 0.02)
            m.dyn.append(('d', px_ - 0.1, px_ + 0.1, yb0, yt0, pdz - 0.62, pdz + 0.62, px_, pdz + 0.62, 0.0, -1.0, (-1.0 if px_ >= 0 else 1.0), 0.0, leafc))
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
    rt = sp['roof']['type']
    rect_ = (-W / 2, W / 2, -D / 2, D / 2)
    pieces = rect_minus(rect_, holes[n]) if n in holes else [rect_]
    for (a0, a1, b0, b1) in pieces:
        m.box(a0, a1, y[n] - SLAB, y[n], b0, b1, rt == 'flat' and sp['roof']['col'] or CEILC, fc={'-y': CEILC, '+y': sp['roof']['col'] if rt == 'flat' else mul(CEILC, .9)}, coll=True, jit=0.02)
        if rt != 'flat': m.surface((a0, a1, b0, b1), [(0, 0, y[n])], False, SLAB)
        else: m.surface((a0, a1, b0, b1), [(0, 0, y[n])], False, SLAB)
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
