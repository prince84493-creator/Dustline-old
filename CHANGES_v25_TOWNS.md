# v25 - houses in every town
- Before: only 7 houses, all inside Bijnor city. Now 64 enterable houses: the same 7 Bijnor houses (unchanged places) + houses in the other 31 towns
  (Najibabad 4, Nagina / Dhampur / Sherkot / Seohara / Chandpur 3 each, 13 mid towns 2 each, the small ones 1 each).
- Same 7 house models (reused), same houses.bin format -> NO Kotlin change. Only changed: assets/models/houses/houses.bin, assets/manifest.txt, tools/houses/make_houses_towns.py (new), tools/houses/audit_map.py (spacing 50 m), tools/houses/sites.txt (list of all places).
- Every house: flat dry land, 8 m+ from asphalt, road 7-30 m in front of the door, 55 m+ from the next house (Bijnor city 130 m), 40 m+ from spawn. Trees keep 60 m away (TreeSystem rule already there).
- audit_map.py: AUDIT OK.  check_houses.py: 0 of 1140 loot points unreachable, every roof reachable.
- Not compiled here: run the GitHub Actions build once.

## v26 addendum - at least 10 buildings in every town
- New `tools/houses/fillers.py` + extra step in `make_houses_towns.py`: 338 plain town buildings (shops / flats, 1-4 floors, windows, door, sign band, flat roof you can land on; collision box; NOT enterable) in the same houses.bin (nl = 0 blocks, neighbours merged into one draw call).
- Every town now has >= 9-10 buildings (houses + plain): small towns 10, mid 12, big 16, Najibabad 20, Bijnor 26. Total 64 enterable + 338 plain = 402.
- Same BJH1 format -> no Kotlin change. audit_map.py: AUDIT OK, check_houses.py: 0 of 1140 unreachable.

## v27 addendum - many more buildings, new designs
- 584 town buildings (was 338): shops/flats 240, mandir 60, masjid 60, godown 46, dhaba 43, petrol pump 40, school 38, water tower 34, hospital 9, bank 7, clock tower 7 (`tools/houses/fillers.py`). Plus the 64 enterable houses = 648 buildings.
- Per town: Bijnor 46, Najibabad 34, 5 big towns 28, 13 mid towns 20, small places 14. Each town gets its own mandir / masjid etc.
- Not enterable (solid, roofs landable). Same houses.bin format, no Kotlin change. audit_map.py OK, check_houses.py 0 unreachable.

## v28 addendum - buildings on the empty land
- 85 hamlets (3-6 buildings, along roads) + 116 farm clusters (2-4 buildings, away from roads) on empty flat land, never near a town (110 m / 90 m clear), 8 m+ off the asphalt, 40 m+ from spawn. Same designs as the towns (shops/houses, dhaba, mandir, masjid, godown, water tower).
- Total now 64 enterable houses + 1304 buildings = 1368 in 455 mesh blocks (houses.bin 37 MB). audit_map.py OK, check_houses.py 0 unreachable.
- If the game is slow on weak phones: raise HAM_GAP / the 230 m farm gap in tools/houses/make_houses_towns.py and run it again (python3 tools/houses/make_houses_towns.py, then python3 tools/sync_manifest.py).
