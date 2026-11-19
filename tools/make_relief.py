#!/usr/bin/env python3
"""v57 (was v53): STRONGER relief - hills up to ~+8 m, valleys, and small pits for puddles. (v53 text follows)
v53: gives the dead-flat Bijnor land (95 % of the cells were exactly 2.40 m) gentle rolling relief.

Reads  tools/orig/bijnor_flat_v52.bin  (the untouched v52 map)  ->  writes app/src/main/assets/maps/bijnor.bin.  Run sync_manifest.py after.

(relief grows at most 0.055 m per metre away from a flat zone = max ~3 degrees there)
What stays EXACTLY flat (houses are baked at y = 2.40 m, roads are drawn on the terrain mesh, the spawn must be level):
  * every house + compound (footprint from houses.bin) and 10 m around it, then a smooth ramp to full relief at 45 m
  * every road / connector and 4 m around it, ramp to full relief at 14 m
  * a 40 m disc round the spawn point
  * the coast: nothing is changed within 50 m of any cell that is not full-height land (beach / slope into the sea), full relief from 200 m
What changes: fields between villages roll up and down (long hills ~380 m, ~150 m swells, ~60 m bumps). Land never drops below 1.4 m (sea level 0, the
walkable test is height > 0.02), and hills reach about +3 m. Only wavelengths >= 60 m so the 6.4 m height grid and the 12.8 m render mesh agree.
"""
import struct, sys, math
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi

SRC = 'tools/orig/bijnor_flat_v52.bin'
DST = 'app/src/main/assets/maps/bijnor.bin'
HOUSES = 'app/src/main/assets/models/houses/houses.bin'
SEED = 57
H_STD = 3.4          # metres of relief (1 sigma)  v57: was 1.5 - the v53 hills were so wide (380 m) that you could not see them
LO, HI = -1.1, 8.6    # clamp of the relief (land never below 1.3 m, hills up to ~11 m)
RES = 3.2             # raster metres per pixel for the distance maps

b = open(SRC, 'rb').read()
assert b[:4] == b'BJN1'
o = 4
n, cell, x0 = struct.unpack_from('<iff', b, o); o += 12
sx, sy, sz, _r = struct.unpack_from('<ffff', b, o); o += 16
nr, = struct.unpack_from('<i', b, o); o += 4 + nr * 8
nl, = struct.unpack_from('<i', b, o); o += 4
layers = []
for _ in range(nl):
    lvl, nv, ni = struct.unpack_from('<iii', b, o); o += 12
    xz = np.frombuffer(b, '<i2', nv * 2, o).astype(np.float64).reshape(nv, 2) / 10.0; o += nv * 4
    idx = np.frombuffer(b, '<u2', ni, o).reshape(-1, 3); o += ni * 2
    layers.append((xz, idx))
nc, = struct.unpack_from('<i', b, o); o += 4
conn = np.frombuffer(b, '<f4', nc * 5, o).reshape(nc, 5); o += nc * 20
gh_off = o
g = (np.frombuffer(b, '<i2', n * n, gh_off).astype(np.float64) / 100.0).reshape(n, n)   # g[k, i]: z index k, x index i
assert gh_off + n * n * 2 == len(b)
span = n * cell
W = int(math.ceil(span / RES))
def px(x): return (x - x0) / RES
def pz(z): return (z - x0) / RES

# ---- flat mask raster
img = Image.new('L', (W, W), 0)
dr = ImageDraw.Draw(img)
for xz, idx in layers:
    for t in idx:
        dr.polygon([(px(xz[a, 0]), pz(xz[a, 1])) for a in t], fill=255)
for x1, z1, x2, z2, w in conn:
    dr.line([(px(x1), pz(z1)), (px(x2), pz(z2))], fill=255, width=max(1, int(round(w / RES))))
