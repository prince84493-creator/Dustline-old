package com.bijnor.game

/**
 * Loot pick-up + gun-change animation curves (v33).
 *
 * PICK-UP (u = 0..1 over the whole animation): the player bends down, the item glides to the grab spot just in front of the feet,
 * the right hand closes round it, lifts it, carries it over the shoulder to the backpack and lets go - the item is added to the bag
 * exactly at [COMMIT]. All hand positions are in PLAYER space (right, up, forward) in metres of the 0.75 scaled world, so the first-person
 * view, the third-person body and the flying item all use the very same path.
 *
 * GUN CHANGE (p = 0..1): the old gun drops out of the view for the first [SWAP_OUT] of the time, the new one swings up for the rest.
 */
object PickupAnim {
    const val GLIDE0 = 0.08f      // the item starts to slide towards the hand
    const val GRAB = 0.36f        // fingers close round the item (it is on the ground in front of the feet)
    const val LIFT = 0.58f        // item lifted in front of the chest
    const val COMMIT = 0.80f      // hand is at the backpack: item goes into the bag (collect())
    const val SWAP_OUT = 0.40f    // share of the gun change spent lowering the old gun
    const val SWAP_DUR = 0.60f    // seconds of a gun change

    private fun sm(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** how far the body is bent over, 0..1 */
    fun bend(u: Float): Float = sm(0f, 0.30f, u) * (1f - sm(0.52f, 0.86f, u))
    /** right arm reaching for the ground, 0..1 */
    fun reach(u: Float): Float = sm(0.04f, 0.34f, u) * (1f - sm(0.42f, 0.62f, u))
    /** right hand carrying the item to the backpack, 0..1 */
    fun bag(u: Float): Float = sm(0.52f, COMMIT, u) * (1f - sm(0.84f, 0.98f, u))
    /** 1 while the fingers are closed round the item */
    fun grip(u: Float): Float = sm(GRAB - 0.04f, GRAB + 0.03f, u) * (1f - sm(COMMIT - 0.02f, COMMIT + 0.04f, u))

    // right-hand key positions, player space (right, up, forward)
    private val K = arrayOf(
        floatArrayOf(0.00f, 0.10f, 0.80f, 0.32f), floatArrayOf(0.34f, 0.05f, 0.12f, 0.52f), floatArrayOf(0.42f, 0.05f, 0.12f, 0.52f),
        floatArrayOf(0.58f, 0.18f, 0.72f, 0.34f), floatArrayOf(0.74f, 0.26f, 0.95f, -0.22f), floatArrayOf(0.84f, 0.26f, 0.95f, -0.22f),
        floatArrayOf(1.00f, 0.10f, 0.80f, 0.32f))

    /** right hand at progress u (player space) -> o[0..2] = right, up, forward */
    fun hand(u0: Float, o: FloatArray) {
        val u = u0.coerceIn(0f, 1f)
        var i = 0; while (i < K.size - 2 && u > K[i + 1][0]) i++
        val a = K[i]; val b = K[i + 1]; val t = sm(a[0], b[0], u)
        o[0] = a[1] + (b[1] - a[1]) * t; o[1] = a[2] + (b[2] - a[2]) * t; o[2] = a[3] + (b[3] - a[3]) * t
    }

    /** 0 = gun fully up, 1 = gun fully lowered, for gun-change progress p (0..1) */
    fun swapDrop(p: Float): Float {
        if (p <= 0f || p >= 1f) return 0f
        return if (p < SWAP_OUT) { val t = p / SWAP_OUT; t * t * (3f - 2f * t) }
        else { val t = (p - SWAP_OUT) / (1f - SWAP_OUT); val e = 1f - (1f - t) * (1f - t) * (1f - t); 1f - e }
    }
}
