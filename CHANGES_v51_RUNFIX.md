# v51 - run/walk me bachi hui problems ki jaanch + fix

## Jaanch kaise ki
Game ke apne hip-drop logic (CharacterModel: sabse neeche wala pair zameen par) ka simulation chalaya aur dekha ki zameen par lagा pair kitni tez peeche jaata hai, jabki character asal me us speed se aage badh raha hai. Agar pair ki speed < character ki speed -> pair zameen par phisalta (skating) dikhta hai.

## Problem 1 (mili aur kam ki): pair phisalna
Pehle (v50): joystick walk 4.5 m/s par pair ~3.2x phisalta tha, sprint run 10 m/s par ~3.7x. (Clips ~2-3.5 m/s ki speed ke hain, game bahut tez chalta hai.)
Fix: Locomotion.kt me `stride()` - speed ke hisaab se hip ka swing badhta hai (sirf pair zameen par lagne / aage badhane wale hisse me; high-knee aur heel-kick waise ke waise video jaise rehte hain). Tables `W_STR` / `R_STR` gen_locomotion.py se generate hote hain.
Naapa hua phisalna (1.0x = bilkul nahi phisalta):
- RUN: 4.5 m/s 1.5x, 6 m/s 1.3x, 8 m/s 1.3x, 10 m/s 1.6x  (pehle 1.8x - 3.7x)
- WALK: 1.7 m/s 1.3x, 3 m/s 1.9x, 4.5 m/s 2.6x  (pehle 1.4x - 3.2x)
- Walk ab bhi 4.5 m/s par thoda phisalta hai: itni tez chaal insaan ke chalne jaisi nahi, sirf animation se poora theek nahi hota. Poora theek karna ho to `GameRenderer.kt` me `WALK_SPEED` kam karo (3.0 par ~1.9x, 2.2 par ~1.3x). Maine speed nahi badli kyunki aim / crouch / pick-up ki speeds usi ke hisaab se hain.

## Dobara jaanch (code)
- Bracket check Locomotion.kt + CharacterModel.kt pass, tables ki lambai sahi (walk 48, run 60 samples).
- Ghutna kabhi ulta nahi mudta (run min 13.6 deg flexion), pair zameen ke neeche nahi jaata (hip-drop clamp), run me haath ka bahar ka swing sign sahi (rotZ: left negative = bahar, right positive = bahar, jump clip jaisa).
- Compile / phone test ab bhi nahi hua (yahan Kotlin compiler / Android SDK nahi hai) -> GitHub Actions build chalakar dekhna.

## Files
BADLI: Locomotion.kt (generated), tools/anim/gen_locomotion.py. NAYI: CHANGES_v51_RUNFIX.md
