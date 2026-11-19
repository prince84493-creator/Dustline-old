# v56 - pistol animation sirf pistol par + baaki guns ke liye do-haath animation
## Pistol
- 3 pistol game me: M1911, G18 (auto), DEAGLE (slot 3). Ground loot me ab har 5 me ~1 weapon pistol hai, starter ring me bhi ek pistol.
- Pistol ki awaaz (shot1) aur wazan (Combat.gunKg) alag.
## Baaki guns (SMG / AR / shotgun / DMR / sniper)
- Ab pistol-pack animation inpar nahi chalti (PistolState rifle mode): player aur bots dono.
- Dono haath se carry / bhaagna / fire (hip fire bhi); reload me left hand magazine nikal kar nayi magazine hip-pouch se leke lagata hai, third person me magazine ka block bhi dikhta hai.
## Files
- PistolState.kt: rifle branch (buildRifle), setGun, update(pistol, walk, runW)
- CharacterModel.kt: skinBaked me rig inputs (rel, lhMagW, lhMagPt, gunO)
- GameRenderer.kt: player + bots me pistol flag / walk / runW, magazine block, loot spawn (lootSlot)
- Realism.kt (pistol sound), Combat.kt (pistol weight)
## Dhyan do
- Is build me Kotlin compiler nahi tha: code compile / phone par test nahi hua. GitHub Actions build me errors aaye to bata dena.
