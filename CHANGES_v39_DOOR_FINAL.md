# v39 (v38 ke upar) - dobara poora audit

## Naya check (asli houses.bin, 1073 darwaze)
- Darwaze ka patta: hinge + patte ki lambai = collision box ka beech (1073 / 1073 sahi), khulne ki disha seedhi aur perpendicular (1073 / 1073).
- Ghar ki mesh me darwaza pehle se chipka hua NAHI hai (0 / 1073), yaani jo patta swing karta hai wahi asli darwaza hai, doorway khali hai.
- Khula patta deewar / furniture me ghusta nahi (0 / 1073 clip).
- HUD button id 103 ki jagah Prefs me save / load hoti hai (map by id, koi limit nahi), sounds apne-aap generate + load hote hain.

## Fix
- Respawn / paani / pause / end screen ke baad purana DOOR tap ya adhura auto-walk baad me nahi chalta (doorReq + doorAssistT reset).

## Animation (v38 se, wahi)
Khulna + band hona dono: 0.62 s ease-in-out swing, alag open / close awaaz, beech me ulta ho sakta hai, double darwaze saath.

## Compile
kotlinc / Android SDK nahi, isliye poora game compile nahi hua. Brackets 6 files me balance. GitHub Actions build chalao, `e:` lines aayein to bhej do.
