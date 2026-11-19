#!/usr/bin/env python3
"""v57: white dashed centre line for every road. bijnor.bin roads -> 1 m raster -> skeleton (centre line) -> dashes.
Writes app/src/main/assets/maps/roadline.bin: 'BJL1', int n, then n x (x1 z1 x2 z2) float32 = one dash each (MapData.buildRoadLine makes the 0.25 m wide quads).
Run: python3 tools/make_roadline.py ; python3 tools/sync_manifest.py"""
import sys, os, struct, numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(HERE, 'houses'))
from mapio import read_bin, road_mask
from skimage.morphology import skeletonize
from scipy import ndimage as ndi
DASH, GAP = 3.0, 4.5
m = read_bin(os.path.join(ROOT, 'app/src/main/assets/maps/bijnor.bin')); x0 = m['x0']
RC = 1.0
mk = ndi.binary_closing(road_mask(m, RC) > 0, iterations=2)
sk = skeletonize(mk)
K = np.ones((3, 3)); K[1, 1] = 0
nb = ndi.convolve(sk.astype(int), K, mode='constant') * sk
junc = sk & (nb >= 3)
clear = ndi.binary_dilation(junc, iterations=9)          # no dashes inside junctions
sk2 = sk & ~clear
lab, nl = ndi.label(sk2, structure=np.ones((3, 3)))
H, W = sk2.shape
dashes = []
def smooth(a, s=3):
    if len(a) < 7: return a
    k = np.exp(-0.5 * (np.arange(-3 * s, 3 * s + 1) / s) ** 2); k /= k.sum()
    p = np.pad(a, ((3 * s, 3 * s), (0, 0)), mode='edge')
    o = np.stack([np.convolve(p[:, c], k, mode='valid') for c in range(2)], 1)
    o[0] = a[0]; o[-1] = a[-1]; return o
objs = ndi.find_objects(lab)
for li, sl in enumerate(objs, 1):
    sub = lab[sl] == li
    ys, xs = np.nonzero(sub)
    if len(ys) < 14: continue
    pts = set(zip((ys + sl[0].start).tolist(), (xs + sl[1].start).tolist()))
    def nbrs(p):
        return [(p[0] + a, p[1] + b) for a in (-1, 0, 1) for b in (-1, 0, 1) if (a or b) and (p[0] + a, p[1] + b) in pts]
    seen = set()
    ends = [p for p in pts if len(nbrs(p)) == 1] or [next(iter(pts))]
    for st in ends:
        if st in seen: continue
        path = [st]; seen.add(st); cur = st
        while True:
            nx = [q for q in nbrs(cur) if q not in seen]
            if not nx: break
            nx.sort(key=lambda q: abs(q[0] - cur[0]) + abs(q[1] - cur[1]))   # straight steps first
            cur = nx[0]; seen.add(cur); path.append(cur)
        if len(path) < 14: continue
        a = np.array([(x0 + p[1] * RC, x0 + p[0] * RC) for p in path])   # world x, z
        a = smooth(a)
        seg = np.hypot(*np.diff(a, axis=0).T); s = np.concatenate([[0], np.cumsum(seg)])
        t = 1.5
        while t + DASH < s[-1] - 1.5:
            i0 = np.searchsorted(s, t); i1 = np.searchsorted(s, t + DASH)
            p0 = a[min(i0, len(a) - 1)]; p1 = a[min(i1, len(a) - 1)]
            if np.hypot(*(p1 - p0)) > 1.5: dashes.append((p0[0], p0[1], p1[0], p1[1]))
            t += DASH + GAP
d = np.array(dashes, dtype='<f4')
open(os.path.join(ROOT, 'app/src/main/assets/maps/roadline.bin'), 'wb').write(b'BJL1' + struct.pack('<i', len(d)) + d.tobytes())
print('dashes', len(d), 'bytes', 8 + d.nbytes)
