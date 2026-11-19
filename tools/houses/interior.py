import math
from kit import *

DIMS = {'sofa': (2.0, 0.9, 0.5), 'table': (1.3, 0.8, 0.75), 'counter': (2.4, 0.6, 0.9), 'wardrobe': (1.1, 0.55, 2.0), 'bed': (2.0, 1.5, 0.5),
        'shelf': (2.0, 0.5, 1.9), 'desk': (1.3, 0.7, 0.76), 'crate': (1.1, 1.1, 1.1), 'altar': (1.8, 1.0, 1.2), 'bench': (2.0, 0.5, 0.45), 'mat': (2.6, 1.3, 0.04),
        'sacks': (1.6, 1.0, 1.2), 'pump': (0.9, 0.9, 1.5)}
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
        kd = sp.get('kinds')
        kinds = kd(f, n) if kd else (['sofa', 'table', 'counter', 'wardrobe'] if f == 0 else ['bed', 'wardrobe', 'table', 'sofa'])
        gap = sp.get('furn_gap', 0.9)
        placed = []
        for kind in kinds:
            for attempt in range(60):
                wall = rnd.choice(['-z', '-x', '+x', '+z'])
                sx, sz, hgt = DIMS.get(kind, DIMS['bed'])
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
                    if not (r[1] + gap < p[0] or r[0] - gap > p[1] or r[3] + gap < p[2] or r[2] - gap > p[3]): ok = False; break
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
        if sp.get('loot_cnt'): cnt = sp['loot_cnt']((2 * iw) * (2 * idp), f, n)
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
    elif kind == 'shelf':      # shop rack: dark frame + 4 coloured shelf boards on the open side
        m.box(x0, x1, yb, yb + 1.9, z0, z1, (0.42, 0.40, 0.38), coll=True)
        cols = [(0.78, 0.30, 0.25), (0.25, 0.50, 0.72), (0.88, 0.76, 0.28), (0.30, 0.62, 0.40)]
        for q in range(4):
            yy = yb + 0.25 + q * 0.42
            if wall == '-z': m.box(x0 + .05, x1 - .05, yy, yy + .24, z0 + .3, z1 + .04, cols[(q + int(x0 * 3)) % 4], jit=0.1)
            elif wall == '+z': m.box(x0 + .05, x1 - .05, yy, yy + .24, z0 - .04, z1 - .3, cols[(q + int(x0 * 3)) % 4], jit=0.1)
            elif wall == '-x': m.box(x0 + .3, x1 + .04, yy, yy + .24, z0 + .05, z1 - .05, cols[(q + int(z0 * 3)) % 4], jit=0.1)
            else: m.box(x0 - .04, x1 - .3, yy, yy + .24, z0 + .05, z1 - .05, cols[(q + int(z0 * 3)) % 4], jit=0.1)
    elif kind == 'desk':       # classroom / office desk with a bench
        m.box(x0, x1, yb + .7, yb + .76, z0, z1, (0.62, 0.46, 0.30))
        for (a, b) in ((x0 + .06, z0 + .06), (x1 - .12, z0 + .06), (x0 + .06, z1 - .12), (x1 - .12, z1 - .12)): m.box(a, a + .06, yb, yb + .7, b, b + .06, DARKW)
        m.cbox(x0, x1, yb, yb + .76, z0, z1)
    elif kind == 'crate':      # godown crates / sacks: stacked wooden boxes
        m.box(x0, x1, yb, yb + .55, z0, z1, (0.66, 0.50, 0.30), coll=True, jit=0.12)
        m.box(x0 + .1, x1 - .1, yb + .55, yb + 1.1, z0 + .1, z1 - .1, (0.58, 0.44, 0.27), coll=True, jit=0.12)
    elif kind == 'sacks':
        m.box(x0, x1, yb, yb + .6, z0, z1, (0.82, 0.76, 0.58), coll=True, jit=0.12)
        m.box(x0 + .15, x1 - .15, yb + .6, yb + 1.2, z0 + .1, z1 - .1, (0.78, 0.72, 0.54), coll=True, jit=0.12)
    elif kind == 'altar':      # mandir: stone plinth with a saffron cloth and a small brass lamp
        m.box(x0, x1, yb, yb + .9, z0, z1, (0.80, 0.77, 0.70), coll=True)
        m.box(x0 + .1, x1 - .1, yb + .9, yb + 1.0, z0 + .1, z1 - .1, (0.92, 0.50, 0.14))
        cx_, cz_ = (x0 + x1) / 2, (z0 + z1) / 2
        m.box(cx_ - .12, cx_ + .12, yb + 1.0, yb + 1.2, cz_ - .12, cz_ + .12, (0.84, 0.66, 0.18))
    elif kind == 'mat':        # prayer / floor mat: flat, no collision
        m.box(x0, x1, yb, yb + .03, z0, z1, (0.20, 0.48, 0.38), jit=0.05)
    elif kind == 'bench':
        m.box(x0, x1, yb + .38, yb + .45, z0, z1, DARKW)
        for a in (x0 + .1, x1 - .18): m.box(a, a + .08, yb, yb + .38, z0 + .05, z1 - .05, DARKW)
        m.cbox(x0, x1, yb, yb + .45, z0, z1)
    elif kind == 'pump':
        m.box(x0, x1, yb, yb + 1.5, z0, z1, (0.80, 0.18, 0.16), coll=True)
    else:  # bed
        m.box(x0, x1, yb, yb + .45, z0, z1, BED, coll=True)
        if wall == '-z': m.box(x0, x1, yb, yb + 1.0, z0, z0 + .1, DARKW); m.box(x0 + .1, x1 - .1, yb + .45, yb + .55, z0 + .12, z0 + .5, (0.95, 0.95, 0.95))
        elif wall == '+z': m.box(x0, x1, yb, yb + 1.0, z1 - .1, z1, DARKW); m.box(x0 + .1, x1 - .1, yb + .45, yb + .55, z1 - .5, z1 - .12, (0.95, 0.95, 0.95))
        elif wall == '-x': m.box(x0, x0 + .1, yb, yb + 1.0, z0, z1, DARKW); m.box(x0 + .12, x0 + .5, yb + .45, yb + .55, z0 + .1, z1 - .1, (0.95, 0.95, 0.95))
        else: m.box(x1 - .1, x1, yb, yb + 1.0, z0, z1, DARKW); m.box(x1 - .5, x1 - .12, yb + .45, yb + .55, z0 + .1, z1 - .1, (0.95, 0.95, 0.95))
