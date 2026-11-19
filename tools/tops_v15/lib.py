import json, base64, struct, numpy as np, os
import os as _os
ROOT=_os.path.normpath(_os.path.join(_os.path.dirname(_os.path.abspath(__file__)),'..','..'))   # project root when this folder is tools/tops_v15
SJ=_os.environ.get('REF_STORE', ROOT+'/app/src/main/assets/store/store.json')
def load(path=SJ):
    d=json.load(open(path)); bin_=bytearray(base64.b64decode(d['bin'])); return d,bin_
def item(d,bin_,id_,fit='survivor'):
    it=[x for x in d['items'] if x['id']==id_][0]
    n,ni=it['n'],it['i']
    idx=np.frombuffer(bytes(bin_[it['idx']:it['idx']+ni*2]),dtype='<u2').astype(np.int64).reshape(-1,3)
    si=np.frombuffer(bytes(bin_[it['si']:it['si']+n*4]),dtype=np.uint8).reshape(n,4).copy()
    sw=np.frombuffer(bytes(bin_[it['sw']:it['sw']+n*4]),dtype=np.uint8).reshape(n,4).copy()
    col=np.frombuffer(bytes(bin_[it['col']:it['col']+n*3]),dtype=np.uint8).reshape(n,3).copy()
    fits={k:np.frombuffer(bytes(bin_[o:o+n*12]),dtype='<f4').reshape(n,3).astype(np.float64) for k,o in it['fit'].items()}
    return dict(it=it,idx=idx,si=si,sw=sw,col=col,fits=fits,pos=fits[fit])
def tri_normals(pos,idx):
    nr=np.zeros_like(pos)
    a,b,c=pos[idx[:,0]],pos[idx[:,1]],pos[idx[:,2]]
    fn=np.cross(b-a,c-a)
    for k in range(3): np.add.at(nr,idx[:,k],fn)
    return nr
def zero_normals(pos,idx,eps=1e-12):
    nr=tri_normals(pos,idx); l=np.linalg.norm(nr,axis=1)
    used=np.zeros(len(pos),bool); used[idx.ravel()]=True
    return np.where((l<=eps)&used)[0]
def components(n,idx):
    p=np.arange(n)
    def f(x):
        while p[x]!=x: p[x]=p[p[x]]; x=p[x]
        return x
    for a,b,c in idx:
        ra,rb,rc=f(a),f(b),f(c); p[rb]=ra; p[f(c)]=f(a)
    return np.array([f(i) for i in range(n)])
def boundary_edges(idx):
    from collections import Counter
    c=Counter()
    for a,b,cc in idx:
        for u,v in ((a,b),(b,cc),(cc,a)): c[(min(u,v),max(u,v))]+=1
    return [e for e,k in c.items() if k==1], [e for e,k in c.items() if k>2]
