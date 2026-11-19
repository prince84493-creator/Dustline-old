package com.bijnor.game

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Privacy / data-deletion screens used by Settings > ACCOUNT.
 * Play Store needs the privacy policy to be reachable INSIDE the app as well as in the Play Console.
 */
object Legal {
    /**
     * Public link of docs/privacy-policy.html. DO NOT edit by hand: it is written by tools/make_privacy_policy.py from tools/developer.json (privacy_policy_url).
     * While it is empty the in-app text below is shown without the "open online" button.
     * The same link must be entered in Play Console > App content > Privacy policy.
     * The TEXT below is also generated (from the real manifest / libraries) - run the script instead of editing it.
     */
    const val PRIVACY_POLICY_URL = "https://battle-royal-privacy-kanishkdevloperr.netlify.app/"

    private const val TEXT = """PRIVACY POLICY (app version 1.0.33, last updated 15 November 2026)

SUMMARY
Bijnor 3D Battle Royal & Survival Game is a single-player game that works offline. Your game data (name, player ID, progress, settings, album pictures) is stored on your device. The game has no accounts and no server of its own. The game shows no ads, has no analytics and does not connect to the internet.

1. WHO PUBLISHES THIS GAME
Bijnor 3D Battle Royal & Survival Game (package com.bijnor.battleroyale3d, app version 1.0.33) is published by kanishkdevloper. Contact: vk.143.official@gmail.com

2. WHAT THE GAME STORES ON YOUR DEVICE
The game saves the following in its private app storage on your device:
- the display name you type, a player ID (a random number the game creates) and the date your player was created;
- coins, diamonds, XP / level, the characters and outfits you own and the ones you wear, and your wish list;
- match statistics: matches played, wins, top-10 finishes, kills, best kills, play time, likes, quits, and a list of your recent matches (date, rank, kills, XP, coins);
- your chosen character and hair style / colour;
- settings: graphics, difficulty, camera view, sensitivity, sound, vibration, HUD layout and similar options;
- photo album: when you tap TAKE SNAPSHOT on the profile screen, the game saves a small JPEG screenshot of the game window in its own storage (only the 12 newest are kept).
The profile screen also shows the language of your device; this is only displayed on your screen and is not stored or sent. The game does not send this information to the publisher. The app does not ask for access to your contacts, camera, microphone, location, or your phone's photo gallery and storage.

3. PERMISSIONS
The app declares these Android permissions:
- Vibration: vibration feedback during play (can be switched off in the game settings).
The app does not declare any other permission.

4. ADVERTISING AND THIRD PARTIES
The app contains no advertising, no analytics and no third-party tracking. It does not connect to the internet. Libraries inside the app:
- AndroidX core-ktx 1.13.1 - Google open-source user-interface library (not an advertising or analytics service).
- AndroidX appcompat 1.7.0 - Google open-source user-interface library (not an advertising or analytics service).

5. SHARING THAT YOU CHOOSE
On the profile screen, "COPY UID" copies your player ID to the clipboard. "INVITE FRIENDS" opens the standard Android share sheet with a short text that contains your display name, level and player ID. Nothing is sent unless you pick an app and a recipient yourself.

6. DEVICE BACKUPS
If backups are turned on in your Android settings, Android may include the app's saved data (for example name, player ID, progress, settings and, when you move to a new phone by device transfer, the photo album) in your device or Google-account backup so it can be restored on a new phone. This is controlled by your device and your Google account.

7. DELETING YOUR DATA
In the game: Settings > ACCOUNT > DELETE ALL MY DATA erases everything the game stored on your device (name, player ID, progress, settings, album). Uninstalling the game, or choosing "Clear storage" for the app in Android settings, does the same. The game keeps this data on your device only until you delete it, and the publisher cannot see or delete it for you because it is never sent to the publisher. Backups made by Android can be managed in your Google account settings.

8. CHILDREN
Bijnor 3D Battle Royal & Survival Game is intended for players aged 13 and over and is not directed to children under 13. The game contains simulated gun combat against computer-controlled characters. If you believe a child under 13 has used the game, contact vk.143.official@gmail.com.

9. YOUR RIGHTS
Because your game data stays on your device and the publisher never receives it, you can view, change and delete it yourself in the game (see section 7). For questions to the publisher, write to vk.143.official@gmail.com.

10. CHANGES TO THIS POLICY
When the app changes in a way that affects this policy (new permission, new library, new data), this page is updated and the date above changes. The version of the app this text describes is shown in section 1.

11. CONTACT
kanishkdevloper - vk.143.official@gmail.com

Online version: https://battle-royal-privacy-kanishkdevloperr.netlify.app/"""

    fun showPrivacy(act: Activity) {
        val tv = TextView(act).apply { text = TEXT; textSize = 13f; setPadding(40, 30, 40, 30) }
        val sv = ScrollView(act).apply { addView(tv) }
        val b = AlertDialog.Builder(act).setTitle("Privacy Policy").setView(sv).setPositiveButton("OK", null)
        if (PRIVACY_POLICY_URL.startsWith("https://")) {
            b.setNeutralButton("OPEN ONLINE VERSION") { _, _ ->
                try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) } catch (_: Throwable) { }
            }
        }
        b.show()
    }

    /** Support contact for complaints / help (Play Store wants a reachable contact). Address comes from tools/developer.json (same as the privacy policy). */
    const val SUPPORT_EMAIL = "vk.143.official@gmail.com"

    fun contactSupport(act: Activity) {
        AlertDialog.Builder(act).setTitle("Contact / Support")
            .setMessage("Complaints, questions or help with the game:\n\n" + SUPPORT_EMAIL + "\n\nPlease write the game name (Bijnor 3D Battle Royal & Survival Game) and what happened.")
            .setPositiveButton("SEND EMAIL") { _, _ ->
                try {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + SUPPORT_EMAIL))
                    i.putExtra(Intent.EXTRA_SUBJECT, "Bijnor 3D Battle Royal & Survival Game support")
                    act.startActivity(i)
                } catch (_: Throwable) {
                    Toast.makeText(act, "No email app found. Write to " + SUPPORT_EMAIL, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("CLOSE", null).show()
    }

    /** Wipes ALL app data (progress, name, settings, album) and closes the game - works like Android's "Clear storage". */
    fun confirmDeleteAll(act: Activity) {
        AlertDialog.Builder(act).setTitle("Delete all my data?")
            .setMessage("This erases your name, ID, coins, diamonds, outfits, settings and photos from this device. It cannot be undone.")
            .setPositiveButton("DELETE") { _, _ ->
                try {
                    val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    if (!am.clearApplicationUserData()) Toast.makeText(act, "Could not delete. Use Android Settings > Apps > Bijnor 3D Battle Royal & Survival Game > Storage > Clear storage.", Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                    Toast.makeText(act, "Use Android Settings > Apps > Bijnor 3D Battle Royal & Survival Game > Storage > Clear storage.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("CANCEL", null).show()
    }
}