roadpix = np.array(img) > 0
# houses
hb = open(HOUSES, 'rb').read(); ho = 8; nh, = struct.unpack_from('<i', hb, 4)
hmask = Image.new('L', (W, W), 0); hd = ImageDraw.Draw(hmask)
for h in range(nh):
    # v54: footprint = the block header (minX maxX minZ maxZ) - works for baked meshes and for instanced buildings (nV = 0)
    a0, a1, b0, b1 = struct.unpack_from('<4f', hb, ho); ho += 28
    nv, ni, nb, ns, nlt = struct.unpack_from('<5i', hb, ho); ho += 20
    ho += nv * 44 + ni * 4 + nb * 24 + ns * 72 + nlt * 12
    hd.rectangle([px(a0), pz(b0), px(a1), pz(b1)], fill=255)
housepix = np.array(hmask) > 0
spawn = Image.new('L', (W, W), 0); ImageDraw.Draw(spawn).ellipse([px(sx - 40), pz(sz - 40), px(sx + 40), pz(sz + 40)], fill=255)
spawnpix = np.array(spawn) > 0

def sst(a, bb, x):
    t = np.clip((x - a) / (bb - a), 0, 1); return t * t * (3 - 2 * t)

# distances in metres, sampled at the height-grid points
def dist_at_grid(mask):
    d = ndi.distance_transform_edt(~mask) * RES
    ii = np.clip(np.round((np.arange(n) * cell) / RES).astype(int), 0, W - 1)
    return d[np.ix_(ii, ii)]
D_house = dist_at_grid(housepix)
D_road = dist_at_grid(roadpix)
D_spawn = dist_at_grid(spawnpix)
plateau = g >= 2.39
D_coast = ndi.distance_transform_edt(plateau) * cell     # distance to the nearest non-plateau cell

# ---- noise
rng = np.random.default_rng(SEED)
def noise(lam):
    m = int(math.ceil(span / lam)) + 4
    a = rng.standard_normal((m, m))
    z = ndi.zoom(a, n / (m - 3) , order=3, mode='reflect')[:n, :n]
    if z.shape != (n, n):
        z = np.pad(z, ((0, n - z.shape[0]), (0, n - z.shape[1])), mode='edge')
    return (z - z.mean()) / z.std()
F = 0.42 * noise(900) + 0.34 * noise(420) + 0.30 * noise(190) + 0.16 * noise(90) + 0.07 * noise(48)   # v57: big hills + swells + small bumps
F /= F.std()
raw = np.clip(H_STD * F, LO, HI)

w_house = sst(18, 78, D_house)
w_road = sst(4, 40, D_road)
w_spawn = sst(40, 120, D_spawn)
w_coast = sst(50, 200, D_coast)
w = w_house * w_road * w_spawn * w_coast
off = ndi.gaussian_filter(raw * w, 1.0)
# slope limit: relief may only grow SLOPE m per metre away from a flat zone (no cliffs along roads / house walls), then a light blur
SLOPE = 0.10    # v57: ~5.7 degrees next to a flat zone (was 0.055)
Dm = np.maximum(np.minimum.reduce([D_road - 4.0, D_house - 18.0, D_spawn - 36.0, D_coast - 40.0]), 0.0)
L = SLOPE * Dm
off = np.clip(off, -L, L)
off = ndi.gaussian_filter(off, 0.8)

# ---- v57: PITS / hollows where rain water collects (puddles). Only where the ground is free (Dm >= 12 m), never on the coast.
#  round pits sigma 8-17 m, 0.5-1.1 m deep (a 12.8 m render mesh shows sigma >= 8 m), plus a few big shallow basins (sigma 22-34 m, 1.2-1.9 m deep)
prng = np.random.default_rng(SEED + 1)
free = (Dm >= 12.0) & plateau
fy, fx = np.nonzero(free)
yy, xx = np.mgrid[0:n, 0:n]
pit = np.zeros((n, n))
def add_pit(ci, ck, sg, dep, ex):
    r = int(3.2 * sg * ex / cell) + 2
    k0, k1 = max(0, ck - r), min(n, ck + r + 1); i0, i1 = max(0, ci - r), min(n, ci + r + 1)
    dx = (xx[k0:k1, i0:i1] - ci) * cell; dz = (yy[k0:k1, i0:i1] - ck) * cell
    pit[k0:k1, i0:i1] -= dep * np.exp(-((dx / (sg * ex)) ** 2 + (dz / (sg / ex)) ** 2))
