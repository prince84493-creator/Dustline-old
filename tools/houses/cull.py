"""v43: hidden-face removal for the hollow buildings.  A box face that lies fully inside an OPPOSITE-facing face on the same plane (two solids touching:
furniture on a floor, slab edge against a wall, stacked boxes, wall piece against wall piece) can never be seen: it is dropped.
Works on Model.V / Model.I (quads written as 4 vertices + 6 indices, triangles as 3 + 3).  Non axis-aligned faces are kept."""
import numpy as np

EPS = 2e-3


def _sub(rects, h):
    """rects minus rect h (all (a0,a1,b0,b1))"""
    out = []
    for (a0, a1, b0, b1) in rects:
        ha0, ha1, hb0, hb1 = max(h[0], a0), min(h[1], a1), max(h[2], b0), min(h[3], b1)
        if ha0 >= ha1 - 1e-6 or hb0 >= hb1 - 1e-6:
            out.append((a0, a1, b0, b1)); continue
        if hb0 > b0 + 1e-6: out.append((a0, a1, b0, hb0))
        if hb1 < b1 - 1e-6: out.append((a0, a1, hb1, b1))
        if ha0 > a0 + 1e-6: out.append((a0, ha0, hb0, hb1))
        if ha1 < a1 - 1e-6: out.append((ha1, a1, hb0, hb1))
    return out


def cull_model(m):
    V = np.array(m.V, np.float64)
    I = np.array(m.I, np.int64)
    nq = 0
    # faces are appended quad by quad (4 verts / 6 idx) or tri (3 verts / 3 idx); walk the index list
    faces = []   # (first_vertex, nverts, idx_start, idx_len)
    i = 0; vcur = 0
    while i < len(I):
        b = I[i]
        if i + 6 <= len(I) and I[i + 1] == b + 1 and I[i + 2] == b + 2 and I[i + 3] == b and I[i + 4] == b + 2 and I[i + 5] == b + 3:
            faces.append((b, 4, i, 6)); i += 6
        else:
            faces.append((b, 3, i, 3)); i += 3
    info = []
    planes = {}
    for fi, (b, nv, i0, il) in enumerate(faces):
        P = V[b:b + nv, :3]; n = V[b, 3:6]
        ax = int(np.argmax(np.abs(n)))
        if abs(abs(n[ax]) - 1.0) > 1e-6 or nv != 4:
            info.append(None); continue
        o = [k for k in range(3) if k != ax]
        c = float(P[0, ax])
        if np.abs(P[:, ax] - c).max() > 1e-6:
            info.append(None); continue
        rect = (float(P[:, o[0]].min()), float(P[:, o[0]].max()), float(P[:, o[1]].min()), float(P[:, o[1]].max()))
        sg = 1 if n[ax] > 0 else -1
        key = (ax, round(c / EPS))
        info.append((key, sg, rect))
        planes.setdefault(key, []).append((fi, sg, rect))
    drop = set()
    for fi, inf in enumerate(info):
        if inf is None: continue
        key, sg, rect = inf
        opp = []
        for dk in (0, -1, 1):   # neighbouring quantised plane (rounding)
            for (fj, sj, rj) in planes.get((key[0], key[1] + dk), ()):
                if sj == -sg and fj != fi: opp.append(rj)
        if not opp: continue
        rem = [rect]
        for h in opp:
            rem = _sub(rem, h)
            if not rem: break
        if not rem: drop.add(fi)
    if not drop: return 0
    nV = []; nI = []; remap = 0
    for fi, (b, nv, i0, il) in enumerate(faces):
        if fi in drop: continue
        base = len(nV)
        nV.extend(m.V[b:b + nv])
        loc = I[i0:i0 + il] - b + base
        nI.extend(int(x) for x in loc)
    n_drop = len(m.V) - len(nV)
    m.V = nV; m.I = nI
    return n_drop
