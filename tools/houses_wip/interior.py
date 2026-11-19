import math
from kit import *

SOFA = (0.55, 0.42, 0.34)
BED = (0.78, 0.74, 0.66)
DARKW = (0.40, 0.27, 0.18)
CNT = (0.80, 0.78, 0.74)


def _inrect(x, z, r, pad=0.0):
    return r[0] - pad <= x <= r[1] + pad and r[2] - pad <= z <= r[3] + pad


def furnish(m, sp, y, info):
    rnd = m.rnd
    W, D = sp['W'], sp['D']
    n = info['n']
    iw, idp = W / 2 - T, D / 2 - T
    allboxes_floor = lambda f: [b for b in m.boxes if b[2] < y[f] + 1.0 and b[3] > y[f] + 0.2]
    info['furn'] = [[] for _ in range(n)]
    for f in range(n):
        keep = list(info['keep'][f])
        # door gaps on this floor: keep clear in front of / behind them
        for (side, gx) in info['gaps'][f]:
            keep.append((gx - 1.5, gx + 1.5, D / 2 - T - 1.8, D / 2 + 0.2))
        # balcony / porch outside is not furnished
        kinds = ['sofa', 'table', 'counter', 'wardrobe'] if f == 0 else ['bed', 'wardrobe', 'table', 'sofa']
        placed = []
        for kind in kinds:
            for attempt in range(60):
                wall = rnd.choice(['-z', '-x', '+x', '+z'])
                if kind == 'sofa': sx, sz, hgt = 2.0, 0.9, 0.5
                elif kind == 'table': sx, sz, hgt = 1.3, 0.8, 0.75
                elif kind == 'counter': sx, sz, hgt = 2.4, 0.6, 0.9
                elif kind == 'wardrobe': sx, sz, hgt = 1.1, 0.55, 2.0
                else: sx, sz, hgt = 2.0, 1.5, 0.5
                horizontal = wall in ('-z', '+z')
                dxs, dzs = (sx, sz) if horizontal else (sz, sx)
                if wall == '-z': cz = -idp + dzs / 2 + 0.05
                elif wall == '+z': cz = idp - dzs / 2 - 0.05
                if horizontal:
                    cx = rnd.uniform(-iw + dxs / 2 + 0.1, iw - dxs / 2 - 0.1)
                else:
                    cx = (-iw + dxs / 2 + 0.05) if wall == '-x' else (iw - dxs / 2 - 0.05)
                    cz = rnd.uniform(-idp + dzs / 2 + 0.1, idp - dzs / 2 - 0.1)
                r = (cx - dxs / 2, cx + dxs / 2, cz - dzs / 2, cz + dzs / 2)
                ok = True
                for k in keep:
                    if not (r[1] < k[0] or r[0] > k[1] or r[3] < k[2] or r[2] > k[3]): ok = False; break
                if not ok: continue
                for p in placed:
                    if not (r[1] + 0.9 < p[0] or r[0] - 0.9 > p[1] or r[3] + 0.9 < p[2] or r[2] - 0.9 > p[3]): ok = False; break
                if not ok: continue
                # keep clear of partition wall corridors
                pt = info.get('part', {}).get(f)
                if pt and not (r[3] + 1.0 < pt[0] - 0.1 or r[2] - 1.0 > pt[0] + 0.1) and r[0] - 0.0 < pt[2] and r[1] > pt[1]:
                    continue
                placed.append(r)
                _add(m, kind, r, y[f] + (GF - GF if f else 0.0) + (0 if f else 0.0), hgt, wall, f == 0)
                break
        info['furn'][f] = placed
    # ---- loot points: reachable open spots (>=0.9 m from every wall / box / stair), >=1.4 m from each other
    boxes = m.boxes
    for f in range(n):
        cnt = int(((2 * iw) * (2 * idp)) / 16) + 2
        cnt = max(3, min(7, cnt))
        pts = []
        for attempt in range(800):
            if len(pts) >= cnt: break
            x = rnd.uniform(-iw + 0.9, iw - 0.9); z = rnd.uniform(-idp + 0.9, idp - 0.9)
            if any(_inrect(x, z, k, 0.2) for k in info['keep'][f]): continue
            bad = False
            for b in boxes:
                if b[2] < y[f] + 1.2 and b[3] > y[f] + 0.1:
                    cx = min(max(x, b[0]), b[1]); cz = min(max(z, b[4]), b[5])
                    if (x - cx) ** 2 + (z - cz) ** 2 < 0.85 ** 2: bad = True; break
            if bad: continue
            if any((x - p[0]) ** 2 + (z - p[2]) ** 2 < 1.5 ** 2 for p in pts): continue
            # not right in front of a door gap
            if f == 0 and abs(x - info['dx']) < 1.6 and z > idp - 2.2: continue
            pts.append((x, y[f], z))
        m.loot += pts


