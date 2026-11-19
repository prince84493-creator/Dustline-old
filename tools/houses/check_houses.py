"""Python copy of MapData.footY / boxBlocked: flood-fills from the door and checks that every loot point is reachable (stairs included)."""
import struct, numpy as np, os, sys, collections
b=open(os.path.join(os.path.dirname(__file__),'../../app/src/main/assets/models/houses/houses.bin'),'rb').read(); o=4
nh,=struct.unpack_from('<i',b,o); o+=4; tot=bad=0
for h in range(nh):
    minX,maxX,minZ,maxZ,y0,cx,cz=struct.unpack_from('<7f',b,o); o+=28
    nv,ni,nb,ns,nl=struct.unpack_from('<5i',b,o); o+=20
    o+=nv*44+ni*4
    B=np.frombuffer(b,'<f4',nb*6,o).reshape(-1,6); o+=nb*24
    S=np.frombuffer(b,'<f4',ns*18,o).reshape(-1,18); o+=ns*72
    L=np.frombuffer(b,'<f4',nl*3,o).reshape(-1,3); o+=nl*12
    if nl==0: continue   # plain town building (no inside)
    def hh(s,x,z): return min(s[4+p*3]*x+s[5+p*3]*z+s[6+p*3] for p in range(4))
    def footY(x,z,y):
        best=y0
        for s in S:
            if int(s[16])&1 and s[0]<=x<=s[1] and s[2]<=z<=s[3]:
                v=hh(s,x,z)
                if v<=y+0.55 and v>best: best=v
        return best
    def blocked(x,z,y,r=0.42):
        for a in B:
            if x>a[0]-r and x<a[1]+r and z>a[4]-r and z<a[5]+r and y<a[3]-0.3 and y+1.7>a[2]: return True
        return False
    # start: outside the door, found as a free cell nearest a ground floor threshold
    st=0.25; seen={}; q=collections.deque()
    thr=[s for s in S if min(s[1]-s[0],s[3]-s[2])<0.5 and max(s[1]-s[0],s[3]-s[2])<1.7 and abs(s[6]-(y0+0.03))<1e-3]
    t=thr[0]; tx,tz=(t[0]+t[1])/2,(t[2]+t[3])/2
    best=None
    for dx in np.arange(-4,4.01,st):
        for dz in np.arange(-4,4.01,st):
            x,z=tx+dx,tz+dz
            if not (minX<x<maxX and minZ<z<maxZ): continue
            if not blocked(x,z,y0+0.03) and (best is None or dx*dx+dz*dz<best[0]) and not any(s[0]<=x<=s[1] and s[2]<=z<=s[3] and int(s[16])&1 for s in S[:0]):
                # outside = not above any floor slab of the house body; accept the nearest free cell that is 2.5+ m from the threshold
                if dx*dx+dz*dz>6: best=(dx*dx+dz*dz,x,z)
    _,sx,sz=best; start=(round((sx-minX)/st),round((sz-minZ)/st),round(y0*10)); q.append(start); seen[start]=1
    while q:
        i,k,yy=q.popleft(); x=minX+i*st; z=minZ+k*st; y=yy/10
        for di,dk in ((1,0),(-1,0),(0,1),(0,-1)):
            nx,nz=x+di*st,z+dk*st
            if not (minX<nx<maxX and minZ<nz<maxZ): continue
            ny=footY(nx,nz,y)
            if abs(ny-y)>0.5 or blocked(nx,nz,ny): continue
            key=(i+di,k+dk,round(ny*10))
            if key not in seen: seen[key]=1; q.append(key)
    reach=0; unreach=[]
    for p in L:
        ok=any(abs(minX+i*st-p[0])<0.5 and abs(minZ+k*st-p[2])<0.5 and abs(yy/10-p[1])<0.3 for (i,k,yy) in seen)
        reach+=ok
        if not ok: unreach.append(tuple(np.round(p,1)))
    top=float(L[:,1].max()); mx=max(yy/10 for (_,_,yy) in seen)
    roof=mx>=top+2.9
    tot+=nl; bad+=nl-reach+(0 if roof else 1)
    print('   roof reached' if roof else '   ROOF NOT REACHED','(highest y %.1f, top floor %.1f)'%(mx,top))
    print('house',h+1,'loot',nl,'reachable',reach,'floors reached',sorted({round(yy/10,1) for (_,_,yy) in seen})[:8],'| unreachable',unreach[:4])
print('TOTAL unreachable',bad,'of',tot)
