# v43 (ADHURA - aapke kehne par yahin roka gaya)

## GAME ME ABHI KOI BADLAV NAHI
houses.bin = v40 wala (jaisa pehle tha). Kotlin me koi change nahi. Game pehle jaisa chalega.
Naya houses.bin game me ABHI MAT LAGANA: uske liye Kotlin loader + draw code likhna baaki hai, warna imaraten dikhengi nahi.

## Kya bana (sirf generator side, tools/houses/)
- cull.py (NAYI): chhupe hue face hatata hai, mesh ~20% chhota.
- tpl_lib.py (NAYI): 78 templates (7 ghar + dukaan 1-4 manzil, dhaba, school, hospital, bank, godown, mandir, masjid, petrol, clock, water tower).
  Har template me khokhli imaarat, seedhi har manzil se chhat tak, darwaze, khidkiyan, har manzil par loot. Har ek reach-test pass.
- bld.py: water tower theek kiya (seedhi se deck tak floor ka gap tha, ab deck pe ja sakte hain, loot reachable).
- Chhoti imaraton ki limit: 2+ manzil ke liye kam se kam 8x8, 1 manzil ke liye 6x7. Dhaba ab 8x7 hai (6x5 me seedhi nahi aati).
- make_houses_v43.py (NAYI, ~75 sec): 64 ghar (jagah bilkul v40 jaisi, check hua) + 1243 imaarat sab enterable.
- Output: tools/houses/out_v43/houses_v43_instanced.bin (19.7 MB, purane 58 MB se kam) + sites_v43.txt.
  Format: har template mesh ek baar, jagah-jagah rotate karke lagana (BJI1). Collision, floor, loot, darwaze world space me.

## Baaki (nahi hua)
1. MapData.loadHouses: BJI1 padhna, nV=0 wale block par khali group na banana.
2. GameRenderer: template upload + har imaarat model matrix (rotation = face*90 degree) se draw, door-paas culling, shadow pass.
3. Khidki glass draw ki doori kam karna (FPS), loot ka kuch hissa kam karna.
4. Game jaisa full test, GitHub Actions build. Kotlin yahan compile nahi ho sakta.
5. Verify script poore houses.bin par abhi nahi chala (sirf har template par reach-test hua).
Is waqt nayi file me total loot points 7330, doors+panes 11652.
