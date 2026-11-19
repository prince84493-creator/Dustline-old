package com.bijnor.game

import android.graphics.Path

/** Shape of the minimap window (a SQUARE with side 2 * half around cx, cy). The map itself is not part of this file. */
object MiniMapShape {
    fun path(cx: Float, cy: Float, half: Float, into: Path = Path()): Path {
        into.reset()
        into.addRect(cx - half, cy - half, cx + half, cy + half, Path.Direction.CW)
        return into
    }
}
