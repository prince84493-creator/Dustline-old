# tops_v15 - how the 6 store tops were rebuilt (v15)

Input : the v14 `store.json` (colours + skin weights are copied from it). Output: new `assets/store/store.json`, 6 store pictures, manifest sizes.

    REF_STORE=/path/to/v14/store.json python3 tools/tops_v15/pack.py     # needs numpy scipy Pillow
    python3 tools/tops_v15/verify_store.py                              # needs the v14 store.json saved as tools/tops_v15/REF_STORE_V14.json

What changed (all 6 tops): clean torso grid (128 columns) + sleeve sweeps welded into widened shoulders (no patches, no gap),
rolled collar / hem / cuffs with an inner lining, exact V-neck cut + cream binding ribbon + chest skin (CROSSHAIR), subtle folds,
no zero-normal or degenerate triangles, pants copied byte-for-byte. `criminal` fits were dropped (no Kotlin code used them).
