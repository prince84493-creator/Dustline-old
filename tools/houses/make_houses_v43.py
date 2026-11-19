#!/usr/bin/env python3
"""v43: EVERY building of the map is enterable (hollow, stairs on every floor up to the roof, doors, windows, loot on every floor) and the map stores
each building model only once.

    python3 tools/houses/make_houses_v43.py        (writes assets/models/houses/houses.bin + tools/houses/sites.txt, ~1 minute)

houses.bin (little endian)
  'BJH1', int nBlocks
  per block (one per placed building): float minX maxX minZ maxZ y0 cx cz | int nV nI nB nS nL      (nV = nI = 0: the mesh is a template, see BJI1)
             float B[nB*6] (minX maxX minY maxY minZ maxZ)   float S[nS*18] (rect x0 x1 z0 z1 | 4 planes a b c (world) | flag | thick)   float L[nL*3] (loot x y z)
  'BJD1', int nDyn, float D[nDyn*16]   (door leaves + window panes, world space)
  'BJI1', int nTpl, per template: int nV nI, float V[nV*11] (local pos3 nor3 uv2 col3), int I[nI]
          int nInst, per instance: int tpl, int face (0..3 quarter turns about +y, local (x,z) -> world (x,z): 0 (x,z) | 1 (z,-x) | 2 (-x,-z) | 3 (-z,x)), float x y z radius
Collision / floors / loot / doors of every instance are written in WORLD space (blocks + BJD1), so the Kotlin side only has to draw each template mesh with a model matrix.
Placement rules are those of v26-v28 (towns, hamlets, farm clusters); the 64 houses keep exactly their v40 places.
"""
import math, os, struct, sys
import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from mapio import read_bin, road_mask
from make_houses import FACES, FDIR, to_world
import tpl_lib

ROOT = os.path.dirname(os.path.dirname(HERE))
BIN_IN = os.path.join(ROOT, 'app/src/main/assets/maps/bijnor.bin')
BIN_OUT = os.path.join(ROOT, 'app/src/main/assets/models/houses/houses.bin')

ROAD_CLEAR = 8.0
ROAD_FRONT_MAX = 30.0
RC = 2.5
TOWN_GAP = 55.0
SPAWN_CLEAR = 40.0
HAM_FREE = 110.0
HAM_GAP = 170.0
Y0 = 2.4

BIJNOR_FIXED = [(0, -1504.5, 78.0, 3), (1, -1584.5, 323.0, 3), (2, -1622.0, -152.0, 1), (3, -1789.5, 83.0, 2),
                (4, -1294.5, 230.5, 2), (5, -1269.5, -37.0, 2), (6, -1412.0, -284.5, 3)]
LIGHT = [2, 5, 6, 0, 1]
HEAVY = [3, 4, 1, 0, 2, 5, 6]
COUNT = {'Nagina': 3, 'Dhampur': 3, 'Sherkot': 3, 'Seohara': 3, 'Chandpur': 3, 'Najibabad': 4,
         'Nehtaur': 2, 'Haldaur Rural': 2, 'Kotawali': 2, 'Jhalu': 2, 'Kiratpur': 2, 'Mandawar': 2, 'Noorpur': 2,
         'Raja Ka Tajpur': 2, 'Afzalgarh': 2, 'Sahanpur': 2, 'Jalalabad': 2, 'Dara Nagar Ganj': 2, 'Kalagarh': 2}
DEFAULT_COUNT = 1


def labels():
    import re
    src = open(os.path.join(ROOT, 'app/src/main/java/com/bijnor/game/BijnorLabels.kt'), encoding='utf-8').read()
    return [(m.group(1), float(m.group(2)), float(m.group(3))) for m in re.finditer(r'MapLabel\("([^"]+)",\s*"[^"]*",\s*(-?[\d.]+)f,\s*(-?[\d.]+)f\)', src)]


def W2f(cx, cz, face):
    def W2(lx, lz):
        return cx + (lx if face == 0 else lz if face == 1 else -lx if face == 2 else -lz), cz + (lz if face == 0 else -lx if face == 1 else -lz if face == 2 else lx)
    return W2


