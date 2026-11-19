import numpy as np, cv2, sys
from gen_locomotion import WALK, RUN
D = np.pi/180
HIP_Y=1.04; THIGH=0.5; SHIN=0.54; SH_Y=1.67; UA=0.30; FA=0.36
def leg_foot(a,k):
    # a: hip angle (+fwd) rad, k: knee flexion (+) rad -> knee, foot  (y, fwd)
    ky = -THIGH*np.cos(a); kz = THIGH*np.sin(a)
    sa = a-k
    fy = ky - SHIN*np.cos(sa); fz = kz + SHIN*np.sin(sa)
    return (ky,kz),(fy,fz)
def pose(u, T, runW=0.0, lock=1.0, amp=1.0):
    out={}
    for side,uu in (('L',u),('R',(u+0.5)%1.0)):
        out[side]=dict(hip=T['hip'](uu)*D*amp, knee=T['knee'](uu)*D, arm=T['arm'](uu)*D*amp, elbow=T['elbow'](uu)*D)
    return out
def draw(P, drop_factor, lean, W=200,H=330,scale=150):
    img = np.full((H,W,3),255,np.uint8)
    gy = H-20
    x0 = W//2
    # foot lock
    feet=[]
    for s in 'LR':
        (ky,kz),(fy,fz)=leg_foot(P[s]['hip'],P[s]['knee']); feet.append(fy)
    drop = max(0,(HIP_Y+min(feet)))*drop_factor  # lowest foot touches ground
    hipy = HIP_Y-drop
    def pt(y,z): return (int(x0+z*scale), int(gy-(y)*scale))
    # torso lean (forward = +z ), lean rad forward
    sh = (SH_Y-HIP_Y)
    shy = hipy+sh*np.cos(lean); shz = sh*np.sin(lean)
    cv2.line(img, pt(hipy,0), pt(shy,shz), (60,60,60),6)
    cv2.circle(img, pt(shy+0.18*np.cos(lean),shz+0.18*np.sin(lean)), 12,(60,60,60),-1)
    cols={'L':(200,60,0),'R':(0,120,220)}
    for s in 'LR':
        a=P[s]['hip']; k=P[s]['knee']
        (ky,kz),(fy,fz)=leg_foot(a,k)
        cv2.line(img, pt(hipy,0), pt(hipy+ky,kz), cols[s],5)
        cv2.line(img, pt(hipy+ky,kz), pt(hipy+fy,fz), cols[s],5)
        cv2.line(img, pt(hipy+fy,fz), pt(hipy+fy,fz-0.18*0), cols[s],5)
        # arm
        aa=P[s]['arm']; ee=P[s]['elbow']
        ey = shy-UA*np.cos(aa); ez = shz+UA*np.sin(aa)
        fa = aa+ee
        hy = ey-FA*np.cos(fa); hz = ez+FA*np.sin(fa)
        cv2.line(img, pt(shy,shz), pt(ey,ez), cols[s],4)
        cv2.line(img, pt(ey,ez), pt(hy,hz), cols[s],4)
    cv2.line(img,(0,gy),(W,gy),(150,150,150),1)
    return img
if __name__=='__main__':
    which = sys.argv[1] if len(sys.argv)>1 else 'walk'
    T = WALK
    n=13
    # video strip
    vs=[]
    f0=int(sys.argv[2]) if len(sys.argv)>2 else 45
    for i in range(n):
        im=cv2.imread('../frames/all_%03d.png'%(f0+2*i+1))
        c=im[40:690,0:560]; c=cv2.resize(c,(200,232)); c=cv2.copyMakeBorder(c,0,98,0,0,cv2.BORDER_CONSTANT,value=(255,255,255)); vs.append(c)
    ms=[]
    for i in range(n):
        u=i/n
        ms.append(draw(pose(u,T), 1.0, 0.04))
    top=np.hstack(vs[:7]); bot=np.hstack(ms[:7]); top2=np.hstack(vs[6:]) ; bot2=np.hstack(ms[6:])
    cv2.imwrite('cmp_walk_a.png', np.vstack([top,bot]))
    cv2.imwrite('cmp_walk_b.png', np.vstack([top2,bot2]))
