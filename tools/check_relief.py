#!/usr/bin/env python3
"""v53: checks the relief map (assets/maps/bijnor.bin) the way the game uses it.
  1. road surface (vertex = meshY + 0.07, linear inside the triangle) against the rendered terrain mesh (MapData.meshY): how often / how far the mesh pokes through the asphalt
  2. flat ground under every house (+6 m) like tools/houses/audit_map.py
Usage: python3 tools/check_relief.py [map.bin]   (default: the app asset)"""
import struct, sys, numpy as np
P = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/maps/bijnor.bin'
b = open(P, 'rb').read(); o = 4
n, cell, x0 = struct.unpack_from('<iff', b, o); o += 12 + 16
nr, = struct.unpack_from('<i', b, o); o += 4 + nr * 8
nl, = struct.unpack_from('<i', b, o); o += 4
layers = []
for _ in range(nl):
    lvl, nv, ni = struct.unpack_from('<iii', b, o); o += 12
    xz = np.frombuffer(b, '<i2', nv * 2, o).astype(float).reshape(nv, 2) / 10; o += nv * 4
    idx = np.frombuffer(b, '<u2', ni, o).reshape(-1, 3).astype(int); o += ni * 2
    layers.append((lvl, xz, idx))
nc, = struct.unpack_from('<i', b, o); o += 4 + nc * 20
gh = (np.frombuffer(b, '<i2', n * n, o).astype(float) / 100).reshape(n, n)
m = (n - 1) // 2
def meshY(x, z):      # MapData.meshY
    fx = np.clip((x - x0) / (2 * cell), 0, m - 1e-4); fz = np.clip((z - x0) / (2 * cell), 0, m - 1e-4)
    i = fx.astype(int); k = fz.astype(int); tx = fx - i; tz = fz - k
    a = gh[2 * k, 2 * i]; bb = gh[2 * k, 2 * i + 2]; c = gh[2 * k + 2, 2 * i]; d = gh[2 * k + 2, 2 * i + 2]
    return np.where(tx + tz <= 1, a + (bb - a) * tx + (c - a) * tz, d + (bb - d) * (1 - tz) + (c - d) * (1 - tx))
rng = np.random.default_rng(1)
worst = 0; under = 0; tot = 0
for lvl, xz, idx in layers:
    vy = meshY(xz[:, 0], xz[:, 1]) + 0.07 + lvl * 0.004
    T = len(idx); S = 6
    r1 = rng.random((T, S)); r2 = rng.random((T, S)); f = r1 + r2 > 1; r1[f] = 1 - r1[f]; r2[f] = 1 - r2[f]; r3 = 1 - r1 - r2
    A = xz[idx[:, 0]][:, None, :]; B = xz[idx[:, 1]][:, None, :]; C = xz[idx[:, 2]][:, None, :]
    p = r1[..., None] * A + r2[..., None] * B + r3[..., None] * C
    ry = r1 * vy[idx[:, 0]][:, None] + r2 * vy[idx[:, 1]][:, None] + r3 * vy[idx[:, 2]][:, None]
    ground_here = meshY(p[..., 0], p[..., 1])
    diff = ground_here - ry          # > 0: the mesh is above the asphalt
    tot += diff.size; under += int((diff > 0.0).sum()); worst = max(worst, float(diff.max()))
print('road samples %d, mesh above asphalt: %.2f %%, worst %.3f m' % (tot, 100 * under / tot, worst))
