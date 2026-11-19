# v61 - sirf THIRD PERSON (first person poora hata diya)

## Kya hata
- Settings se "Camera view (on foot) FIRST PERSON / THIRD PERSON" option hata (`SettingsView.kt`), `Prefs.thirdPerson` bhi (`Prefs.kt`, purani saved setting ignore hoti hai).
- HUD ka FPP/TPP view-switch button (id 29) hata (`HudLayout.kt`, `HudIcons.kt`).
- Game me first-person viewmodel (camera ke saamne wale haath + gun + FPP reload/pickup/fists code) `GameRenderer.kt` se poora hata.
  Ab har waqt camera character ke peeche hai aur character ki apni body dikhti hai.

## Aim (ADS / red dot / scope)
- Pehle aim karte hi camera aankhon me ghus kar FPP ban jata tha. Ab nahi: aim par camera paas aur dayein kandhe ke upar aata hai
  (boom 3.1 m -> 1.9 m, side 0.55 m -> 0.72 m). Body, gun, reload sab dikhte rehte hain.
- Goli abhi bhi sir se crosshair wale point tak jaati hai (v59 ka aim-fix same). Scope lens bhi pehle jaisa zoom deta hai.

## Animations (ab sab third-person body par)
Ye sab pehle se body par bane the, ab FPP ke bina yahi dikhte hain:
- reload (rifle: baayein haath se magazine nikalna / lagana, pistol pack), gun badalna (swap), loot uthana (jhukna + haath + backpack),
  punch combo (jab / cross / hook / uppercut), muzzle flash, recoil, jump, lean, crouch, prone, swim.
- Loot uthate waqt item ab body ke asli haath se backpack tak jaata hai (pehle FPP me camera ke haath se jaata tha).
- Goli ki line ab hamesha dikhti hai (pehle FPP me shuru ke 6 m chhupi thi).
