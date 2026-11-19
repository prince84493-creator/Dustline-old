#!/usr/bin/env python3
"""Builds assets/models/houses/houses.bin : the 7 enterable Bijnor houses (hollow, door, stairs, loot points) placed INSIDE Bijnor city,
beside the roads, never on a road, 130 m+ apart, each model exactly once, no trees.

    python3 tools/houses/make_houses.py            (writes the bin + tools/houses/sites.txt)

houses.bin (little endian)
  "BJH1", int nHouses
  per house: float minX maxX minZ maxZ y0 cx cz | int nV nI nB nS nL
             float V[nV*11] (world pos3 nor3 uv2 col3)  int I[nI]  float B[nB*6] (minX maxX minY maxY minZ maxZ)
             float S[nS*18] (rect x0 x1 z0 z1 | 4 planes a b c (y = a*x + b*z + c, world) | flag | thick)   float L[nL*3] (loot x y z)
"""
import math, os, struct, sys
import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from mapio import read_bin, road_mask
from specs import SPECS
from build import build

ROOT = os.path.dirname(os.path.dirname(HERE))
BIN_IN = os.path.join(ROOT, 'app/src/main/assets/maps/bijnor.bin')
BIN_OUT = os.path.join(ROOT, 'app/src/main/assets/models/houses/houses.bin')

CITY = (-1522.0, 78.0)      # Bijnor label
CITY_R = 380.0              # houses only inside this circle (the no-tree town circle of Bijnor is 450 m)
MIN_GAP = 130.0             # house to house (m)
ROAD_CLEAR = 8.0            # no part of a house (porch, balcony, roof edge) closer than this to the asphalt
ROAD_FRONT_MAX = 30.0       # the road in front of the door must be at most this far
RC = 2.5                    # road raster cell (m)
# face: world direction the door looks at.  local (x, z) -> world offset
FACES = {0: (1, 0, 0, 1), 1: (0, -1, 1, 0), 2: (-1, 0, 0, -1), 3: (0, 1, -1, 0)}   # x = p*dX + q*dZ ; z = r*dX + s*dZ
FDIR = {0: (0, 1), 1: (1, 0), 2: (0, -1), 3: (-1, 0)}


def to_world(face, lx, lz):
    """local (x,z) -> world offset (dX,dZ) (inverse of FACES)."""
    if face == 0: return lx, lz
    if face == 1: return lz, -lx
    if face == 2: return -lx, -lz
    return -lz, lx


