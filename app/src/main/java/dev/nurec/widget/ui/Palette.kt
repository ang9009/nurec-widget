package dev.nurec.widget.ui

import androidx.compose.ui.graphics.Color
import dev.nurec.widget.data.Level

/** Scoreboard palette shared by the widget and the app. */
object Palette {
    val Ground = Color(0xFF0B0C0E)
    val Card = Color(0xFF101114)
    val Tile = Color(0xFF1A1C21)
    val Track = Color(0xFF2A2D33)
    val Text = Color(0xFFF2F2F0)
    val Muted = Color(0xFFA3A8B0)
    val Body = Color(0xFFC7CBD1)
    val Lime = Color(0xFFB6F23C)
    val Amber = Color(0xFFFFB020)
    val Coral = Color(0xFFFF5A4E)
    val Offline = Color(0xFF5C6068)
    val Chip = Color(0xFF3A3D44)
    val OnLime = Color(0xFF101114)

    fun forLevel(level: Level, stale: Boolean = false): Color = when {
        stale -> Offline
        level == Level.LOW -> Lime
        level == Level.MEDIUM -> Amber
        level == Level.HIGH -> Coral
        else -> Offline
    }
}
