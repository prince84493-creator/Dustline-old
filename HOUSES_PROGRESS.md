> NOTE (v53): ye file purani history hai. Abhi game me 64 enterable ghar + ~1123 solid imaarat hain (houses.bin, 445 blocks); upar ke 'ABHI NAHI LAGE' wale note purane hain.

# HOUSES - ab tak ka kaam (latest: v24 roof landing, neeche dekho)

## Sach (pehle yeh padho)
Is zip mein game wahi hai jo aapne diya tha. Abhi koi ghar game mein NAHI laga.
Koi Kotlin file, koi asset, koi map file badli nahi gayi. Sirf project padha gaya aur plan bana.
Kotlin yahan compile nahi ho sakta, isliye GitHub Actions build zaroori hai.

## Jo samajh liya (game mein ghar lagane ki jagah)
- `MapData.kt`
  - `groups`: mesh list. Material `"house"` = vertex colour mesh (STRIDE 11 = pos3 nor3 uv2 col3).
  - `blockers`: collision boxes (abhi khali). Collision grid `buildGrid()` se pehle bharna hai.
  - `footY()` aur `ceilAt()`: abhi khali hooks. Floor, seedhi (ramp) aur chhat ka sir-takrana yahin se chalega.
  - `houseSpots` / `extraHouses`: abhi khali. Inhe bharne se saara open loot sirf gharon mein chala jayega, isliye alag list banani hai.
- `GameRenderer.kt`
  - `map.groups` upload: line ~767. `"house"` draw: uMode 1.
  - Culling band hai aur shader mein do-taraf roshni hai, isliye triangle ki direction ki chinta nahi.
  - Player height: `footY(...)` line ~1187. Loot: `spawnLoot()`, `addRandomLootAt()`, `stockEveryHouse()` line ~1400-1490.
- `TreeSystem.kt`: ped pehle se Bijnor ke 450 m circle ke andar nahi lagte. Phir bhi `spotOk()` mein gharon ke 45 m ka exclusion jodna hai.
- `AssetPreloader.kt`: sirf `manifest.txt` mein likhi files ka size check hota hai. Nayi asset file jodo to manifest mein size likho.

## Jo karna BAAKI hai
1. Generator `tools/make_houses.py`: 7 ghar (`Bijnor_houses_partial_work.zip` ke `rec/*.json` se)
   - ped, zameen, jhadi hatao; har ghar sirf EK baar.
   - solid body box ki jagah khokhla shell (deewar 0.3 m), front par darwaza (+z), har manzil ka floor.
   - seedhi: ek taraf se doosri taraf badalti hui (east / west patti), floor mein seedhi ke upar chhed.
   - andar loot points har floor par.
   - output: `assets/models/houses/houses.bin` + `manifest.txt` mein size.
2. `MapData.kt`: `houses.bin` load karke groups + sirf deewaron ke blockers + `footY` / `ceilAt` + chhat ki surface.
3. Jagah (`HOUSE_SITES.txt`, Bijnor label x -1522, z 78, radius 450 m): road raster se dobara check karna.
   - koi hissa road par nahi, door road ki taraf, ek dusre se 130 m+ door.
4. Loot: har ghar ke andar alag se (open loot waisa hi rahe), upar ki manzil par bhi.
5. `TreeSystem.kt`: gharon ke paas ped exclusion.
6. GitHub Actions se build.

## Saat ghar (har ek ek baar)
building3d (apartment, 2 tower), house_3d (modern 3 floor), house3d (2 floor), house3d (1) cottage,
ghar_3d_model (basement + 2 floor), villa-3d (3 floor), house_3d_ultra_realistic (2 floor + wings).

---
# Update (is session) - ADHURA, game mein ghar ABHI BHI NAHI lage
Kaam beech mein roka gaya. Game code (Kotlin), assets, map - kuch nahi badla.
Naya: `tools/houses_wip/` (Python, abhi chalaya/test NAHI hua, sirf likha gaya):
- `mapio.py`  bijnor.bin padhta hai + road raster banata hai (yeh chala aur theek kaam kiya; 7 sites ka road-map picture bana).
- `kit.py`, `build.py`, `roofs.py`, `interior.py`  khokhla ghar banane ka generator: deewar 0.3 m, darwaza kata hua, har manzil ka floor,
  seedhi (ramp + steps, floor mein chhed), chhat (flat/gable/hip), khidkiyan, furniture, andar loot points.
- Abhi baaki: 7 ghar ke specs, houses.bin likhna, BFS se andar-reachability check, sites ko road ke side dobara chunna,
  Kotlin (MapData footY/ceilAt/blockers, loot, TreeSystem ke 60 m exclusion), manifest sync, GitHub Actions build.
- Design notes: loot ke liye alag `homeSpots` list banani hai (houseSpots khali rakho warna saara loot gharon mein chala jayega).
- 2 ghar (building3d apartment, house_3d_ultra_realistic) ki HTML file nahi mili thi; baaki 5 HTML mili.

