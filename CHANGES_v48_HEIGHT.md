# v48 - character ki height = ghar ki height (Free Fire jaisa)

## Naap (houses.bin se, 455 ghar)
- Darwaza 2.14 m (7 ft), khidki sill 0.7 m (kamar ke level), shisha 0.7-2.05 m, chhat 2.95 m (insaan ki height ka ~1.65 guna).
- Free Fire: character 5.5-6 ft (1.68-1.83 m), darwaze ~7 ft - sir darwaze ke upar se thoda neeche. Ab bhi bilkul waisa.

## Kya galat tha
MapData.PLAYER_K = 0.75 tha: model 2.15 x 0.8 x 0.75 = 1.29 m, aankh 1.27 m -> character darwaze (2.14 m) ke mukable bahut chhota, screen par ~27%.

## Ab
- PLAYER_K = 1.0, PERSON_H = 1.78 m, CharRig.SCALE = 0.83 -> player aur bots dono 1.78 m.
- Aankh 1.66 m (sir ke upar se 12 cm neeche), sir ki clearance BODY_H 1.85 m (darwaze 2.14 m se neeche, isliye darwaze/chhat me sir nahi atakta; pehle 2.18 hota to atak jata).
- Bots ka hit-box model jitna (1.78 m), headshot zone upar ke 28 cm. Player ka hit-box 1.78 m.
- TPP camera 3.4 m -> 3.1 m peeche: character screen ki height ka ~41% (Free Fire 35-45%). Scope/ADS par body gayab (FPP) pehle jaisa.
- Paani me tairna thoda adjust (sir paani ke upar ~34 cm).
- Crouch / prone: eyeDrop same (aankh 1.16 m / 0.71 m).

## Test nahi hua
Android SDK nahi, syntax check pass. Phone par dekhna: darwaze se guzarna, khidki ke upar se kudna, sir ka chhat se na takrana.
