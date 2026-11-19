# v64 - patthar (boulder) + jhaadi (bush) solid, collision multi-pass (v63 ke upar)

## Jo mila
1. Map me patthar / jhaadi ke objects the hi nahi (MapData.kt: "no rocks"), isliye "patthar ke andar se nikal jaata hai" ka koi collision ban hi nahi sakta tha. Grass sirf zameen ki texture hai (object nahi).
2. `MapData.resolve` ek hi baar chalta tha: ped / patthar se bahar dhakelne par body deewar ke andar ja sakti thi (aur ulta). Ab 4 pass tak dohraata hai jab tak body hilna band na kare.
3. Ghar audit (houses.bin, 1307 ghar, 78 templates): deewaron ke collision boxes mesh se mel khaate hain. Jo hisse "bina box" dikhe wo seedhi ki railing nikli (seedhi step-boxes se banti hai) - koi gayab deewar nahi mili, isliye ghar ke data me kuch nahi badla.

## Fix
- TreeSystem.kt: 2 nayi type - `rock` (faceted boulder, ~1.7 m) aur `bush` (ghani jhaadi, ~1.8 m). Wahi instanced draw + shadow path jo ped ka hai (naya GL code nahi).
  Placement: 520 patthar (akele + 2-4 ke dher), 800 jhaadi (akeli + jhund). Sadak se 6.5 m, ghar se 60 m, shehar / paani / spawn se door (ped wale hi rules). Ped apni purani jagah par hain (alag random stream).
  Collider: patthar r = 0.72 m x size (solid), jhaadi r = 0.42 m x size (ghana beech ka hissa rokta hai, bahar ke patte chhoo kar guzar jaate hain).
  Satellite map par sirf ped dikhte hain (patthar / jhaadi nahi).
- MapData.kt: `resolve` multi-pass; `randomLandAny` ab ped / patthar / jhaadi ke andar loot ya bot start nahi deta.
- Bots (`Bots.free`) pehle se `circleBlocked` dekhte hain, to patthar / jhaadi ke gird ghoom kar chalte hain.

## Jaan-bujh kar nahi badla
- Ghaas (grass) par chalna asli zindagi me bhi hota hai, wo solid nahi. Ghani jhaadi solid hai.

## Test nahi hua
Android SDK / Kotlin compiler yahan nahi hai: bracket balance check hua, compile nahi. GitHub Actions se build karke `e:` lines aayein to bhej do.
Phone par dekhna: patthar / jhaadi ka size aur ginti (TreeSystem.place() me 520 / 800), collider radius (`cr` array).
