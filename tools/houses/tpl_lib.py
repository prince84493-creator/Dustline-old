"""v43: the template library.  Every enterable building of the map (the 7 houses + shops / dhaba / school / hospital / bank / godown / mandir / masjid /
petrol pump / clock tower / water tower) exists as a few TEMPLATES (hollow shell, stairs floor -> floor -> roof, doors, windows, furniture, loot on every floor).
The map only stores each template mesh once and places it many times (houses.bin 'BJI1'), so memory and file size stay small.
Every template is rebuilt until EVERY loot point and the roof can be reached from outside the door (reach.py), and its hidden faces are removed (cull.py).

    lib = build_library()      -> list of template dicts (plain data):  kind, name, V (nv,11 f4), I, boxes, surf, loot, dyn, bbox, door, n
"""
import os, random, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bld
from specs import SPECS
from build import build
from reach import unreachable
from cull import cull_model

# (W, D) per floor count that the builder can fit a stair + door + roof stair into (checked by probe: 1 floor needs W>=6, D>=7 ; 2+ floors need W>=8, D>=8)
SHOP_SIZES = {1: [(6, 7), (7, 7), (8, 8), (9, 8), (10, 9), (12, 9)],
              2: [(8, 8), (9, 8), (10, 9), (12, 9), (14, 10)],
              3: [(8, 8), (10, 9), (12, 9), (14, 10)],
              4: [(9, 9), (12, 9), (14, 10)]}
SHOP_VARIANTS = {1: 3, 2: 3, 3: 2, 4: 2}
DHABA_SIZES = [(8, 7)]              # a 6x5 dhaba cannot hold a stair + door + roof stair: it is 8 x 7 now
DHABA_VARIANTS = 4
SPECIAL_VARIANTS = {'school': 2, 'hospital': 2, 'bank': 2, 'godown': 3, 'mandir': 3, 'masjid': 2, 'petrol': 3, 'clock': 2, 'tower': 1}


def _pack(kind, name, m, info, bbox, door, extra=None):
    V = np.array(m.V, np.float32)
    t = dict(kind=kind, name=name, V=V, I=np.array(m.I, np.int32), boxes=[tuple(b) for b in m.boxes], surf=list(m.surf), loot=[tuple(p) for p in m.loot],
             dyn=list(m.dyn), bbox=bbox, door=door, n=info.get('n', 1))
    if extra: t.update(extra)
    return t


def _bbox(m):
    V = np.array(m.V, np.float32)
    return (float(V[:, 0].min()), float(V[:, 0].max()), float(V[:, 2].min()), float(V[:, 2].max()))


def build_library(seed=43, log=print):
    rng = random.Random(seed)
    lib = []
    # ---- the 7 houses (same models / same furniture seeds as v40: identical meshes)
    for i, sp in enumerate(SPECS):
        for attempt in range(60):
            m, info = build(sp, 100 + i + 1000 * attempt)
            bad = unreachable(m, sp, info)
            if not bad: break
        else:
            raise SystemExit('FAILED ' + sp['name'] + ': ' + str(bad[:3]))
        n0 = len(m.V); cull_model(m)
        lib.append(_pack('house', sp['name'], m, info, _bbox(m), (sp.get('door_x', 0.0), sp['D'] / 2 + 0.2), dict(spec_index=i)))
        log('  house %-24s verts %6d -> %6d loot %2d (after %d build(s))' % (sp['name'], n0, len(m.V), len(m.loot), attempt + 1))

    def add(kind, name, **kw):
        r = bld.make_template(kind, rng, **kw)
        m = r['m']; n0 = len(m.V); cull_model(m)
        lib.append(_pack(kind, name, m, r['info'], _bbox(m), r['door'], dict(W=r['sp']['W'], D=r['sp']['D'])))
        log('  %-9s %-14s verts %6d -> %6d loot %2d boxes %3d dyn %2d' % (kind, name, n0, len(m.V), len(m.loot), len(m.boxes), len(m.dyn)))

    for fl, sizes in SHOP_SIZES.items():
        for (W, D) in sizes:
            for v in range(SHOP_VARIANTS[fl]): add('shop', 'shop%dx%dx%d_%d' % (W, D, fl, v), W=W, D=D, floors=fl)
    for (W, D) in DHABA_SIZES:
        for v in range(DHABA_VARIANTS): add('dhaba', 'dhaba%dx%d_%d' % (W, D, v), W=W, D=D)
    for kind, nv in SPECIAL_VARIANTS.items():
        for v in range(nv): add(kind, '%s_%d' % (kind, v))
    return lib


if __name__ == '__main__':
    import time
    t = time.time(); lib = build_library()
    print('templates', len(lib), 'verts', sum(len(x['V']) for x in lib), '%.0fs' % (time.time() - t))
