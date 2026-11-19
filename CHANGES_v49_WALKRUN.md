# v49 - naya WALK + alag RUN animation (purana gait hata diya)

## Kya badla
- **Purana chalne/bhaagne ka animation jad se hata diya**: CharacterModel me sin() wala gait (jog/spr blend, Gait.amp) aur pistol-pack ke WALK / RUN clips (PistolState) ab use nahi hote.
- **Naya file `Locomotion.kt` (object Loco)**: ek hi walk cycle + ek alag run cycle.
  - WALK: aapke video "Male Walk Cycle Animation Reference" se match kiya (cycle 1.04 s, 1.9 step/s; heel strike -> mid stance -> toe-off -> swing; hip +28/-21 deg, knee 4 deg se ~50 deg, opposite haath ka khula swing, sar ka up-down bob, pair zameen par).
  - RUN: video me run nahi tha, isliye alag bana: bada hip swing, ghutna ~115 deg tak uthta hai, kohni ~90 deg mudi hui, aage jhukav ~11 deg, thoda flight phase.
- **Kab walk, kab run**:
  - Joystick se chalna = HAMESHA walk clip (speed kuch bhi ho, sirf cadence thoda badhta hai).
  - RUN button dabane par hi run clip (auto-run bhi). Button chhodte hi 0.12 s me wapas walk.
  - Bots: sirf jaldi me (speed > 4.2 m/s: urgent / cover) run, baaki walk.
- Pistol/gun ke saath: pair, kamar ki height aur dhad ka halka hilna naye cycle se; haath aur gun pistol-pack ki hold/aim pose me hi rehte hain (gun haath me hai, haath nahi jhool sakte). Bina gun (fists/relaxed) me haath ka poora swing aata hai.
- Pair zameen par: stance wala pair ab hawa me nahi tairta (hip neeche hota hai, walk me poora, run me 55%).
- Crouch / prone / swim / jump / pickup / skate: pehle jaisa (inhe nahi chheda).

## Files
- NAYI: app/.../Locomotion.kt, tools/anim/gen_locomotion.py (key poses badal kar dobara generate), tools/anim/preview_stick.py, docs/walk_run_preview.png
- BADLI: CharacterModel.kt (Gait, CharPose.runW, skinOld, skinBaked), PistolState.kt (WALK/RUN clip hata), GameRenderer.kt (runBlend), Bots.kt (runW)

## Dhyan do
- Android SDK / kotlinc yahan nahi, isliye compile + phone test nahi hua (bracket check + generator check pass). GitHub Actions build chalakar dekhna.
- Walk speed ab 4.5 m/s (WALK_SPEED), pair thoda phisal sakta hai; kam karne par aur sahi dikhega.
- Reference video sirf naap (timing / angle) ke liye use hua, game me shamil nahi.

## v49 recheck
- Joystick walk speed 6.2 -> 4.5 m/s (GameRenderer.kt, WALK_SPEED) taki walk animation me pair kam phisle. Sprint 10 aur baaki speeds same.
