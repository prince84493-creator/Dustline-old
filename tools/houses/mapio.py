import struct, numpy as np
def read_bin(path):
    b=open(path,'rb').read(); o=0
    assert b[:4]==b'BJN1'; o=4
    n,=struct.unpack_from('<i',b,o); o+=4
    cell,x0=struct.unpack_from('<ff',b,o); o+=8
    sx,sy,sz,isr=struct.unpack_from('<ffff',b,o); o+=16
    nr,=struct.unpack_from('<i',b,o); o+=4
    ring=np.frombuffer(b,'<f4',nr*2,o); o+=nr*8
    nl,=struct.unpack_from('<i',b,o); o+=4
    layers=[]
    for l in range(nl):
        lvl,nv,ni=struct.unpack_from('<iii',b,o); o+=12
        xz=np.frombuffer(b,'<i2',nv*2,o).astype(np.float32)/10; o+=nv*4
        idx=np.frombuffer(b,'<u2',ni,o).astype(np.int64); o+=ni*2
        layers.append((lvl,xz.reshape(-1,2),idx.reshape(-1,3)))
    nc,=struct.unpack_from('<i',b,o); o+=4
    conn=np.frombuffer(b,'<f4',nc*5,o).reshape(-1,5); o+=nc*20
    gh=np.frombuffer(b,'<i2',n*n,o).astype(np.float32).reshape(n,n)/100
    return dict(n=n,cell=cell,x0=x0,spawn=(sx,sy,sz),ring=ring.reshape(-1,2),layers=layers,conn=conn,gh=gh)
def road_mask(m,RC=2.5):
    n,cell,x0=m['n'],m['cell'],m['x0']
    rn=int(np.ceil(n*cell/RC))+1
    mk=np.zeros((rn,rn),np.uint8)
    for lvl,xz,idx in m['layers']:
        for tri in idx:
            a,b_,c=xz[tri[0]],xz[tri[1]],xz[tri[2]]
            den=(b_[1]-c[1])*(a[0]-c[0])+(c[0]-b_[0])*(a[1]-c[1])
            if abs(den)<1e-9: continue
            i0=max(0,int(np.floor((min(a[0],b_[0],c[0])-x0)/RC))-1); i1=min(rn-1,int(np.ceil((max(a[0],b_[0],c[0])-x0)/RC))+1)
            k0=max(0,int(np.floor((min(a[1],b_[1],c[1])-x0)/RC))-1); k1=min(rn-1,int(np.ceil((max(a[1],b_[1],c[1])-x0)/RC))+1)
            ii=np.arange(i0,i1+1); kk=np.arange(k0,k1+1)
            px=(x0+ii*RC)[None,:]; pz=(x0+kk*RC)[:,None]
            l1=((b_[1]-c[1])*(px-c[0])+(c[0]-b_[0])*(pz-c[1]))/den
            l2=((c[1]-a[1])*(px-c[0])+(a[0]-c[0])*(pz-c[1]))/den
            l3=1-l1-l2
            ok=(l1>=-.25)&(l2>=-.25)&(l3>=-.25)
            sub=mk[k0:k1+1,i0:i1+1]; sub[ok]=1
    for x1,z1,x2,z2,w in m['conn']:
        L=np.hypot(x2-x1,z2-z1); steps=max(1,int(L)); rad=int(w/2/RC)+1
        for s in range(steps+1):
            t=s/steps; x=x1+(x2-x1)*t; z=z1+(z2-z1)*t
            ci=int(round((x-x0)/RC)); ck=int(round((z-x0)/RC))
            mk[max(0,ck-rad):ck+rad+1,max(0,ci-rad):ci+rad+1]=1
    return mk
