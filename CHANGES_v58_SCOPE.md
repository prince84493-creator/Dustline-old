# v58 - naya SCOPE system (purana black-mask scope poori tarah hata diya)

## Pehle kya galat tha
2X / 4X / 8X scope khulne par poori screen par kaala mask aa jaata tha, sirf beech ka gol hissa dikhta tha.
Poori screen zoom bhi ho jaati thi.

## Ab
- Scope sirf screen ke BEECH me ek gol lens me khulta hai. Lens ke bahar ki screen NORMAL rehti hai (zoom nahi, kaali nahi).
- Saare buttons (stick, FIRE, JUMP, AIM, RELOAD, map, chips...) side me waise hi dikhte hain, scope ke upar nahi chhupte.
- Lens ke andar duniya asli scope-zoom me dikhti hai (2X / 4X / 8X sach me utna bada), dushman, ped, ghar sab dikhte hain.
- Scope ka body: kaali ring + orange ring + patli neeli ring, upar orange block, dono side bars.
- Reticle: patli cross lines, beech me moti stadia lines, mil ticks, orange `>>  <<` chevrons, lal dot, aur scope ka naam (2X/4X/8X).
- Jitna bada scope utna zyada screen cover (lens radius = screen height ka):
  - 2X: 0.28  (16:9 screen ka ~14 % area)
  - 4X: 0.37  (~24 %)
  - 8X: 0.46  (~37 %)
  Ring ke saath chaudai: 2X ~37 %, 4X ~49 %, 8X ~60 % (16:9 par). Lamba phone (20:9) par % thoda kam.
  Bade-chhote karne ke liye `scopeBaseFrac()` (GameRenderer.kt).
- Khulne / band hone ka animation: ring chhoti se badi hoti hai aur fade hoti hai.

## Khulna / band hona
- AIM button ek baar dabao = scope khulta hai, dobara dabao = band.
- Gun badalne par (switch / loot se nayi gun) scope apne aap band. Reload, run, crouch, prone, grenade, swim par bhi band (pehle se).

## Code me kya badla
- `GameRenderer.kt`
  - `onDrawFrame`: poori duniya ka draw `scenePass(lens)` me gaya. Pehle normal screen, phir scope khula ho to doosri baar
    sirf lens ke chhote square target (FBO) me zoom FOV se, aur use gol karke screen par chipka diya. FOV ab lens ke size aur
    2X/4X/8X ke hisaab se nikalta hai.
  - Scope khula ho to AO band (depth match nahi karta). Gun scope ke samay chhup jaati hai.
  - `scopeLensFrac`, `scopeLensMag`, `ensureLens()` naye.
  - Agar phone lens target nahi bana paaye to puraana full-screen zoom fallback (game crash nahi hota).
- `GameScreen.kt`: `drawScopeMask`, `scopeLensR` aur puraana reticle HATAYE. Naya `drawScopeBody()`.
