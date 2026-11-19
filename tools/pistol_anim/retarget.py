# Python mirror of the Kotlin retarget core (PistolRig) - used to validate numbers before they go into the game.
import numpy as np, struct, re, math
def qmul(a,b):
    ax,ay,az,aw=a; bx,by,bz,bw=b
    return np.array([aw*bx+ax*bw+ay*bz-az*by, aw*by-ax*bz+ay*bw+az*bx, aw*bz+ax*by-ay*bx+az*bw, aw*bw-ax*bx-ay*by-az*bz])
def qrot(q,v):
    x,y,z,w=q; tx=2*(y*v[2]-z*v[1]); ty=2*(z*v[0]-x*v[2]); tz=2*(x*v[1]-y*v[0])
    return np.array([v[0]+w*tx+(y*tz-z*ty), v[1]+w*ty+(z*tx-x*tz), v[2]+w*tz+(x*ty-y*tx)])
def qarc(a,b):                      # shortest arc, unit vectors
    d=float(np.dot(a,b))
    if d>0.999999: return np.array([0,0,0,1.0])
    if d<-0.999999:
        ax=np.cross(a,[1,0,0]); 
        if np.linalg.norm(ax)<1e-4: ax=np.cross(a,[0,0,1])
        ax/=np.linalg.norm(ax); return np.array([ax[0],ax[1],ax[2],0.0])
    c=np.cross(a,b); q=np.array([c[0],c[1],c[2],1+d]); return q/np.linalg.norm(q)
RU=[np.array([-0.05835416,-0.36471350,0.02188281]),np.array([0.05835416,-0.36471350,0.02188281])]
RF=[np.array([0.007384,-0.369175,-0.12552]),np.array([-0.007384,-0.369175,-0.12552])]
SX=[-0.27,0.27]
LU=float(np.linalg.norm(RU[0])); LF=float(np.linalg.norm(RF[0]))
def load(path):
    b=open(path,'rb').read(); n,K=struct.unpack_from('<ii',b,4); o=12; clips=[]
    for i in range(n):
        fps,dur,loop,v,nf,_=struct.unpack_from('<ffifii',b,o); o+=24
        fr=np.frombuffer(b,dtype='<f4',count=nf*K,offset=o).reshape(nf,K).astype(float); o+=nf*K*4
        clips.append(dict(fps=fps,dur=dur,loop=loop,v=v,fr=fr))
    return clips
def parse_arms(src):
    arms={}
    for m in re.finditer(r'"(\w+)" to GunArms\(\s*arrayOf\(floatArrayOf\(([^)]*)\), floatArrayOf\(([^)]*)\)\),\s*arrayOf\(floatArrayOf\(([^)]*)\), floatArrayOf\(([^)]*)\)\),\s*arrayOf\(floatArrayOf\(([^)]*)\), floatArrayOf\(([^)]*)\)\)\)',src):
        g=m.group(1); f=lambda s:[float(x.replace('f','')) for x in s.split(',')]
        arms[g]=dict(uq=[np.array(f(m.group(2))),np.array(f(m.group(3)))],fq=[np.array(f(m.group(4))),np.array(f(m.group(5)))],e1=[np.array(f(m.group(6))),np.array(f(m.group(7)))])
    return arms
def solve_arm(s,f,ga,minG):
    uq0,fq0,e0=ga['uq'],ga['fq'],ga['e1']
    W0=[np.array([SX[i],0,0])+e0[i]+qrot(fq0[i],RF[i]) for i in (0,1)]
    qD=f[24:28]/np.linalg.norm(f[24:28]); d=f[28:31]
    tf=lambda X: qrot(qD,X-W0[1])+W0[1]+d
    wG=min(1.0,max(f[34],minG))
    if s==1: Tf=W0[1]+d; wg=1.0
    else:
        free=np.array([SX[0],0,0])+f[31:34]; Tf=free+(tf(W0[0])-free)*wG; wg=wG
    T=Tf-np.array([SX[s],0,0])
    sg=-1.0 if s==0 else 1.0
    Hf=np.array([sg*0.35,-0.9,0.10]); Hg=qrot(qD,e0[s]); H=Hf+(Hg-Hf)*wg
    dist=float(np.linalg.norm(T)); lo=abs(LU-LF)+1e-3; hi=LU+LF-1e-3
    short=max(0.0,dist-hi); dc=min(hi,max(lo,dist)); u=T/dist if dist>1e-5 else np.array([0,-1.0,0])
    a=(LU*LU-LF*LF+dc*dc)/(2*dc); h=math.sqrt(max(0.0,LU*LU-a*a))
    pd=H-u*float(np.dot(H,u))
    if np.linalg.norm(pd)<1e-5: pd=np.cross(u,[0,1.0,0])
    pd/=np.linalg.norm(pd); E=u*a+pd*h; Wr=u*dc
    u0=e0[s]/np.linalg.norm(e0[s]); u1=E/np.linalg.norm(E)
    uq=qmul(qarc(u0,u1),uq0[s]); uq/=np.linalg.norm(uq)
    f0=qrot(fq0[s],RF[s]); f0/=np.linalg.norm(f0); fv=Wr-E; f1=fv/np.linalg.norm(fv)
    fq=qmul(qarc(f0,f1),fq0[s]); fq/=np.linalg.norm(fq)
    # verify: rebuild elbow + wrist from the output quaternions
    Eb=qrot(uq,RU[s]); Wb=E+qrot(fq,RF[s])
    return dict(T=T,short=short,elbow_err=float(np.linalg.norm(Eb-E)),wrist_err=float(np.linalg.norm(Wb-Wr)),E=E,uq=uq,fq=fq)
