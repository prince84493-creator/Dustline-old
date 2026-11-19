# v10 - neck fix + realistic character (edit of v9 copy)
- NECK: the jacket collar floated over bare skin (gap between collar and T-shirt, reference-photo skin strip painted in tex_11, bare chest skin under it).
  Fixed in textures only (no geometry/Kotlin change): black crew-neck T-shirt painted on the skin chest/neck base (tex_05 atlas), skin strip in the jacket texture (tex_11) repainted, jaw shadow added.
- Jacket back: pasted flat rectangle replaced by fabric colour-matched to its surroundings (tex_11).
- Realism: skin atlas (pores, blush, lips, light stubble), realistic iris/sclera, strand hair + strand eyebrows (tex_05/06/07/10), new CharRenderer shader (wrap/sub-surface skin, specular, rim, micro-bump, eye glint - MATS in CharacterModel.kt).
- Character files are scrambled with the same rule as tools/protect_character.py; manifest.txt synced.
- Not compiled here (no Android SDK). Kotlin change vs the original is only the CharRenderer shader + MATS table.
