package dev.nurec.widget.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

/** Glance can't draw arcs, so ring gauges are rendered to a bitmap. */
object RingBitmap {
    fun draw(sizePx: Int, strokePx: Float, fraction: Float, color: Color, track: Color = Palette.Track): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inset = strokePx / 2f + 1f
        val oval = RectF(inset, inset, sizePx - inset, sizePx - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
        }
        paint.color = track.toArgb()
        canvas.drawArc(oval, 0f, 360f, false, paint)
        val sweep = 360f * fraction.coerceIn(0.02f, 1f)
        paint.color = color.toArgb()
        canvas.drawArc(oval, -90f, sweep, false, paint)
        return bmp
    }

    private fun Color.toArgb(): Int = android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
}
