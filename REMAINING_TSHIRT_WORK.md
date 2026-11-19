# T-shirt work - v15 status (replaces the v14 "partial" list)

Rebuilt offline with tools/tops_v15 (read its README). Verified by tools/tops_v15/verify_store.py (a Python port of the Cloth.kt loader) and by software renders with the game's cloth shading.

## Done
1. Zero-normal / black-dot points: new meshes have 0 zero-normals and 0 degenerate triangles; Cloth.kt shader + normals() now also guard against a zero normal (no NaN).
2. Shoulder seam / gap / stair-step raglan seam: old floating sleeves + jagged shoulder patches removed. Torso shoulders are widened and the sleeve dome sits inside them (one continuous surface). Raglan / henley colour seam is a smooth curve.
3. V-neck: exact straight V cut (not triangle stair-steps), cream binding ribbon, chest skin patch under the V.
4. Collar / neck inside: rolled collar with inner lining, all 6 tops.
5. Low-poly tees (RAGLAN, HENLEY, WHITE, SWEATER): re-sampled to 128 columns, smooth silhouette and colour.
6. Sleeve ends / hem: rolled cuff + hem with lining; hem lip / bump smoothed; subtle folds (armpit, drape, sleeve ruching).
7. criminal: removed from the 4 rebuilt tops (dead data, no Kotlin code used it).
8. Store pictures: 6 new 264x264 pictures + thumbs (3x supersampled).
9. store.json (9.03 MB) + manifest.txt sizes refreshed: 98 entries, 0 mismatch. Pants are byte-identical to v14.
10. Audit: 54 Kotlin files bracket-checked, every asset path used in code exists, every asset is in the manifest. Store has only TOP and PANTS items (no cap / glasses / mask / shoes / bundle items exist yet, only the code paths).

## NOT done (cannot be done without a phone / Android SDK)
- Real phone test and APK build (#1-#3): run GitHub Actions "Build Bijnor", then check the tops in STORE > T-SHIRTS and in a match (arms raised / prone).
- WHITE TEE rename to IVORY TEE: not done (would also change credits.txt / CREDITS.md).