def world_aabb(t, cx, cz, face, pad=0.0):
    lx0, lx1, lz0, lz1 = t['bbox']
    pts = [to_world(face, a, b) for a in (lx0 - pad, lx1 + pad) for b in (lz0 - pad, lz1 + pad)]
    return (cx + min(p[0] for p in pts), cx + max(p[0] for p in pts), cz + min(p[1] for p in pts), cz + max(p[1] for p in pts))


def inst_data(t, cx, cz, face):
    """collision boxes / floor surfaces / loot / doors+panes of one placed template, in world space"""
    p, q, r, s = FACES[face]; W2 = W2f(cx, cz, face)
    B = []
    for (a0, a1, b0, b1, c0, c1) in t['boxes']:
        xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
        B.append([min(xs_), max(xs_), b0 + Y0, b1 + Y0, min(zs_), max(zs_)])
    S = []
    for sf in t['surf']:
        a0, a1, c0, c1 = sf['rect']
        xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
        row = [min(xs_), max(xs_), min(zs_), max(zs_)]
        for (a, b, c) in sf['planes']:
            if c >= 1e3: row += [0.0, 0.0, 1e4]; continue
            A = a * p + b * r; Bc = a * q + b * s
            row += [A, Bc, c + Y0 - A * cx - Bc * cz]
        row += [float(sf['flag']), float(sf['thick'])]
        S.append(row)
    L = [[W2(x, z)[0], y + Y0, W2(x, z)[1]] for (x, y, z) in t['loot']]
    D = []
    for dd in t['dyn']:
        a0, a1, b0, b1, c0, c1 = dd[1:7]
        xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
        if dd[0] == 'w': D.append([0.0, min(xs_), max(xs_), b0 + Y0, b1 + Y0, min(zs_), max(zs_), 0, 0, 0, 0, 0, 0, *dd[7]])
        else:
            hx, hz = W2(dd[7], dd[8]); cdx, cdz = to_world(face, dd[9], dd[10]); odx, odz = to_world(face, dd[11], dd[12])
            D.append([1.0, min(xs_), max(xs_), b0 + Y0, b1 + Y0, min(zs_), max(zs_), hx, hz, cdx, cdz, odx, odz, *dd[13]])
    return (np.array(B, np.float32).reshape(-1, 6), np.array(S, np.float32).reshape(-1, 18), np.array(L, np.float32).reshape(-1, 3), D)


