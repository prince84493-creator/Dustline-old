#!/usr/bin/env python3
"""v25: puts the 7 enterable house models into EVERY town of the Bijnor map (Najibabad, Nagina, Dhampur, ... all 32 place names), not only Bijnor city.

    python3 tools/houses/make_houses_towns.py        (writes assets/models/houses/houses.bin + tools/houses/sites.txt)

Same houses.bin format (BJH1) as make_houses.py, so the Kotlin loader needs no change: one block per placed house (a model that is used in
several towns is simply written once per place).  The 7 Bijnor-city houses keep EXACTLY their old places (sites kept below).
Rules for every house: flat dry land (2.4 m), no part of it closer than 8 m to asphalt, a road 7-30 m in front of the door, min gap to any
other house, never near the spawn point.  Every model is rebuilt until EVERY loot point can be reached (reach.py), as before.
"""
import math, os, struct, sys
import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from mapio import read_bin, road_mask
from specs import SPECS
from build import build
from reach import unreachable
from make_houses import FACES, FDIR, to_world
import fillers

ROOT = os.path.dirname(os.path.dirname(HERE))
BIN_IN = os.path.join(ROOT, 'app/src/main/assets/maps/bijnor.bin')
BIN_OUT = os.path.join(ROOT, 'app/src/main/assets/models/houses/houses.bin')
sys.path.insert(0, os.path.join(ROOT, 'tools'))

ROAD_CLEAR = 8.0
ROAD_FRONT_MAX = 30.0
RC = 2.5
TOWN_GAP = 55.0          # house to house inside a town (m); Bijnor-city houses keep their old 130 m spacing
SPAWN_CLEAR = 40.0
HAM_FREE = 110.0         # hamlets only where no building is within this distance (m)
HAM_GAP = 170.0          # hamlet centre to hamlet centre (m)
Y0 = 2.4

# (model index in SPECS, x, z, face): the 7 old Bijnor-city houses, unchanged
BIJNOR_FIXED = [(0, -1504.5, 78.0, 3), (1, -1584.5, 323.0, 3), (2, -1622.0, -152.0, 1), (3, -1789.5, 83.0, 2),
                (4, -1294.5, 230.5, 2), (5, -1269.5, -37.0, 2), (6, -1412.0, -284.5, 3)]
LIGHT = [2, 5, 6, 0, 1]   # cheaper meshes (5-8k vertices) first for the small places
HEAVY = [3, 4, 1, 0, 2, 5, 6]

# how many houses per town (label order of BijnorLabels.kt: biggest first). Bijnor itself already has 7.
COUNT = {'Nagina': 3, 'Dhampur': 3, 'Sherkot': 3, 'Seohara': 3, 'Chandpur': 3, 'Najibabad': 4,
         'Nehtaur': 2, 'Haldaur Rural': 2, 'Kotawali': 2, 'Jhalu': 2, 'Kiratpur': 2, 'Mandawar': 2, 'Noorpur': 2,
         'Raja Ka Tajpur': 2, 'Afzalgarh': 2, 'Sahanpur': 2, 'Jalalabad': 2, 'Dara Nagar Ganj': 2, 'Kalagarh': 2}
DEFAULT_COUNT = 1


def labels():
    import re
    src = open(os.path.join(ROOT, 'app/src/main/java/com/bijnor/game/BijnorLabels.kt'), encoding='utf-8').read()
    return [(m.group(1), float(m.group(2)), float(m.group(3))) for m in re.finditer(r'MapLabel\("([^"]+)",\s*"[^"]*",\s*(-?[\d.]+)f,\s*(-?[\d.]+)f\)', src)]


def dyn_rec(dd, W2, face, Y0):
    """local dyn entry -> 16 floats in world space (type, box, hinge, closed dir, open dir, rgb)"""
    a0, a1, b0, b1, c0, c1 = dd[1:7]
    xs = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
    if dd[0] == 'w': return [0.0, min(xs), max(xs), b0 + Y0, b1 + Y0, min(zs), max(zs), 0, 0, 0, 0, 0, 0, *dd[7]]
    hx, hz = W2(dd[7], dd[8]); cdx, cdz = to_world(face, dd[9], dd[10]); odx, odz = to_world(face, dd[11], dd[12])
    return [1.0, min(xs), max(xs), b0 + Y0, b1 + Y0, min(zs), max(zs), hx, hz, cdx, cdz, odx, odz, *dd[13]]


