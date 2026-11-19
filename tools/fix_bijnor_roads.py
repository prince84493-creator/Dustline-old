#!/usr/bin/env python3
"""Repairs the road meshes inside app/src/main/assets/maps/bijnor.bin (v1.0.32). Safe to run again (idempotent).

Problems it fixes (found by the map audit):
  1. Road triangles that stick out into the sea (some corners were 6 m under water) -> cut back to the coast.
  2. Very long road triangles near the coast whose corner heights do not follow the sloping ground (up to 2.9 m
     off: the asphalt sank into the ground or floated) -> split until the road follows the ground within 5 cm.
  3. Degenerate (zero-area) triangles and unused vertices -> removed.
Format of the file is unchanged (BJN1). Run:  python3 tools/fix_bijnor_roads.py   then   python3 tools/sync_manifest.py
"""
import struct, sys, pathlib
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
BIN = ROOT / 'app/src/main/assets/maps/bijnor.bin'
b = BIN.read_bytes()
assert b[:4] == b'BJN1'
o = 4
n, cell, x0 = struct.unpack_from('<iff', b, o); o += 12
head_end = o + 16                                   # spawn xyz + island radius
nr, = struct.unpack_from('<i', b, head_end); ring_end = head_end + 4 + nr * 8
o = ring_end
nl, = struct.unpack_from('<i', b, o); o += 4
layers = []
for _ in range(nl):
    lvl, nv, ni = struct.unpack_from('<iii', b, o); o += 12
    v = np.frombuffer(b, '<i2', nv * 2, o).reshape(-1, 2).astype(int); o += nv * 4
    i = np.frombuffer(b, '<u2', ni, o).astype(int); o += ni * 2
    layers.append((lvl, v, i.reshape(-1, 3)))
tail_start = o                                      # connectors + height grid, copied unchanged
gh_off = None
nc, = struct.unpack_from('<i', b, o)
gh_off = o + 4 + nc * 20
gh = np.frombuffer(b, '<i2', n * n, gh_off).reshape(n, n) / 100.0

def ground(x, z):                                   # same maths as MapData.ground() (x, z in metres)
    fx = min(max((x - x0) / cell, 0), n - 1.001); fz = min(max((z - x0) / cell, 0), n - 1.001)
    i = int(fx); k = int(fz); tx = fx - i; tz = fz - k
    return gh[k, i] * (1 - tx) * (1 - tz) + gh[k, i + 1] * tx * (1 - tz) + gh[k + 1, i] * (1 - tx) * tz + gh[k + 1, i + 1] * tx * tz

def mesh(x, z):                                     # height of the RENDERED terrain (12.8 m grid, quad diagonal b-c) = MapData.meshY()
    m = (n - 1) // 2
    fx = min(max((x - x0) / (2 * cell), 0), m - 1e-4); fz = min(max((z - x0) / (2 * cell), 0), m - 1e-4)
    i = int(fx); k = int(fz); tx = fx - i; tz = fz - k
    a = gh[2 * k, 2 * i]; bb = gh[2 * k, 2 * i + 2]; c = gh[2 * k + 2, 2 * i]; d = gh[2 * k + 2, 2 * i + 2]
    return a + (bb - a) * tx + (c - a) * tz if tx + tz <= 1 else d + (bb - d) * (1 - tz) + (c - d) * (1 - tx)

def G(p): return ground(p[0] / 10.0, p[1] / 10.0)   # p in tenths of a metre (water test: real ground)
def H(p): return mesh(p[0] / 10.0, p[1] / 10.0)     # p in tenths of a metre (road height: the terrain you SEE)
MID = lambda a, c: ((a[0] + c[0]) // 2, (a[1] + c[1]) // 2)
SEA_KEEP = 0.3                                      # ground below this = sea / water edge
MIN_EDGE = 30                                       # 3.0 m (in tenths)
TOL = 0.05                                          # road may differ 5 cm from the real ground

def area2(a, c, d): return (c[0] - a[0]) * (d[1] - a[1]) - (d[0] - a[0]) * (c[1] - a[1])
def elen(a, c): return max(abs(a[0] - c[0]), abs(a[1] - c[1]))

def walk(a, c, d, out, stat):
    if area2(a, c, d) == 0: stat['degenerate'] += 1; return
    gs = [G(a), G(c), G(d)]
    hs = [H(a), H(c), H(d)]
    m = [MID(a, c), MID(c, d), MID(d, a)]
    cen = ((a[0] + c[0] + d[0]) // 3, (a[1] + c[1] + d[1]) // 3)
    samp = m + [cen]
    wts = [(.5, .5, 0), (0, .5, .5), (.5, 0, .5), (1 / 3, 1 / 3, 1 / 3)]
    err = max(abs(H(s) - (w[0] * hs[0] + w[1] * hs[1] + w[2] * hs[2])) for s, w in zip(samp, wts))
    sea = min(gs + [G(s) for s in samp]) < SEA_KEEP
    big = max(elen(a, c), elen(c, d), elen(d, a)) > MIN_EDGE
    if not sea and err <= TOL: out.append((a, c, d)); return
    if big:
        m1, m2, m3 = m
        for t in ((a, m1, m3), (m1, c, m2), (m3, m2, d), (m1, m2, m3)): walk(*t, out, stat)
        return
    if sea:
        if G(cen) >= SEA_KEEP and min(gs) >= -0.3: out.append((a, c, d))
        else: stat['cut'] += 1
    else: out.append((a, c, d))

out_layers = []; stat = {'degenerate': 0, 'cut': 0}
for lvl, v, tr in layers:
    tris = []
    for t in tr: walk(tuple(v[t[0]]), tuple(v[t[1]]), tuple(v[t[2]]), tris, stat)
    vid = {}; verts = []; idx = []
    for t in tris:
        for p in t:
            if p not in vid: vid[p] = len(verts); verts.append(p)
            idx.append(vid[p])
    assert len(verts) < 65536
    print('layer', lvl, 'tris', len(tr), '->', len(tris), 'verts', len(v), '->', len(verts))
    out_layers.append((lvl, np.array(verts, '<i2'), np.array(idx, '<u2')))
print('dropped sea-edge slivers', stat['cut'], 'degenerate', stat['degenerate'])

res = bytearray(b[:ring_end]) + struct.pack('<i', len(out_layers))
for lvl, v, i in out_layers:
    res += struct.pack('<iii', lvl, len(v), len(i)) + v.tobytes() + i.tobytes()
res += b[tail_start:]
BIN.write_bytes(bytes(res))
print('bijnor.bin', len(b), '->', len(res))
