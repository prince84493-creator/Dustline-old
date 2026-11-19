# Roof landing test (v24)

Takes the REAL function bodies (landSurface, freeLandingSpot, houseEscape, surfH, footY) out of MapData.kt, wraps them in a tiny stub,
loads the REAL houses.bin, drops a parachutist onto every 1 m of every house (two frame rates) and then walks him with the game's rules
(footY + collision boxes). It prints, per house: how many spots end up on the roof, how many of those the OLD (terrain-only) landing
would have dropped INSIDE the house, whether the roof stair leads to the inside, and STUCK (= cannot get down at all).

    python3 gen.py ../../app/src/main/java/com/bijnor/game/MapData.kt        # writes Harness.kt
    sed -i 's/    private fun surfH(/    fun surfH(/' Harness.kt
    kotlinc Harness.kt -d out && kotlin -cp out HarnessKt ../../app/src/main/assets/models/houses/houses.bin

Result at v24: TOTAL roof landings 2006, old-inside 1492, no-interior-route 0, stuck 0.
