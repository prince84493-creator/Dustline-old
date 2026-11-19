# v38 DOOR ANIMATION + FIXES (v37 ke upar)

## Animation
- Darwaza ab smooth (ease-in-out) swing karta hai: shuru me dheere, beech me tez, end me dheere. Pehle seedha / jhatke wala tha.
- Khulna aur band hona dono me poora 0.62 s ka swing (pehle 0.45 s). Darwaze ka patta hinge par 90 degree ghoomta hai.
- Alag awaaz: kholne par creak, band karne par lakdi ki thud + latch click (naya sound "doorc").
- Swing beech me ulta bhi ho sakta hai (khulte waqt CLOSE dabao to wahin se wapas band hota hai).

## Meri v37 ki galtiyan jo theek ki
1. Band hote darwaze ke beech player aa jaaye to darwaza uspar band ho jaata tha (guard sirf pehle frame par lagta tha). Ab wo wapas khul jaata hai.
2. DOOR button deewar ke peeche wale darwaze par bhi aa sakta tha. Ab sirf tab aata hai jab player darwaze ke SAAMNE ho (side se adhe patte + 1 m ke andar, aur 2.4 m ke andar).
3. Double darwaze (do patte) me sirf ek patta khulta tha. Ab dono patte saath khulte / band hote hain.
4. Darwaza patta 25 % khulne par raasta deta tha jabki patta abhi aadha band dikhta tha; ab 30 % par (animation ke hisaab se).
5. Walk-in assist ka time naye swing ke hisaab se badla (0.3 s baad chalna shuru, tab tak darwaza paar karne layak khul chuka hota hai).
6. Bekaar variable (doorSel) hataya.

## Files
MapData.kt, GameRenderer.kt (drawDyn easing, sounds, doorMates), SoundFx.kt (doorc), CHANGES_v38_DOOR_ANIM.md.

## Verify (asli houses.bin ke 1073 darwaze)
- 25752 jagah (har darwaze ke saamne 0.7 / 1.2 / 1.8 / 2.2 m, side se +-0.5 m, dono taraf) khade hokar test: 0 baar "koi darwaza nahi mila"; 704 baar double darwaze ka dusra patta mila (ab dono saath chalte hain).
- Darwaze ke patte se 3.5 m side me (deewar ke peeche) khade hone par button 0 / 1073 darwazon par aaya.
- State machine 9 situations: khulna 0.20 s me paar-layak, poora 0.43 s me khula, band 0.63 s me, bar wale darwaze par bot ruka, door me khade ho to wapas khula, band darwaze ke paas khade rehne par apne-aap nahi khula: sab pass.
- Poora game yahan compile NAHI hua (kotlinc / Android SDK nahi). Brackets 6 files me balance hain. GitHub Actions build chalao, `e:` lines aayein to bhej do.
