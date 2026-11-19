import json,base64,numpy as np,sys,os,io,math
sys.path.insert(0,'/home/claude/fixed/tools/tops_v15')
ROOT='/home/claude/fixed'
SJ=ROOT+'/app/src/main/assets/store/store.json'
K=128
d=json.load(open(SJ));blob=bytearray(base64.b64decode(d['bin']))
def sst(a,b,x):
    t=np.clip((x-a)/(b-a),0,1);return t*t*(3-2*t)
def getP(it):
    return np.frombuffer(bytes(blob[it['fit']['survivor']:it['fit']['survivor']+it['n']*12]),'<f4').reshape(-1,3).astype(float).copy()
def putP(it,P):
    o=it['fit']['survivor'];blob[o:o+it['n']*12]=P.astype('<f4').tobytes()
TS={'t-shirt':73,'sweater_pack':74,'t-shirt___lp':73,'t-shirt_3d_model_free':73,'tee_desert_stripe':115}
ZF,ZB=-0.135,0.066
log=[]
def shell_at(G,rows,y):
    """interpolate shell xz per column at height y; rows sorted by y"""
    ys=G[rows,:,1].mean(1);o=np.argsort(ys);ys=ys[o];R=G[rows][o]
    out=np.zeros((K,3))
    for k in range(K):
        for c in (0,2): out[k,c]=np.interp(y,ys,R[:,k,c])
    return out
def clamp_ring(G,h,rows,margin=0.004):
    y=G[h,:,1].mean();O=shell_at(G,rows,y)
    cs=O[:,[0,2]].mean(0)
    L=G[h,:,[0,2]].T if False else G[h][:,[0,2]]
    rl=np.linalg.norm(L-cs,axis=1);ro=np.linalg.norm(O[:,[0,2]]-cs,axis=1)
    f=np.minimum(1,np.maximum(ro-margin,0.02)/np.maximum(rl,1e-9))
    G[h][:,[0,2]]=cs+(L-cs)*f[:,None]
    return int((f<0.9999).sum())
for it in d['items']:
    if it['id'] not in TS: continue
    id_=it['id'];T=TS[id_];P=getP(it);G=P[:T*K].reshape(T,K,3)
    g=T-6  # last grid row
    ys=G[:,:,1].mean(1)
    assert abs(ys[T-5]-(ys[g]+0.002))<0.003,(id_,ys[T-5],ys[g])
    # ---- 1) collar / neck: shrink front-back depth of the neck rows (same map for roll + lining rings)
    y0=1.70 if id_=='tee_desert_stripe' else 1.745
    def mapring(r,t):
        zs=G[r,:,2];zf,zb=zs.min(),zs.max()
        zf2=zf+t*(max(ZF,zf)-zf)
        zb2=zb+t*(min(ZB,zb)-zb)
        return zf,zb,zf2,zb2
    for r in range(5,g+1):
        t=float(sst(y0,1.79,ys[r]))
        if t<=0: continue
        zf,zb,zf2,zb2=mapring(r,t)
        G[r,:,2]=zf2+(G[r,:,2]-zf)/(zb-zf)*(zb2-zf2)
    # affine of the last grid row (already applied) -> recompute from original data
    P0=getP(it);G0=P0[:T*K].reshape(T,K,3)
    zf,zb=G0[g,:,2].min(),G0[g,:,2].max();zf2,zb2=max(ZF,zf),min(ZB,zb)
    a=(zb2-zf2)/(zb-zf)
    for r in range(T-5,T):
        G[r,:,2]=zf2+(G0[r,:,2]-zf)*a
    # ---- 2) lining clamps (hem rings 0,1 and collar lining rings T-2,T-1)
    shell=list(range(5,g+1))
    n1=sum(clamp_ring(G,h,[r for r in shell if G[r,:,1].mean()>G[h,:,1].mean()+0.001 and G[r,:,1].mean()<1.5]) for h in (0,1))
    low=lambda h:[r for r in shell if G[r,:,1].mean()<G[h,:,1].mean()-0.002]
    n2=sum(clamp_ring(G,h,low(h)) for h in (T-2,T-1))
    P[:T*K]=G.reshape(-1,3);putP(it,P)
    log.append((id_,'hem clamped cols',n1,'collar lining clamped cols',n2))
print(*log,sep='\n')
json.dump  # keep
d['bin']=base64.b64encode(bytes(blob)).decode()
open('/home/claude/r/stage1.json','w').write(json.dumps(d,separators=(',',':')))
open('/home/claude/r/stage1.bin','wb').write(bytes(blob))
