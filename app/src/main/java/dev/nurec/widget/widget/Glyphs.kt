package dev.nurec.widget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import dev.nurec.widget.R
import kotlin.math.ceil

/**
 * Widget text in the bundled fonts. Launchers ignore custom fonts in widgets (Glance's Text and
 * even android:fontFamily in RemoteViews layouts), so text is drawn into an ALPHA_8 bitmap — a
 * 1-byte-per-pixel mask — and colored with a tint. That keeps the whole widget update small
 * (widget updates are capped; big bitmaps get the update dropped).
 */
object Glyphs {
    enum class Face(@FontRes val res: Int) {
        REGULAR(R.font.lato_regular),
        BOLD(R.font.lato_bold),
        BLACK(R.font.lato_black),
    }

    class Mask(val bitmap: Bitmap, val widthDp: Float, val heightDp: Float)

    private val faces = HashMap<Face, Typeface>()

    private fun typeface(context: Context, face: Face): Typeface = synchronized(faces) {
        faces.getOrPut(face) {
            runCatching { ResourcesCompat.getFont(context, face.res) }.getOrNull() ?: Typeface.DEFAULT
        }
    }

    private fun paint(context: Context, face: Face, sizeDp: Float, tracking: Float): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(context, face)
            textSize = sizeDp * context.resources.displayMetrics.density
            letterSpacing = tracking
        }

    /**
     * @param maxWidthDp ellipsize to this width.
     * @param lineFace/lineSizeDp use another font's line metrics, so two masks placed side by
     *   side with top alignment share a baseline (e.g. "17" and a smaller "%").
     */
    fun render(
        context: Context,
        text: String,
        face: Face,
        sizeDp: Float,
        tracking: Float = 0f,
        maxWidthDp: Float? = null,
        lineFace: Face = face,
        lineSizeDp: Float = sizeDp,
        tight: Boolean = false,
    ): Mask {
        val density = context.resources.displayMetrics.density
        val p = paint(context, face, sizeDp, tracking)
        val line = if (lineFace == face && lineSizeDp == sizeDp) p else paint(context, lineFace, lineSizeDp, 0f)
        val shown = if (maxWidthDp != null) {
            TextUtils.ellipsize(text, p, maxWidthDp * density, TextUtils.TruncateAt.END).toString()
        } else text
        // tight: the mask spans exactly cap top → baseline, so blocks can be aligned to the
        // visible glyphs (digits and capitals) instead of the font's padded line box.
        val ascent = if (tight) capHeightPx(line) else -line.fontMetrics.ascent
        val descent = if (tight) 0f else line.fontMetrics.descent
        val w = ceil(p.measureText(shown)).toInt().coerceAtLeast(1)
        val h = ceil(ascent + descent).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(bmp).drawText(shown, 0f, ascent, p)
        return Mask(bmp, w / density, h / density)
    }

    /** Width in dp of [text] at [sizeDp]. */
    fun widthDp(context: Context, text: String, face: Face, sizeDp: Float, tracking: Float = 0f): Float =
        paint(context, face, sizeDp, tracking).measureText(text) / context.resources.displayMetrics.density

    /** Largest size in [minDp]..[maxDp] (0.5 dp steps) at which every text fits [widthDp]. */
    fun fitSize(
        context: Context, texts: List<String>, face: Face, maxDp: Float, minDp: Float, widthDp: Float, tracking: Float = 0f,
    ): Float {
        var size = maxDp
        while (size > minDp && texts.any { widthDp(context, it, face, size, tracking) > widthDp }) size -= 0.5f
        return size
    }

    private fun capHeightPx(p: Paint): Float {
        val r = android.graphics.Rect()
        p.getTextBounds("H", 0, 1, r)
        return r.height().toFloat()
    }

    /** Height in dp of capital letters (and lining digits) at [sizeDp]. */
    fun capDp(context: Context, face: Face, sizeDp: Float): Float =
        capHeightPx(paint(context, face, sizeDp, 0f)) / context.resources.displayMetrics.density

    /**
     * Text centered (by cap height) in a [boxDp]×[boxDp] mask. Shown with fitCenter, the view
     * scales the whole box, so the text keeps its size relative to the box on any launcher.
     */
    fun renderInBox(context: Context, text: String, face: Face, sizeDp: Float, boxDp: Float): Mask {
        val density = context.resources.displayMetrics.density
        val p = paint(context, face, sizeDp, 0f).apply { textAlign = Paint.Align.CENTER }
        val px = ceil(boxDp * density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ALPHA_8)
        Canvas(bmp).drawText(text, px / 2f, px / 2f + capHeightPx(p) / 2f, p)
        return Mask(bmp, boxDp, boxDp)
    }
}
