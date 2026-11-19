# v16 - t-shirt fixes (offline, NOT compiled, NOT run on a phone)
1. Collar/neck: the neck opening of RAGLAN, OLIVE SWEATER, HENLEY, WHITE and DESERT STRIPE stuck out ~5 cm in front of the throat
   (front z -0.19, neck -0.10). Now front -0.135 / back +0.066 (same as the survivor's own jacket collar). Roll + lining rings use the same map.
2. Collar lining poked through the outer cloth (2-4 cm) = dark slab at the throat. Now inside (verified, 0 mm outside).
3. Hem lining poked through the front (up to 1.7 cm) = dark crescent in the store picture. Now inside.
4. CROSSHAIR V-NECK was navy; the name / generator say crimson. Recoloured to crimson (cream print, V binding and chest skin kept).
5. 6 store pictures + thumbs re-rendered, manifest.txt synced (98 entries, 0 mismatch). No Kotlin file changed. Pants unchanged.
Scripts: tools/tops_v16 (fix_geometry.py, fix_colour_pictures.py, verify_lining.py).
Still needs: GitHub Actions build + phone test (lobby avatar, STORE > T-SHIRTS, match).