---
# Update (session 3) - ADHURA, game mein ghar ABHI BHI NAHI lage
Naya: `tools/houses/` (houses_wip ka sudhra hua copy):
- `specs.py`: 7 gharon ke specs (har HTML se rang / manzil / chhat). Saare 7 build ho jaate hain (test hua, koi error nahi).
- `build.py`: seedhi ki ramp theek, darwaze ki dehleez (threshold) surface, chhat ki ceiling surface jodi.
- `make_houses.py`: Bijnor ke andar road ke side jagah chunta hai (road se 8 m+ door, darwaze ke saamne road, 130 m gap) aur `houses.bin` likhta hai. Likha gaya, ABHI CHALAYA NAHI GAYA.
Baaki: make_houses.py chalana, Kotlin (MapData: houses.bin load, blockers, footY/ceilAt, homeLoot; GameRenderer loot; TreeSystem 60 m exclusion), manifest sync, GitHub build.
Game code / assets abhi bhi waise hi hain jaise v19 mein the.

# Update (session 4) - houses.bin BAN GAYA, Kotlin ABHI JUDA NAHI
`python3 tools/houses/make_houses.py` chala: 7 ghar Bijnor ke andar road ke side chune gaye (sites: tools/houses/sites.txt),
`assets/models/houses/houses.bin` (2.4 MB) bana, manifest sync hua. Format: make_houses.py ke upar likha hai.
Baaki (Kotlin): MapData.kt mein houses.bin load (groups "house", blockers, footY/ceilAt, homeLoot), GameRenderer loot, TreeSystem exclusion.
Game mein ghar tab tak nahi dikhenge jab tak yeh Kotlin kaam na ho.

# Update (session 5) - Kotlin JUDA, ab game mein ghar aane chahiye (compile NAHI hua, GitHub build zaroori)
- MapData.kt: loadHouses() (houses.bin -> groups "house", blockers), footY/ceilAt (floor, seedhi, chhat), houses + homeLoot lists.
- GameRenderer.kt: stockHomes() - har andar ke loot point par ek random item.
- TreeSystem.kt: har ghar ke 60 m ke andar ped nahi.
- tools/houses/check_houses.py: reachability test. 6 ghar: poora loot reachable. building3d_apartment (ghar 5): 35 mein se sirf 7 reachable (upar ki manzilon ki seedhi mein dikkat) - ABHI THEEK NAHI KIYA.

# Update (v21) - apartment fixed for good, audit added
- Cause of "7 of 35": the shipped houses.bin did not match the source and a random sofa closed the 0.6 m gap beside the stair rail at the top of the first stair. Stair rail now ends at the top of the stair (wide exit).
- tools/houses/reach.py: make_houses.py flood-fills every house from outside the door (player r 0.46) and rebuilds the furniture (up to 60 tries) until EVERY loot point can be reached; no unreachable loot can be written to houses.bin any more.
- tools/houses/audit_map.py: checks houses.bin against bijnor.bin (flat ground, road clearance, 120 m spacing, spawn, loot not in walls / floating, indices, NaN). Current result: AUDIT OK. check_houses.py: 0 of 138 unreachable.
- MapData.loadHouses(): reads into temporary lists and only then commits (a damaged file can no longer leave meshes without collision).
- Known, left as is: 15 road vertices at the coast near x -630 / z 1897 lie up to 0.16 m under sea level.
- Kotlin is NOT compiled here: run the GitHub Actions build.

# Update (v23) - roof stairs
- Roofs were already standable (parachute landing on the roof works). New: every house has a ROOF STAIR from the top floor up to the roof, so you can walk from the roof down into the house.
  - Flat roofs (modern house, apartment): hole in the roof slab + stair + rails; the slab is the roof deck.
  - Pitched roofs (5 houses): the stair keeps climbing through the ceiling and through a hatch cut into the roof mesh until it meets the roof surface (roofs.py roof_face(hole=...)).
- reach.py now also fails the build if the roof cannot be reached from the door; check_houses.py prints 'roof reached' per house.

# Update (v24) - ROOF LANDING fixed (player no longer falls through the roof)
- Cause: GameRenderer.stepAirPlayer() only compared the fall with the TERRAIN (map.ground). A parachutist over a house therefore passed straight through the roof,
  touched down on the ground and landPlayer() put him on the ground floor INSIDE the house. (Test: 1492 of 2006 sampled landing spots ended inside the house.)
- Fix (Kotlin only, houses.bin unchanged and identical to what make_houses.py writes):
  - MapData.landSurface(x, z, y0, y1): first standable roof / balcony / porch-roof surface crossed during the fall. MapData.freeLandingSpot(): slides the landing off a parapet / rail / chimney and returns the new height.
  - GameRenderer.stepAirPlayer(): lands ON that surface; the body is also pushed out of walls / parapets in the air (map.resolve), the parachute flare starts above the roof, and a toast says "ROOF LANDING ... TAKE THE ROOF STAIR DOWN INTO THE HOUSE".
  - Bots (stepBotsAir) never touch down on a roof (they cannot use the stair): they land 3 m beside the house (MapData.houseEscape) instead of dropping through it.
- The roof stairs (v23) are unchanged: flat roofs (house 1, apartment) have a stairwell in the roof slab, pitched roofs have a hatch with the stair climbing out of it.
- tools/roof_landing_test: Kotlin test with the real functions + real houses.bin: 2006 roof landings, every one can walk down the roof stair into the house, 0 stuck.
- Kotlin is still NOT compiled with the Android SDK here: run the GitHub Actions build.
