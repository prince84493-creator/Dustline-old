# v62 - map audit (v61 third-person par bana)

## Jo jaancha gaya (script se, poora data)
- `bijnor.bin`: 801x801 height grid, ek hi land (koi tuta hua tapu nahi), koi spike / khadda nahi, sabse tez dhalaan 9.3 degree.
- Roads: koi connector paani me nahi, road mask ghar se nahi takrata, `roadline.bin` (5037 dashes) sab road par.
- `houses.bin`: 1307 ghar. Koi ghar paani me nahi, koi ek doosre par nahi, koi road par nahi, zameen se mel khata hai,
  collision boxes me NaN / ulte box nahi, 7330 loot points me se ek bhi deewar / furniture ke andar nahi, har ghar me loot hai,
  mesh ka rotation collision boxes se mel khata hai. Shehron ke naam (labels) sab flat zameen par aur ghar ke paas hain.
- `manifest.txt` ke sab assets maujood aur sahi size ke.
Yaani map ka data saaf hai; jo mila wo code me tha (neeche).

## Fix
1. **Neeche ke khet / ghaati (1.3 - 2.0 m) me kuch nahi aata tha** (`MapData.randomLandAny`): "ground < 2 m = samudri kinara" wale rule se
   zameen ka ~21 % (2.1 km2) khaali tha - na loot, na bots, na airdrop. Ab asli kinara test hai: point >= 1.0 m ho aur
   uske 10 m ke charo taraf sukhi zameen ho. Eligible zameen 79 % -> 97 %.
2. **Kinare par tairte waqt anjaan deewar** (`MapData.buildWater`): 0 - 2 cm unchai wale cells na zameen the na paani
   (walk = h > 0.02, paani = h <= 0.0). Ab paani = h <= 0.02, dono ek hi seema.

## Jo jaan-bujh kar nahi badla
- Kuch road vertices (17) aur 3 dash kinare par paani ke andar hain (map file ka hissa) - road wahin khatam hoti dikhti hai.
