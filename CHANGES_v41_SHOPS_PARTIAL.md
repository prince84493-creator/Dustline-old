# v41 (ADHURA) - dukaan / bazaar / school / hospital / bank ki upar ki manzil

## Kya hua
- tools/houses/fillers.py aur tools/houses/make_houses_towns.py me naya code likha gaya: 2+ manzil wali imaarat (dukaan, school, hospital, bank)
  ki upar ki manzilein khokhli (seedhi, slab me chhed, asli khidki, beech ka darwaza, har manzil par loot).
- Ye code 12 test imaraton par chala (loot sab reachable), lekin POORE MAP ka houses.bin iske saath dobara NAHI bana (generator 5 min se zyada leta hai).

## Isliye abhi game me kya hai
- assets/models/houses/houses.bin = v40 wala (64 gharon me har manzil par darwaza). Dukaan / bazaar abhi game me upar se theos hain.
- Dukaan wala kaam game me lane ke liye: `python3 tools/houses/make_houses_towns.py` (lamba chalta hai) phir `python3 tools/sync_manifest.py`.
- Kuch bhi compile / game me test nahi hua. GitHub Actions build chalao.
