// Bakes every clip of Pistol_Animation_Pack.html (its own FK + IK code, run headless) into assets/models/anim/pistol.anim
// usage: node bake_pistol.js Pistol_Animation_Pack.html out.anim
const fs = require('fs'), vm = require('vm');
const html = fs.readFileSync(process.argv[2], 'utf8');
const src = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));
const stub = () => new Proxy(function () {}, {
  get: (t, k) => (k === Symbol.toPrimitive ? () => 0 : k === 'length' ? 0 : k === 'dataset' ? {} : stub()),
  set: () => true, apply: () => stub(), construct: () => stub()
});
const ctx = { console, Math, Array, Object, JSON, Number, Float32Array, Blob: function () {}, URL: { createObjectURL: () => '' },
  performance: { now: () => 0 }, requestAnimationFrame: () => 0, addEventListener: () => {}, innerWidth: 800, innerHeight: 600,
  devicePixelRatio: 1, document: { getElementById: () => stub(), querySelectorAll: () => [], createElement: () => ({ dataset: {}, classList: { toggle() {}, add() {} }, style: {}, appendChild() {}, set onclick(v) {} }), body: { appendChild() {} } } };
ctx.window = ctx; vm.createContext(ctx); vm.runInContext(src, ctx);
const PA = ctx.PistolAnim;
if (!PA) throw new Error('PistolAnim not exposed');

const S = 1.38;                       // pack metres -> game character units (arm length .76 / .55)
const mm = (a, b) => { const c = new Array(9); for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) c[i*3+j] = a[i*3]*b[j] + a[i*3+1]*b[3+j] + a[i*3+2]*b[6+j]; return c; };
const mv = (m, v) => [m[0]*v[0]+m[1]*v[1]+m[2]*v[2], m[3]*v[0]+m[4]*v[1]+m[5]*v[2], m[6]*v[0]+m[7]*v[1]+m[8]*v[2]];
const mT = m => [m[0],m[3],m[6],m[1],m[4],m[7],m[2],m[5],m[8]];
const sub = (a, b) => [a[0]-b[0], a[1]-b[1], a[2]-b[2]];
const add = (a, b) => [a[0]+b[0], a[1]+b[1], a[2]+b[2]];
const P = [-1,1,-1];                                  // pack (+z forward, +x left) -> game (-z forward, -x left): x,z flip
const pv = v => [-v[0], v[1], -v[2]];
const pm = m => { const r = new Array(9); for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) r[i*3+j] = P[i]*P[j]*m[i*3+j]; return r; };
function m2q(m) {
  const t = m[0]+m[4]+m[8]; let x, y, z, w;
  if (t > 0) { const s = Math.sqrt(t+1)*2; w = s/4; x = (m[7]-m[5])/s; y = (m[2]-m[6])/s; z = (m[3]-m[1])/s; }
  else if (m[0] > m[4] && m[0] > m[8]) { const s = Math.sqrt(1+m[0]-m[4]-m[8])*2; w = (m[7]-m[5])/s; x = s/4; y = (m[1]+m[3])/s; z = (m[2]+m[6])/s; }
  else if (m[4] > m[8]) { const s = Math.sqrt(1+m[4]-m[0]-m[8])*2; w = (m[2]-m[6])/s; x = (m[1]+m[3])/s; y = s/4; z = (m[5]+m[7])/s; }
  else { const s = Math.sqrt(1+m[8]-m[0]-m[4])*2; w = (m[3]-m[1])/s; x = (m[2]+m[6])/s; y = (m[5]+m[7])/s; z = s/4; }
  const n = Math.hypot(x, y, z, w) || 1; return [x/n, y/n, z/n, w/n];
}
const qG = m => m2q(pm(m));          // pack rotation matrix -> game-axes quaternion

