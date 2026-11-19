import sys, importlib.util
import numpy as np
from tb import *
from lib import *
from build_imp import torso_rings_with_rolls, rings_to_part, mirror_x, KS as _KS
from build2 import widen_shoulders
spec = importlib.util.spec_from_file_location('mc', ROOT + '/tools/make_clothes.py')
mc = importlib.util.module_from_spec(spec); spec.loader.exec_module(mc)
SHOULDER = np.array([.27, 1.67, 0.0]); ARM_D = mc.ELBOW - mc.SHOULDER; ARM_D = ARM_D / np.linalg.norm(ARM_D)
SKIN = np.array([196, 142, 106.])
CFGP = {'tee_desert_stripe': dict(nt=13440, sl_end=16148, R=105, sleeve=0.205, vneck=None),
        'tee_crimson_crosshair': dict(nt=12662, sl_end=15350, R=101, sleeve=0.150, vneck=(1.62, 0.105))}

def build_proc(id_):
    cfg = CFGP[id_]; K = 128
    d, b = load(); o = item(d, b, id_)
    P0, C0, SI0, SW0 = o['pos'], o['col'].astype(float), o['si'].astype(int), o['sw'].astype(float)
    items = {it['id']: it for it in mc.ITEMS}
    m = mc.make_shirt(items[id_]['spec'])
    R = cfg['R']; nt = cfg['nt']
    G = m.pos[:R * K].reshape(R, K, 3)
    ys = G[:, 0, 1]
    Gw = widen_shoulders(G, ys)
    rings = torso_rings_with_rolls(Gw, ys, P0[:nt], C0[:nt], P0[:nt], SI0[:nt], SW0[:nt], Gcol=G)
    torso = rings_to_part(rings)
    skin_patch = None; ribbon = None
    if cfg['vneck']:
        ap, wd = cfg['vneck']; top = 1.80
        X, Y, Z = torso.pos[:, 0], torso.pos[:, 1], torso.pos[:, 2]
        czv = np.empty(len(Z)); 
        # centre z of each ring
        Rn = len(rings); czr = np.array([r[0][:, 2].mean() for r in rings]); czv = np.repeat(czr, K)
        vline = ap + (np.abs(X) / wd) * (top - ap)
        f1 = Y - vline
        f2 = (czv - 0.03 - Z) * 2.0
        f = np.minimum(f1, f2)
        keep, rem = clip_mesh(torso.pos, torso.col, torso.si, torso.sw, torso.idx, f)
        torso = drop_degenerate(keep)
        # trim ridge along the cut edge
        X, Y, Z = torso.pos[:, 0], torso.pos[:, 1], torso.pos[:, 2]
        vl = ap + (np.abs(X) / wd) * (top - ap)
        dist = np.abs(Y - vl) * 0.85
        czv2 = np.interp(Y, [r[0][:, 1].mean() for r in rings][5:-5], czr[5:-5])
        front = Z < czv2 - 0.02
        ridge = 0.003 * sstep(0.010, 0.0, dist) * front
        dd = torso.pos - np.stack([np.zeros_like(Y), Y, czv2], 1); dd[:, 1] = 0
        dd /= np.maximum(np.linalg.norm(dd, axis=1, keepdims=True), 1e-9)
        torso.pos = torso.pos + dd * ridge[:, None]
        # chest skin under the V
        rem = drop_degenerate(rem)
        Xr, Yr, Zr = rem.pos[:, 0], rem.pos[:, 1], rem.pos[:, 2]
        czr2 = np.interp(Yr, [r[0][:, 1].mean() for r in rings][5:-5], czr[5:-5])
        dr = rem.pos - np.stack([np.zeros_like(Yr), Yr, czr2], 1); dr[:, 1] = 0
        dr /= np.maximum(np.linalg.norm(dr, axis=1, keepdims=True), 1e-9)
        rem.pos = rem.pos - dr * 0.007
        # V binding: a real ribbon along the exact V line (colour trim alone is stair-stepped at 1 cm vertex spacing)
        Xt, Yt, Zt = torso.pos[:, 0], torso.pos[:, 1], torso.pos[:, 2]
        czt = np.interp(Yt, [r[0][:, 1].mean() for r in rings][5:-5], czr[5:-5])
        frt = Zt < czt - 0.02
        navy_b = np.median(torso.col[(np.abs(Xt) < 0.05) & (Yt > 1.25) & (Yt < 1.33) & frt], axis=0)
        torso.col[frt & (Yt > 1.60) & (np.abs(Xt) < 0.17)] = navy_b[None, :]
        vlt = ap + (np.abs(Xt) / wd) * (top - ap)
        onl = (np.abs(Yt - vlt) < 6e-4) & frt
        cream = np.array([236., 222., 196.])
        fr_ids = np.where(frt)[0]
        from scipy.spatial import cKDTree as _KD
        kd = _KD(torso.pos[fr_ids][:, [0, 1]])
        sp_pos = []; sp_idx = []
        for sgn in (1, -1):
            ids = np.where(onl & (sgn * Xt > 1e-4))[0]
            ids = ids[np.argsort(Xt[ids] * sgn)]            # from the apex outwards
            ids = np.r_[np.where(onl & (np.abs(Xt) <= 1e-4))[0][:1], ids] if (onl & (np.abs(Xt) <= 1e-4)).any() else ids
            # polyline resampled every 4 mm
            pl = torso.pos[ids]
            keepu = np.r_[True, np.linalg.norm(np.diff(pl, axis=0), axis=1) > 1e-5]
            pl = pl[keepu]
            L = np.r_[0, np.cumsum(np.linalg.norm(np.diff(pl, axis=0), axis=1))]
            n = max(4, int(L[-1] / 0.004)); ss = np.linspace(0, L[-1], n + 1)
            pl = np.stack([np.interp(ss, L, pl[:, c]) for c in range(3)], 1)
            u = np.gradient(pl[:, :2], axis=0); u /= np.linalg.norm(u, axis=1, keepdims=True)
            nn_ = np.stack([-u[:, 1], u[:, 0]], 1)
            nn_ = np.where((nn_[:, 1] > 0)[:, None], -nn_, nn_)    # points below the V line
            wdt = 0.0115
            pin_xy = pl[:, :2] + nn_ * wdt
            dd_, ii_ = kd.query(pin_xy, k=3)
            wgt = 1 / np.maximum(dd_, 1e-6); wgt /= wgt.sum(1, keepdims=True)
            zin = (torso.pos[fr_ids][ii_][:, :, 2] * wgt).sum(1)
            pin = np.stack([pin_xy[:, 0], pin_xy[:, 1], zin], 1)
            cz_ = np.interp(pl[:, 1], [r[0][:, 1].mean() for r in rings][5:-5], czr[5:-5])
            def lift(p, h):
                dv = p - np.stack([np.zeros(len(p)), p[:, 1], cz_ if len(p) == len(cz_) else cz_], 1); dv[:, 1] = 0
                dv /= np.maximum(np.linalg.norm(dv, axis=1, keepdims=True), 1e-9)
                return p + dv * h
            o0 = len(sp_pos) and sum(len(q) for q in sp_pos)
            sp_pos += [lift(pl, 0.0035), lift(pin, 0.0030)]
            m_ = len(pl)
            base = sum(len(q) for q in sp_pos[:-2])
            for k in range(m_ - 1):
                a_, b_, c_, d_ = base + k, base + k + 1, base + m_ + k + 1, base + m_ + k
                sp_idx += [(a_, b_, c_), (a_, c_, d_)]
        rib_pos = np.vstack(sp_pos); rib_idx = np.array(sp_idx)
        rib = Part(rib_pos, np.tile(cream, (len(rib_pos), 1)), np.zeros((len(rib_pos), 4), int), np.zeros((len(rib_pos), 4)), rib_idx)
        rib.sw[:, 0] = 1.0
        # orient like the torso (outward): normal should point away from the body axis
        cc = np.stack([np.zeros(len(rib.pos)), rib.pos[:, 1], np.full(len(rib.pos), czr.mean())], 1)
        nr_ = tri_normals(rib.pos, rib.idx)
        if np.mean(np.einsum('ij,ij->i', nr_[rib.idx[:, 0]], rib.pos[rib.idx[:, 0]] - cc[rib.idx[:, 0]])) < 0: rib.idx = rib.idx[:, ::-1]
        ribbon = rib
        shade = 0.88 + 0.12 * sstep(1.64, 1.80, Yr)               # slightly darker toward the neck (collar-bone shadow)
        rem.col = SKIN[None, :] * (0.92 + 0.10 * sstep(1.62, 1.72, Yr))[:, None] * (1 - 0.18 * sstep(1.77, 1.80, Yr))[:, None]
        rem.si = np.zeros_like(rem.si); rem.sw = np.zeros_like(rem.sw); rem.sw[:, 0] = 1.0
        skin_patch = rem
    # sleeves from the generator (right sleeve = +x). Use rings, add cuff roll
    S, Ks = 34, 40
    sl_parts = []
    start = R * K
    side_pos = m.pos[start:start + S * Ks].reshape(S, Ks, 3)           # first generated = right (+x)
    pr = side_pos
    cen = pr.mean(1)
    c = cen[-1]; t = (cen[-1] - cen[-2]); t /= np.linalg.norm(t)
    U = pr[-1] - c; r = np.linalg.norm(U, axis=1, keepdims=True); U /= r
    cuff = [(0.0035, 0.002, 1.0), (0.001, 0.0065, 1.08), (-0.0045, 0.0035, 1.0), (-0.0065, -0.012, 0.62), (-0.0065, -0.040, 0.55)]
    rr = [pr[i] for i in range(S)] + [c + U * (r + dr_) + t * dt for dr_, dt, _ in cuff]
    Pm = np.array(rr); Rn = len(Pm)
    pos = Pm.reshape(-1, 3)
    idx = grid_indices(Rn, Ks)
    cen_rep = np.repeat(Pm.mean(1), Ks, axis=0)
    nrm = tri_normals(pos, idx)
    if np.mean(np.einsum('ij,ij->i', nrm[idx[:, 0]], pos[idx[:, 0]] - cen_rep[idx[:, 0]])) < 0: idx = idx[:, ::-1]
    # colours from the stored sleeve of the same side
    side_mask = np.arange(nt, cfg['sl_end'])
    sp = P0[side_mask]; sc_ = C0[side_mask]
    right = sp[:, 0] > 0
    a_new = (pos - SHOULDER) @ ARM_D
    sp_r = sp[right]; sc_r = sc_[right]
    # base fabric colour = median of the stored sleeve vertices away from stripes / cuff
    a_old = (sp_r - SHOULDER) @ ARM_D
    if id_ == 'tee_desert_stripe':
        A = 0.205
        sand = np.median(C0[:nt][(np.abs(P0[:nt, 0]) < 0.05) & (P0[:nt, 1] > 1.2) & (P0[:nt, 1] < 1.35) & (P0[:nt, 2] > 0)], axis=0)
        navy = np.array([28., 43., 73.]); teal = np.array([41., 144., 154.]); sand_d = sand * 0.82
        col = np.tile(sand, (len(pos), 1))
        col = col * (1 - 0.5 * sstep(A - 0.012, A - 0.001, a_new))[:, None] + sand_d * (0.5 * sstep(A - 0.012, A - 0.001, a_new))[:, None]
        def band(c0, hw, colr):
            m_ = sstep(0.0025, 0.0, np.abs(a_new - c0) - hw)
            return m_
        mb = band(A - 0.045, 0.007, navy); mr = band(A - 0.073, 0.0045, teal)
        col = col * (1 - mb[:, None]) + navy * mb[:, None]
        col = col * (1 - mr[:, None]) + teal * mr[:, None]
    else:
        A = 0.150
        navy = np.median(C0[:nt][(np.abs(P0[:nt, 0]) < 0.05) & (P0[:nt, 1] > 1.2) & (P0[:nt, 1] < 1.35) & (P0[:nt, 2] > 0)], axis=0)
        cream = np.array([236., 222., 196.])
        col = np.tile(navy, (len(pos), 1))
        mc_ = sstep(0.0035, 0.0, np.abs(a_new - (A - 0.012)) - 0.005)
        col = col * (1 - mc_[:, None]) + cream * mc_[:, None]
    col = col + np.random.RandomState(3).normal(0, 1.2, col.shape)
    dark = np.ones((len(pos), 1)); dark[(Rn - 5) * Ks:, 0] = np.repeat([1.0, 1.08, 1.0, 0.62, 0.55], Ks)
    col = np.clip(col * dark, 0, 255)
    along = (pos - SHOULDER) @ ARM_D
    wu = sstep(-0.07, 0.06, along) * (pos[:, 0] >= 0.20)
    si = np.zeros((len(pos), 4), int); sw = np.zeros((len(pos), 4))
    si[:, 0] = BODY; si[:, 1] = UP_R
    sw[:, 0] = 1 - wu; sw[:, 1] = wu
    sr = Part(pos, col, si, sw, idx)
    sl = mirror_x(sr, swap_bones=False)
    sl.si = np.where(sl.si == UP_R, UP_L, sl.si)
    parts = [torso, sr, sl] + ([skin_patch] if skin_patch is not None else []) + ([ribbon] if ribbon is not None else [])
    out = merge(parts)
    out.n_main = torso.n() + sr.n() + sl.n()
    return out
