# Upload se PEHLE - 5 zaroori baatein (seedhi, sach)

## 1. GitHub par kya jaayega
`1_PROJECT_github_par_daalo` ka content (`.github` folder samet) private repo mein. `2_PROOF_PRIVATE_sirf_apne_paas_rakho` KABHI nahi.

## 2. Compile ki pushti NAHI (sach)
Is version ko Android SDK se abhi COMPILE nahi kiya gaya (v1.0.8 mein bhi nahi). Isliye Build safal hoga - iski pakki guarantee nahi.
Kya kiya gaya: brackets, resources, manifest sizes, privacy --check - sab pass.
Kya karna hai: GitHub > Actions > "Build Bijnor (APK + AAB)" > Run workflow. Fail ho to log mein `e:` wali lines / "What went wrong" bhejo - sahi karenge. Jab tak build green na ho, AAB upload mat karo.

## 3. Upload Signing Key
GitHub > Settings > Secrets and variables > Actions mein ye 4 secrets:
- KEYSTORE_BASE64
- KEYSTORE_PASSWORD
- KEY_ALIAS
- KEY_PASSWORD
Inke bina Release AAB UNSIGNED banta hai aur Play Console use sweekar nahi karega (workflow ka aakhri step tab laal ho jaata hai). Key kaise banti hai: tools/make-keystore.sh / .bat ya Actions "make-upload-key". Keystore file + passwords ki 2 copy alag jagah (Drive + phone) rakho - kho gayi to app update nahi hoga.

## 4. Asli phone par test (build ke baad debug APK install karke)
Ek POORA match khelo aur ye jaancho:
- [ ] Character (lobby + match mein dikhta hai, kapde badalna)
- [ ] Map (plane se kood, utarna, ghar mein ghusna, paani / tairna, safe zone)
- [ ] Loading screen (start, match loading, result -> lobby)
- [ ] Credits & Licenses (khulta hai, saare 9 naam, links tap karne par khulte hain)
- [ ] Privacy Policy + Contact / Support (Settings > ACCOUNT)
- [ ] Crash / freeze: Easy / Normal / Hard, graphics LOW / MID / HIGH, home dabakar wapas aana
Poori list: PLAY_POLICY_COMPLIANCE.md

## 5. Play Console / baaki
versionCode (60) pehle upload hua ho to +1; docs/index.html Netlify par dobara; IARC rating (Blood = Yes); Data safety (DATA_SAFETY_ANSWERS.md); asli screenshots; naam / trademark khoj (REPORT_16_POINTS.md ke links bhi click karke dekho).
