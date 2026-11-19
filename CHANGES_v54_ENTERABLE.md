# v54 - ab SABHI imaarat enterable (dukaan, school, hospital, bank, ghar...) + walk / zameen fix

## 1. 1307 imaarat andar se khuli (1st floor, 2nd floor, seedhi, rooftop)
- `assets/models/houses/houses.bin` = v43 ka instanced file (BJI1): 78 imaarat-templates ek baar, 1307 jagah rotate karke lagaye (64 ghar jagah wahi + 1243 dukaan / dhaba / school / hospital / bank / godown / mandir / masjid / petrol / clock tower / water tower).
  Har imaarat khokhli hai: darwaze, khidkiyan, har manzil par seedhi chhat tak, har manzil par loot. File 54 MB -> 19.7 MB.
- `MapData.kt`: BJI1 padhta hai (`tpls`, `insts`), khaali (nV = 0) block par mesh group nahi banta. Collision / floor / loot / darwaze pehle se world space me the.
- `GameRenderer.kt`: templates ek baar GPU par; `drawBuildings()` har imaarat ko model matrix (face * 90 deg) se banata hai, distance culling (low 230 m / mid 320 m / high 420 m) + peeche wale door ke skip. Shadow pass me bhi (`renderShadows`).
- Interior loot ~7300 points hai: 3 me se 2 ko stock karta hai (`stockHomes`), taaki loot list halki rahe.
- Rotation ki jaanch (Python): 1307 imaraton ka rotated mesh bounding box = file ke collision footprint se 0.00 m farq.

## 2. Relief / barish naye houses.bin ke hisaab se dobara
- `tools/make_relief.py`: ghar ka footprint ab block header se (instanced me mesh nahi hota). `bijnor.bin` dobara bana -> nayi imaraton ke neeche pahaadi nahi.
- `tools/make_roofs.py`: instanced imaraton ke liye (template ka raster ek baar, har jagah ghuma kar). `roofs.bin` dobara (1307 entries, sab footprint ke andar).
- `manifest.txt` sync.

## 3. Walk phisalna kam
- `WALK_SPEED` 4.5 -> 3.6, aim 4.2 -> 3.2 (GameRenderer).
- `Gait.cadence()` (CharacterModel.kt): kadam tez aate hain (1.9 + 0.27 v, max 3.0 / sec; pehle 1.9 + 0.13 v, max 2.75) -> chhota stride = kam skating.
- Agar speed pehle jaisi chahiye: `WALK_SPEED` wapas 4.5 kar do (phisalna wapas aayega).

## 4. Zameen ka rang ab height ke hisaab se (MapData.buildTerrain)
- Gaddhe (< 2.4 m): gehra, geela hara. Pahaadi (2.6 - 5.2 m): sookha olive -> tan. Dhalaan: mitti jaisa.
- 2.4 m ki flat zameen (ghar, sadak, gaon), beach aur samundar ka rang bilkul pehle jaisa.

## Test
- Kotlin compile (Android SDK ke bina): MapData.kt saaf; GameRenderer / CharacterModel me meri lines par sirf `GL` / `Matrix` (Android) "unresolved" aaya, aur koi error nahi.
- Phone par / GitHub Actions build abhi nahi chala -> build karke dekhna. Error aaye to `e:` wali lines bhej dena.
- Dekhna: (1) dukaan / school / hospital / bank ke andar jao, seedhi se 1st-2nd floor aur chhat tak; (2) FPS (agar kam ho to `drawBuildings` ki range kam karo); (3) walk me pair; (4) pahaadi ka rang.

## Files
BADLI: MapData.kt, GameRenderer.kt, CharacterModel.kt, assets/models/houses/houses.bin, roofs.bin, assets/maps/bijnor.bin, assets/manifest.txt, tools/make_relief.py, tools/make_roofs.py
NAYI: CHANGES_v54_ENTERABLE.md
