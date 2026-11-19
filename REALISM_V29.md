# v29 REALISM (on top of v28_empty_land)

## 1. Fall damage (player + bots)
- Walking off a roof / balcony / floor edge is now a real fall (before the body slid down in 0.1 s, no damage). Jumping does the same.
- 0-2 m: no damage. Then 14*e + 1.2*e^2 HP (e = drop - 2 m): 3.2 m (1st floor) = 18 HP, 5 m = 53, 6.8 m (2nd floor) = 95, 10 m = 189, ~10.5 m+ = dead (200 HP).
- Armour does NOT protect against a fall. Deep water = no damage, wading = 40 %. Warm-up = no damage.
- Hard landing: HP bar flash, vibration, "hurt" sound, medkit healing is interrupted, limp (slower, no sprint) for 1.2-6 s.
- Bots fall too (and can die from it: feed "X died from a fall").
- Files: Realism.kt (numbers), GameRenderer.kt (landed(), fall start, limp), Bots.kt (bot fall).

## 2. Footstep / gun noise and bot hearing
- Player noise radius (m): prone 2.5, crouch 4.5, walk 14, sprint 30, jump 9, landing 10 + 6*drop, grenade bounce 16, grenade blast 160.
- Gunshots: VSS (suppressed) 26, SMG 75, shotgun 100, DMR 140, sniper 180, AMR 230.
- Bots no longer know where you are (the old 140 m wall-hack is gone). A bot goes for you while it SEES you (53 m, 42 m when you crouch, 32 m when you lie prone);
  after that, or when it HEARS a shot / steps / blast / landing, it walks to the last known spot (position is never exact) for 8-9 s, then gives up.
- Walls / floors between cut the hearing range by 40 % each. Bots also hear each other's gunshots and run to fights.
- Files: Bots.kt (hear(), alertT, investigate), GameRenderer.kt (noise(), playerStep()), MapData.kt (soundWalls()).

## 3. Gunshot distance / walls / inside the house
- Every bot shot sounds like ITS gun (VSS = quiet thump, shotgun = boom, others by slot), pans left/right and fades with distance (0 at range x 1.6).
- Far = dull + echo, behind a wall = muffled and quieter (each wall -40 % volume), inside a house = room reverb, over 25 m it arrives late (343 m/s).
- Doors / windows let sound through (real gaps), furniture does not block it, floors and roofs do.
- You now hear the bots' footsteps (louder when they run), their landings and grenades the same way.
- Files: SoundFx.kt (playWorld(), _far/_muf/_in variants), MapData.kt (soundWalls(), covered()), GameRenderer.kt (hearAt()).
