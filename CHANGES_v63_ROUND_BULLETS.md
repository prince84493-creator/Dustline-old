# v63 - round bullets / holes + pistol wall rule (v62 ke upar)

## Problems mili
1. Goli ka nishaan (hole) CUBE tha (square dabba) aur deewar / zameen se hawa me tika hota tha, surface ke saath flat nahi.
2. Goli ki chamakti line (slug / tracer) square beam thi.
3. Deewar / zameen se udne wale chip (debris) square cube the.
4. Pistol (M1911 / G18 / DEAGLE) bhi deewar ke aar-paar jaati thi (SMG wala 0.40 m rule lagta tha).
5. Bade gun (sniper / DMR / M82) ki penetration kam thi.

## Fix
- GameRenderer.kt: Mark ab surface normal yaad rakhta hai; nishaan GOL flat disc banke deewar / zameen par chipakta hai (plaster ring + gol kala chhed, lakdi, mitti alag rang).
- Goli aur tracer ki line gol (cylinder), chip gol (chhoti goli).
- Penetration.kt: pistol = 0 (deewar nahi nikalti). Shotgun = 0. Badi guns ke numbers: DMR 0.75 m, Sniper 0.90 m, M82 1.60 m (5 deewar). SMG / P90 pehle jaise.
- Bots ki goli par bhi same rule (wahi Penetration.kt use hota hai).

## Note
Android SDK yahan nahi, isliye compile nahi kiya. GitHub Actions build chalao; `e:` lines aayein to bhej do.

## Part 2 - round body hit-box
6. Bots / player ka hit-box square dabba tha (0.6 x 0.5 m). Kone khaali hawa the, wahan goli lag jaati thi ya melee lag jaata tha. Ab body gol (elliptic cylinder) hai: `MapData.rayBody`. Ye 6 jagah laga: player ki goli, aim distance, free-camera aim, melee, bots ki goli (player + bot target).

## Part 3
7. Bots ki goli deewar se chipke hue bot ke saamne se 0.6 m aage se shuru hoti thi - patli deewar ke PEECHE se - isliye deewar ko bina chhue player ko lag jaati thi. Ab agar 0.7 m ke andar deewar hai to goli bot se hi shuru hoti hai aur deewar par lagti hai (Bots.kt `muzzleSlug`).
8. Khoon ki boondein, zameen par khoon ka daag, paani ki boondein bhi square thi - ab gol (GameRenderer drawBall / drawFlatDisc).

## Part 4
9. Muzzle flash (gun ki nali ki aag) square box thi - ab gol. Khali khol (casing / shotgun shell) bhi ab gol cylinder hain.

## Part 5 - hath me gun nahi (FISTS) to sit / prone / jump nahi hota tha
10. CharacterModel.kt: baithne (crouch), let-ne (prone) aur jump ka body pose sirf RIFLE / RELAXED mode me lagta tha. Fists (BOX mode) me ye pose lagta hi nahi tha - body neeche dhans jaata tha par taangein seedhi rehti thi, prone me body khadi rehti thi. Ab teeno pose BOX mode me bhi lagte hain.

## Part 6 - baithne / let-ne par bhi poori height par goli lagti thi
11. Bots player ko hamesha khade (1.78 m) hit-box se dekhte aur maarte the - crouch / prone me bhi. Ab hit-box ki unchai crouch / prone ke saath ghatti hai (`BotSystem.playerH`, GameRenderer har frame set karta hai) - bots ka nishana aur line-of-sight bhi neeche ho jaata hai. Matlab sit / prone sach me bachata hai.

## Part 7 - character tree / deewar ke aar-paar nikal jaata tha
12. Trees me collision tha hi nahi (TreeSystem sirf ped lagata aur dikhata tha). Ab har ped ka tana solid hai (gol collider, radius = tane ka radius x ped ka size, kam se kam 0.22 m): `MapData.setCircleColliders / resolve`. Player aur bots dono ped se takrate hain, bots ped ke aas-paas ghoom kar chalte hain.
13. Tez chalne (sprint 10 m/s) par slow frame me ek step 1 m tak ka ho jaata tha - patli deewar (0.25 m) se lamba - isliye body deewar ke doosri taraf kood jaati thi. Ab har frame chhote steps (max 0.2 m) me chalta hai, har step ke baad deewar / ped se bahar dhakela jaata hai.
Note: घास (grass) par chalna asli zindagi me bhi hota hai, isliye usko solid nahi banaya. Map data me patthar / rock ke alag objects nahi hain (MapData.kt me likha hai "no rocks"), isliye patthar ka collision nahi jod paaya.
