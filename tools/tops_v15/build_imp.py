import numpy as np
from tb import *
from lib import *

K_NEW = 128
KS = 36
SHOULDER = np.array([.27, 1.67, 0.0]); ELBOW = np.array([.328, 1.305, .022])
ARM_D = (ELBOW - SHOULDER) / np.linalg.norm(ELBOW - SHOULDER)


def old_parts(d, b, id_, n_torso, K_old, nsl, ks_old):
    m = item(d, b, id_)
    P, C, SI, SW, IDX = m['pos'], m['col'].astype(float), m['si'].astype(int), m['sw'].astype(float), m['idx']
    R = n_torso // K_old
    G = P[:n_torso].reshape(R, K_old, 3)
    sl = P[n_torso:n_torso + nsl]
    return dict(m=m, G=G, R=R, torso_idx=np.arange(n_torso), slv=np.arange(n_torso, n_torso + nsl),
                P=P, C=C, SI=SI, SW=SW)


def build_torso_imported(o, hem_smooth_rows=8, y_step=0.0125, ys_override=None):
    G = o['G']
    ys_old = G[:, 0, 1]
    G128 = resample_periodic(G, K_NEW)
    y0, y1 = ys_old[0], ys_old[-1]
    body = np.arange(y0, 1.76, y_step)
    ys_new = np.unique(np.round(np.concatenate([body, [1.77, 1.78, 1.79, y1]]), 5))
    ys_new = ys_new[ys_new <= y1 + 1e-9]
    Gn = resample_rows(G128, ys_old, ys_new)
    Gn[:, :, 1] = ys_new[:, None]
    # soften the single-row lip at the hem
    Gn = smooth_grid(Gn, passes=8, lam=0.6, rows=slice(1, 9))
    Gn[:, :, 1] = ys_new[:, None]
    return Gn, ys_new


def torso_rings_with_rolls(Gn, ys, col_src_pos, col_src_col, si_src_pos, si_src, sw_src, Gcol=None):
    """returns ring list [(pos(K,3), col(K,3), si, sw)] bottom->top including hem + collar rolls"""
    R, K, _ = Gn.shape
    base_pos = [Gn[i] for i in range(R)]
    allp = Gn.reshape(-1, 3)
    lookup = (Gn if Gcol is None else Gcol).reshape(-1, 3)
    cols = transfer(lookup, col_src_pos, col_src_col, k=4).reshape(R, K, 3)
    nn = nearest(lookup, si_src_pos)
    si = si_src[nn].reshape(R, K, 4); sw = sw_src[nn].reshape(R, K, 4)
    rings = []
    # ---- hem roll (prepended, bottom up): lining (inside, higher), lip, outer bulge, base...
    b0 = Gn[0]; c0 = cols[0]; s0 = si[0]; w0 = sw[0]
    hem = [(-0.0055, 0.040, 0.58), (-0.0055, 0.012, 0.62), (-0.0045, -0.0035, 0.8), (0.0005, -0.0065, 0.9), (0.0035, -0.002, 1.0)]
    for dr, dy, dark in hem:
        rings.append((radial_offset_xz(b0, dr, dy), c0 * dark, s0.copy(), w0.copy()))
    for i in range(R):
        rings.append((Gn[i].copy(), cols[i], si[i], sw[i]))
    bt = Gn[-1]; ct = cols[-1]; st = si[-1]; wt = sw[-1]
    top = [(0.0035, 0.002, 1.0), (0.001, 0.0065, 1.12), (-0.0045, 0.0035, 1.0), (-0.0065, -0.012, 0.62), (-0.0065, -0.040, 0.55)]
    for dr, dy, dark in top:
        rings.append((radial_offset_xz(bt, dr, dy), np.clip(ct * dark, 0, 255), st.copy(), wt.copy()))
    return rings


def rings_to_part(rings):
    R = len(rings); K = rings[0][0].shape[0]
    pos = np.vstack([r[0] for r in rings]); col = np.vstack([r[1] for r in rings])
    si = np.vstack([r[2] for r in rings]); sw = np.vstack([r[3] for r in rings])
    return Part(pos, col, si, sw, grid_indices(R, K))


