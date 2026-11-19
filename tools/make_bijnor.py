import json, base64, struct, math
import numpy as np
from scipy import ndimage
from PIL import Image, ImageDraw

SRC='bijnor_data.json'
OUT_DIR='app/src/main/assets'
d=json.load(open(SRC))
ring=np.array(d['ring'],dtype=np.float64).reshape(-1,2)

N=801; CELL=6.4; X0=-2560.0
RINGC=16

# ---- 1. land mask: scanline even-odd fill of the ring (same as fillPoly of the HTML)
land=np.zeros((N,N),dtype=np.uint8)
P=ring; Q=np.roll(ring,-1,axis=0)
for k in range(N):
    z=X0+k*CELL
    m=((P[:,1]<=z)&(Q[:,1]>z))|((Q[:,1]<=z)&(P[:,1]>z))
    if not m.any(): continue
    p=P[m]; q=Q[m]
    xs=np.sort(p[:,0]+(z-p[:,1])/(q[:,1]-p[:,1])*(q[:,0]-p[:,0]))
    for j in range(0,len(xs)-1,2):
        i0=max(0,math.ceil((xs[j]-X0)/CELL)); i1=min(N-1,math.floor((xs[j+1]-X0)/CELL))
        if i1>=i0: land[k,i0:i1+1]=1
land[:RINGC,:]=0; land[-RINGC:,:]=0; land[:,:RINGC]=0; land[:,-RINGC:]=0
print('land cells',int(land.sum()),'area km2',land.sum()*CELL*CELL/1e6)

# ---- 2. signed distance to the coast (m), blurred like blur(sd,N,2,2)
dL=ndimage.distance_transform_edt(land)          # land cell -> nearest sea cell
dS=ndimage.distance_transform_edt(1-land)        # sea cell  -> nearest land cell
sd=np.where(land==1,dL-0.5,-(dS-0.5))*CELL
for _ in range(2):
    sd=ndimage.uniform_filter1d(sd,size=5,axis=1,mode='nearest')
    sd=ndimage.uniform_filter1d(sd,size=5,axis=0,mode='nearest')

# ---- 3. height: Bijnor is a flat plain (2.4 m), sea floor slopes away
def sst(a,b,x):
    t=np.clip((x-a)/(b-a),0,1); return t*t*(3-2*t)
gh=np.where(sd<=0, -(0.2*np.minimum(-sd,45)+0.03*np.maximum(0,-sd-45)), 2.4*sst(0,22,sd))
t=gh.copy()
gh[1:-1,1:-1]=t[1:-1,1:-1]*.5+(t[1:-1,:-2]+t[1:-1,2:]+t[:-2,1:-1]+t[2:,1:-1])*.125
gh=np.where((land==1)&(gh<.5),.5,gh)
ghc=np.clip(np.round(gh*100),-32000,32000).astype(np.int16)
print('gh range',gh.min(),gh.max())

# ---- 4. roads
layers=[]
for r in d['roads']:
    v=np.frombuffer(base64.b64decode(r['v']),dtype='<i2'); i=np.frombuffer(base64.b64decode(r['i']),dtype='<u2')
    assert len(v)==r['nv']*2
    layers.append((r['lvl'],r['nv'],v,i))
conn=np.array(d['conn'],dtype=np.float32)
print('layers',[(l[0],l[1],len(l[3])//3) for l in layers],'conn',len(conn))

# ---- 5. road raster (2 m) -> spawn point near the centre, far from coast and roads
RS=2560; PX=5120/RS
img=Image.new('L',(RS,RS),0); dr=ImageDraw.Draw(img)
for lvl,nv,v,i in layers:
    xz=v.reshape(-1,2)/10.0
    for a in range(0,len(i),3):
        pts=[((xz[i[a+b],0]+2560)/PX,(xz[i[a+b],1]+2560)/PX) for b in range(3)]
        dr.polygon(pts,fill=255)
for c in conn:
    dr.line([((c[0]+2560)/PX,(c[1]+2560)/PX),((c[2]+2560)/PX,(c[3]+2560)/PX)],fill=255,width=max(1,int(c[4]/PX)))
road=np.array(img)>0
droad=ndimage.distance_transform_edt(~road)*PX
# coast distance in metres on the same 2 m raster
land2=np.array(Image.fromarray((land*255).astype(np.uint8)).resize((RS,RS),Image.NEAREST))>0
dcoast=ndimage.distance_transform_edt(land2)*PX
ys,xs=np.nonzero((dcoast>150)&(droad>40))
cx=(xs+0.5)*PX-2560; cz=(ys+0.5)*PX-2560
j=np.argmin(cx*cx+cz*cz)
spawn=(float(cx[j]),2.4,float(cz[j]))
print('spawn',spawn)
islandR=float(np.max(np.hypot(ring[:,0],ring[:,1])))
print('island radius',islandR,'bbox',ring[:,0].min(),ring[:,0].max(),ring[:,1].min(),ring[:,1].max())

# ---- 6. write the binary
out=bytearray()
out+=b'BJN1'
out+=struct.pack('<iff',N,CELL,X0)
out+=struct.pack('<ffff',spawn[0],spawn[1],spawn[2],islandR)
out+=struct.pack('<i',len(ring))
out+=ring.astype('<f4').tobytes()
out+=struct.pack('<i',len(layers))
for lvl,nv,v,i in layers:
    out+=struct.pack('<iii',lvl,nv,len(i)); out+=v.astype('<i2').tobytes(); out+=i.astype('<u2').tobytes()
out+=struct.pack('<i',len(conn)); out+=conn.astype('<f4').tobytes()
out+=ghc.astype('<i2').tobytes()
import os
os.makedirs(OUT_DIR+'/maps',exist_ok=True)
open(OUT_DIR+'/maps/bijnor.bin','wb').write(out)
print('bijnor.bin',len(out))

# ---- 7. seamless ground tile (neutral grey, mean 0.5: the shader multiplies it by the vertex colour x 2)
S=256
rng=np.random.default_rng(20261115)
def tile_noise(freq,seed):
    r=np.random.default_rng(seed); a=r.standard_normal((S,S))
    F=np.fft.fft2(a); fy=np.fft.fftfreq(S)[:,None]; fx=np.fft.fftfreq(S)[None,:]
    f=np.sqrt(fx*fx+fy*fy)*S
    filt=np.exp(-(f/freq)**2)
    g=np.real(np.fft.ifft2(F*filt)); g=(g-g.mean())/g.std(); return g
n=0.5+0.045*tile_noise(3,1)+0.04*tile_noise(9,2)+0.035*tile_noise(26,3)+0.03*tile_noise(70,4)
rgb=np.stack([n+0.004*tile_noise(40,5), n+0.0*tile_noise(40,6), n-0.004*tile_noise(40,7)],axis=-1)
os.makedirs(OUT_DIR+'/textures',exist_ok=True)
Image.fromarray(np.clip(rgb*255,0,255).astype(np.uint8)).save(OUT_DIR+'/textures/ground.jpg',quality=92)
print('ground.jpg',os.path.getsize(OUT_DIR+'/textures/ground.jpg'))
import tempfile
json.dump({'spawn':spawn,'islandR':islandR},open(os.path.join(tempfile.gettempdir(),'bijnor_meta.json'),'w'))
print('NEXT: run  python3 tools/fix_bijnor_roads.py  (cuts roads back to the coast, makes them follow the ground)  and then  python3 tools/sync_manifest.py')
