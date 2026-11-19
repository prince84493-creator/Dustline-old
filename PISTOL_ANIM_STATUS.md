# Pistol animation - STATUS (v56)
- Ek-haath wala pistol animation pack (pistol.anim, PistolAnim.K = 40) SIRF pistols par chalta hai: M1911, G18, DEAGLE (Gun.pistol = true, slot 3).
- Baaki saari guns RIFLE mode (PistolState.update(..., pistol = false)) me chalti hain: dono haath gun par.
  - chalna / khade rehna: low ready, gun seene ke paas, muzzle neeche, dono haath gun par
  - bhaagna: gun body ke aar-paar, muzzle neeche-left, dono haath, kadmon ke saath hilti hui
  - fire / aim (hip fire bhi): dono haath se kandhe par, har shot par recoil
  - reload (PUBG / BGMI / Free Fire jaisa): right hand grip par, left hand magazine nikalta hai, hip pouch se nayi leta hai, lagata hai, thapthapata hai, wapas hand-guard par
- Phone par compile + test baaki hai (is build me compiler nahi tha).