def _add(m, kind, r, y0, hgt, wall, ground):
    x0, x1, z0, z1 = r
    yb = GF if ground else y0
    if ground: yb = GF
    else: yb = y0
    if kind == 'sofa':
        m.box(x0, x1, yb, yb + .42, z0, z1, SOFA, coll=True)
        # back rest on the wall side
        if wall == '-z': m.box(x0, x1, yb + .42, yb + .85, z0, z0 + .22, mul(SOFA, .9))
        elif wall == '+z': m.box(x0, x1, yb + .42, yb + .85, z1 - .22, z1, mul(SOFA, .9))
        elif wall == '-x': m.box(x0, x0 + .22, yb + .42, yb + .85, z0, z1, mul(SOFA, .9))
        else: m.box(x1 - .22, x1, yb + .42, yb + .85, z0, z1, mul(SOFA, .9))
    elif kind == 'table':
        m.box(x0, x1, yb + .7, yb + .76, z0, z1, DARKW, coll=False)
        m.box(x0 + .1, x0 + .18, yb, yb + .7, z0 + .1, z0 + .18, DARKW); m.box(x1 - .18, x1 - .1, yb, yb + .7, z1 - .18, z1 - .1, DARKW)
        m.box(x0 + .1, x0 + .18, yb, yb + .7, z1 - .18, z1 - .1, DARKW); m.box(x1 - .18, x1 - .1, yb, yb + .7, z0 + .1, z0 + .18, DARKW)
        m.cbox(x0, x1, yb, yb + .76, z0, z1)
    elif kind == 'counter':
        m.box(x0, x1, yb, yb + .88, z0, z1, CNT, coll=True)
        m.box(x0 - .02, x1 + .02, yb + .88, yb + .94, z0 - .02, z1 + .02, (0.25, 0.25, 0.27))
    elif kind == 'wardrobe':
        m.box(x0, x1, yb, yb + 2.0, z0, z1, DARKW, coll=True)
    else:  # bed
        m.box(x0, x1, yb, yb + .45, z0, z1, BED, coll=True)
        if wall == '-z': m.box(x0, x1, yb, yb + 1.0, z0, z0 + .1, DARKW); m.box(x0 + .1, x1 - .1, yb + .45, yb + .55, z0 + .12, z0 + .5, (0.95, 0.95, 0.95))
        elif wall == '+z': m.box(x0, x1, yb, yb + 1.0, z1 - .1, z1, DARKW); m.box(x0 + .1, x1 - .1, yb + .45, yb + .55, z1 - .5, z1 - .12, (0.95, 0.95, 0.95))
        elif wall == '-x': m.box(x0, x0 + .1, yb, yb + 1.0, z0, z1, DARKW); m.box(x0 + .12, x0 + .5, yb + .45, yb + .55, z0 + .1, z1 - .1, (0.95, 0.95, 0.95))
        else: m.box(x1 - .1, x1, yb, yb + 1.0, z0, z1, DARKW); m.box(x1 - .5, x1 - .12, yb + .45, yb + .55, z0 + .1, z1 - .1, (0.95, 0.95, 0.95))
