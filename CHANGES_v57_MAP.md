# v57 - map: barish, ooncha-neecha zameen, puddle + kichad, minimap me ghar
## 1. Barish ghar ke andar nahi
- tools/make_roofs.py: har chhat ka raster ab 1 m (2 cell) bada (eaves, chhote chhed, tirchhi girti boondein) -> roofs.bin dobara.
## 2. Zameen ooncha-neecha (tools/make_relief.py, original tools/orig/bijnor_flat_v52.bin se)
- Pahaadiyan ~+8 m tak (pehle ~3 m), halki ghati, ~1280 gaddhe (puddle ke liye), slope max ~9 deg, aam taur par <4 deg.
- Ghar (18 m), sadak (4 m), spawn, samundar kinaara BILKUL flat. 6.4 m grid aur 12.8 m mesh ab milte hain.
- MapData.buildTerrain: pahaadi ka rang 2.8-7.5 m par sookha.
## 3. Puddle + kichad (GameRenderer shader, TreeSystem WeatherSystem.pud)
- Barish me pani gaddhon / neeche wali zameen / chhoti jagah par bharta hai, kinaare kichad (bhura), boondon ki lehren; dhoop me dheere sookhta hai. Sadak par kabhi-kabhi.
## 4. Minimap + big map
- Saare 1307 ghar / dukaan / school boxes me (chhota ghar, 2 manzil, bada public = alag rang). MapData.bldg.
- Minimap: view cone, bada arrow, andar aaye gaon ke naam, neeche nearest jagah + doori, view 240 m.
## 5. Audit: tools/audit_v57.py - 0 FAIL (ghar flat, sea me nahi, overlap nahi, sadak par nahi).
## Test: Kotlin compile nahi hua (SDK nahi). Shader WebGL2 me compile + render test hua. Build me error aaye to `e:` lines bhejo.

## v57 part 2 (screenshots wala kaam)
- Plane: map edge se shuru (pehle +300 m bahar), intro me peeche se 130 m door se aakar camera ke UPAR se guzarta hai (pehle 700 m peeche).
- Zameen ka rang: dry olive / tan / mitti / grey-brown (kam green), MapData.buildTerrain.
- Sadak ke beech white dashed patti: tools/make_roadline.py -> assets/maps/roadline.bin (5037 dashes), MapData.buildRoadLine + renderer "roadline".
- Satellite map: MapImage.kt naya (zameen ke rang + shading + sadak + ghar + ped). Minimap ab usi se bana hai.
- Naya full-screen map: gol map, grid A-H / 1-8, MARK / DELETE / ZOOM IN, BACK, HP bar, drag + pinch zoom (GameScreen.kt).
- NOTE: is hisse ka Kotlin compile nahi hua. Full map ka layout phone par dekhna padega.
