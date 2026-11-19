# v40 UPAR KI MANZILON PAR DARWAZE (v39 ke upar)

## Kya badla
- 64 enterable gharon me ab HAR manzil (ground, 2nd, 3rd ... ) par beech ki deewar + asli darwaza hai (pehle 46 gharon me upar koi darwaza nahi tha).
- Ye darwaza DOOR button se khulta / band hota hai, andar baith kar CLOSE karo to bar ho jaata hai (v37 ka wahi system).
- Deewar khidki / balcony ke darwaze se takrati nahi (x apne-aap chuna jaata hai). Furniture / loot deewar aur darwaze ke raste me nahi aate.
- Seedhi, chhat, loot (har manzil par) pehle jaise hain.

## Files
- tools/houses/build.py (partition + door leaf)
- assets/models/houses/houses.bin (dobara bana), assets/manifest.txt (size sync)
- Kotlin me KOI badlav nahi.

## Verify
- 7 ghar models par reachability test pass (loot unreachable = 0).
- Poora game yahan compile NAHI hua (Android SDK nahi). GitHub Actions build chalao.
- Baaki (pehle jaisa): bazaar / dukaan / mandir jaisi 1304 imaarat upar se theos hain.
