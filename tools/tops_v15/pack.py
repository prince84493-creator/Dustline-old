"""pack.py - rebuild the 6 store tops and write assets/store/store.json + pictures + manifest sizes.
usage: REF_STORE=<v14 store.json> python3 pack.py [--out <project root>]"""
import sys, os, io, json, base64, math
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import *
from tb import *
from build2 import build_imported
from build_proc import build_proc, CFGP
from folds import apply_folds
import rp

OUT = ROOT
if '--out' in sys.argv: OUT = sys.argv[sys.argv.index('--out') + 1]
ASSETS = os.path.join(OUT, 'app', 'src', 'main', 'assets'); STORE = os.path.join(ASSETS, 'store')

IMP = {'t-shirt': (1600, 64, 181, 9, 20, 1.02, False), 'sweater_pack': (1600, 64, 541, 27, 20, 1.00, True),
       't-shirt___lp': (1600, 64, 261, 13, 20, 1.015, False), 't-shirt_3d_model_free': (1600, 64, 181, 9, 20, 1.02, False)}
PROC_HEM = {'tee_desert_stripe': 0.93, 'tee_crimson_crosshair': 0.97}
SEED = {'t-shirt': 1, 'sweater_pack': 2, 't-shirt___lp': 3, 't-shirt_3d_model_free': 4, 'tee_desert_stripe': 5, 'tee_crimson_crosshair': 6}

def build_top(id_):
    if id_ in IMP:
        cfg = IMP[id_]
        part, _ = build_imported(id_, *cfg[:5])
        part = apply_folds(part, part.n(), cfg[5], long_sleeve=cfg[6], seed=SEED[id_])
    else:
        part = build_proc(id_)
        part = apply_folds(part, part.n_main, PROC_HEM[id_], seed=SEED[id_])
    return part

def render_pic(part, size=264):
    cv = rp.Canvas(size, size, ss=3)
    cam = dict(yaw=math.pi, pitch=0.0, dist=4.0, center=(0, 1.42, 0), scale=2.05 * size * 3 * 0.45)
    cv.draw(part.pos, rp.normals(part.pos, part.idx), part.idx, part.col / 255.0, cam)
    return cv.save(os.path.join(STORE, 'tmp_pic.png'))

def main():
    d, blob = load()
    old_items = d['items']
    rebuilt = {it['id']: build_top(it['id']) for it in old_items if it.get('cat') != 'pants'}
    new_blob = bytearray(); new_items = []
    def align():
        while len(new_blob) % 4: new_blob.append(0)
    for it in old_items:
        id_ = it['id']; nit = dict(it)
        if id_ in rebuilt:
            part = rebuilt[id_]; n = part.n()
            assert n < 65535 and part.idx.max() < n
            align(); idx_off = len(new_blob); new_blob += part.idx.astype('<u2').reshape(-1).tobytes()
            si_off = len(new_blob); new_blob += part.si.astype('u1').reshape(-1).tobytes()
            sw_off = len(new_blob); new_blob += np.clip(np.round(part.sw * 255), 0, 255).astype('u1').reshape(-1).tobytes()
            col_off = len(new_blob); new_blob += np.clip(np.round(part.col), 0, 255).astype('u1').reshape(-1).tobytes()
            align(); pos_off = len(new_blob); new_blob += part.pos.astype('<f4').reshape(-1).tobytes()
            nit.update(n=int(n), i=int(len(part.idx) * 3), idx=idx_off, si=si_off, sw=sw_off, col=col_off, fit={'survivor': pos_off})
            im = render_pic(part)
            jp = os.path.join(STORE, id_ + '.jpg'); im.convert('RGB').save(jp, 'JPEG', quality=90)
            buf = io.BytesIO(); Image.open(jp).convert('RGB').resize((132, 132), Image.LANCZOS).save(buf, 'JPEG', quality=80)
            nit['thumb'] = base64.b64encode(buf.getvalue()).decode()
            print(id_, 'verts', n, 'tris', len(part.idx), flush=True)
        else:
            n, ni = it['n'], it['i']
            align(); idx_off = len(new_blob); new_blob += bytes(blob[it['idx']:it['idx'] + ni * 2])
            si_off = len(new_blob); new_blob += bytes(blob[it['si']:it['si'] + n * 4])
            sw_off = len(new_blob); new_blob += bytes(blob[it['sw']:it['sw'] + n * 4])
            col_off = len(new_blob); new_blob += bytes(blob[it['col']:it['col'] + n * 3])
            fits = {}
            for k, o in it['fit'].items():
                align(); fits[k] = len(new_blob); new_blob += bytes(blob[o:o + n * 12])
            nit.update(idx=idx_off, si=si_off, sw=sw_off, col=col_off, fit=fits)
        new_items.append(nit)
    align()
    out = dict(d); out['items'] = new_items; out['bin'] = base64.b64encode(bytes(new_blob)).decode()
    sj = os.path.join(STORE, 'store.json')
    json.dump(out, open(sj, 'w'), separators=(',', ':'))
    tp = os.path.join(STORE, 'tmp_pic.png')
    if os.path.exists(tp): os.remove(tp)
    mp = os.path.join(ASSETS, 'manifest.txt'); raw = open(mp).read(); res = []
    for ln in raw.splitlines():
        if '|' in ln:
            p = ln.split('|')[0]; fp = os.path.join(ASSETS, p)
            res.append('%s|%d' % (p, os.path.getsize(fp)) if os.path.exists(fp) else ln)
        else: res.append(ln)
    open(mp, 'w').write('\n'.join(res) + ('\n' if raw.endswith('\n') else ''))
    print('store.json', os.path.getsize(sj), 'bytes')

if __name__ == '__main__':
    main()
