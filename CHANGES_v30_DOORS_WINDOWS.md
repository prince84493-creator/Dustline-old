# v30 DOORS + WINDOWS (v29 realism ke upar)

## Darwaze (64 enterable ghar, har ek ka samne wala darwaza)
- Darwaza ab asli patta hai (hinge wali side par ghoomta hai, andar ki taraf ~0.45 s mein khulta hai).
- Koi bhi (player ya bot) 2.2 m ke andar aaye to khulta hai; sab 3.8 m se door jayein to band ho jaata hai.
- Band darwaza = deewar (collision, goli aur awaaz rukti hai). Khula = raasta. Khulne/band hone ki awaaz ("door").

## Khidkiyan
- Deewar mein asli chhed (neeche sill, upar lintel, chaaron taraf frame). Sheesha alag pane hai.
- Goli lagne par sheesha tootta hai: tukde udte hain, awaaz ("glass"), bots 28 m tak sunte hain. Goli toote sheeshe ke paar chali jaati hai.
- Toota hua sheesha = andar jaane ka raasta (kood kar jao). Sill ki collision 0.3 m neeche rakhi hai taaki ek chhoti jump se paar ho jaye.

## Files
- tools/houses/kit.py, build.py, make_houses_towns.py : chhed + door leaf + "BJD1" records (houses.bin ke aakhir mein).
- assets/models/houses/houses.bin : dobara bana (manifest sync hua).
- MapData.kt (Dyn, updateDoors, paneAt, breakPane), GameRenderer.kt (stepDyn, breakGlass, drawDyn, shoot), SoundFx.kt (glass, door).
- Kotlin yahan compile NAHI hua: GitHub Actions build chalao.

## Baaki (abhi nahi)
- Bazaar / mandir jaisi 1304 "filler" imaarat andar se khali nahi hain (pehle jaisi).
- Balcony ke darwaze aur andar ke partition darwaze khule raste hi hain.
- Bots sheeshe ke paar dekh/goli nahi chala sakte (sheesha unke liye deewar hai jab tak toote nahi).

## v30b - baaki sab theek kiya
- Bazaar / dukaan / dhaba / school / aspatal / bank (921 imaarat): neeche ka kamra asli hai - samne ka darwaza khulta-band hota hai, samne + peeche + dono side ki khidki toot-ti hai. Upar ki manzil theos block hai (khidkiyan sirf dikhaave ki).
- Balcony ke darwaze (do patte) aur ghar ke andar ke partition darwaze ab asli darwaze hain.
- Bots ab intact sheeshe ke paar dekhte hain; bot ki goli bhi sheesha todti hai (MapData.shootThrough). Band darwaza unke liye deewar hai.
- Compile bug pakda: drawDyn() mein `uA` local tha, ab wahin define hai.
- Mandir / masjid / paani ki tanki / petrol pump / godown / ghantaghar (383) ghar nahi hain, pehle jaise theos.
- Kotlin abhi bhi compile NAHI hua (yahan Android SDK nahi): GitHub Actions build chalao.
