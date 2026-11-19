package com.bijnor.game

/**
 * v29 REALISM: the numbers behind fall damage, gun noise and gun sound.
 *
 * Fall damage   - the floors of the Bijnor houses are 3.2 m apart (1st floor 3.2 m, 2nd 6.8 m, roofs 10-16 m). Up to 2 m the knees absorb the landing,
 *                 above that every metre hurts more than the one before: 3.2 m = 18 HP, 5 m = 53, 6.8 m = 95, 10 m = 189, from ~10.5 m it kills (200 HP).
 *                 Armour does not help against a fall. Water breaks the fall (deep = no damage, wading = 40 %).
 * Gun noise     - how far a shot carries in the open air (m). Bots inside that range (walls cut it by 40 % each) turn toward the sound, and the same
 *                 radius x 1.6 is how far the player can still hear it.
 */
object Realism {
    /** drop (m) that costs nothing */
    const val FALL_SAFE = 2.0f
    /** the floor under the feet is this much lower (m) = you stepped off an edge and now FALL (stairs and slopes are far below this) */
    const val FALL_START = 0.7f

    // ---- v31 BLEEDING: a bullet / grenade hit that gets through the armour can open a wound. HP drains until a bandage or a medkit is used.
    /** worst bleed rate (HP per second) with several wounds */
    const val BLEED_MAX = 4.0f
    /** bandage: seconds to wrap it (you walk slowly meanwhile, a new hit interrupts), HP it gives, how many fit in the bag */
    const val BANDAGE_TIME = 2.5f
    const val BANDAGE_HP = 15f
    const val BANDAGE_MAX = 10
    /** HP actually lost (after armour) below this never opens a wound */
    const val BLEED_MIN_HIT = 4f
    /** chance that a hit of [hpLost] HP opens a wound: a graze rarely, a heavy hit nearly always */
    fun bleedChance(hpLost: Float): Float = (0.30f + hpLost / 70f).coerceIn(0.30f, 0.95f)
    /** HP/s that this wound adds to the bleed rate (wounds add up until BLEED_MAX) */
    fun bleedAdd(hpLost: Float): Float = 0.8f + (hpLost / 60f).coerceAtMost(1.2f)

    fun fallDamage(h: Float): Float {
        val e = h - FALL_SAFE
        return if (e <= 0f) 0f else 14f * e + 1.2f * e * e
    }

    /** radius (m, open air) that a shot of this gun carries */
    fun gunRadius(g: Gun): Float = when {
        g.id == "vss" -> 26f          // suppressed
        g.cls == "amr" -> 230f
        g.cls == "sniper" -> 180f
        g.cls == "dmr" -> 140f
        g.cls == "shotgun" -> 100f
        else -> 75f                   // smg
    }

    /** name of the shot sample of this gun (SoundFx) */
    fun shotSound(g: Gun): String = if (g.id == "vss") "shotS" else if (g.cls == "shotgun") "shotG" else if (g.pistol) "shot1" else "shot" + minOf(g.slot, 2)
}
