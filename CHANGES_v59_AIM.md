# v59 - nishana (crosshair / scope / red dot) aur goli ab ek hi jagah

## Problem
Scope (1X red dot aur 2X/4X/8X) khulne par nishana ek jagah dikhta tha aur goli doosri jagah lagti thi.

## Mili hui wajah (3 bugs) aur fix
1. **HUD ka beech != 3D screen ka beech (notch wale phone).**
   HUD view par notch / safe-area ka left-right margin lagta hai, to HUD chaudai me 3D picture se chhota hai. HUD ke `w/2` par
   crosshair, red dot, scope ring aur hit-marker ban rahe the, jabki goli 3D picture ke beech se jaati hai. Notch jitna, nishana utna khisakta tha.
   Fix: `GameScreen.kt` me `aimCx()` - nishana, scope ring + reticle aur hit-marker ab 3D picture ke asli beech me.
2. **Goli neeche girti / upar jaati thi (gravity + drag, fixed "zero range").**
   Purane code me barrel bas ek fixed doori (SMG 40 m, DMR 100 m, sniper 250 m...) par nishane se milta tha. Us se paas / door goli upar-neeche.
   Fix: `GameRenderer.kt` `aimDistance()` + `aimLift()` - jis cheez par nishana hai (zameen / deewar / bot) uski doori nikalti hai, aur
   bullet ka same gravity + drag simulate karke itna jhukaav deti hai ki goli theek usi point se guzre. Kisi bhi doori par nishana = goli.
   Third-person me bhi (sir se crosshair ke point tak).
3. **Suppression (goli paas se guzarne par) camera kaanpta tha, goli seedhi jaati thi.**
   Camera ka kaanpna sirf dikhta tha, aim nahi badalta tha. Scope me ye kaanpna bahut bada dikhta tha. Fix: goli ko wahi kaanpna milta hai
   jo camera dikha raha hai (`shkX/Y/Z`), isliye nishana aur goli ek hi jagah.

## Extra
- Scope (2X/4X/8X) khula ho to goli ka bikhraav (spread) 65 % tak kam, taaki scope se tight group bane.

## Pehle wala v58 (naya scope ring + gol lens zoom) isme shamil hai - dekho CHANGES_v58_SCOPE.md

# v60 - audit: bachi hui chhoti problems (isi zip me)
- Scope lens ka render-target kabhi texture ke roop me bound nahi chhodta (kuch phones par GL "feedback loop" se kaali / kharab lens aa sakti thi).
- Gun badalte waqt ya item uthate waqt scope band rehta hai aur us dauran khul bhi nahi sakta (pehle khulte hi band hokar jhapakta tha).
- Plane / freefall / parachute me purani scope ring nahi bachti.
