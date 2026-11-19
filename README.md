# Bijnor 3D Battle Royal & Survival Game - native Kotlin / Android project

Single-player 3D battle royale on an original Bijnor island map. Native Kotlin + OpenGL ES 3.0 (no WebView), 100% offline.

- Package: `com.bijnor.battleroyale3d` (Kotlin package `com.bijnor.game`)
- Version: 1.0.33 (versionCode 60)
- Permissions: `VIBRATE` only. No internet, no ads, no analytics, no third-party SDK (only AndroidX core + appcompat).

## v1.0.33 - all advertising removed
- Deleted the ad code completely (all ad manager / consent classes), the ad library dependencies, the ad app id and every ad unit id.
- Removed the `INTERNET`, `ACCESS_NETWORK_STATE` and advertising-ID permissions from the manifest.
- Removed the WATCH VIDEO button (lobby), the 2X COINS button (result screen) and the 2X button (level-up screen). Level-up and match coins are paid once, normally.
- Diamonds now come from the coin exchange (tap the coin counter in the lobby). The "+" button says so.
- Removed Settings > ACCOUNT > DATA & PRIVACY SETTINGS (it only existed for the ad consent form).
- Privacy policy regenerated for an offline game with no data collected (`tools/make_privacy_policy.py` -> `docs/`, `PRIVACY_POLICY.md`, in-app text in `Legal.kt`). Publish `docs/index.html` again.
- Play Console: Ads = No, Advertising ID = No, Data safety = nothing collected / shared (`PLAY_STORE/DATA_SAFETY_ANSWERS.md`).
- Old abbreviations of the previous game name that were left in code comments were removed too.

## Build
1. Push the project to a PRIVATE GitHub repo and run Actions > Build (see `PLAY_STORE/BUILD_AND_UPLOAD.md`), or open it in Android Studio.
2. `tools/sync_manifest.py` keeps `assets/manifest.txt` in step with the asset files (the build runs it).
3. `tools/make_privacy_policy.py --check` fails the build if the privacy policy no longer matches the app.
4. Not compiled in the session that made this version (no Android SDK there): if the build prints lines with `e:`, send them and they will be fixed.

## Project map
- `app/src/main/java/com/bijnor/game/` - game code (`GameRenderer.kt` match logic + drawing, `GameScreen.kt` HUD, `LobbyView.kt`, `StoreView.kt`, `ProfileView.kt`, `ResultView.kt`, `LevelUpView.kt`, `SettingsView.kt`, `Legal.kt` ...)
- `app/src/main/assets/` - maps, 3D models, textures, store data, credits
- `tools/` - asset / policy / build helper scripts
- `PLAY_STORE/` - store listing text, declarations, checklists
- `CREDITS.md`, `LICENSES/`, `THIRD_PARTY_NOTICES.md`, `ASSET_LICENSES.md` - licence records for the third-party 3D works (CC BY 4.0 credits must stay in the game and the store listing)