function rawFrame(id, t) {
  const pose = PA.sample(id, t); const W = PA.computeSkeleton(pose);
  const Rc = W.chest.rot;
  const c = add(W.chest.pos, mv(Rc, [0, .15, 0]));
  const Rh = W.handR.rot;
  return { pose, W, Rc, c, Rg: mm(mT(Rc), Rh), g: mul3(mv(mT(Rc), sub(W.gun.p, c)), S) };
}
const mul3 = (a, s) => [a[0]*s, a[1]*s, a[2]*s];

// reference: the two-hand aim hold = the pose the game's baked rifle hold stands for
const REF = rawFrame('aim2', 1.05);
const K = 40;
function frame(id, t) {
  const f = rawFrame(id, t), W = f.W, pose = f.pose, o = new Array(K).fill(0);
  const set = (i, a) => a.forEach((v, k) => o[i + k] = v);
  set(0, qG(f.Rc));                                            // chest (torso) world rotation
  set(4, qG(mm(mT(f.Rc), W.head.rot)));                        // head relative to chest
  set(8, qG(W.legL.rot));  set(12, qG(W.shinL.loc));           // left thigh world, left shin local
  set(16, qG(W.legR.rot)); set(20, qG(W.shinR.loc));
  const dR = mm(f.Rg, mT(REF.Rg));                             // gun rotation relative to the reference hold (chest space)
  set(24, qG(dR));
  set(28, pv(sub(f.g, REF.g)));                                // gun origin displacement (chest space, game units)
  const sL = add(W.chest.pos, mv(f.Rc, [.19, .15, 0]));        // left shoulder
  set(31, pv(mul3(mv(mT(f.Rc), sub(W.handL.pos, sL)), S)));    // left wrist, relative to the left shoulder (chest space)
  const lh = pose.lh, tot = lh.gw + lh.cw; o[34] = Math.min(1, lh.gw / Math.max(1, tot));
  o[35] = pose.fx.flash > .5 ? 1 : 0; o[36] = pose.fx.slide; set(37, pv(f.g));   // 37..39 = absolute gun origin (chest space)
  return o;
}
// clip list (order is the contract with PistolAnim.kt): id, name, [t0, t1] (null = whole clip)
const LIST = [['idle'],['walk'],['run'],['turnL'],['turnR'],['ready'],['aim1'],['aim2'],['high'],['lower'],['comms'],['reload'],
  ['cdown'],['cidle'],['cwalk'],['creload'],['stand'],['aim2', 'kick2', 1.30, 1.92], ['aim1', 'kick1', 1.40, 2.06]];
const FPS = 30, chunks = [];
const head = Buffer.alloc(12); head.write('PANM', 0, 'ascii'); head.writeInt32LE(LIST.length, 4); head.writeInt32LE(K, 8); chunks.push(head);
const report = [];
for (const e of LIST) {
  const id = e[0], name = e[1] || id, C = PA.CLIPS[id];
  const t0 = e[2] !== undefined ? e[2] : 0, t1 = e[3] !== undefined ? e[3] : C.dur, dur = t1 - t0;
  const n = Math.max(2, Math.round(dur * FPS) + 1), buf = Buffer.alloc(24 + n * K * 4);
  buf.writeFloatLE(FPS, 0); buf.writeFloatLE(dur, 4); buf.writeInt32LE(C.loop && e[2] === undefined ? 1 : 0, 8);
  buf.writeFloatLE(C.v || 0, 12); buf.writeInt32LE(n, 16); buf.writeInt32LE(0, 20);
  for (let i = 0; i < n; i++) { const fr = frame(id, Math.min(t1, t0 + i / FPS)); for (let k = 0; k < K; k++) buf.writeFloatLE(fr[k], 24 + (i*K + k)*4); }
  chunks.push(buf); report.push(name + ' dur=' + dur.toFixed(2) + ' frames=' + n + ' v=' + (C.v || 0) + ' loop=' + (C.loop && e[2] === undefined ? 1 : 0));
}
fs.writeFileSync(process.argv[3], Buffer.concat(chunks)); console.log(report.join('\n')); console.log('bytes', fs.statSync(process.argv[3]).size);
