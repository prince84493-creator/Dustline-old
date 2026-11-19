"""tb.py - clean shell builder for the 6 store tops (torso grid + welded sleeve sweeps + rolled edges)."""
import numpy as np
from scipy.spatial import cKDTree
from scipy.interpolate import CubicSpline

BODY, UP_L, UP_R, FORE_L, FORE_R, THIGH_L, THIGH_R = 0, 5, 6, 7, 8, 1, 2


def sstep(a, b, x):
    t = np.clip((np.asarray(x, dtype=np.float64) - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def grid_indices(R, K):
    """R rings x K points (closed in K); outward normals when rings go up and k runs counter-clockwise seen from above."""
    i = np.arange(R - 1)[:, None]; k = np.arange(K)[None, :]
    a = i * K + k; b = i * K + (k + 1) % K; c = (i + 1) * K + (k + 1) % K; d = (i + 1) * K + k
    t1 = np.stack([a, c, b], -1).reshape(-1, 3); t2 = np.stack([a, d, c], -1).reshape(-1, 3)
    return np.concatenate([t1, t2])


def tri_normals(pos, idx):
    nr = np.zeros_like(pos)
    a, b, c = pos[idx[:, 0]], pos[idx[:, 1]], pos[idx[:, 2]]
    fn = np.cross(b - a, c - a)
    for k in range(3):
        np.add.at(nr, idx[:, k], fn)
    return nr


class Part:
    """vertex arrays of one surface piece"""
    def __init__(self, pos, col=None, si=None, sw=None, idx=None):
        self.pos = np.asarray(pos, float)
        n = len(self.pos)
        self.col = np.zeros((n, 3)) if col is None else np.asarray(col, float)
        self.si = np.zeros((n, 4), int) if si is None else np.asarray(si, int)
        self.sw = np.zeros((n, 4)) if sw is None else np.asarray(sw, float)
        self.idx = np.zeros((0, 3), int) if idx is None else np.asarray(idx, int)

    def n(self):
        return len(self.pos)


def merge(parts):
    out = Part(np.zeros((0, 3)))
    for p in parts:
        o = out.n()
        out.pos = np.vstack([out.pos, p.pos]); out.col = np.vstack([out.col, p.col])
        out.si = np.vstack([out.si, p.si]); out.sw = np.vstack([out.sw, p.sw])
        out.idx = np.vstack([out.idx, p.idx + o])
    return out


# ------------------------------------------------------------------------------------------ colour / weight transfer
def transfer(new_pos, old_pos, old_vals, k=4, power=2.0, floor=1e-4):
    """inverse-distance weighted transfer of per-vertex values (colours) from the old mesh"""
    tree = cKDTree(old_pos)
    d, i = tree.query(new_pos, k=k)
    w = 1.0 / np.maximum(d, floor) ** power
    w /= w.sum(1, keepdims=True)
    return np.einsum('nk,nkc->nc', w, old_vals[i].astype(float))


def nearest(new_pos, old_pos):
    return cKDTree(old_pos).query(new_pos)[1]


# ------------------------------------------------------------------------------------------ smoothing on grids
def smooth_grid(G, passes=1, lam=0.5, closed_k=True, rows=None):
    """Laplacian smoothing of an (R,K,3) grid. rows = slice of rows that may move (ends fixed)."""
    G = G.copy()
    R = G.shape[0]
    lo, hi = (0, R) if rows is None else (rows.start, rows.stop)
    for _ in range(passes):
        H = G.copy()
        up = np.roll(G, -1, axis=1); dn = np.roll(G, 1, axis=1)
        avg = 0.5 * (up + dn)
        # row neighbours
        rr = np.arange(lo, hi)
        r0 = np.clip(rr - 1, 0, R - 1); r1 = np.clip(rr + 1, 0, R - 1)
        avg = 0.5 * avg[rr] + 0.25 * (G[r0] + G[r1])
        H[rr] = G[rr] + lam * (avg - G[rr])
        G = H
    return G


def resample_periodic(G, K_new):
    """(R,K,3) -> (R,K_new,3) with a periodic cubic spline along k"""
    R, K, _ = G.shape
    t = np.arange(K + 1)
    out = np.zeros((R, K_new, 3))
    tn = np.arange(K_new) * K / K_new
    for r in range(R):
        ext = np.vstack([G[r], G[r][:1]])
        cs = CubicSpline(t, ext, bc_type='periodic')
        out[r] = cs(tn)
    return out


def resample_rows(G, ys_old, ys_new):
    R, K, _ = G.shape
    cs = CubicSpline(ys_old, G.reshape(R, -1), bc_type='natural')
    return cs(ys_new).reshape(len(ys_new), K, 3)


# ------------------------------------------------------------------------------------------ rolled edges on a ring list
def ring_centre_xz(ring):
    return np.array([0.0, ring[:, 1].mean(), ring[:, 2].mean()])


def radial_offset_xz(ring, dr, dy, centre=None):
    """move a torso ring radially (in the xz plane) by dr and vertically by dy"""
    c = ring_centre_xz(ring) if centre is None else centre
    d = ring - c; d[:, 1] = 0
    l = np.maximum(np.linalg.norm(d, axis=1, keepdims=True), 1e-9)
    out = ring + d / l * dr
    out[:, 1] += dy
    return out


def clip_mesh(pos, col, si, sw, idx, f):
    """Split a triangle mesh with the scalar field f (per vertex). Returns (kept part f<=0, removed part f>0) as Parts.
    New vertices are created on the edges where f changes sign (shared between neighbouring triangles)."""
    f = np.asarray(f, float)
    edge_cache = {}
    P = [pos]; C = [col]; S = [si]; W = [sw]
    nv = [len(pos)]

    def cut(a, b):
        key = (min(a, b), max(a, b))
        if key in edge_cache:
            return edge_cache[key]
        t = f[a] / (f[a] - f[b])
        p = pos[a] + (pos[b] - pos[a]) * t
        c = col[a] + (col[b] - col[a]) * t
        # skinning: take the vertex that is closer to the cut
        src = a if t < 0.5 else b
        P.append(p[None]); C.append(c[None]); S.append(si[src][None]); W.append(sw[src][None])
        edge_cache[key] = nv[0]; nv[0] += 1
        return edge_cache[key]

    keep_t, rem_t = [], []
    fx = list(f) + [0.0] * 0
    # extend f lazily (new verts lie exactly on f=0)
    for a, b, c in idx:
        s = [f[a] > 0, f[b] > 0, f[c] > 0]
        n_out = sum(s)
        if n_out == 0:
            keep_t.append((a, b, c)); continue
        if n_out == 3:
            rem_t.append((a, b, c)); continue
        v = [a, b, c]
        # rotate so that the lone vertex is first
        if n_out == 1:
            o = [i for i in range(3) if s[i]][0]
            q0, q1, q2 = v[o], v[(o + 1) % 3], v[(o + 2) % 3]   # q0 outside
            m1 = cut(q0, q1); m2 = cut(q2, q0)
            rem_t.append((q0, m1, m2))
            keep_t.append((m1, q1, q2)); keep_t.append((m1, q2, m2))
        else:
            o = [i for i in range(3) if not s[i]][0]
            q0, q1, q2 = v[o], v[(o + 1) % 3], v[(o + 2) % 3]   # q0 inside
            m1 = cut(q0, q1); m2 = cut(q2, q0)
            keep_t.append((q0, m1, m2))
            rem_t.append((m1, q1, q2)); rem_t.append((m1, q2, m2))
    pos2 = np.vstack(P); col2 = np.vstack(C); si2 = np.vstack(S); sw2 = np.vstack(W)

    def compact(tris):
        tris = np.array(tris, int).reshape(-1, 3)
        used = np.unique(tris)
        remap = -np.ones(len(pos2), int); remap[used] = np.arange(len(used))
        return Part(pos2[used], col2[used], si2[used], sw2[used], remap[tris])
    return compact(keep_t), compact(rem_t)


def drop_degenerate(part, eps=1e-10):
    a, b, c = part.pos[part.idx[:, 0]], part.pos[part.idx[:, 1]], part.pos[part.idx[:, 2]]
    ar = np.linalg.norm(np.cross(b - a, c - a), axis=1)
    part.idx = part.idx[ar > eps]
    used = np.unique(part.idx)
    remap = -np.ones(part.n(), int); remap[used] = np.arange(len(used))
    return Part(part.pos[used], part.col[used], part.si[used], part.sw[used], remap[part.idx])
