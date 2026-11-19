# BIJNOR MAP is in (4561 m x 3925 m)

Source: Bijnor_Map_With_Roads_v24.html. Imported as it is: land outline, 3 road layers, 89 road connectors, 32 place names. No houses / trees / bridges were added (the file has none).

- `assets/maps/bijnor.bin`  baked by `tools/make_bijnor.py` (height grid 801x801, cell 6.4 m, ring, roads, connectors). Land = plain at 2.4 m (v53: rolling hills added by tools/make_relief.py, 1.4-5.7 m, flat around houses / roads / spawn / beach), sea all around.
- `assets/textures/ground.jpg`  seamless grass detail tile (generated).
- `MapData.kt` loads the file: terrain + road meshes, walk grid, water (sea), road raster. `randomLandAny()` = random open-ground point on the whole map.
- `MapImage.kt` + `GameScreen.kt`: big map = whole-map picture + place names; minimap = sharp vector land + roads around the player.
- `BijnorLabels.kt` the 32 place names.
- Player / bots are 25 % smaller: `MapData.PLAYER_K = 0.75` (model, eye height, hit boxes).
- Loot lies in the OPEN anywhere on the land (never on roads / coast), count x6 (`LOOT_MULT`). Bots start spread over the whole map.
- Plane route, auto-jump time, safe-zone radii / times and the landing limit follow the 4561 m size (`ISLAND_R`, `ZP.KR`, `ZP.KT`).
- Run `python3 tools/sync_manifest.py`-style size update if any asset changes (manifest.txt sizes are checked at start-up).

NOT compiled here (no Android SDK): run the GitHub Actions build once.

## v1.0.32 map audit (roads repaired)
Found by checking `bijnor.bin` (land mask, ring, 3 road layers, 89 connectors, spawn, 32 labels) and the code that uses it:
1. Road triangles stuck out into the SEA (13 corners, some 6 m under water, e.g. near x -1490 / z -1385 and x -290 / z 1965 = outside the land outline).
2. Long road triangles near the coast did not follow the sloping ground: up to 2.9 m off (asphalt sank into the ground or floated). Now max 0.16 m.
3. 1 zero-area road triangle + 20 unused road vertices.
Fix: `tools/fix_bijnor_roads.py` (patches bijnor.bin in place, same BJN1 format, safe to run twice). Roads: 1354 + 1044 + 2074 triangles -> 4680 + 1932 + 2074. Land, height grid, connectors, spawn, labels are byte-identical.
Checked and OK: one single land piece, no lakes / holes, spawn on open land (2.4 m), all 89 connectors on land (18-22 m long), all 32 towns on land and next to a road (Kalagarh 105 m), ISLAND_R / LAND_HALF constants match the file, manifest sizes.
Known, NOT changed (design): the coast is a 1-2.6 m step into the sea (you simply walk / swim over it); roads have no white centre line (the old roadpaint.bin is not part of this map).
Run the GitHub Actions build once: nothing was compiled here.

### v1.0.32 second pass
4. Near the coast roads sank into the terrain you SEE (the terrain mesh has a vertex every 12.8 m, roads were placed on the finer 6.4 m ground grid): 8 % of road samples were buried, up to 0.28 m. Fix: new `MapData.meshY()` (height of the rendered mesh); `buildRoads()` puts roads and connectors on it, and `tools/fix_bijnor_roads.py` splits road triangles against that same surface. Now 0.26 % under the mesh (max 0.14 m, hidden by the polygon offset), road-vs-terrain error max 0.14 m.
5. Sea depth: inside the height grid the sea was up to 76 m deep, outside the grid the game assumes 7.7 m (OPEN_SEA_DEPTH), so swimmers saw the depth jump at the grid edge and divers could go 76 m down. `tools/fix_bijnor_sea_depth.py` limits the sea floor to 9 m (never drawn, land untouched).
Checked, left as is: ring vs height-grid coast differ by up to ~2 cells (coast smoothing, 0.5 % of cells); Kalagarh label 105 m from a road; one 18 m connector stub near (-450, 390).
