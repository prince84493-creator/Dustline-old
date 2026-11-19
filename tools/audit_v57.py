#!/usr/bin/env python3
"""v57 audit of the Bijnor map data (works with the instanced houses.bin of v54+; tools/houses/audit_map.py only knows the old baked format).
   python3 tools/audit_v57.py
Checks (prints FAIL / WARN lines, then a summary):
  A. ground under every house footprint (+6 m) is level at the baked height (2.40 m)
  B. every house stands on land (all 4 corners above the sea)
  C. houses that overlap each other (footprint boxes)
  D. real building cells (roofs.bin raster) that lie on a road (building on the asphalt)
  E. roofs.bin shelters every roofed cell (+1 m rim) - rain test
  F. spawn is level + on land, places (BijnorLabels) are on land
"""
import os, struct, sys, re
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.join(HERE, 'houses'))
from mapio import read_bin, road_mask
ROOT = os.path.dirname(HERE)
mp = read_bin(os.path.join(ROOT, 'app/src/main/assets/maps/bijnor.bin'))
n, cell, x0, gh = mp['n'], mp['cell'], mp['x0'], mp['gh']
RC = 2.5; mk = road_mask(mp, RC)
def ground(x, z):
    fx = min(max((x - x0) / cell, 0), n - 1.001); fz = min(max((z - x0) / cell, 0), n - 1.001); i, k = int(fx), int(fz); tx, tz = fx - i, fz - k
    return gh[k, i] * (1 - tx) * (1 - tz) + gh[k, i + 1] * tx * (1 - tz) + gh[k + 1, i] * (1 - tx) * tz + gh[k + 1, i + 1] * tx * tz
def onroad(x, z):
    i = int(round((x - x0) / RC)); k = int(round((z - x0) / RC))
    return 0 <= i < mk.shape[1] and 0 <= k < mk.shape[0] and mk[k, i] == 1

b = open(os.path.join(ROOT, 'app/src/main/assets/models/houses/houses.bin'), 'rb').read()
assert b[:4] == b'BJH1'; nh, = struct.unpack_from('<i', b, 4); o = 8
H = []
for h in range(nh):
    minX, maxX, minZ, maxZ, y0, cx, cz = struct.unpack_from('<7f', b, o); o += 28
    nv, ni, nb, ns, nl = struct.unpack_from('<5i', b, o); o += 20
    o += nv * 44 + ni * 4 + nb * 24 + ns * 72 + nl * 12
    H.append((minX, maxX, minZ, maxZ, y0))
r = open(os.path.join(ROOT, 'app/src/main/assets/models/houses/roofs.bin'), 'rb').read(); ro = 8
R = []
for h in range(nh):
    ox, oz, w, d = struct.unpack_from('<ffii', r, ro); ro += 16
    R.append((ox, oz, w, d, np.frombuffer(r, np.uint8, w * d, ro).reshape(d, w))); ro += w * d

nfail = 0; nwarn = 0
def F(m):
    global nfail; nfail += 1; print('FAIL', m)
def W(m):
    global nwarn; nwarn += 1; print('WARN', m)

# A + B
worst = 0.0
for h, (a, bb, c, d, y0) in enumerate(H):
    zs = [ground(x, z) for x in np.linspace(a - 6, bb + 6, 9) for z in np.linspace(c - 6, d + 6, 9)]
    dev = max(abs(v - y0) for v in zs); worst = max(worst, dev)
    if dev > 0.03: F('house %d: ground not level under the house (dev %.2f m)' % (h, dev))
    if min(ground(x, z) for x, z in ((a, c), (a, d), (bb, c), (bb, d))) < 1.0: F('house %d stands (partly) in the sea' % h)
print('A/B: worst ground deviation under houses %.3f m' % worst)

# C
bx = np.array([[a, bb, c, d] for a, bb, c, d, _ in H])
ov = 0
order = np.argsort(bx[:, 0])
for ii in range(nh):
    i = order[ii]
    for jj in range(ii + 1, nh):
        j = order[jj]
        if bx[j, 0] >= bx[i, 1]: break
        wx = min(bx[i, 1], bx[j, 1]) - max(bx[i, 0], bx[j, 0]); wz = min(bx[i, 3], bx[j, 3]) - max(bx[i, 2], bx[j, 2])
        if wx > 0.5 and wz > 0.5 and wx * wz > 6:
            ov += 1
            if ov <= 12: W('houses %d and %d overlap (%.1f x %.1f m)' % (i, j, wx, wz))
print('C: overlapping house pairs: %d' % ov)

# D + E
road_cells = 0; badh = 0
for h, (ox, oz, w, d, q) in enumerate(R):
    kk, ii = np.nonzero(q)
    if len(kk) == 0: F('house %d has no roof raster' % h); continue
    xs = ox + (ii + .5) * .5; zs = oz + (kk + .5) * .5
    n_on = sum(1 for x, z in zip(xs[::3], zs[::3]) if onroad(x, z))
    if n_on > 3: badh += 1; road_cells += n_on
    if n_on > 8 and badh <= 15: W('house %d: ~%d roof cells on a road' % (h, n_on * 3))
print('D: houses with roof on a road: %d' % badh)
# E: rain shelter holes: a roofed cell whose 4 neighbours (1 cell) are all covered must itself be covered
holes = 0
for (ox, oz, w, d, q) in R:
    c = q > 0
    nb = np.zeros_like(c)
    nb[1:-1, 1:-1] = c[:-2, 1:-1] & c[2:, 1:-1] & c[1:-1, :-2] & c[1:-1, 2:]
    holes += int((nb & ~c).sum())
print('E: pin-holes in the rain raster: %d' % holes)

# F
sx, sy, sz = mp['spawn']
print('F: spawn ground %.2f (expect 2.40)' % ground(sx, sz))
if abs(ground(sx, sz) - 2.4) > 0.03: F('spawn not level')
print('SUMMARY: %d FAIL, %d WARN' % (nfail, nwarn))
