# v50 - naya RUN animation (aapke "running animation" video se), purana run jad se hata diya

## Video se kya naapa
- Video: 720x1280, 24 fps, daudta hua character camera ki taraf. Do jagah saaf run cycle hai (frame 1-55 aur 103-144); beech ka kneel/kood wala hissa cycle nahi hai, use nahi liya.
- **Ek poora cycle (bayan + dayan kadam) = 15 frame = 0.625 s = 3.2 kadam/sec** (ghutna ooncha uthne ke peak frame 104, 111-112, 119-120; haath ka pattern bhi har 15 frame me dohrata hai).
- Ek pair ka timeline (15 frame me): zameen par pair lagna -> weight -> push-off -> **heel kick (ghutna ~122 deg)** -> **high knee (jaangh ~72 deg)** -> aage pair badhana. Stance ~37 %, baaki hawa me (flight phase).
- Haath: kohni 78-110 deg mudi hui, ek mutthi thodi ke level tak uthti hai (kandha +70 deg), doosra haath peeche (-42 deg) aur **bahar ki taraf khula hua** (video jaisa open hand, 26 deg tak). Aage jhukav ~7 deg, kandhe kamar ke ulte ghoomte hain.

## Kya hataya
- v49 ka apna banaya hua RUN (gen_locomotion.py ke purane RUN keys, Gait ki purani run cadence 2.9-3.7, lean 11.5 deg, armLz/foreLz ke chhote adjustments) poora hata diya. Walk (v49 wala, aapke walk video se) waisa hi hai.

## Kab walk, kab run (pehle jaisa, badla nahi)
- Joystick se chalna = HAMESHA walk clip (speed kuch bhi ho).
- RUN button dabane par hi run clip (auto-run bhi). Button chhodte hi 0.12 s me wapas walk.
- Bots: sirf jaldi me (speed > 4.2 m/s) run.

## Files
- BADLI: app/.../Locomotion.kt (generated; run tables 60 samples/cycle + naya R_ABD), CharacterModel.kt (Gait.cadence run = 3.2 + 0.045*v, cap 3.7; haath bahar swing armLz/armRz = abdL/abdR), tools/anim/gen_locomotion.py (naye run keys, dobara generate kar sakte hain), docs/walk_run_preview.png
- NAYI: CHANGES_v50_RUN.md
- Video game me shamil nahi (sirf timing / angle naapne ke liye).

## Dhyan do
- Android SDK / kotlinc yahan nahi: compile + phone test nahi hua (bracket check + generator check pass). GitHub Actions build chalakar phone par dekhna.
- Pose video me 3D pose-estimation model nahi tha, isliye angle frame dekh kar naape (front view me hip ka aage/peeche thoda andaza hai). Kam/zyada lage to tools/anim/gen_locomotion.py me RUN_HIP_KEYS / RUN_KNEE_KEYS / harm(...) badal kar dobara generate karo.
- Sprint speed 10 m/s hai, video ka run itni tez nahi hota: tez speed par pair thoda phisalta dikh sakta hai. Zyada ho to GameRenderer.kt me sprint speed (10f) kam karna.