def main():
    print('building the template library ...')
    lib = tpl_lib.build_library()
    print('templates', len(lib), 'verts', sum(len(t['V']) for t in lib))
    by_kind = {}
    for i, t in enumerate(lib): by_kind.setdefault(t['kind'], []).append(i)
    shops = {}
    for i in by_kind['shop']: shops.setdefault(lib[i]['n'], []).append(i)

    mp = read_bin(BIN_IN)
    gh, n, cell, x0 = mp['gh'], mp['n'], mp['cell'], mp['x0']
    sp_x, _, sp_z = mp['spawn']
    mk = road_mask(mp, RC)
    D = (ndimage.distance_transform_edt(mk == 0) * RC).astype(np.float32)
    rn = D.shape[0]
    ii = np.arange(rn); gx = (x0 + ii * RC - x0) / cell
    GX, GZ = np.meshgrid(np.clip(gx, 0, n - 1.001), np.clip(gx, 0, n - 1.001))
    G = ndimage.map_coordinates(gh, [GZ, GX], order=1)
    flat = (np.abs(G - Y0) <= 0.02)
    print('grid', rn, 'x', rn, 'flat land cells', int(flat.sum()))

    PAD = 40
    def padded(a, fill): return np.pad(a, PAD, constant_values=fill)
    Dp = padded(D, 0.0); Fp = padded(flat, False); Rp = padded(D < 0.6, False)
    def sh(arr, dx, dz):
        di = int(round(dx / RC)); dk = int(round(dz / RC))
        return arr[PAD + dk:PAD + dk + rn, PAD + di:PAD + di + rn]

    # ---------------------------------------------------------------- the 64 houses: same rules / same rng as v25 -> same places as v40
    cache = {}
    def sites(mi, face):
        if (mi, face) in cache: return cache[(mi, face)]
        t = lib[mi]
        lx0, lx1, lz0, lz1 = t['bbox']
        dx_door = t['door'][0]; Dd2 = t['door'][1]
        xs = np.arange(lx0 - 0.4, lx1 + 0.41, 1.5); zs = np.arange(lz0 - 0.4, lz1 + 0.41, 1.5)
        samp = [(a, b) for a in xs for b in zs]
        ok = np.ones((rn, rn), bool); done = set()
        for (a, b) in samp:
            wx, wz = to_world(face, a, b)
            key = (int(round(wx / RC)), int(round(wz / RC)))
            if key in done: continue
            done.add(key); ok &= sh(Dp, wx, wz) >= ROAD_CLEAR
        done = set()
        for (a, b) in [(xs[0] - 5, zs[0] - 5), (xs[0] - 5, zs[-1] + 5), (xs[-1] + 5, zs[0] - 5), (xs[-1] + 5, zs[-1] + 5)] + samp[::3]:
            wx, wz = to_world(face, a, b)
            key = (int(round(wx / RC)), int(round(wz / RC)))
            if key in done: continue
            done.add(key); ok &= sh(Fp, wx, wz)
        fx_, fz_ = FDIR[face]
        ddx, ddz = to_world(face, dx_door, Dd2)
        front = np.full((rn, rn), 99.0, np.float32)
        for dd in np.arange(ROAD_FRONT_MAX, 6.9, -1.0):
            front = np.where(sh(Rp, ddx + fx_ * dd, ddz + fz_ * dd), np.float32(dd), front)
        ok &= front <= ROAD_FRONT_MAX
        cache[(mi, face)] = (ok, front)
        return cache[(mi, face)]

    placed = []   # (template idx, cx, cz, face, town)
    for (mi, x, z, f) in BIJNOR_FIXED: placed.append((mi, x, z, f, 'Bijnor'))
    XS = x0 + np.arange(rn) * RC
    XX, ZZ = np.meshgrid(XS, XS)
    rng = np.random.default_rng(25)
    towns = [t for t in labels() if t[0] != 'Bijnor']
    print(len(towns), 'towns')
    for ti, (name, tx, tz) in enumerate(towns):
        cnt = COUNT.get(name, DEFAULT_COUNT)
        pool = (HEAVY if COUNT.get(name, 1) >= 3 else LIGHT)
        pool = pool[ti % len(pool):] + pool[:ti % len(pool)]
        for h in range(cnt):
            mi = pool[h % len(pool)]
            best = None
            for radius in (260.0, 380.0, 520.0, 700.0):
                dl = np.hypot(XX - tx, ZZ - tz)
                near = dl <= radius
                far = np.ones((rn, rn), bool)
                for (pm, px, pz, pf, pt) in placed:
                    gap = 130.0 if pt == 'Bijnor' else TOWN_GAP
                    if abs(px - tx) < radius + 300 and abs(pz - tz) < radius + 300:
                        far &= np.hypot(XX - px, ZZ - pz) >= gap
                far &= np.hypot(XX - sp_x, ZZ - sp_z) >= SPAWN_CLEAR
                for face in range(4):
                    ok, front = sites(mi, face)
                    m = ok & near & far
                    if not m.any(): continue
                    kk, jj = np.nonzero(m)
                    sc = -0.25 * dl[kk, jj] - 3.0 * np.abs(front[kk, jj] - 12.0) + rng.random(len(kk)) * 6.0
                    b = int(np.argmax(sc))
                    cand = (float(sc[b]), float(XX[kk[b], jj[b]]), float(ZZ[kk[b], jj[b]]), face)
                    if best is None or cand[0] > best[0]: best = cand
                if best is not None: break
            if best is None:
                print('  !! no site for', name, 'house', h + 1); continue
            _, cx, cz, face = best
            placed.append((mi, cx, cz, face, name))
            print('%-16s %-22s x=%7.1f z=%7.1f face=%d' % (name, lib[mi]['name'], cx, cz, face))
    aabbs = [world_aabb(lib[mi], cx, cz, face, 0.3) for (mi, cx, cz, face, tn) in placed]
    nhouse = len(placed)

    # ---------------------------------------------------------------- town buildings (every kind is an ENTERABLE template now)
    TARGET_BIG, TARGET_MID, TARGET_SMALL, TARGET_NAJ, TARGET_BIJNOR = 28, 20, 14, 34, 46
    allt = [('Bijnor',) + tuple(t[1:]) for t in labels() if t[0] == 'Bijnor'] + towns
    nh_town = {}
    for (_, _, _, _, tn) in placed: nh_town[tn] = nh_town.get(tn, 0) + 1
    def target(tn):
        if tn == 'Bijnor': return TARGET_BIJNOR
        if tn == 'Najibabad': return TARGET_NAJ
        c = COUNT.get(tn, DEFAULT_COUNT)
        return TARGET_BIG if c >= 3 else TARGET_MID if c == 2 else TARGET_SMALL
    MUST_BIG = ['school', 'hospital', 'bank', 'petrol', 'tower', 'clock', 'godown', 'dhaba', 'godown']
    MUST_MID = ['school', 'petrol', 'tower', 'godown', 'dhaba']
    MUST_SMALL = ['tower', 'dhaba']
    RANDOM_POOL = ['shop'] * 11 + ['dhaba', 'godown', 'petrol', 'school']
    frng = np.random.default_rng(27)

    def pick(kind, big, hamlet=False):
        if kind == 'shop':
            if hamlet:
                Wq = float(frng.choice([6.0, 7.0, 8.0, 9.0])); Dq = float(frng.choice([6.0, 7.0, 8.0])); fl = int(frng.choice([1, 1, 2]))
            else:
                Wq = float(frng.choice([7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 14.0])); Dq = float(frng.choice([7.0, 8.0, 9.0, 10.0]))
                r_ = frng.random(); fl = 1 if r_ < 0.28 else 2 if r_ < 0.68 else 3 if r_ < 0.92 else (4 if big else 3)
            cands = shops[fl]
            return min(cands, key=lambda i: abs(lib[i]['W'] - Wq) + abs(lib[i]['D'] - Dq) + float(frng.random()) * 0.5)
        c = by_kind[kind]
        return c[int(frng.integers(len(c)))]

    boxes_all = list(aabbs)
    def cellof(x, z): return int(round((z - x0) / RC)), int(round((x - x0) / RC))
    def lookup(arr, x, z, default):
        k, i = cellof(x, z)
        return arr[k, i] if 0 <= k < rn and 0 <= i < rn else default

    def fits(t, cx, cz, face, need_road):
        bx0, bx1, bz0, bz1 = world_aabb(t, cx, cz, face)
        for xx in np.arange(bx0 - 6.5, bx1 + 6.51, 2.0):
            for zz in np.arange(bz0 - 6.5, bz1 + 6.51, 2.0):
                if not lookup(flat, xx, zz, False): return None
                inside = bx0 - 1.5 <= xx <= bx1 + 1.5 and bz0 - 1.5 <= zz <= bz1 + 1.5
                if inside and lookup(D, xx, zz, 0.0) < 7.5: return None
        for (a0, a1, b0, b1) in boxes_all:
            if bx0 - 5 < a1 and bx1 + 5 > a0 and bz0 - 5 < b1 and bz1 + 5 > b0: return None
        if need_road:
            fx_, fz_ = FDIR[face]; ddx, ddz = to_world(face, t['door'][0], t['door'][1])
            if not any(lookup(D, cx + ddx + fx_ * dd, cz + ddz + fz_ * dd, 99.0) < 0.6 for dd in np.arange(7.0, 36.0, 1.0)): return None
        return (bx0 - 0.3, bx1 + 0.3, bz0 - 0.3, bz1 + 0.3)

    fil = []   # (town, cx, cz, face, template idx, kind)
    for (tn, tx, tz) in allt:
        need = target(tn) - nh_town.get(tn, 0); got = 0; tries = 0
        c = COUNT.get(tn, DEFAULT_COUNT)
        big = c >= 3 or tn in ('Bijnor', 'Najibabad')
        queue = list(MUST_BIG if big else MUST_MID if c == 2 else MUST_SMALL)
        if tn in ('Bijnor', 'Najibabad'): queue = queue + ['school', 'petrol', 'tower', 'hospital']
        maxr = 450.0 if tn == 'Bijnor' else 330.0
        fails = 0; kind = None
        while got < need and tries < 9000:
            tries += 1
            if kind is None:
                kind = queue.pop(0) if queue else RANDOM_POOL[int(frng.integers(len(RANDOM_POOL)))]; fails = 0
            rr = min(maxr, 25.0 + tries * 0.1) * math.sqrt(frng.random()); th = frng.random() * 6.2832
            cx = round((tx + rr * math.cos(th)) / 0.5) * 0.5; cz = round((tz + rr * math.sin(th)) / 0.5) * 0.5
            fails += 1
            if fails > 600: kind = None; continue
            if math.hypot(cx - sp_x, cz - sp_z) < SPAWN_CLEAR: continue
            ti_ = pick(kind, big); face = int(frng.integers(4))
            bb_ = fits(lib[ti_], cx, cz, face, True)
            if bb_ is None: continue
            boxes_all.append(bb_); fil.append((tn, cx, cz, face, ti_, kind)); got += 1; kind = None
        print('%-16s houses %d + buildings %d (wanted %d) after %d tries' % (tn, nh_town.get(tn, 0), got, need, tries))

    def region_flat(mine):
        xs_ = []; zs_ = []
        for (_, cx, cz, face, ti_, _k) in mine:
            a0, a1, b0, b1 = world_aabb(lib[ti_], cx, cz, face)
            xs_ += [a0, a1]; zs_ += [b0, b1]
        for xx in np.arange(min(xs_) - 6.5, max(xs_) + 6.51, 2.0):
            for zz in np.arange(min(zs_) - 6.5, max(zs_) + 6.51, 2.0):
                if not lookup(flat, xx, zz, False): return False
        return True

    def cluster_pass(name, cand_mask_fn, gap_c, want_lo, want_hi, need_road, min_got, label):
        occ = np.zeros((rn, rn), bool)
        for (a0, a1, b0, b1) in boxes_all:
            k0, i0 = cellof(a0, b0); k1, i1 = cellof(a1, b1); occ[max(k0, 0):k1 + 1, max(i0, 0):i1 + 1] = True
        dbuild = ndimage.distance_transform_edt(~occ) * RC
        sx_, sz_ = np.meshgrid(XS, XS)
        cand = cand_mask_fn(dbuild, sx_, sz_)
        kk_, ii_ = np.nonzero(cand); perm = frng.permutation(len(kk_)); cen = []
        for q in perm:
            x_, z_ = float(XS[ii_[q]]), float(XS[kk_[q]])
            if cen:
                ca = np.array(cen)
                if np.min(np.hypot(ca[:, 0] - x_, ca[:, 1] - z_)) < gap_c: continue
            cen.append((x_, z_))
        print(label, 'centres', len(cen), 'of', len(kk_), 'candidate cells')
        HPOOL = ['shop'] * 12 + ['godown', 'godown', 'dhaba', 'dhaba', 'tower']
        nclu = 0
        for hid, (hx_, hz_) in enumerate(cen):
            tn = name % hid; want = int(frng.integers(want_lo, want_hi)); got = 0; tries = 0; mine = []
            while got < want and tries < 400:
                tries += 1
                rr = (8.0 + tries * 0.12) * math.sqrt(frng.random()); th = frng.random() * 6.2832
                cx = round((hx_ + rr * math.cos(th)) / 0.5) * 0.5; cz = round((hz_ + rr * math.sin(th)) / 0.5) * 0.5
                kind = HPOOL[int(frng.integers(len(HPOOL)))]
                if kind in ('mandir', 'masjid', 'tower') and any(f[5] == kind for f in mine): kind = 'shop'
                ti_ = pick(kind, False, hamlet=True); face = int(frng.integers(4))
                bb_ = fits(lib[ti_], cx, cz, face, need_road)
                if bb_ is None: continue
                boxes_all.append(bb_); mine.append((tn, cx, cz, face, ti_, kind)); got += 1
            if got >= min_got and region_flat(mine): fil.extend(mine); nclu += 1
            else:
                for _ in mine: boxes_all.pop()
        print(label, 'placed', nclu)

    cluster_pass('H%d', lambda db, sx_, sz_: flat & (D >= 10) & (D <= 30) & (db >= HAM_FREE) & (np.hypot(sx_ - sp_x, sz_ - sp_z) >= 80), HAM_GAP, 3, 7, True, 3, 'hamlet')
    cluster_pass('H9%d', lambda db, sx_, sz_: flat & (D >= 10) & (db >= 90.0) & (np.hypot(sx_ - sp_x, sz_ - sp_z) >= 80), 230.0, 2, 5, False, 2, 'farm cluster')
    from collections import Counter
    print(Counter(f[5] for f in fil))

    # ---------------------------------------------------------------- write
    blocks = [(mi, cx, cz, face, tn, lib[mi]['kind']) for (mi, cx, cz, face, tn) in placed] + [(ti_, cx, cz, face, tn, kind) for (tn, cx, cz, face, ti_, kind) in fil]
    out = bytearray(b'BJH1' + struct.pack('<i', len(blocks)))
    DYN = []; lines = []; INST = []; nl_tot = 0
    for idx, (ti_, cx, cz, face, tn, kind) in enumerate(blocks):
        t = lib[ti_]
        B, S, L, Dd = inst_data(t, cx, cz, face)
        DYN.extend(Dd)
        a0, a1, b0, b1 = world_aabb(t, cx, cz, face, 0.3)
        out += struct.pack('<7f', a0, a1, b0, b1, Y0, cx, cz)
        out += struct.pack('<5i', 0, 0, len(B), len(S), len(L))
        out += B.astype('<f4').tobytes() + S.astype('<f4').tobytes() + L.astype('<f4').tobytes()
        lx0, lx1, lz0, lz1 = t['bbox']
        rad = max(math.hypot(a, b) for a in (lx0, lx1) for b in (lz0, lz1)) + 1.0
        INST.append((ti_, face, cx, Y0, cz, rad)); nl_tot += len(L)
        lines.append('%d %-16s %-22s %-8s x=%.1f z=%.1f face=%d y0=%.2f aabb x %.1f..%.1f z %.1f..%.1f  boxes=%d surf=%d loot=%d' %
                     (idx + 1, tn, t['name'], kind, cx, cz, face, Y0, a0, a1, b0, b1, len(B), len(S), len(L)))
    out += b'BJD1' + struct.pack('<i', len(DYN)) + np.array(DYN, np.float32).astype('<f4').tobytes()
    out += b'BJI1' + struct.pack('<i', len(lib))
    for t in lib:
        V = t['V'].astype('<f4'); I = t['I'].astype('<i4')
        out += struct.pack('<2i', len(V), len(I)) + V.tobytes() + I.tobytes()
    out += struct.pack('<i', len(INST))
    for (ti_, face, x, y, z, rad) in INST: out += struct.pack('<2i4f', ti_, face, x, y, z, rad)
    os.makedirs(os.path.dirname(BIN_OUT), exist_ok=True)
    open(BIN_OUT, 'wb').write(bytes(out))
    open(os.path.join(HERE, 'sites.txt'), 'w').write('\n'.join(lines) + '\n')
    print('houses', nhouse, '+ buildings', len(fil), '= blocks', len(blocks), '| loot points', nl_tot, '| doors+panes', len(DYN), '| houses.bin', len(out), 'bytes')


if __name__ == '__main__':
    main()
