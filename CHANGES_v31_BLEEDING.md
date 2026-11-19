# v31 BLEEDING (v30b ke upar)

## Kaise kaam karta hai
- Goli ya grenade se armour ke baad kam se kam 4 HP lage to khoon behne ka chance hai (halki chot ~35 %, bhaari chot ~90 %+).
- Khoon behne par HP har second ghat-ta hai: 0.8 se 2.0 HP/s ek zakhm se. Kai zakhm jodte hain, max 4 HP/s.
- Apne aap band NAHI hota. Sirf BANDAGE (2.5 s, khoon band + 15 HP) ya MEDKIT (2.2 s, khoon band + 100 HP) rok-ta hai.
- Naya hit bandage/medkit ko beech mein rok deta hai (pehle jaisa). Heal ke dauran chalna dheema hota hai (pehle jaisa).
- Khoon se mar gaye to feed mein "BLEEDING eliminated YOU". Mushroom (EP) abhi bhi 1 HP/s wapas deta hai, to wo khoon ko thoda kaat-ta hai.
- Girne (fall) aur zone ki chot se khoon nahi behta.

## Screen par
- Screen ke kinaare gehre laal pulse karte hain (jitna zyada khoon, utni tez dhadkan).
- HP bar laal, uske upar "BLEEDING -x.x HP/s" chip.
- Zameen par khoon ki boondein girti hain (40 s tak dikhti hain).
- HEAL slot khud BANDAGE dikhata hai jab bandage hai aur khoon beh raha hai (ya medkit nahi hai).

## Bandage item
- Naya loot: safed patti + laal patta. Ghar, bot ke crate, map mein spawn. Bag mein max 10, backpack panel mein "Bandages".
- Auto-pickup wahi setting jo medkit ki hai (Settings > Med Kits).

## Files
Realism.kt (numbers), Loot.kt (BANDAGE), GameRenderer.kt (startBleed / stepBleed / heal / spawn / draw), GameScreen.kt (HUD).
Kotlin yahan compile NAHI hua (Android SDK nahi): GitHub Actions build chalao, `e:` lines aayein to bhej do.
