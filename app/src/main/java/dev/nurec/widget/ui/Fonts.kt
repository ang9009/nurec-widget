package dev.nurec.widget.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.FontRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import dev.nurec.widget.R
import kotlin.math.ceil

/** Bundled fonts from the mockup (both SIL OFL 1.1, see /licenses). */
object Fonts {
    val Archivo = FontFamily(
        Font(R.font.archivo_regular, FontWeight.Normal),
        Font(R.font.archivo_medium, FontWeight.Medium),
        Font(R.font.archivo_semibold, FontWeight.SemiBold),
        Font(R.font.archivo_bold, FontWeight.Bold),
    )
    val Barlow = FontFamily(
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )

    /** Font choices for widget text, which is drawn to bitmaps (Glance can't load custom fonts). */
    enum class Face(@FontRes val res: Int) {
        BODY(R.font.archivo_regular),
        BODY_MEDIUM(R.font.archivo_medium),
        BODY_SEMIBOLD(R.font.archivo_semibold),
        BODY_BOLD(R.font.archivo_bold),
        DISPLAY(R.font.barlow_condensed_bold),
        DISPLAY_SEMIBOLD(R.font.barlow_condensed_semibold),
    }

    private val cache = HashMap<Face, Typeface>()

    fun typeface(context: Context, face: Face): Typeface = synchronized(cache) {
        cache.getOrPut(face) {
            runCatching { ResourcesCompat.getFont(context, face.res) }.getOrNull() ?: Typeface.DEFAULT
        }
    }
}

/** A run of text in one face/size/color; several runs share a baseline (e.g. "17" + small "%"). */
data class TextRun(
    val text: String,
    val face: Fonts.Face,
    val sizeDp: Float,
    val color: Color,
    /** In em, like CSS letter-spacing. */
    val tracking: Float = 0f,
)

class RenderedText(val bitmap: Bitmap, val widthDp: Float, val heightDp: Float)

/**
 * Draws text into a tight bitmap so the widget can show the bundled fonts.
 * Sizes are in dp so the layout matches the mockup; text longer than [maxWidthDp] is ellipsized.
 */
object TextBitmap {
    fun render(context: Context, runs: List<TextRun>, maxWidthDp: Float? = null): RenderedText {
        val density = context.resources.displayMetrics.density
        val paints = runs.map { run ->
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Fonts.typeface(context, run.face)
                textSize = run.sizeDp * density
                color = run.color.toArgb()
                letterSpacing = run.tracking
            }
        }
        // Ellipsize only single-run text (labels); multi-run text is short numerals.
        val texts = runs.mapIndexed { i, run ->
            if (maxWidthDp != null && runs.size == 1) {
                TextUtils.ellipsize(run.text, paints[i], maxWidthDp * density, TextUtils.TruncateAt.END).toString()
            } else run.text
        }
        val widths = texts.mapIndexed { i, t -> paints[i].measureText(t) }
        val ascent = paints.maxOf { -it.fontMetrics.ascent }
        val descent = paints.maxOf { it.fontMetrics.descent }
        val w = ceil(widths.sum()).toInt().coerceAtLeast(1)
        val h = ceil(ascent + descent).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        var x = 0f
        texts.forEachIndexed { i, t ->
            canvas.drawText(t, x, ascent, paints[i])
            x += widths[i]
        }
        return RenderedText(bmp, w / density, h / density)
    }

    private fun Color.toArgb(): Int = android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
}
