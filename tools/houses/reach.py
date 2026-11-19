"""Reachability test in the house's local frame (same rules as MapData.footY / blocked): flood-fill from outside the door, then tell which loot points are unreachable.
Uses a slightly FAT player (r=0.46) and a 0.2 m grid so a pass here means the real player (r=0.42) fits with margin."""
import collections


def unreachable(m, sp, info, r=0.46, st=0.2):
    """Returns the list of unreachable loot points; the string 'ROOF' is appended when the roof (reached by the roof stair) cannot be reached."""
    B = m.boxes
    S = [(s['rect'], s['planes'], s['flag']) for s in m.surf]

    def foot(x, z, y):
        best = 0.0
        for (rc, pl, fl) in S:
            if fl & 1 and rc[0] <= x <= rc[1] and rc[2] <= z <= rc[3]:
                v = min(a * x + b * z + c for (a, b, c) in pl)
                if v <= y + 0.55 and v > best: best = v
        return best

    def blocked(x, z, y):
        for a in B:
            if x > a[0] - r and x < a[1] + r and z > a[4] - r and z < a[5] + r and y < a[3] - 0.3 and y + 1.7 > a[2]: return True
        return False

    W, D = sp['W'], sp['D']
    x0, x1, z0, z1 = info.get('bounds') or (-W / 2 - 4, W / 2 + 4, -D / 2 - 4, D / 2 + 5)
    dx = info['dx']
    start = info.get('start')
    for zz in (D / 2 + 3.0, D / 2 + 2.5, D / 2 + 3.5, D / 2 + 2.0):
        if start: break
        for ox in (0, 0.4, -0.4, 0.8, -0.8):
            if not blocked(dx + ox, zz, 0.03): start = (dx + ox, zz); break
        if start: break
    if start is None: return list(m.loot)
    seen = {}; q = collections.deque()
    k0 = (0, 0, 0.03); seen[(0, 0, 3)] = 0.03; q.append(k0)
    while q:
        i, k, y = q.popleft(); x = start[0] + i * st; z = start[1] + k * st
        for di, dk in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + di * st, z + dk * st
            if not (x0 < nx < x1 and z0 < nz < z1): continue
            ny = foot(nx, nz, y)
            if abs(ny - y) > 0.5 or blocked(nx, nz, ny): continue
            key = (i + di, k + dk, round(ny * 10))
            if key not in seen: seen[key] = ny; q.append((i + di, k + dk, ny))
    info['reached_y'] = sorted({round(v, 1) for v in seen.values()})
    bad = []
    for p in m.loot:
        ok = any(abs(start[0] + i * st - p[0]) < 0.35 and abs(start[1] + k * st - p[2]) < 0.35 and abs(v - p[1]) < 0.3 for (i, k, _), v in seen.items())
        if not ok: bad.append(p)
    rs = info.get('roofstair')
    if rs:
        top = [(i, k, v) for (i, k, _), v in seen.items() if v >= rs['y'] - 0.15 and abs(start[0] + i * st - rs['x']) < 1.2 and abs(start[1] + k * st - rs['z']) < 1.2]
        if not top: bad.append('ROOF')
    return bad
