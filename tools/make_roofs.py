#!/usr/bin/env python3
"""v53/v54: houses.bin -> roofs.bin  (rain shelter map). v54: also reads instanced buildings (BJI1): one raster per template, turned to each placement.

For every house a 0.5 m top-view raster of its highest solid surface (roof, balcony, porch roof, floor of a terrace).
RainFx drops that are BELOW that height at their x,z are hidden -> no rain inside a house.

roofs.bin (little endian):  "BJR1", int nh, then per house (same order as houses.bin):
    float ox, float oz (world x,z of cell 0,0), int w, int d, then w*d bytes (row-major, k*w+i)
    byte b: 0 = nothing above the ground, else top height = y0 + b*0.1 m   (y0 = ground y of the house)
"""
import struct, sys, math
import numpy as np
from scipy import ndimage as ndi

SRC = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/models/houses/houses.bin'
DST = sys.argv[2] if len(sys.argv) > 2 else 'app/src/main/assets/models/houses/roofs.bin'
CELL = 0.5

def raster(v, ix, y0):
    """top-view raster (0.5 m cells) of the highest surface of one mesh. Returns ox, oz, w, d, q (uint8, d x w)."""
    ox = math.floor(float(v[:, 0].min()) - 0.5); oz = math.floor(float(v[:, 2].min()) - 0.5)
    w = int(math.ceil((float(v[:, 0].max()) + 0.5 - ox) / CELL)) + 1
    d = int(math.ceil((float(v[:, 2].max()) + 0.5 - oz) / CELL)) + 1
    top = np.full((d, w), -1e9, dtype=np.float64)
    tri = ix.reshape(-1, 3)
    P = v[tri][:, :, :3].astype(np.float64)               # (T,3,3)
    e1 = P[:, 1] - P[:, 0]; e2 = P[:, 2] - P[:, 0]
    nrm = np.cross(e1, e2); ln = np.linalg.norm(nrm, axis=1) + 1e-12
    ny = np.abs(nrm[:, 1]) / ln
    keep = (ny > 0.15) & (P[:, :, 1].max(axis=1) > y0 + 0.35)   # skip walls and the ground-level skirt
    for t in np.nonzero(keep)[0]:
        (ax, ay, az), (bx, by, bz), (cx_, cy_, cz_) = P[t]
        i0 = int(math.floor((min(ax, bx, cx_) - ox) / CELL)); i1 = int(math.floor((max(ax, bx, cx_) - ox) / CELL))
        k0 = int(math.floor((min(az, bz, cz_) - oz) / CELL)); k1 = int(math.floor((max(az, bz, cz_) - oz) / CELL))
        den = (bz - cz_) * (ax - cx_) + (cx_ - bx) * (az - cz_)
        if abs(den) < 1e-12:
            continue
        for k in range(k0, k1 + 1):
            pz = oz + (k + 0.5) * CELL
            for i in range(i0, i1 + 1):
                px = ox + (i + 0.5) * CELL
                l1 = ((bz - cz_) * (px - cx_) + (cx_ - bx) * (pz - cz_)) / den
                l2 = ((cz_ - az) * (px - cx_) + (ax - cx_) * (pz - cz_)) / den
                l3 = 1 - l1 - l2
                if l1 >= -0.08 and l2 >= -0.08 and l3 >= -0.08:
                    y = l1 * ay + l2 * by + l3 * cy_
                    if y > top[k, i]:
                        top[k, i] = y
        # tiny triangles that miss every cell centre: stamp the vertices
        for (x_, y_, z_) in ((ax, ay, az), (bx, by, bz), (cx_, cy_, cz_)):
            i = int(math.floor((x_ - ox) / CELL)); k = int(math.floor((z_ - oz) / CELL))
            if 0 <= i < w and 0 <= k < d and y_ > top[k, i] and (k1 - k0 + i1 - i0) <= 1:
                top[k, i] = y_
    # close pin-holes (gaps between roof tiles): an empty cell with >= 5 covered neighbours takes the lowest neighbour
    cov = top > -1e8
    pad = np.pad(top, 1, constant_values=-1e9)
    nbrs = np.stack([pad[1 + dk:1 + dk + d, 1 + di:1 + di + w] for dk in (-1, 0, 1) for di in (-1, 0, 1) if (dk, di) != (0, 0)])
    ncov = (nbrs > -1e8).sum(axis=0)
    lowest = np.where(nbrs > -1e8, nbrs, 1e9).min(axis=0)
    fill = (~cov) & (ncov >= 5)
    top = np.where(fill, lowest, top)
    q = np.where(top > -1e8, np.clip(np.round((top - y0) / 0.1), 1, 255), 0).astype(np.uint8)
    return ox, oz, w, d, q