# ------------------------------------------------------------------------------------------------ sleeves
def left_sleeve_measures(o, n_torso, nr, ks_old):
    P = o['P'][n_torso:n_torso + nr * ks_old].reshape(nr, ks_old, 3)
    cen = P.mean(1)
    rad = np.linalg.norm(P - cen[:, None], axis=2).mean(1)
    # weight toward the forearm per ring (SI/SW of the old ring)
    SI = o['SI'][n_torso:n_torso + nr * ks_old].reshape(nr, ks_old, 4)
    SW = o['SW'][n_torso:n_torso + nr * ks_old].reshape(nr, ks_old, 4)
    wf = np.zeros(nr)
    for i in range(nr):
        w = SW[i, 0]; s = SI[i, 0]
        tot = max(w.sum(), 1.0)
        wf[i] = sum(w[k] for k in range(4) if s[k] == FORE_L) / tot
    return cen, rad, wf


def sleeve_path(cen, rad, p0=np.array([-.13, 1.665, -.004]), start_ring=3, root_r=.060, mid=None, step=.0105):
    """control points: root inside torso -> over the shoulder -> down the old ring centres"""
    ctrl = [p0]
    if mid is not None:
        ctrl.append(mid)
    ctrl += [cen[i] for i in range(1, len(cen))]
    ctrl = np.array(ctrl)
    rctrl = [root_r] + ([None] if mid is not None else []) + [rad[i] for i in range(1, len(cen))]
    # fill None radius by interpolation
    L = np.r_[0, np.cumsum(np.linalg.norm(np.diff(ctrl, axis=0), axis=1))]
    rr = np.array([np.nan if v is None else v for v in rctrl], float)
    ok = ~np.isnan(rr)
    rr = np.interp(L, L[ok], rr[ok])
    cs = CubicSpline(L, ctrl, bc_type='natural')
    cr = CubicSpline(L, rr, bc_type='natural')
    n = max(8, int(round(L[-1] / step)))
    s = np.linspace(0, L[-1], n + 1)
    return cs(s), np.clip(cr(s), 0.045, 0.1), s, L


def build_sleeve(path, rad, left, col_src_pos, col_src_col, rings_wf_fn=None, cuff_dark=0.6, skin_ref=None):
    S = len(path)
    t = np.gradient(path, axis=0); t /= np.linalg.norm(t, axis=1, keepdims=True)
    ref = np.array([0, 0, 1.0])
    n1 = np.cross(t, ref); n1 /= np.linalg.norm(n1, axis=1, keepdims=True); n2 = np.cross(t, n1)
    ang = 2 * np.pi * np.arange(KS) / KS
    U = np.cos(ang)[None, :, None] * n1[:, None, :] + np.sin(ang)[None, :, None] * n2[:, None, :]   # (S,KS,3)
    pts = path[:, None, :] + rad[:, None, None] * U
    rings_pts = [pts[i] for i in range(S)]
    # cuff roll
    c = path[-1]; tt = t[-1]; u = U[-1]; r = rad[-1]
    cuff = [(0.0035, 0.002, 1.0), (0.001, 0.0065, 1.08), (-0.0045, 0.0035, 1.0), (-0.0065, -0.012, 0.62), (-0.0065, -0.040, 0.55)]
    cuff_dark = []
    for dr, dt, dark in cuff:
        rings_pts.append(c + u * (r + dr) + tt * dt); cuff_dark.append(dark)
    P = np.array(rings_pts)                     # (S+5, KS, 3)
    Rn = P.shape[0]
    pos = P.reshape(-1, 3)
    idx = grid_indices(Rn, KS)
    # outward check
    nrm = tri_normals(pos, idx)
    cen = np.repeat(np.vstack([path, np.repeat(path[-1:], 5, axis=0)]), KS, axis=0)
    if np.mean(np.einsum('ij,ij->i', nrm[idx[:, 0]], pos[idx[:, 0]] - cen[idx[:, 0]])) < 0:
        idx = idx[:, ::-1]
    return pos, idx, Rn, t


def mirror_x(part, swap_bones=True):
    p = Part(part.pos * np.array([-1, 1, 1]), part.col.copy(), part.si.copy(), part.sw.copy(), part.idx[:, ::-1].copy())
    if swap_bones:
        p.si = np.where(p.si == UP_L, UP_R, np.where(p.si == FORE_L, FORE_R, p.si))
    return p