def main():
    mp = read_bin(BIN_IN)
    gh, n, cell, x0 = mp['gh'], mp['n'], mp['cell'], mp['x0']
    mk = road_mask(mp, RC)
    dist = ndimage.distance_transform_edt(mk == 0) * RC            # metres to the nearest asphalt cell

    def road_d(x, z):
        i = int(round((x - x0) / RC)); k = int(round((z - x0) / RC))
        if i < 0 or k < 0 or i >= mk.shape[1] or k >= mk.shape[0]: return 0.0
        return float(dist[k, i])

    def ground(x, z):
        fx = min(max((x - x0) / cell, 0), n - 1.001); fz = min(max((z - x0) / cell, 0), n - 1.001)
        i, k = int(fx), int(fz); tx, tz = fx - i, fz - k; a = k * n + i
        g = gh.reshape(-1)
        return g[a] * (1 - tx) * (1 - tz) + g[a + 1] * tx * (1 - tz) + g[a + n] * (1 - tx) * tz + g[a + n + 1] * tx * tz

    models = []
    for i, sp in enumerate(SPECS):
        from reach import unreachable
        for attempt in range(60):   # furniture is random: keep rebuilding until EVERY loot point can be reached
            m, info = build(sp, 100 + i + 1000 * attempt)
            bad = unreachable(m, sp, info)
            if not bad: break
        else:
            raise SystemExit('FAILED ' + sp['name'] + ': ' + str(bad[:3]))
        print(sp['name'], 'reachable loot', len(m.loot), 'after', attempt + 1, 'build(s)')
        V = np.array(m.V, np.float32)
        bb = (V[:, 0].min(), V[:, 0].max(), V[:, 2].min(), V[:, 2].max())
        models.append((sp, m, info, bb))

    chosen = []   # (cx, cz, face, y0)
    sites = []
    order = list(range(len(SPECS)))
    placed = {}
    rng = np.random.default_rng(7)
    for idx in order:
        sp, m, info, bb = models[idx]
        lx0, lx1, lz0, lz1 = bb
        W, D = sp['W'], sp['D']
        dx_door = sp.get('door_x', 0.0)
        # footprint samples (local), a bit larger than the real geometry
        xs = np.arange(lx0 - 0.4, lx1 + 0.41, 1.5); zs = np.arange(lz0 - 0.4, lz1 + 0.41, 1.5)
        samp = [(a, b) for a in xs for b in zs]
        cands = []
        for face in range(4):
            fx_, fz_ = FDIR[face]
            for cx in np.arange(CITY[0] - CITY_R, CITY[0] + CITY_R, 2.5):
                for cz in np.arange(CITY[1] - CITY_R, CITY[1] + CITY_R, 2.5):
                    if math.hypot(cx - CITY[0], cz - CITY[1]) > CITY_R: continue
                    # door ray: the road must be in front of the door
                    ddx, ddz = to_world(face, dx_door, D / 2 + 0.2)
                    ox, oz = cx + ddx, cz + ddz
                    front = None
                    for dd in np.arange(7.0, ROAD_FRONT_MAX + 0.1, 1.0):
                        if road_d(ox + fx_ * dd, oz + fz_ * dd) < 0.6: front = dd; break
                    if front is None: continue
                    ok = True; nearest = 1e9
                    for (a, b) in samp:
                        wx, wz = to_world(face, a, b)
                        rd = road_d(cx + wx, cz + wz)
                        nearest = min(nearest, rd)
                        if rd < ROAD_CLEAR: ok = False; break
                    if not ok: continue
                    # flat dry ground under and 5 m around the footprint (never the coast ramp / water)
                    for (a, b) in [(xs[0] - 5, zs[0] - 5), (xs[0] - 5, zs[-1] + 5), (xs[-1] + 5, zs[0] - 5), (xs[-1] + 5, zs[-1] + 5)] + samp[::3]:
                        wx, wz = to_world(face, a, b)
                        if abs(ground(cx + wx, cz + wz) - 2.4) > 0.02: ok = False; break
                    if not ok: continue
                    cands.append((cx, cz, face, front, nearest))
        print(sp['name'], 'valid candidates', len(cands))
        if not cands: raise SystemExit('no site for ' + sp['name'])
        best = None; bs = -1e9
        for (cx, cz, face, front, nearest) in cands:
            md = min([math.hypot(cx - c[0], cz - c[1]) for c in chosen] or [1e9])
            if md < MIN_GAP: continue
            if not chosen:
                sc = -math.hypot(cx - CITY[0], cz - CITY[1]) - abs(front - 12) * 3
            else:
                sc = min(md, 260.0) - abs(front - 12) * 3 - 0.3 * math.hypot(cx - CITY[0], cz - CITY[1])
            if sc > bs: bs = sc; best = (cx, cz, face, front)
        if best is None: raise SystemExit('no spaced site for ' + sp['name'])
        cx, cz, face, front = best
        y0 = 2.4
        chosen.append((cx, cz, face, y0))
        placed[idx] = (cx, cz, face, y0, front)
        print('  ->', sp['name'], 'x=%.1f z=%.1f face=%d road in front %.0f m' % (cx, cz, face, front))

    # ------------------------------------------------------------ world transform + write
    out = bytearray(b'BJH1' + struct.pack('<i', len(SPECS)))
    lines = []
    for idx, (sp, m, info, bb) in enumerate(models):
        cx, cz, face, y0, front = placed[idx]
        p, q, r, s = FACES[face]
        def W2(lx, lz):
            return cx + (lx if face == 0 else lz if face == 1 else -lx if face == 2 else -lz), cz + (lz if face == 0 else -lx if face == 1 else -lz if face == 2 else lx)
        V = np.array(m.V, np.float32).copy()
        for k in range(len(V)):
            X, Z = W2(V[k, 0], V[k, 2]); nx, nz = to_world(face, V[k, 3], V[k, 5])
            V[k, 0] = X; V[k, 1] += y0; V[k, 2] = Z; V[k, 3] = nx; V[k, 5] = nz
        I = np.array(m.I, np.int32)
        B = []
        for (a0, a1, b0, b1, c0, c1) in m.boxes:
            xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
            B.append([min(xs_), max(xs_), b0 + y0, b1 + y0, min(zs_), max(zs_)])
        B = np.array(B, np.float32)
        S = []
        for sf in m.surf:
            a0, a1, c0, c1 = sf['rect']
            xs_ = [W2(a, c)[0] for a in (a0, a1) for c in (c0, c1)]; zs_ = [W2(a, c)[1] for a in (a0, a1) for c in (c0, c1)]
            row = [min(xs_), max(xs_), min(zs_), max(zs_)]
            for (a, b, c) in sf['planes']:
                if c >= 1e3: row += [0.0, 0.0, 1e4]; continue
                A = a * p + b * r; Bc = a * q + b * s   # y = A*dX + Bc*dZ + c  (dX = X - cx)
                row += [A, Bc, c + y0 - A * cx - Bc * cz]
            row += [float(sf['flag']), float(sf['thick'])]
            S.append(row)
        S = np.array(S, np.float32)
        L = np.array([[*W2(x, z)[:1], y + y0, W2(x, z)[1]] for (x, y, z) in m.loot], np.float32)
        minX, maxX = float(V[:, 0].min() - 0.3), float(V[:, 0].max() + 0.3); minZ, maxZ = float(V[:, 2].min() - 0.3), float(V[:, 2].max() + 0.3)
        out += struct.pack('<7f', minX, maxX, minZ, maxZ, y0, cx, cz)
        out += struct.pack('<5i', len(V), len(I), len(B), len(S), len(L))
        out += V.astype('<f4').tobytes() + I.astype('<i4').tobytes() + B.astype('<f4').tobytes() + S.astype('<f4').tobytes() + L.astype('<f4').tobytes()
        lines.append('%d %-24s x=%.1f z=%.1f face=%d y0=%.2f aabb x %.1f..%.1f z %.1f..%.1f  verts=%d boxes=%d surf=%d loot=%d' %
                     (idx + 1, sp['name'], cx, cz, face, y0, minX, maxX, minZ, maxZ, len(V), len(B), len(S), len(L)))
    os.makedirs(os.path.dirname(BIN_OUT), exist_ok=True)
    open(BIN_OUT, 'wb').write(bytes(out))
    open(os.path.join(HERE, 'sites.txt'), 'w').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('houses.bin', len(out), 'bytes')


if __name__ == '__main__':
    main()
