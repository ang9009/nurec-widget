package dev.nurec.widget.ui

import androidx.compose.ui.graphics.Color
import dev.nurec.widget.data.Level

/**
 * Dark theme in Northeastern colors (from northeastern.edu's theme: nu-red #C8102E,
 * nu-red-bright #FF3347, dark #120D0E, neutral greys). Unofficial; no university marks used.
 */
object Palette {
    val Ground = Color(0xFF0C0809)
    val Card = Color(0xFF120D0E)
    val Tile = Color(0xFF1D1718)
    val Track = Color(0xFF2F2627)
    val Text = Color(0xFFFAFAFA)
    val Muted = Color(0xFFA3A3A3)
    val Body = Color(0xFFD4D4D4)
    val Tag = Color(0xFF8A8384)
    val Red = Color(0xFFC8102E)
    val RedBright = Color(0xFFFF3347)
    val RedLight = Color(0xFFFF6B7A)
    /** 18 % NU red over Card, for the LIVE pill (widgets can't do alpha backgrounds reliably). */
    val LivePill = Color(0xFF330E14)
    val Gold = Color(0xFFF2A900)
    val Low = Color(0xFFF5F5F5)
    val Offline = Color(0xFF5C5555)
    val Chip = Color(0xFF3A3132)

    fun forLevel(level: Level, stale: Boolean = false): Color = when {
        stale -> Offline
        level == Level.LOW -> Low
        level == Level.MEDIUM -> Gold
        level == Level.HIGH -> RedBright
        else -> Offline
    }
}