def main():
    mp = read_bin(BIN_IN)
    gh, n, cell, x0 = mp['gh'], mp['n'], mp['cell'], mp['x0']
    sp_x, _, sp_z = mp['spawn']
    mk = road_mask(mp, RC)
    D = (ndimage.distance_transform_edt(mk == 0) * RC).astype(np.float32)      # metres to the nearest asphalt cell (grid cell i,k = x0 + i*RC)
    rn = D.shape[0]
    # ground height on the same 2.5 m grid
    ii = np.arange(rn); gx = (x0 + ii * RC - x0) / cell
    GX, GZ = np.meshgrid(np.clip(gx, 0, n - 1.001), np.clip(gx, 0, n - 1.001))
    G = ndimage.map_coordinates(gh, [GZ, GX], order=1)
    flat = (np.abs(G - Y0) <= 0.02)
    # 5 m margin around a footprint must be flat too: erode by the margin separately via shifts below
    print('grid', rn, 'x', rn, 'flat land cells', int(flat.sum()))

    # ---- build every model once (furniture random, rebuilt until all loot is reachable)
    models = []
    for i, sp in enumerate(SPECS):
        for attempt in range(60):
            m, info = build(sp, 100 + i + 1000 * attempt)
            bad = unreachable(m, sp, info)
            if not bad: break
        else:
            raise SystemExit('FAILED ' + sp['name'] + ': ' + str(bad[:3]))
        V = np.array(m.V, np.float32)
        bb = (V[:, 0].min(), V[:, 0].max(), V[:, 2].min(), V[:, 2].max())
        models.append((sp, m, info, bb))
        print(sp['name'], 'loot', len(m.loot), 'verts', len(V), 'after', attempt + 1, 'build(s)')

    PAD = 40
    def padded(a, fill):
        return np.pad(a, PAD, constant_values=fill)
    Dp = padded(D, 0.0); Fp = padded(flat, False)
    Rp = padded(D < 0.6, False)
    def sh(arr, dx, dz):
        """arr value at (cell + (dx, dz) metres) for every cell."""
        di = int(round(dx / RC)); dk = int(round(dz / RC))
        return arr[PAD + dk:PAD + dk + rn, PAD + di:PAD + di + rn]

    # ---- valid-site masks per (model, face): {(m, f): (mask, front)}
    cache = {}
    def sites(mi, face):
        if (mi, face) in cache: return cache[(mi, face)]
        sp, m, info, bb = models[mi]
        lx0, lx1, lz0, lz1 = bb
        dx_door = sp.get('door_x', 0.0); Dd = sp['D']
        xs = np.arange(lx0 - 0.4, lx1 + 0.41, 1.5); zs = np.arange(lz0 - 0.4, lz1 + 0.41, 1.5)
        samp = [(a, b) for a in xs for b in zs]
        ok = np.ones((rn, rn), bool)
        done = set()
        for (a, b) in samp:
            wx, wz = to_world(face, a, b)
            key = (int(round(wx / RC)), int(round(wz / RC)))
            if key in done: continue
            done.add(key)
            ok &= sh(Dp, wx, wz) >= ROAD_CLEAR
        done = set()
        for (a, b) in [(xs[0] - 5, zs[0] - 5), (xs[0] - 5, zs[-1] + 5), (xs[-1] + 5, zs[0] - 5), (xs[-1] + 5, zs[-1] + 5)] + samp[::3]:
            wx, wz = to_world(face, a, b)
            key = (int(round(wx / RC)), int(round(wz / RC)))
            if key in done: continue
            done.add(key)
            ok &= sh(Fp, wx, wz)
        fx_, fz_ = FDIR[face]
        ddx, ddz = to_world(face, dx_door, Dd / 2 + 0.2)
        front = np.full((rn, rn), 99.0, np.float32)
        for dd in np.arange(ROAD_FRONT_MAX, 6.9, -1.0):
            front = np.where(sh(Rp, ddx + fx_ * dd, ddz + fz_ * dd), np.float32(dd), front)
        ok &= front <= ROAD_FRONT_MAX
        cache[(mi, face)] = (ok, front)
        return cache[(mi, face)]

    placed = []   # (model idx, cx, cz, face, town)
    for (mi, x, z, f) in BIJNOR_FIXED:
        placed.append((mi, x, z, f, 'Bijnor'))

    def cellxz(i, k): return x0 + i * RC, x0 + k * RC
    XS = x0 + np.arange(rn) * RC
    XX, ZZ = np.meshgrid(XS, XS)
    rng = np.random.default_rng(25)
    towns = [t for t in labels() if t[0] != 'Bijnor']
    print(len(towns), 'towns')
    order_light = 0
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
                # spacing to everything already placed (incl. Bijnor city)
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
            print('%-16s %-22s x=%7.1f z=%7.1f face=%d' % (name, SPECS[mi]['name'], cx, cz, face))

    # ---- write (same world transform as make_houses.py)
    out = bytearray(b'BJH1' + struct.pack('<i', len(placed)))
    DYN = []   # v30 doors + window panes (appended as 'BJD1' after the last house)
    lines = []
    aabbs = []
    for idx, (mi, cx, cz, face, town) in enumerate(placed):
        sp, m, info, bb = models[mi]
        p, q, r, s = FACES[face]
        def W2(lx, lz):
            return cx + (lx if face == 0 else lz if face == 1 else -lx if face == 2 else -lz), cz + (lz if face == 0 else -lx if face == 1 else -lz if face == 2 else lx)
        V = np.array(m.V, np.float32).copy()
        for k in range(len(V)):
            X, Z = W2(V[k, 0], V[k, 2]); nx, nz = to_world(face, V[k, 3], V[k, 5])
            V[k, 0] = X; V[k, 1] += Y0; V[k, 2] = Z; V[k, 3] = nx; V[k, 5] = nz
        I = np.array(m.I, np.int32)
        B = []
        for (a0, a1, b0, b1, c0, c1) in m.boxes:
            xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
            B.append([min(xs_), max(xs_), b0 + Y0, b1 + Y0, min(zs_), max(zs_)])
        B = np.array(B, np.float32)
        S = []
        for sf in m.surf:
            a0, a1, c0, c1 = sf['rect']
            xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
            row = [min(xs_), max(xs_), min(zs_), max(zs_)]
            for (a, b, c) in sf['planes']:
                if c >= 1e3: row += [0.0, 0.0, 1e4]; continue
                A = a * p + b * r; Bc = a * q + b * s
                row += [A, Bc, c + Y0 - A * cx - Bc * cz]
            row += [float(sf['flag']), float(sf['thick'])]
            S.append(row)
        S = np.array(S, np.float32)
        L = np.array([[*W2(x, z)[:1], y + Y0, W2(x, z)[1]] for (x, y, z) in m.loot], np.float32)
        for dd in m.dyn:
            a0, a1, b0_, b1_, c0, c1 = dd[1:7]
            xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
            if dd[0] == 'w': DYN.append([0.0, min(xs_), max(xs_), b0_ + Y0, b1_ + Y0, min(zs_), max(zs_), 0, 0, 0, 0, 0, 0, *dd[7]])
            else:
                hx_, hz_ = W2(dd[7], dd[8]); cdx, cdz = to_world(face, dd[9], dd[10]); odx, odz = to_world(face, dd[11], dd[12])
                DYN.append([1.0, min(xs_), max(xs_), b0_ + Y0, b1_ + Y0, min(zs_), max(zs_), hx_, hz_, cdx, cdz, odx, odz, *dd[13]])
        minX, maxX = float(V[:, 0].min() - 0.3), float(V[:, 0].max() + 0.3); minZ, maxZ = float(V[:, 2].min() - 0.3), float(V[:, 2].max() + 0.3)
        aabbs.append((minX, maxX, minZ, maxZ))
        out += struct.pack('<7f', minX, maxX, minZ, maxZ, Y0, cx, cz)
        out += struct.pack('<5i', len(V), len(I), len(B), len(S), len(L))
        out += V.astype('<f4').tobytes() + I.astype('<i4').tobytes() + B.astype('<f4').tobytes() + S.astype('<f4').tobytes() + L.astype('<f4').tobytes()
        lines.append('%d %-16s %-22s x=%.1f z=%.1f face=%d y0=%.2f aabb x %.1f..%.1f z %.1f..%.1f  verts=%d boxes=%d surf=%d loot=%d' %
                     (idx + 1, town, sp['name'], cx, cz, face, Y0, minX, maxX, minZ, maxZ, len(V), len(B), len(S), len(L)))

    # ---------------------------------------------------------------- v26/v27: town buildings (shops / flats + mandir, masjid, school, hospital, bank, petrol pump, water tower, godown, clock tower, dhaba)
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
    boxes_all = list(aabbs)
    def cellof(x, z): return int(round((z - x0) / RC)), int(round((x - x0) / RC))
    def lookup(arr, x, z, default):
        k, i = cellof(x, z)
        return arr[k, i] if 0 <= k < rn and 0 <= i < rn else default
    fil = []   # (town, cx, cz, face, dict)
    for (tn, tx, tz) in allt:
        need = target(tn) - nh_town.get(tn, 0); got = 0; tries = 0
        c = COUNT.get(tn, DEFAULT_COUNT)
        queue = list(MUST_BIG if (c >= 3 or tn in ('Bijnor', 'Najibabad')) else MUST_MID if c == 2 else MUST_SMALL)
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
            if fails > 600: kind = None; continue      # this kind does not fit here: take the next one
            if math.hypot(cx - sp_x, cz - sp_z) < SPAWN_CLEAR: continue
            bd = fillers.make_special(kind, frng, c >= 3 or tn in ('Bijnor', 'Najibabad'))
            W, Dp = bd['W'], bd['D']
            face = int(frng.integers(4))
            hw, hd = (W / 2, Dp / 2) if face in (0, 2) else (Dp / 2, W / 2)
            bx0, bx1, bz0, bz1 = cx - hw, cx + hw, cz - hd, cz + hd
            ok = True
            for xx in np.arange(bx0 - 6.5, bx1 + 6.51, 2.0):
                for zz in np.arange(bz0 - 6.5, bz1 + 6.51, 2.0):
                    if not lookup(flat, xx, zz, False): ok = False; break
                    inside = bx0 - 1.5 <= xx <= bx1 + 1.5 and bz0 - 1.5 <= zz <= bz1 + 1.5
                    if inside and lookup(D, xx, zz, 0.0) < 7.5: ok = False; break
                if not ok: break
            if not ok: continue
            for (a0, a1, b0, b1) in boxes_all:
                if bx0 - 5 < a1 and bx1 + 5 > a0 and bz0 - 5 < b1 and bz1 + 5 > b0: ok = False; break
            if not ok: continue
            fx_, fz_ = FDIR[face]; ddx, ddz = to_world(face, 0.0, Dp / 2 + 0.2)
            if not any(lookup(D, cx + ddx + fx_ * dd, cz + ddz + fz_ * dd, 99.0) < 0.6 for dd in np.arange(7.0, 36.0, 1.0)): continue
            boxes_all.append((bx0, bx1, bz0, bz1)); fil.append((tn, cx, cz, face, bd, kind)); got += 1; kind = None
        print('%-16s houses %d + buildings %d (wanted %d) after %d tries' % (tn, nh_town.get(tn, 0), got, need, tries))
    def region_flat(mine):
        xs_ = []; zs_ = []
        for (_, cx, cz, face, bd, _k) in mine:
            hw, hd = (bd['W'] / 2, bd['D'] / 2) if face in (0, 2) else (bd['D'] / 2, bd['W'] / 2)
            xs_ += [cx - hw, cx + hw]; zs_ += [cz - hd, cz + hd]
        for xx in np.arange(min(xs_) - 6.5, max(xs_) + 6.51, 2.0):
            for zz in np.arange(min(zs_) - 6.5, max(zs_) + 6.51, 2.0):
                if not lookup(flat, xx, zz, False): return False
        return True
    # ---------------------------------------------------------------- v28: hamlets (3-6 buildings) on the EMPTY land along the roads, away from every town
    occ = np.zeros((rn, rn), bool)
    for (a0, a1, b0, b1) in boxes_all:
        k0, i0 = cellof(a0, b0); k1, i1 = cellof(a1, b1); occ[max(k0, 0):k1 + 1, max(i0, 0):i1 + 1] = True
    dbuild = ndimage.distance_transform_edt(~occ) * RC
    sx_, sz_ = np.meshgrid(XS, XS)
    cand = flat & (D >= 10) & (D <= 30) & (dbuild >= HAM_FREE) & (np.hypot(sx_ - sp_x, sz_ - sp_z) >= 80)
    kk_, ii_ = np.nonzero(cand); perm = frng.permutation(len(kk_)); cen = []
    for q in perm:
        x_, z_ = float(XS[ii_[q]]), float(XS[kk_[q]])
        if cen:
            ca = np.array(cen)
            if np.min(np.hypot(ca[:, 0] - x_, ca[:, 1] - z_)) < HAM_GAP: continue
        cen.append((x_, z_))
    print('hamlet centres', len(cen), 'of', len(kk_), 'candidate cells')
    HPOOL = ['shop'] * 12 + ['godown', 'godown', 'dhaba', 'dhaba', 'tower']
    nham = 0
    for hid, (hx_, hz_) in enumerate(cen):
        tn = 'H%d' % hid; want = int(frng.integers(3, 7)); got = 0; tries = 0; mine = []
        while got < want and tries < 400:
            tries += 1
            rr = (8.0 + tries * 0.12) * math.sqrt(frng.random()) + (0 if got else 0); th = frng.random() * 6.2832
            cx = round((hx_ + rr * math.cos(th)) / 0.5) * 0.5; cz = round((hz_ + rr * math.sin(th)) / 0.5) * 0.5
            kind = HPOOL[int(frng.integers(len(HPOOL)))]
            if kind in ('mandir', 'masjid', 'tower') and any(f[5] == kind for f in mine): kind = 'shop'
            if kind == 'shop': bd = fillers.make_building(frng, False, W=float(frng.choice([6.0, 7.0, 8.0, 9.0])), D=float(frng.choice([6.0, 7.0, 8.0])), floors=int(frng.choice([1, 1, 2])))
            else: bd = fillers.make_special(kind, frng, False)
            W, Dp = bd['W'], bd['D']; face = int(frng.integers(4))
            hw, hd = (W / 2, Dp / 2) if face in (0, 2) else (Dp / 2, W / 2)
            bx0, bx1, bz0, bz1 = cx - hw, cx + hw, cz - hd, cz + hd
            ok = True
            for xx in np.arange(bx0 - 6.5, bx1 + 6.51, 2.0):
                for zz in np.arange(bz0 - 6.5, bz1 + 6.51, 2.0):
                    if not lookup(flat, xx, zz, False): ok = False; break
                    inside = bx0 - 1.5 <= xx <= bx1 + 1.5 and bz0 - 1.5 <= zz <= bz1 + 1.5
                    if inside and lookup(D, xx, zz, 0.0) < 7.5: ok = False; break
                if not ok: break
            if not ok: continue
            for (a0, a1, b0, b1) in boxes_all:
                if bx0 - 5 < a1 and bx1 + 5 > a0 and bz0 - 5 < b1 and bz1 + 5 > b0: ok = False; break
            if not ok: continue
            fx_, fz_ = FDIR[face]; ddx, ddz = to_world(face, 0.0, Dp / 2 + 0.2)
            if not any(lookup(D, cx + ddx + fx_ * dd, cz + ddz + fz_ * dd, 99.0) < 0.6 for dd in np.arange(7.0, 36.0, 1.0)): continue
            boxes_all.append((bx0, bx1, bz0, bz1)); mine.append((tn, cx, cz, face, bd, kind)); got += 1
        if got >= 3 and region_flat(mine): fil += mine; nham += 1
        else:
            for _ in mine: boxes_all.pop()
    print('hamlets placed', nham)
    # ---------------------------------------------------------------- v28b: farm clusters (2-4 buildings) on the rest of the empty land, no road needed
    occ = np.zeros((rn, rn), bool)
    for (a0, a1, b0, b1) in boxes_all:
        k0, i0 = cellof(a0, b0); k1, i1 = cellof(a1, b1); occ[max(k0, 0):k1 + 1, max(i0, 0):i1 + 1] = True
    dbuild = ndimage.distance_transform_edt(~occ) * RC
    sx_, sz_ = np.meshgrid(XS, XS)
    cand = flat & (D >= 10) & (dbuild >= 90.0) & (np.hypot(sx_ - sp_x, sz_ - sp_z) >= 80)
    kk_, ii_ = np.nonzero(cand); perm = frng.permutation(len(kk_)); cen = []
    for q in perm:
        x_, z_ = float(XS[ii_[q]]), float(XS[kk_[q]])
        if cen:
            ca = np.array(cen)
            if np.min(np.hypot(ca[:, 0] - x_, ca[:, 1] - z_)) < 230.0: continue
        cen.append((x_, z_))
    print('farm centres', len(cen), 'of', len(kk_), 'candidate cells')
    HPOOL = ['shop'] * 12 + ['godown', 'godown', 'dhaba', 'dhaba', 'tower']
    nham = 0
    for hid, (hx_, hz_) in enumerate(cen):
        tn = 'H9%d' % hid; want = int(frng.integers(2, 5)); got = 0; tries = 0; mine = []
        while got < want and tries < 400:
            tries += 1
            rr = (8.0 + tries * 0.12) * math.sqrt(frng.random()) + (0 if got else 0); th = frng.random() * 6.2832
            cx = round((hx_ + rr * math.cos(th)) / 0.5) * 0.5; cz = round((hz_ + rr * math.sin(th)) / 0.5) * 0.5
            kind = HPOOL[int(frng.integers(len(HPOOL)))]
            if kind in ('mandir', 'masjid', 'tower') and any(f[5] == kind for f in mine): kind = 'shop'
            if kind == 'shop': bd = fillers.make_building(frng, False, W=float(frng.choice([6.0, 7.0, 8.0, 9.0])), D=float(frng.choice([6.0, 7.0, 8.0])), floors=int(frng.choice([1, 1, 2])))
            else: bd = fillers.make_special(kind, frng, False)
            W, Dp = bd['W'], bd['D']; face = int(frng.integers(4))
            hw, hd = (W / 2, Dp / 2) if face in (0, 2) else (Dp / 2, W / 2)
            bx0, bx1, bz0, bz1 = cx - hw, cx + hw, cz - hd, cz + hd
            ok = True
            for xx in np.arange(bx0 - 6.5, bx1 + 6.51, 2.0):
                for zz in np.arange(bz0 - 6.5, bz1 + 6.51, 2.0):
                    if not lookup(flat, xx, zz, False): ok = False; break
                    inside = bx0 - 1.5 <= xx <= bx1 + 1.5 and bz0 - 1.5 <= zz <= bz1 + 1.5
                    if inside and lookup(D, xx, zz, 0.0) < 7.5: ok = False; break
                if not ok: break
            if not ok: continue
            for (a0, a1, b0, b1) in boxes_all:
                if bx0 - 5 < a1 and bx1 + 5 > a0 and bz0 - 5 < b1 and bz1 + 5 > b0: ok = False; break
            if not ok: continue
            fx_, fz_ = FDIR[face]; ddx, ddz = to_world(face, 0.0, Dp / 2 + 0.2)
            boxes_all.append((bx0, bx1, bz0, bz1)); mine.append((tn, cx, cz, face, bd, kind)); got += 1
        if got >= 2 and region_flat(mine): fil += mine; nham += 1
        else:
            for _ in mine: boxes_all.pop()
    print('farm clusters placed', nham)
    from collections import Counter
    print(Counter(f[5] for f in fil))
    cellsd = {}
    for f in fil: cellsd.setdefault((f[0], 0, 0) if f[0][0] == 'H' and f[0][1:].isdigit() else (f[0], int(f[1] // 90), int(f[2] // 90)), []).append(f)
    blocks = []
    for k, lst in cellsd.items():
        for q in range(0, len(lst), 8): blocks.append(lst[q:q + 8])
    nbl = 0
    for blk in blocks:
        Vs = []; Is = []; Bs = []; Ss = []
        for (tn, cx, cz, face, bd, kind) in blk:
            p, q, r, s_ = FACES[face]
            def W2(lx, lz):
                return cx + (lx if face == 0 else lz if face == 1 else -lx if face == 2 else -lz), cz + (lz if face == 0 else -lx if face == 1 else -lz if face == 2 else lx)
            msh = bd['m']
            V = np.array(msh.V, np.float32).copy()
            for k in range(len(V)):
                X, Z = W2(V[k, 0], V[k, 2]); nx, nz = to_world(face, V[k, 3], V[k, 5])
                V[k, 0] = X; V[k, 1] += Y0; V[k, 2] = Z; V[k, 3] = nx; V[k, 5] = nz
            Is.append(np.array(msh.I, np.int32) + sum(len(v) for v in Vs)); Vs.append(V)
            for (a0, a1, b0, b1, c0, c1) in bd['boxes']:
                xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
                Bs.append([min(xs_), max(xs_), b0 + Y0, b1 + Y0, min(zs_), max(zs_)])
            DYN.extend(dyn_rec(dd, W2, face, Y0) for dd in bd.get('dyn', []))
            for (a0, a1, c0, c1, planes) in bd['surfs']:
                xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
                row = [min(xs_), max(xs_), min(zs_), max(zs_)]
                pl = list(planes) + [(0.0, 0.0, 1e4)] * (4 - len(planes))
                for (a, b, c) in pl:
                    if c >= 1e3: row += [0.0, 0.0, 1e4]; continue
                    A = a * p + b * r; Bc = a * q + b * s_
                    row += [A, Bc, c + Y0 - A * cx - Bc * cz]
                row += [1.0, 0.2]; Ss.append(row)
        V = np.concatenate(Vs); I = np.concatenate(Is); B = np.array(Bs, np.float32); S = np.array(Ss, np.float32)
        minX, maxX = float(V[:, 0].min() - 0.3), float(V[:, 0].max() + 0.3); minZ, maxZ = float(V[:, 2].min() - 0.3), float(V[:, 2].max() + 0.3)
        out += struct.pack('<7f', minX, maxX, minZ, maxZ, Y0, (minX + maxX) / 2, (minZ + maxZ) / 2)
        out += struct.pack('<5i', len(V), len(I), len(B), len(S), 0)
        out += V.astype('<f4').tobytes() + I.astype('<i4').tobytes() + B.astype('<f4').tobytes() + S.astype('<f4').tobytes()
        lines.append('B%-3d %-16s %d buildings  aabb x %.1f..%.1f z %.1f..%.1f verts=%d  [%s]' % (nbl + 1, blk[0][0], len(blk), minX, maxX, minZ, maxZ, len(V), ','.join(b[5] for b in blk))); nbl += 1
    out[4:8] = struct.pack('<i', len(placed) + nbl)
    print('town buildings', len(fil), 'in', nbl, 'blocks')
    out += b'BJD1' + struct.pack('<i', len(DYN)) + np.array(DYN, np.float32).astype('<f4').tobytes()
    print('v30 doors+panes', len(DYN))
    os.makedirs(os.path.dirname(BIN_OUT), exist_ok=True)
    open(BIN_OUT, 'wb').write(bytes(out))
    open(os.path.join(HERE, 'sites.txt'), 'w').write('\n'.join(lines) + '\n')
    print('enterable houses', len(placed), '+ buildings', len(fil), 'houses.bin', len(out), 'bytes')


if __name__ == '__main__':
    main()
