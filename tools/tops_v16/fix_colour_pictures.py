import json,base64,numpy as np,sys,os,io,math
sys.path.insert(0,'/home/claude/fixed/tools/tops_v15')
from PIL import Image
import rp
ROOT='/home/claude/fixed';ST=ROOT+'/app/src/main/assets/store/'
d=json.load(open('stage1.json'));blob=bytearray(base64.b64decode(d['bin']))
it=[x for x in d['items'] if x['id']=='tee_crimson_crosshair'][0];n=it['n']
C=np.frombuffer(bytes(blob[it['col']:it['col']+n*3]),np.uint8).reshape(n,3).astype(float)
navy=np.median(C[(C[:,2]>C[:,0]+40)],axis=0);cream=np.array([238.,225.,198.]);crim=np.array([138.,30.,44.])
print('navy median',navy)
A=np.stack([navy,cream],1)            # 3x2
sol,res,_,_=np.linalg.lstsq(A,C.T,rcond=None)  # 2xn
u,v=sol;fit=(A@sol).T;resid=np.linalg.norm(C-fit,axis=1)
mask=(resid<22)&(u>-0.05)&(v>-0.05)
new=C.copy();new[mask]=np.clip(np.outer(np.clip(u[mask],0,None),crim)+np.outer(np.clip(v[mask],0,None),cream),0,255)
print('recoloured',mask.sum(),'of',n,'left unchanged (skin etc.)',(~mask).sum())
blob[it['col']:it['col']+n*3]=np.round(new).astype(np.uint8).tobytes()
# pictures + thumbs for all 6 tops
def P_of(i):
    return np.frombuffer(bytes(blob[i['fit']['survivor']:i['fit']['survivor']+i['n']*12]),'<f4').reshape(-1,3).astype(float)
for i in d['items']:
    if i.get('cat')=='pants':continue
    nn=i['n'];pos=P_of(i);idx=np.frombuffer(bytes(blob[i['idx']:i['idx']+i['i']*2]),'<u2').astype(int).reshape(-1,3)
    col=np.frombuffer(bytes(blob[i['col']:i['col']+nn*3]),np.uint8).reshape(nn,3).astype(float)
    size=264;cv=rp.Canvas(size,size,ss=3)
    cam=dict(yaw=math.pi,pitch=0.0,dist=4.0,center=(0,1.42,0),scale=2.05*size*3*0.45)
    cv.draw(pos,rp.normals(pos,idx),idx,col/255.0,cam)
    im=cv.save(ST+'tmp_pic.png');jp=ST+i['id']+'.jpg';im.convert('RGB').save(jp,'JPEG',quality=90)
    buf=io.BytesIO();Image.open(jp).convert('RGB').resize((132,132),Image.LANCZOS).save(buf,'JPEG',quality=80)
    i['thumb']=base64.b64encode(buf.getvalue()).decode();print('picture',i['id'])
os.remove(ST+'tmp_pic.png')
d['bin']=base64.b64encode(bytes(blob)).decode()
json.dump(d,open(ST+'store.json','w'),separators=(',',':'))
print('store.json',os.path.getsize(ST+'store.json'))
