# v37 DOOR BUTTON (v36 ke upar)

## Pehle se kya tha (maine check kiya)
houses.bin me 455 building entries hain. Asli ghar (andar se khokhle, kamre, furniture, seedhiyan, chhat, loot) pehle se bane hue the.
1073 darwaze + 4774 khidki ke sheeshe. Jin entries me darwaza nahi hai wo compound walls / chhoti deewarein hain (4-5 deewar ke dabbe), ghar nahi.
Sheeshe wali kuch badi imaratein (3 ki list: 88, 165, 202) me darwaza nahi, unme sheesha todkar ghus sakte ho (pehle jaisa).

## Kya badla
- Naya DOOR button (HUD): jab bhi koi darwaza 2.6 m ke andar ho (usi manzil par) tab button aata hai. Band darwaze par "OPEN", khule par "CLOSE".
- Darwaza ab player ke paas jaane se apne-aap NAHI khulta. Sirf button se khulta hai.
- OPEN dabane ke baad darwaza khulte hi (~0.2 s) player bahar se andar apne-aap 2-3 m andar chal deta hai (stick chhune par / paani me / jump par ye ruk jaata hai). Andar se bahar ya kamre ke darwaze par ye auto-walk nahi hota.
- Ghar ke andar baithkar CLOSE dabao to darwaza band ho jaata hai aur BAR ho jaata hai: bots us darwaze ko tab tak nahi kholte jab tak player 20 m ke andar hai. 20 m se door jaate hi wo wapas bots ke auto mode me aa jaata hai.
- Khula chhoda hua darwaza 40 m door jaane par bots ke auto mode me wapas aata hai (jab koi paas na ho to band).
- Darwaze ke beech khade ho to darwaza band nahi hota ("STEP AWAY FROM THE DOOR TO CLOSE IT").
- Bots pehle jaise darwaze apne-aap kholte / band karte hain (2.2 m / 3.8 m).
- Button HUD editor me bhi dikhta hai (id 103, "DOOR"), jahan chaho wahan rakh sakte ho.

## Files
MapData.kt (Dyn.pm, updateDoors, doorNear), GameRenderer.kt (doorPrompt, toggleDoor, doorStep, walk-in assist), GameScreen.kt (button + touch), HudLayout.kt (id 103), HudIcons.kt (icon).

## Verify
- Asli houses.bin ke saare 1073 darwaze: dono taraf se 1.5 m par khade hone par button wahi darwaza pakadta hai (2146 / 2146).
- 249 mukhya darwaze jo ghar ki deewar par hain: 247 me walk-in sirf bahar->andar taraf lagta hai (2 multi-part imaratein, wahan runtime par chhat check (map.covered) faisla karta hai).
- Darwaze ke state-machine ki 7 situations simulate ki (khulna, band karna, bot ka bar wale darwaze par rukna, door me khade ho to na band hona): sab pass.
- Poora game yahan compile NAHI hua (Android SDK / kotlinc nahi hai). Brackets balance hain aur naye code ke sare naam apni class me maujood hain. GitHub Actions build chalao, `e:` lines aayein to bhej do.
