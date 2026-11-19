# v32 BALLISTICS (v31 ke upar)

## Kya badla
- Goli ab INSTANT (hitscan) nahi hai. Har goli ek ud-ti hui projectile hai: nali se asli speed se nikalti hai, gravity se neeche girti hai (drop) aur hawa se dheemi hoti hai (drag).
- Door ke target par lead (aage ka nishana) aur hold-over (upar ka nishana) lagana padta hai. Jitna door, utni der aur utna zyada drop.
- Sights "zero" hain: SMG 40 m, shotgun 20 m, VSS 60 m, DMR 100 m, sniper 250 m, M82 350 m. Isse kam door goli thodi unchi, zyada door neeche jaati hai.
- Goli ki speed (m/s): MP5 400, UMP 330, MP40 380, Vector 380, Bizon 360, P90 715, VSS/VSK 290, M590 400, Kar98k 760, M24 800, AWM 900, M82 853.
- Bots ki goli bhi ud-ti hai. Bot ab chalte hue player ke aage nishana lagate hain aur drop ka hisaab rakhte hain, to unse bachne ke liye ab sach mein hilna padta hai.
- Goli kaanch todti hai aur aage nikalti hai (pehle jaisa), deewar / zameen par nishaan banta hai jab goli pahunchti hai.
- Damage falloff ab goli ke asli udaan-fasle (distance flown) se hota hai.
- Tez goli ke peeche chamakti line (tracer) dikhti hai. Apni goli pehle 6 m crosshair par nahi dikhti.

## Files
Ballistics.kt (NAYI: speed / drag / zero numbers), GameRenderer.kt (shoot -> Slug, stepSlugs, drawSlug), Bots.kt (BotSlug, stepSlugs, lead + drop).
Numbers sirf Ballistics.kt mein hain - wahan badal kar feel tune karo.
Kotlin yahan compile NAHI hua (Android SDK nahi): GitHub Actions build chalao, `e:` lines aayein to bhej do.
