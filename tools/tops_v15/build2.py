import numpy as np
from tb import *
from lib import *
from scipy.interpolate import PchipInterpolator
from build_imp import SHOULDER, ARM_D, KS, K_NEW, torso_rings_with_rolls, rings_to_part, mirror_x, old_parts, build_torso_imported

# shoulder profile of the widened trunk (y -> half width); rows below 1.5 are left alone
SH_Y  = np.array([1.50, 1.56, 1.62, 1.66, 1.69, 1.715, 1.735, 1.755, 1.77, 1.785, 1.80])
SH_HW = np.array([0.00, 0.238, 0.268, 0.288, 0.298, 0.300, 0.286, 0.228, 0.165, 0.127, 0.0])   # 0 = "keep old"

def widen_shoulders(Gn, ys, prof_y=SH_Y, prof_hw=SH_HW):
    G = Gn.copy()
    for i, y in enumerate(ys):
        if y < prof_y[1] - 0.04 or y >= prof_y[-1]: continue
        t = np.interp(y, prof_y[1:-1], prof_hw[1:-1])
        cur = np.abs(G[i, :, 0]).max()
        want = max(cur, t) if y < 1.785 else cur
        # blend in below 1.56
        k = sstep(1.50, 1.58, y)
        want = cur + (want - cur) * k
        G[i, :, 0] *= want / cur
    return G

def resample_sleeve(o, n_torso, nr, ks_old, step=0.0105, first=3):
    P = o['P'][n_torso:n_torso + nr * ks_old].reshape(nr, ks_old, 3)[first:]
    nr = nr - first
    cap = o['P'][n_torso + nr * ks_old]
    Pk = resample_periodic(P, KS)
    cen = Pk.mean(1)
    L = np.r_[0, np.cumsum(np.linalg.norm(np.diff(cen, axis=0), axis=1))]
    n = max(6, int(round(L[-1] / step)))
    s = np.linspace(0, L[-1], n + 1)
    cs = PchipInterpolator(L, Pk.reshape(nr, -1), axis=0)
    R = cs(s).reshape(n + 1, KS, 3)
    return R, cap

def sleeve_surface(R, cap=None):
    """dome (4 rings + apex) over ring 0, tube rings, rolled cuff. returns ring array (Rn,KS,3), apex point, n_dome"""
    c0 = R[0].mean(0); c1 = R[3].mean(0)
    ax = (c0 - c1) / np.linalg.norm(c0 - c1)
    r0 = np.linalg.norm(R[0] - c0, axis=1).mean()
    dome = []
    for j in range(4, 0, -1):          # outermost (smallest) first
        ph = (j / 5.0) * (np.pi / 2) * 1.0
        dome.append(c0 + ax * (r0 * 0.62 * np.sin(ph)) + (R[0] - c0) * np.cos(ph))
    apex = c0 + ax * (r0 * 0.62)
    rings = dome + [R[i] for i in range(len(R))]
    c = R[-1].mean(0); cp = R[-2].mean(0)
    t = (c - cp) / np.linalg.norm(c - cp)
    U = R[-1] - c; r = np.linalg.norm(U, axis=1, keepdims=True); U = U / r
    cuff = [(0.0035, 0.002, 1.0), (0.001, 0.0065, 1.08), (-0.0045, 0.0035, 1.0), (-0.0065, -0.012, 0.62), (-0.0065, -0.040, 0.55)]
    for dr, dt, dark in cuff:
        rings.append(c + U * (r + dr) + t * dt)
    return np.array(rings), apex, 4

def build_sleeve2(o, n_torso, nr, ks_old, nsl):
    R, cap = resample_sleeve(o, n_torso, nr, ks_old)
    P, apex, nd = sleeve_surface(R)
    Rn = len(P)
    pos = np.vstack([P.reshape(-1, 3), apex[None]])
    ia = Rn * KS
    idx = grid_indices(Rn, KS)
    cen = np.repeat(P.mean(1), KS, axis=0)
    nrm = tri_normals(pos, idx)
    if np.mean(np.einsum('ij,ij->i', nrm[idx[:, 0]], pos[idx[:, 0]] - cen[idx[:, 0]])) < 0:
        idx = idx[:, ::-1]
    # apex fan, oriented like its neighbours (normal pointing away from the dome centre)
    fan = np.array([[ia, (k + 1) % KS, k] for k in range(KS)])
    fn = np.cross(pos[fan[:, 1]] - pos[fan[:, 0]], pos[fan[:, 2]] - pos[fan[:, 0]])
    if np.mean(np.einsum('ij,j->i', fn, np.array([0, 1.0, 0]))) < 0:
        fan = fan[:, ::-1]
    idx = np.vstack([idx, fan])
    old_pos = o['P'][n_torso:n_torso + nr * ks_old + 1]; old_col = o['C'][n_torso:n_torso + nr * ks_old + 1]
    col = transfer(pos, old_pos, old_col, k=4)
    dark = np.ones((len(pos), 1))
    dark[(Rn - 5) * KS:Rn * KS, 0] = np.repeat([1.0, 1.08, 1.0, 0.62, 0.55], KS)
    col = np.clip(col * dark, 0, 255)
    pm = pos * np.array([-1, 1, 1])
    along = (pm - SHOULDER) @ ARM_D
    wu = sstep(-0.07, 0.06, along) * (pm[:, 0] >= 0.20)
    SI = o['SI'][n_torso:n_torso + nr * ks_old].reshape(nr, ks_old, 4); SW = o['SW'][n_torso:n_torso + nr * ks_old].reshape(nr, ks_old, 4)
    cen_old = o['P'][n_torso:n_torso + nr * ks_old].reshape(nr, ks_old, 3).mean(1)
    wf = np.array([sum(SW[i, 0][k] for k in range(4) if SI[i, 0][k] == FORE_L) / max(SW[i, 0].sum(), 1) for i in range(nr)])
    ys = cen_old[:, 1]; order = np.argsort(ys)
    wfv = np.interp(pos[:, 1], ys[order], wf[order])
    si = np.zeros((len(pos), 4), int); sw = np.zeros((len(pos), 4))
    si[:, 0] = BODY; si[:, 1] = UP_L; si[:, 2] = FORE_L
    sw[:, 0] = 1 - wu; sw[:, 1] = wu * (1 - wfv); sw[:, 2] = wu * wfv
    return Part(pos, col, si, sw, idx)

def build_imported(id_, n_torso, K_old, nsl, nr, ks_old):
    d, b = load()
    o = old_parts(d, b, id_, n_torso, K_old, nsl, ks_old)
    G0, ys = build_torso_imported(o)
    Gn = widen_shoulders(G0, ys)
    rings = torso_rings_with_rolls(Gn, ys, o['P'][:n_torso], o['C'][:n_torso], o['P'][:n_torso], o['SI'][:n_torso], o['SW'][:n_torso], Gcol=G0)
    torso = rings_to_part(rings)
    sl = build_sleeve2(o, n_torso, nr, ks_old, nsl)
    sr = mirror_x(sl)
    return merge([torso, sl, sr]), o
