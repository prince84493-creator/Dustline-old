# v55 - PISTOLS + do-haath gun animation (ADHURA / WIP)
> NOTE: v56 me ye sab POORA ho gaya (CHANGES_v56_PISTOLS_RIFLE.md). Neeche ki "BAAKI" list purani hai.
## Ho gaya
- 3 pistol add: M1911, G18 (auto), DEAGLE - slot 3 (PISTOL). Models: assets/models/guns/{m1911,g18,deagle}.gun (tools/make_guns.py)
- Pistol ke haath-pakad: GunHold (OZ + ARMS) CharacterModel.kt, ReloadAnim.kt me pistol anchors
- Slot 3 ki safety: owned 4 slot, backpack list, shot sound slot cap
- PistolRig.kt: rifle-mode inputs (rel, lhMagW, lhMagPt, gunO) + magazine-hand logic taiyaar
- PistolState.kt: rifle-mode fields + reload keyframe tables taiyaar (update() abhi nahi badla)
## BAAKI (nahi hua)
- PistolState.update() me rifle branch (sprint carry / dono haath fire / reload), pistol=false param
- GameRenderer: paPlayer.update / b.pa.update me pistol flag (guns[gi].pistol) + rig + walkPhase
- CharacterModel.skinBaked: pig.rel / lhMagW / gunO set karna
- Pistol loot spawn (gunOfSlot(3)), pistol ka HUD / switch
- Kotlin compile / phone test nahi hua (compiler nahi tha)
=> Abhi bhi purani pack animation sab guns par chalti hai.