def turn(ox, oz, w, d, q, face, x, z):
    """local raster -> world raster for an instance at (x, z) turned by `face` quarter turns: local (x,z) -> world (x,z): 0 (x,z) | 1 (z,-x) | 2 (-x,-z) | 3 (-z,x)."""
    def f(lx, lz):
        return (lx, lz) if face == 0 else (lz, -lx) if face == 1 else (-lx, -lz) if face == 2 else (-lz, lx)
    cs = [f(ox, oz), f(ox + w * CELL, oz), f(ox, oz + d * CELL), f(ox + w * CELL, oz + d * CELL)]
    mnx = min(c[0] for c in cs); mnz = min(c[1] for c in cs)
    w2, d2 = (w, d) if face % 2 == 0 else (d, w)
    out = np.zeros((d2, w2), np.uint8)
    kk, ii = np.nonzero(q)
    lx = ox + (ii + 0.5) * CELL; lz = oz + (kk + 0.5) * CELL
    wx, wz = f(lx, lz)
    i2 = np.floor((wx - mnx) / CELL).astype(int); k2 = np.floor((wz - mnz) / CELL).astype(int)
    ok = (i2 >= 0) & (i2 < w2) & (k2 >= 0) & (k2 < d2)
    out[k2[ok], i2[ok]] = q[kk[ok], ii[ok]]
    return x + mnx, z + mnz, w2, d2, out


b = open(SRC, 'rb').read()
assert b[:4] == b'BJH1'
o = 8
nh, = struct.unpack_from('<i', b, 4)
out = [b'BJR1', struct.pack('<i', nh)]
tot_cov = 0
blocks = []
for h in range(nh):
    minX, maxX, minZ, maxZ, y0, cx, cz = struct.unpack_from('<7f', b, o); o += 28
    nv, ni, nb, ns, nl = struct.unpack_from('<5i', b, o); o += 20
    if nv > 0:   # baked mesh (v40 and older files)
        v = np.frombuffer(b, '<f4', nv * 11, o).reshape(nv, 11); o += nv * 44
        ix = np.frombuffer(b, '<i4', ni, o); o += ni * 4
        blocks.append(('mesh', raster(v, ix, y0), y0))
    else:
        blocks.append(('inst', None, y0))
    o += nb * 24 + ns * 72 + nl * 12
tpl = []; inst = []
if any(k == 'inst' for k, _, _ in blocks):
    assert b[o:o + 4] == b'BJD1'; nd, = struct.unpack_from('<i', b, o + 4); o += 8 + nd * 64
    assert b[o:o + 4] == b'BJI1'; nt, = struct.unpack_from('<i', b, o + 4); o += 8
    for t in range(nt):
        nv, ni = struct.unpack_from('<2i', b, o); o += 8
        v = np.frombuffer(b, '<f4', nv * 11, o).reshape(nv, 11); o += nv * 44
        ix = np.frombuffer(b, '<i4', ni, o); o += ni * 4
        tpl.append((v, ix))
    nn, = struct.unpack_from('<i', b, o); o += 4
    for q_ in range(nn):
        inst.append(struct.unpack_from('<2i4f', b, o)); o += 24
    assert nn == sum(1 for k, _, _ in blocks if k == 'inst'), 'instances must match the empty blocks'
cache = {}
ii_ = 0
for h, (kind, rs, y0) in enumerate(blocks):
    if kind == 'mesh':
        ox, oz, w, d, q = rs
    else:
        t, face, x, y, z, rad = inst[ii_]; ii_ += 1
        if t not in cache:
            cache[t] = raster(tpl[t][0], tpl[t][1], 0.0)   # template y is local: ground = 0
            print('template', t, 'raster', cache[t][2], 'x', cache[t][3], 'covered', int((cache[t][4] > 0).sum()), flush=True)
        ox, oz, w, d, q = turn(*cache[t], face, x, z)
    q = ndi.maximum_filter(q, size=5)   # v57: grow every roof by 1 m (2 cells): eaves, pin-holes, drops that slant in under the edge -> never rain inside
    tot_cov += int((q > 0).sum())
    out.append(struct.pack('<ffii', ox, oz, w, d)); out.append(q.tobytes())
open(DST, 'wb').write(b''.join(out))
print('wrote', DST, sum(len(x) for x in out), 'bytes, covered cells', tot_cov)