npit = 0
if len(fy):
    for _ in range(1500):
        t = prng.integers(len(fy)); ci, ck = int(fx[t]), int(fy[t])
        if Dm[ck, ci] < 22: continue
        add_pit(ci, ck, prng.uniform(8, 17), prng.uniform(0.5, 1.1), prng.uniform(0.8, 1.4)); npit += 1
    for _ in range(90):
        t = prng.integers(len(fy)); ci, ck = int(fx[t]), int(fy[t])
        if Dm[ck, ci] < 45: continue
        add_pit(ci, ck, prng.uniform(22, 34), prng.uniform(1.2, 1.9), prng.uniform(0.8, 1.3)); npit += 1
print('pits', npit)
off = off + pit * sst(10, 26, Dm)

# ---- v57: make the 6.4 m height grid and the 12.8 m render mesh agree: the odd grid points become the average of their even neighbours
c_ = off[::2, ::2]
f_ = np.empty_like(off)
f_[::2, ::2] = c_
f_[1::2, ::2] = (c_[:-1] + c_[1:]) / 2
f_[::2, 1::2] = (c_[:, :-1] + c_[:, 1:]) / 2
f_[1::2, 1::2] = (c_[:-1, :-1] + c_[1:, :-1] + c_[:-1, 1:] + c_[1:, 1:]) / 4
off = f_
off *= sst(0, 3, Dm)           # hard guarantee: exactly flat on roads, houses, spawn and the beach (the clip above already makes it ~0 there)
off[Dm <= 0.0] = 0.0
off = np.where(plateau, off, 0.0)
gnew = g + off
gnew = np.where(plateau, np.maximum(gnew, 1.3), g)

print('land cells', int(plateau.sum()), ' changed >5cm: %.1f %%' % (100 * (np.abs(off) > 0.05).sum() / plateau.sum()),
      ' >0.5m: %.1f %%' % (100 * (np.abs(off) > 0.5).sum() / plateau.sum()))
print('offset min %.2f max %.2f  new land min %.2f max %.2f' % (off.min(), off.max(), gnew[plateau].min(), gnew[plateau].max()))
gy, gx = np.gradient(gnew, cell)
sl = np.degrees(np.arctan(np.hypot(gx, gy)))[plateau]
print('slope deg: mean %.2f  p95 %.2f  max %.2f' % (sl.mean(), np.percentile(sl, 95), sl.max()))
# check: flat where it must be
hm = D_house < 14
print('house cells (<8 m) max |offset|: %.4f' % (np.abs(off[hm]).max() if hm.any() else 0))
rm = D_road < 3
print('road cells (<3 m) max |offset|: %.4f' % (np.abs(off[rm]).max() if rm.any() else 0))

q = np.clip(np.round(gnew * 100), -32768, 32767).astype('<i2')
open(DST, 'wb').write(b[:gh_off] + q.tobytes())
print('wrote', DST)

# ---- preview (hill-shade + flat zones), not part of the app
if len(sys.argv) > 1:
    e = gnew
    gy2, gx2 = np.gradient(e, cell)
    shade = np.clip(0.6 + 1.6 * (-gx2 * 0.6 - gy2 * 0.8), 0, 1)
    rgb = np.dstack([shade * 160 + 60, shade * 190 + 50, shade * 120 + 40]).astype(np.uint8)
    rgb[~plateau] = (40, 90, 170)
    fl = (D_road < 3) | (D_house < 8)
    rgb[fl] = (200, 60, 60)
    Image.fromarray(rgb).save(sys.argv[1])
    print('preview', sys.argv[1])
