# v19: trees + wind + weather (only this, nothing else changed)

New file: `app/src/main/java/com/bijnor/game/TreeSystem.kt`
- 7 tree types from ped-3d.html (Neem-jaisa, Oak, Babool, Ghana ped, Patjhad, Pine, Sukha ped), 2 shapes each, built in code (no new assets).
- Placed ~2000 trees: small groves, lone field trees, and a moderate number beside roads.
  NEVER on a road (centre + 16 points at 3.5 m and 6.5 m are checked against the road raster, so every trunk is >= ~6 m from the asphalt and the crown never hangs over it), NEVER inside a town
  (radius around each of the 32 labels: Bijnor 450 m, big towns 260 m, others 190 m, small ones 130 m), not in water / coast.
- Drawn instanced (one draw per shape) within 420 m; wind sway in the vertex shader (same formula as the HTML), stronger in storm.
- WeatherSystem: sunshine -> rain -> storm (strong wind + lightning) -> rain -> sunshine, repeats automatically. Starts with ~170 s sun.
  Sky colour, fog distance, light, wet (darker) ground/trees follow the weather. RainFx = rain streaks around the camera.

Edited: `GameRenderer.kt` (shader: instanced tree path + weather uniforms, draw calls, clear colour). Original kept as nothing else touched.
Not compiled here (no Android SDK): run the GitHub Actions build once.
Tuning: counts and town radii at the top of `TreeSystem.place()` / `cityR()`; weather timings in `WeatherSystem.PH`.

Verified with a Python copy of the placement on the real bijnor.bin: 0 trees on a road, min trunk-to-asphalt distance 5.8 m, 0 trees inside a town circle, 0 in water; ~2450 trees (1480 grove, 330 lone, 650 roadside, evenly along all roads).
Tree draw range: 420 m (gfx high) / 320 m (mid) / 220 m (low).
