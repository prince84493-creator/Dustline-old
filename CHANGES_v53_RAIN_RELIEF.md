# v53 - ghar ke andar barish band + map ab flat nahi

## 1. Barish ghar ke andar nahi girti
Pehle: `RainFx` camera ke aas-paas 44x44 m ke dabbe me boondein girata tha, chhat ka koi check nahi tha. 445 me se 381 gharon me
"ceiling" data hai hi nahi, isliye purana `ceilAt()` yahan kaam nahi aata.

Ab:
- `tools/make_roofs.py` har ghar ke mesh se top-view raster (0.5 m cell) banata hai: us x,z par sabse oonchi surface (chhat / chhajja / terrace).
  Output: `assets/models/houses/roofs.bin` (2.1 MB, houses.bin ke order me).
- `MapData.loadRoofs / rainPrepare / roofOver` - boond agar chhat ke NEECHE hai to nahi dikhti.
- `RainFx.update(..., map)` - chhupi hui boondein draw nahi hoti. Camera ghar ke andar ho to andar barish nahi, bahar dikhti hai (khidki/darwaze se).
- Har frame sirf paas ke gharon me dekha jaata hai (22 m ke andar), isliye bhaari nahi.
- roofs.bin na mile to barish pehle jaisi (game crash nahi hota).

## 2. Map flat tha -> halki pahaadi / dhalaan
Pehle: zameen ke 95 % cells bilkul 2.40 m par the.
Ab (`tools/make_relief.py`, original `tools/orig/bijnor_flat_v52.bin` se): khet me ~380 m ki lambi pahaadi, ~150 m ki lahar, ~60 m ke tile.
- Zameen 1.4 m se ~5.7 m tak; dhalaan zyada se zyada ~4.5 deg, aam taur par 2 deg se kam.
- BILKUL FLAT rakha: har ghar + 8 m, har sadak + 4 m, spawn ke 36 m, aur samundar kinaare 40 m (ghar 2.40 m par baked hain, sadak terrain par bichhi hai).
- Flat zone se relief dheere-dheere uthta hai (0.055 m per m) - sadak / deewar ke kinaare par "kagaar" nahi banta.
- Sirf >= 60 m wali lehrein, taaki 6.4 m height grid aur 12.8 m render mesh me farq na pade.
- Slider: `H_STD` (1.5 m) aur `SLOPE` in make_relief.py. Aur oonchi pahaadi chahiye to H_STD badhao, script dobara chalao, phir `python3 tools/sync_manifest.py`.
- NOTE: `tools/make_bijnor.py` chalane se bijnor.bin dobara flat ho jaayega - uske baad make_relief.py chalao.

## Files
BADLI: MapData.kt, TreeSystem.kt (RainFx), GameRenderer.kt (1 line), assets/maps/bijnor.bin, assets/manifest.txt
NAYI: assets/models/houses/roofs.bin, tools/make_roofs.py, tools/make_relief.py, tools/orig/bijnor_flat_v52.bin, CHANGES_v53_RAIN_RELIEF.md

## Test nahi hua
Yahan Kotlin compiler / Android SDK nahi hai -> GitHub Actions se build karke phone par dekhna:
1. Barish me kisi ghar ke andar jao - andar boond nahi, chhat ke bahar girti dikhe.
2. Khule maidan me chalo - halki chadhai/utraai, camera/character zameen par chipka rahe.
3. Sadak par chalo - sadak flat, kinaare par kinara tootta na dikhe.
4. Samundar kinaare tak jao - beach pehle jaisa.

## Jaanch (audit) ke baad ke fix
- Relief ka ghar-flat margin 8 m -> 14 m: audit_map.py ne house 406 ke paas 7 cm farq pakda tha, ab theek. `tools/check_relief.py` nayi: sadak vs terrain mesh ki simulation (original jaisa hi, koi regression nahi).
- `TreeSystem.spotOk`: `ground < 2.0` -> `< 1.3`, warna neeche ke khet (1.4-2.0 m) me ped nahi lagte.
- `tools/houses/audit_map.py`: BJD1 (darwaze + khidki) block ab padhta hai. Result: AUDIT OK (6002 doors/panes).
- check_houses.py: 0 of 1095 loot unreachable. Static check: brackets, Kotlin lexer, member references, asset paths, GLSL uniforms, duplicate declarations - sab saaf.
- PISTOL_ANIM_STATUS.md theek kiya (pistol animation poori wired hai).

## ABHI BHI BAAKI (nahi kiya)
1. Dukaan / school / hospital / bank ~1123 imaarat abhi solid hain (v41-v43 ka kaam adhura). Iske liye Kotlin me instanced draw likhna padega - bina compile ke risky, isliye nahi chheda.
2. Walk animation 4.5 m/s par thoda phisalta hai (CHANGES_v51_RUNFIX.md me likha hai).
3. (Ho gaya) Terrain ka rang: unchi jagah thodi sookhi/peeli (MapData.buildTerrain).
4. Kotlin compile / phone test nahi hua. GitHub Actions build chalao, error aaye to `e:` wali lines bhejo.
