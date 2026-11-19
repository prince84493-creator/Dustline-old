# v45 REAL LOADING + plane intro (v44 + combat realism se aage)

## Loading ab asli hai
- Boot bar = asset files 55% + sounds (synth + SoundPool load) 25% + lobby ka 3D character screen par draw hona 20%. 100% tabhi jab teeno sach me ho gaye (30 s se zyada atke to bhi aage badh jaata hai, hang nahi).
- Lobby character loading screen ke neeche pehle se ban jaata hai, isliye lobby / store kaala nahi khulta. LobbyAvatarView ne EGL context preserve kiya (store/vault/match se aane par models dobara load nahi hote).
- START: map parse = 0-35% bar, phir match screen loading screen ke NEECHE banta hai aur GameRenderer.loadProgress (shaders, terrain, trees, guns, plane, parachute, characters, shadow) = 35-100%. Duniya (`hold`) tab tak ruki rehti hai; bar 100% hone par hi loading hat-ti hai aur 30 s warm-up shuru hota hai.
- 30 s warm-up khatam -> ab loading screen aati hai (naya round GL me load hota hai), 100% par hi plane aata hai. Restart par bhi yahi.
- Result screen se lobby: nakli 1.8 s bar ki jagah chhota (0.7 s) bar, avatar ready hone tak.

## Plane animation
- Sirf pehla animation (side camera, plane peeche se aata hai). Dusra (camera plane ke chaaro taraf ghoomta tha, nose -> side -> behind) hata diya; pehle ke baad 0.9 s me seedha normal chase camera me blend.

## Test nahi hua
Android SDK yahan nahi hai, isliye pura build nahi chala. Kotlin syntax check pass; GitHub Actions build + phone test chahiye.

## v45b - har loading screen asli
- START ki loading: map parse 0-30%, match screen banna 30-40% (bar pehle paint hoti hai, phir UI thread busy), GPU/models/guns/plane/characters 40-100% (GameRenderer.loadProgress). Bar bich me 0 par wapas nahi girti.
- Warm-up ke baad / restart ki loading: wahi real GL loading (0 -> 100%).
- Result -> lobby: nakli timer hata diya; bar 100% jab lobby ka character sach me draw ho gaya.
- Ab game me koi timer-wali nakli loading nahi bachi: boot, START, warm-up ke baad, restart, result -> lobby sab real.
