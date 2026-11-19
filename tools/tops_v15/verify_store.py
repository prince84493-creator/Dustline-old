import json, base64, os, sys, numpy as np
ROOT=os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','..')); A=ROOT+'/app/src/main/assets'
new=json.load(open(A+'/store/store.json')); old=json.load(open('REF_STORE_V14.json'))
nb=base64.b64decode(new['bin']); ob=base64.b64decode(old['bin'])
print('blob bytes',len(nb),'items',len(new['items']),'ids same order',[i['id'] for i in new['items']]==[i['id'] for i in old['items']])
bad=0
def arrs(it,b):
    n,ni=it['n'],it['i']
    idx=np.frombuffer(b,'<u2',count=ni,offset=it['idx']).astype(int).reshape(-1,3)
    si=np.frombuffer(b,np.uint8,count=n*4,offset=it['si']).reshape(n,4)
    sw=np.frombuffer(b,np.uint8,count=n*4,offset=it['sw']).reshape(n,4)
    col=np.frombuffer(b,np.uint8,count=n*3,offset=it['col']).reshape(n,3)
    fit={k:np.frombuffer(b,'<f4',count=n*3,offset=o).reshape(n,3) for k,o in it['fit'].items()}
    return idx,si,sw,col,fit
for it,ot in zip(new['items'],old['items']):
    n,ni=it['n'],it['i']
    # bounds
    ends=[it['idx']+ni*2,it['si']+n*4,it['sw']+n*4,it['col']+n*3]+[o+n*12 for o in it['fit'].values()]
    assert max(ends)<=len(nb),(it['id'],'offset out of blob')
    idx,si,sw,col,fit=arrs(it,nb)
    assert ni%3==0 and idx.max()<n and idx.min()>=0
    assert si.max()<=10,('bone id',si.max())            # CharRig has 11 bones (0..10), uBones[12]
    assert (sw.sum(1)>0).all(),'vertex with zero weight sum'
    for k,P in fit.items():
        assert np.isfinite(P).all()
        # Cloth.kt normals(): per-vertex sum of face normals, then normalise
        a,b_,c=P[idx[:,0]],P[idx[:,1]],P[idx[:,2]]
        fn=np.cross(b_-a,c-a); nr=np.zeros((n,3))
        for q in range(3): np.add.at(nr,idx[:,q],fn)
        l=np.linalg.norm(nr,axis=1)
        used=np.zeros(n,bool); used[idx.ravel()]=True
        zero=int(((l<=1e-12)&used).sum()); unused=int((~used).sum())
        area=np.linalg.norm(fn,axis=1); degen=int((area<1e-10).sum())
        if zero or degen: bad+=1
        print(f"{it['id']:24s} fit={k:9s} n={n:6d} tris={ni//3:6d} zeroNormals={zero} degenerateTris={degen} unusedVerts={unused} bboxY={P[:,1].min():.3f}..{P[:,1].max():.3f} bboxX=±{np.abs(P[:,0]).max():.3f}")
    if it.get('cat')=='pants':
        oi=arrs(ot,ob)
        same=all(np.array_equal(x,y) for x,y in zip(oi[:4],arrs(it,nb)[:4])) and all(np.array_equal(oi[4][k],arrs(it,nb)[4][k]) for k in ot['fit'])
        print('   pants byte-identical to v14:',same); assert same
        assert it['thumb']==ot['thumb']
# manifest
mf=open(A+'/manifest.txt').read().splitlines(); mm=0
for ln in mf:
    if '|' in ln:
        p,s=ln.split('|'); fp=os.path.join(A,p)
        if not os.path.exists(fp) or os.path.getsize(fp)!=int(s.strip()): mm+=1; print('MANIFEST MISMATCH',p)
print('manifest entries',sum('|' in l for l in mf),'mismatches',mm)
print('ALL OK' if bad==0 and mm==0 else 'PROBLEMS')
