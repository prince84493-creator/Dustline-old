# v36 WALL PENETRATION (v35 ke upar)

## Kya badla
- Patli deewar / lakdi ke darwaze / furniture se goli ab NIKAL jaati hai (damage kam, speed kam). Pehle goli pehli deewar par hi mar jaati thi.
- Moti deewar, concrete block, badi building, zameen / pahad se goli ab bhi RUKTI hai.
- Deewar ki motai goli ke raaste ke saath naapi jaati hai: seedha shot patli deewar se nikalta hai, tirchha shot deewar me zyada lamba hota hai (isliye kabhi rukta hai). Deewar ke saath saath chalti goli (grazing) rukti hai.
- Do touch karte box (kone / corner) ek hi deewar gine jaate hain.
- Goli ke dono taraf nishaan (hole) banta hai; deewar ke peeche khada bot ab maara ja sakta hai.
- Bots ki goli par bhi same rule (damage kam hokar lagta hai).

## Numbers (Penetration.kt - sirf yahan badlo)
| Gun | Max deewar (m) | Max deewar ginti | Damage bachta |
|---|---|---|---|
| SMG (MP5, UMP, MP40, Vector, Bizon, VSS) | 0.40 | 2 | ~40 % |
| P90 | 0.48 | 2 | ~45 % |
| VSK-94 (DMR) | 0.62 | 3 | ~60 % |
| Kar98k / M24 / AWM | 0.70 | 3 | ~70 % |
| M82 | 1.30 | 4 | ~80 % |
| M590 shotgun | 0 (nahi nikalti) | - | - |
Houses ki deewarein 0.25 / 0.30 m ki hain (7678 + 2574 boxes), isliye SMG seedha shot me nikal jaati hai.

## Files
Penetration.kt (NAYI), MapData.kt (lastHitTerrain, wallThickness), GameRenderer.kt (Slug + stepSlugs), Bots.kt (BotSlug + stepSlugs).

## Verify kaise kiya
Asli houses.bin (23719 collision boxes) par Penetration.kt ko JVM me compile karke test kiya (stepSlugs wale logic ka port):
- 400 patli deewaron par seedha shot: SMG / DMR / sniper ~99 % nikli, shotgun 0 %.
- Moti blocks (679): SMG / sniper / DMR / shotgun 0 pass (M82 sirf kuch kone se).
- 45 degree par SMG 257/300 nikli, deewar ke saath grazing shot ruki.
Poora game yahan compile NAHI hua (Android SDK nahi); poore project me mere badle hue hisson me koi syntax / reference error nahi aaya. GitHub Actions build chalao, `e:` lines aayein to bhej do.

---
# v36 MATERIAL-WISE IMPACT (isi version me)

## Kya badla
Ab goli kis cheez par lagi, usse mark aur particles alag hote hain (pehle sab jagah ek jaisa kala dabba tha):
| Material | Mark | Particles |
|---|---|---|
| Concrete (deewar, chhat/farsh, sadak) | halka plaster chip + beech me kala chhed | grey dhool ka baadal + patthar ke tukde |
| Lakdi (darwaze, furniture) | gehra bhura chhed | halke bhure chip / splinters + halki bhuri dhool |
| Paani (talaab, nadi, samundar) | koi mark nahi | chheente (boondein upar uchhalti) + safed jhaag |
| Mitti (zameen) | khudi hui mitti ka daag | bhuri dhool ka baadal + dhele |
- Goli deewar ke aar-paar nikle to dono taraf (andar aur bahar) alag effect aata hai; bahar wale taraf chip goli ki disha me udte hain.
- Particles surface ke normal ki taraf udte hain (deewar se bahar, zameen se upar).
- Bots ki goli ke impact bhi dikhte hain (80 m ke andar), taaki gunfire me hi nahi, aas-paas bhi realistic lage.
- Paani ki satah par goli ruk jaati hai (seabed tak nahi jaati) aur splash banta hai.

## Material kaise pata chalta hai
Collision boxes par material tag nahi hai, isliye shape se: darwaza = lakdi; 2 m ya usse unchi cheez (deewar, pillar), farsh/chhat slab, bada block = concrete; baaki (table, bed, almari, railing) = lakdi. Houses.bin me: 14772 concrete, 8947 lakdi. Zameen = mitti, sadak = concrete, paani = splash.

## Files
Impact.kt (NAYI), MapData.kt (lastBox, impactMaterial, impactNormal, waterCross), GameRenderer.kt (Mark material, impactFx / step / draw), Bots.kt (onImpact).

## Verify
Impact.kt JVM par asli houses.bin ke saath chalaya: 23719 boxes classify hue, darwaza = lakdi, aur 20000 random shots me face-normal 20000/20000 sahi (surface ki taraf, goli ke saamne). Poora project compile: mere naye code me sirf Android (GL/Matrix) wale unresolved reference, jo bina SDK ke aate hain; koi aur error nahi. Asli game me dikhna / feel ke liye GitHub Actions build chalao.
Tune karna ho to GameRenderer.kt me `impactFx` ke numbers (kitne chip, kis rang ke, kitni der) badlo.
