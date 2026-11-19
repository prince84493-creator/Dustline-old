import numpy as np
from tb import sstep, tri_normals

def vertex_normals(pos, idx):
    nr = tri_normals(pos, idx)
    l = np.linalg.norm(nr, axis=1, keepdims=True)
    return nr / np.maximum(l, 1e-12)

def apply_folds(part, n_main, hem_y, long_sleeve=False, seed=1, protect_print=True):
    """subtle cloth folds: displacement along the vertex normal (amplitude 1-3.5 mm) + darker creases"""
    rng = np.random.RandomState(seed)
    P = part.pos.copy(); N = vertex_normals(part.pos, part.idx)
    X, Y, Z = P[:n_main, 0], P[:n_main, 1], P[:n_main, 2]
    ph = rng.rand(8) * 6.28
    disp = np.zeros(n_main)
    # 1. torso drape below the chest (vertical folds that fade out upward and at the hem)
    th = np.arctan2(Z + 0.01, X)
    torso_zone = (np.abs(X) < 0.26) & (Y < 1.45)
    drape = (0.0022 * np.sin(th * 7 + ph[0]) + 0.0014 * np.sin(th * 13 + ph[1] + Y * 9)) * sstep(1.45, 1.20, Y) * sstep(hem_y, hem_y + 0.07, Y)
    disp += drape * torso_zone
    # 2. armpit folds radiating from the armpit, both sides
    for sgn in (1, -1):
        A = np.array([sgn * 0.215, 1.56, 0.0])
        d = np.sqrt((X - A[0]) ** 2 + (Y - A[1]) ** 2 + (Z - A[2]) ** 2)
        ang = np.arctan2(Y - A[1], (Z - A[2]) * 1.0 + 1e-9)
        w = np.exp(-(d / 0.075) ** 2) * (0.0032 * np.sin(ang * 5 + ph[2] + sgn) + 0.0016 * np.sin(ang * 9 + ph[3]))
        disp += w * (np.abs(X) > 0.10)
    # 3. sleeve ruching (rings of gathered cloth near the cuff; elbow bunching on long sleeves)
    on_sleeve = np.abs(X) > 0.215
    cuff_y = Y.copy()
    if long_sleeve:
        rr = 0.0032 * np.sin(Y * 2 * np.pi / 0.048 + ph[4]) * sstep(1.16, 1.02, Y)
        el = 0.0030 * np.sin(Y * 2 * np.pi / 0.052 + ph[5]) * np.exp(-((Y - 1.31) / 0.06) ** 2)
        ang_s = np.arctan2(Z, X + np.sign(X) * 0.0) 
        disp += (rr + el) * on_sleeve * (1 + 0.5 * np.sin(ang_s * 3 + ph[6]))
    else:
        hem_s = 0.0016 * np.sin(np.arctan2(Z, np.abs(X) - 0.3) * 6 + ph[4]) * sstep(1.62, 1.50, Y)
        disp += hem_s * on_sleeve * (Y < 1.58)
    # keep collar / hem rolls and the chest print area clean
    disp *= sstep(1.79, 1.76, Y) * sstep(hem_y - 0.004, hem_y + 0.02, Y)
    if protect_print:
        disp *= 1 - 0.8 * ((np.abs(X) < 0.13) & (Y > 1.33) & (Y < 1.56))
    disp = np.where(disp < 0, disp * 0.5, disp)          # fold valleys go in only half as far (cloth must never sink into the body)
    P[:n_main] += N[:n_main] * disp[:, None]
    col = part.col.copy()
    col[:n_main] *= (1 - np.clip(-disp * 35, 0, 0.18) + np.clip(disp * 25, 0, 0.08))[:, None]
    return type(part)(P, np.clip(col, 0, 255), part.si, part.sw, part.idx)