def foot_drop(f):
    ys=[]
    for t0,s0 in ((8,12),(16,20)):
        th=f[t0:t0+4]/np.linalg.norm(f[t0:t0+4]); sh=f[s0:s0+4]/np.linalg.norm(f[s0:s0+4])
        v1=qrot(th,[0,-0.5,-0.015]); v2=qrot(th,qrot(sh,[0,-0.54,0])); ys.append(v1[1]+v2[1])
    return min(max(1.04+min(ys),0.0),0.75), ys
def lean_of(q):
    v=qrot(q/np.linalg.norm(q),[0,1,0]); return math.atan2(v[2],v[1])

def smooth01(x):
    x=min(1.0,max(0.0,x)); return x*x*(3-2*x)
REACH=0.985*(LU+LF)
def gun_solve(f,ga,minG):
    """returns qD, d, wG, W0 - the gun displacement after hybrid mapping + reach correction"""
    uq0,fq0,e0=ga['uq'],ga['fq'],ga['e1']
    W0=[np.array([SX[i],0,0])+e0[i]+qrot(fq0[i],RF[i]) for i in (0,1)]
    qD=f[24:28]/np.linalg.norm(f[24:28]); dd=f[28:31]; gabs=f[37:40]
    lam=smooth01(float(np.linalg.norm(dd))/0.30)
    d=dd*(1-lam)+(gabs-W0[1])*lam
    wG=min(1.0,max(f[34],minG))
    free=np.array([SX[0],0,0])+f[31:34]
    for _ in range(8):
        rr=W0[1]+d-np.array([SX[1],0,0]); n=float(np.linalg.norm(rr))
        if n>REACH: d=d-rr*(1-REACH/n)
        tlg=qrot(qD,W0[0]-W0[1])+W0[1]+d; tl=free+(tlg-free)*wG
        rl=tl-np.array([SX[0],0,0]); n=float(np.linalg.norm(rl))
        if n>REACH and wG>0: d=d-rl*(1-REACH/n)*min(1.0,wG)*0.7
    return qD,d,wG,W0
def solve_arm2(s,f,ga,minG):
    qD,d,wG,W0=gun_solve(f,ga,minG)
    uq0,fq0,e0=ga['uq'],ga['fq'],ga['e1']
    free=np.array([SX[0],0,0])+f[31:34]
    if s==1: Tf=W0[1]+d; wg=1.0
    else:
        tlg=qrot(qD,W0[0]-W0[1])+W0[1]+d; Tf=free+(tlg-free)*wG; wg=wG
    T=Tf-np.array([SX[s],0,0]); sg=-1.0 if s==0 else 1.0
    Hf=np.array([sg*0.35,-0.9,0.10]); Hg=qrot(qD,e0[s]); H=Hf+(Hg-Hf)*wg
    dist=float(np.linalg.norm(T)); lo=abs(LU-LF)+1e-3; hi=LU+LF-1e-3
    short=max(0.0,dist-hi); dc=min(hi,max(lo,dist)); u=T/dist if dist>1e-5 else np.array([0,-1.0,0])
    a=(LU*LU-LF*LF+dc*dc)/(2*dc); h=math.sqrt(max(0.0,LU*LU-a*a))
    pd=H-u*float(np.dot(H,u))
    if np.linalg.norm(pd)<1e-5: pd=np.cross(u,[0,1.0,0])
    pd/=np.linalg.norm(pd); E=u*a+pd*h; Wr=u*dc
    return dict(short=short,E=E,Wr=Wr,T=T,d=d)
