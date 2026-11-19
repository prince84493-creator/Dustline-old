# v52 - map se saare mandir aur masjid hata diye gaye

Game sirf maje ke liye hai, isliye map ki koi bhi dharmik imaarat nahi rakhi gayi.

## Kya badla
- `app/src/main/assets/models/houses/houses.bin`: 107 mandir + 74 masjid (kul 181 imaarat) poori tarah hata di gayi: unka mesh, collision boxes aur chhat ki surface. 10 blocks poore khali ho gaye to unhe bhi hata diya. Baaki shop / dhaba / school / hospital / bank / godown / petrol / tower / clock aur 64 enterable ghar jaise ke taise hain (1123 imaarat bachi).
- `houses.bin` ka size 58,202,652 -> 54,460,492 bytes. `manifest.txt` mein naya size likh diya gaya (warna start par "Damaged file" aata).
- Kotlin code mein koi badlav nahi.
- `tools/houses/make_houses_towns.py` aur `make_houses_v43.py`: MUST_* / RANDOM_POOL / HPOOL lists se 'mandir' aur 'masjid' hata diye, taaki dobara generate karne par wapas na aayen.

## Dhyan do
- `tools/houses/out_v43/` (adhura v43 output, game mein use nahi hota) mein purane mandir / masjid abhi bhi hain. v43 ko game mein lagane se pehle use dobara generate karna.
- Kotlin yahan compile nahi hua: GitHub Actions build chalakar test karo.
