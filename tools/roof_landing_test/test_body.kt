    // ------------------------------------------------------------------ test
    fun walkReach(sx: Float, sz: Float, sy: Float, house: House, roofYIgnored: Float): Pair<Boolean, Boolean> {
        // returns (reachesInterior below the roof, reachesGround anywhere)
        val st = 0.25f
        val seen = HashSet<Long>(); val q = ArrayDeque<FloatArray>()
        fun key(x: Float, z: Float, y: Float): Long = ((Math.round(x / st).toLong() and 0xFFFFF) shl 40) or ((Math.round(z / st).toLong() and 0xFFFFF) shl 20) or (Math.round(y * 10).toLong() and 0xFFFFF)
        q.add(floatArrayOf(sx, sz, sy)); seen.add(key(sx, sz, sy))
        var interior = false; var ground = false
        var yTop = -1e9f
        for (sf in house.surf) if (sf[16].toInt() and 2 != 0) { val v = surfH(sf, (sf[0] + sf[1]) / 2, (sf[2] + sf[3]) / 2); if (v > yTop) yTop = v }
        val roofY = yTop + 1.0f
        val bx0 = house.minX + 0.7f; val bx1 = house.maxX - 0.7f; val bz0 = house.minZ + 0.7f; val bz1 = house.maxZ - 0.7f
        var cnt = 0
        while (q.isNotEmpty() && cnt < 400000) {
            val c = q.removeFirst(); cnt++
            val x = c[0]; val z = c[1]; val y = c[2]
            if (y <= house.y0 + 0.15f) ground = true
            if (x > bx0 && x < bx1 && z > bz0 && z < bz1 && y < roofY - 2.0f) interior = true
            if (interior && ground) break
            for (d in 0 until 4) {
                val nx = x + (if (d == 0) st else if (d == 1) -st else 0f); val nz = z + (if (d == 2) st else if (d == 3) -st else 0f)
                if (nx < house.minX - 6f || nx > house.maxX + 6f || nz < house.minZ - 6f || nz > house.maxZ + 6f) { ground = true; continue }
                val ny = footY(nx, nz, y)
                if (blocked(nx, nz, 0.42f, ny)) continue
                val k = key(nx, nz, ny)
                if (seen.add(k)) q.add(floatArrayOf(nx, nz, ny))
            }
        }
        return Pair(interior, ground)
    }
}

fun main(args: Array<String>) {
    val m = MapStub(); m.load(args[0])
    var totalOldInside = 0; var totalNewOnRoof = 0; var totalStuck = 0; var totalNoInterior = 0; var total = 0
    for ((hi, h) in m.houses.withIndex()) {
        var oldInside = 0; var newRoof = 0; var stuck = 0; var noInterior = 0; var n = 0; var minRoofY = 1e9f; var maxRoofY = -1e9f
        var x = h.minX - 1f
        while (x <= h.maxX + 1f) {
            var z = h.minZ - 1f
            while (z <= h.maxZ + 1f) {
                for (dt in floatArrayOf(1f / 60f, 0.12f)) {
                    // OLD behaviour: only terrain stops the fall -> footY at ground height
                    val oldY = m.footY(x, z, 2.4f)
                    val oldIn = oldY > 2.4f + 0.01f
                    // NEW behaviour
                    var y = 80f; val vy = -5.5f; var land = 0f; var hit = false
                    val p = floatArrayOf(x, 0f, z)
                    while (true) {
                        val ny = y + vy * dt
                        val roofY = m.landSurface(x, z, y, ny)
                        if (roofY > -1e8f || ny <= 2.4f) { land = if (roofY > 2.4f) roofY else 2.4f; hit = roofY > 2.4f + 0.5f; break }
                        y = ny
                    }
                    if (hit) {
                        p[0] = x; p[2] = z; val fl = m.freeLandingSpot(p, land, 0.42f)
                        val sy = m.footY(p[0], p[2], fl)
                        if (kotlin.math.abs(sy - fl) > 0.02f) println("  house ${hi + 1}: footY %.2f != landing %.2f at %.2f %.2f".format(sy, fl, p[0], p[2]))
                        if (m.blocked(p[0], p[2], 0.42f, sy)) { println("  house ${hi + 1}: start %.2f %.2f land %.2f -> p %.2f %.2f sy %.2f still blocked by ".format(x, z, land, p[0], p[2], sy) + m.boxes.filter { b -> p[0] > b.minX - 0.42f && p[0] < b.maxX + 0.42f && p[2] > b.minZ - 0.42f && p[2] < b.maxZ + 0.42f && sy < b.maxY - 0.3f && sy + 1.7f > b.minY }.joinToString { b -> "[%.2f..%.2f y %.2f..%.2f z %.2f..%.2f]".format(b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ) }) }
                        n++; newRoof++; if (oldIn) oldInside++
                        if (land < minRoofY) minRoofY = land; if (land > maxRoofY) maxRoofY = land
                        if (dt > 0.1f) continue   // BFS only once per spot
                        val (inter, gr) = m.walkReach(p[0], p[2], sy, h, maxRoofY)
                        if (!inter) noInterior++
                        if (!gr && !inter) stuck++
                    } else if (oldIn) oldInside++   // would be inside the house but the new logic did not stop on a roof (porch roof / balcony handled as hit only if > 0.5 above ground)
                }
                z += 1f
            }
            x += 1f
        }
        println("house ${hi + 1}: roof/deck landings $newRoof  (old logic: $oldInside of the sampled spots ended INSIDE the house)  roofY %.2f..%.2f   cannot reach interior: $noInterior   STUCK: $stuck".format(minRoofY, maxRoofY))
        totalOldInside += oldInside; totalNewOnRoof += newRoof; totalStuck += stuck; totalNoInterior += noInterior
    }
    println("TOTAL roof landings $totalNewOnRoof, old-inside $totalOldInside, no-interior-route $totalNoInterior, stuck $totalStuck")
}
