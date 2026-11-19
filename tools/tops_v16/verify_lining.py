import json,base64,numpy as np,sys
K=128
TS={'t-shirt':73,'sweater_pack':74,'t-shirt___lp':73,'t-shirt_3d_model_free':73,'tee_desert_stripe':115}
def run(path):
    d=json.load(open(path));b=base64.b64decode(d['bin']);ok=True
    for it in d['items']:
        if it['id'] not in TS: continue
        T=TS[it['id']];n=it['n']
        P=np.frombuffer(b,'<f4',count=n*3,offset=it['fit']['survivor']).reshape(n,3).astype(float)
        G=P[:T*K].reshape(T,K,3);g=T-6
        cen=G[:,:,[0,2]].mean(1);rad=np.linalg.norm(G[:,:,[0,2]]-cen[:,None],axis=2);ym=G[:,:,1].mean(1)
        def outer(h,rows):
            o=np.argsort(ym[rows]);yy=ym[h]
            return np.array([np.interp(yy,ym[rows][o],rad[rows][o][:,k]) for k in range(K)])
        shell=np.arange(5,g+1)
        hem=[ (rad[h]-outer(h,shell[(ym[shell]>ym[h]+.001)&(ym[shell]<1.5)])).max() for h in (0,1)]
        col=[ (rad[h]-outer(h,shell[ym[shell]<ym[h]-.002])).max() for h in (T-2,T-1)]
        zf,zb=G[g,:,2].min(),G[g,:,2].max()
        topz=G[T-5:T,:,2]
        print('%-22s hem out max(mm) %.1f %.1f | collar lining out max(mm) %.1f %.1f | neck row front %.3f back %.3f | collar rings front %.3f back %.3f'%(it['id'],hem[0]*1e3,hem[1]*1e3,col[0]*1e3,col[1]*1e3,zf,zb,topz.min(),topz.max()))
        if max(hem+col)>0.5e-3: ok=False
    print('ALL LINING INSIDE' if ok else 'STILL OUTSIDE')
run(sys.argv[1])
