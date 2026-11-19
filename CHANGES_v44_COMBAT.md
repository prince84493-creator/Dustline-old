# v44 COMBAT REALISM (lean, recoil pattern, whiz + suppression, weight / jam / durability)

Naya code `app/src/main/java/com/bijnor/game/Combat.kt` (saare numbers ek jagah) + hooks.

## 13 Lean / peek
- HUD: do naye buttons (id 58 LEAN LEFT, 59 LEAN RIGHT), stick ke dono taraf. Dabaye rakho = jhaank-o, chhodo = seedha. HUD editor me move ho sakte hain.
- Sar 0.42 m side me jaata hai, camera ~11 deg tilt hota hai. Deewar ho to sar uske andar nahi jaata (ray se 0.22 m gap).
- Goli jhuke hue sar se nikalti hai; bots ko bhi tum jhuki hui jagah par dikhte ho (hit-box + nazar).
- Sprint / swim / prone / heal me lean nahi. Lean dabate hi sprint ruk jaata hai. TPP me body bhi side me jhukti hai.

## 14 Recoil pattern
- Random hata diya. Har goli ka number (spray me) fix hai: upar ki taraf kick pehli 10 goliyon me badhta hai, side ka kick har gun ka apna fixed table (RecoilPattern.LAT).
- Pause ke baad (delay*2.5 + 0.3 s) pattern phir shuru. ADS / crouch / prone me kick kam (pehle jaisa).

## 15 Bullet whiz + suppression
- Bot ki goli agar tumhe lage bina 2.4 m ke andar se guzre: "whiz" awaaz (jis taraf se guzri us side se), screen halki hilti hai, kinaare andhere hote hain, aim thoda kharab (spread +60% max). 2 sec me khud theek.
- Subsonic (VSS / VSK) ki goli sirf halki "whoosh" (whizS), kam suppression.
- Bots.kt: onWhiz callback, BotSlug.whizzed. SoundFx: whiz, whizS.

## 16 Weight / jam / durability
- Weight (kg): gun + magazine, reserve ammo, medkit/bandage/mushroom/grenade, scopes, vest, helmet. 14 kg tak kuch nahi; 14 -> 32 kg me speed 36% tak kam, jump bhi kam. Backpack panel me "Weight" line, HUD me ammo ke neeche kg (sirf bhaari hone par).
- Durability: har gun 0-100%. Har goli se ghisti hai (SMG 0.12, sniper 0.55, M82 0.9 per shot). 55% se neeche jam ka chance (0.4% -> ~7%); 35% se neeche accuracy bhi giri.
- Jam hone par goli nahi nikalti, "click" awaaz, RELOAD button "UNJAM" ban jaata hai, 1.4 s me clear (aur +6% durability). Backpack me har gun ke saamne %.
- Zameen par mili gun 60-100% durability ki; replace karke gira di gayi gun apni durability ke saath girti hai (taaza gun lena = repair).

## Test nahi hua (Kotlin yahan Android SDK ke bina compile nahi hota)
Combat.kt + Weapons.kt akele compile ho gaye; baaki files me syntax error nahi (sirf missing-Android-SDK errors). GitHub Actions build + phone par test zaroori.
