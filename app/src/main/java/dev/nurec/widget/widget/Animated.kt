package dev.nurec.widget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import dev.nurec.widget.R
import dev.nurec.widget.data.UiState
import dev.nurec.widget.ui.Fonts
import dev.nurec.widget.ui.Palette
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Widgets can't run animations, but RemoteViews' ViewFlipper auto-advances through child views on
 * its own (only while the widget is on screen). Both animations are pre-drawn frames in a flipper.
 */
object Animated {

    private const val PULSE_FRAMES = 16 // x 125 ms = 2 s, matching the mockup's CSS pulse

    /** Pulsing LIVE dot: solid dot with a lime halo that grows and fades (box-shadow 0→7px). */
    fun pulse(context: Context, dotDp: Float, boxDp: Float): RemoteViews {
        val frames = pulseFrames(context, dotDp, boxDp)
        return flipper(context, R.layout.flipper_pulse, frames)
    }

    /**
     * Refresh button during cooldown: one frame per remaining second ("0:15" → "0:01") with the
     * ring draining, then the idle button. The widget is redrawn when the cooldown ends; the
     * trailing idle frames cover any delay so it never wraps back to "0:15".
     */
    fun countdown(context: Context, left: Duration, sizeDp: Float, cornerDp: Float): RemoteViews {
        val total = UiState.COOLDOWN.seconds.toFloat()
        val secs = ceil(left.toMillis() / 1000.0).toInt().coerceIn(1, total.toInt())
        val frames = ArrayList<Bitmap>(secs + IDLE_PAD)
        for (s in secs downTo 1) frames += countdownFrame(context, s, s / total, sizeDp, cornerDp)
        val idle = idleFrame(context, sizeDp, cornerDp)
        repeat(IDLE_PAD) { frames += idle } // same Bitmap object: RemoteViews sends it once
        return flipper(context, R.layout.flipper_countdown, frames)
    }

    private const val IDLE_PAD = 120

    private fun flipper(context: Context, layout: Int, frames: List<Bitmap>): RemoteViews {
        val rv = RemoteViews(context.packageName, layout)
        frames.forEach { bmp ->
            val f = RemoteViews(context.packageName, R.layout.flipper_frame)
            f.setImageViewBitmap(R.id.frame, bmp)
            rv.addView(R.id.flipper, f)
        }
        return rv
    }

    private val pulseCache = HashMap<String, List<Bitmap>>()

    private fun pulseFrames(context: Context, dotDp: Float, boxDp: Float): List<Bitmap> {
        val d = context.resources.displayMetrics.density
        val key = "$dotDp/$boxDp/$d"
        return synchronized(pulseCache) {
            pulseCache.getOrPut(key) {
                val px = (boxDp * d).roundToInt()
                val c = px / 2f
                val r = dotDp * d / 2f
                val maxSpread = (boxDp - dotDp) / 2f * d
                (0 until PULSE_FRAMES).map { i ->
                    val t = i / PULSE_FRAMES.toFloat()
                    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    if (t < 0.7f) {
                        val k = t / 0.7f
                        paint.color = Palette.Lime.copy(alpha = 0.55f * (1 - k)).argb()
                        canvas.drawCircle(c, c, r + maxSpread * k, paint)
                    }
                    paint.color = Palette.Lime.argb()
                    canvas.drawCircle(c, c, r, paint)
                    bmp
                }
            }
        }
    }

    private fun countdownFrame(context: Context, secs: Int, fraction: Float, sizeDp: Float, cornerDp: Float): Bitmap {
        val d = context.resources.displayMetrics.density
        val px = (sizeDp * d).roundToInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = 2.5f * d
        val ringInset = stroke / 2f + 0.5f
        val ring = RectF(ringInset, ringInset, px - ringInset, px - ringInset)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        p.color = Palette.Track.argb()
        canvas.drawArc(ring, 0f, 360f, false, p)
        p.color = Palette.Lime.argb()
        canvas.drawArc(ring, -90f, 360f * fraction, false, p)

        val inset = 4f * d
        p.style = Paint.Style.FILL
        p.color = Palette.Tile.argb()
        canvas.drawRoundRect(RectF(inset, inset, px - inset, px - inset), cornerDp * d, cornerDp * d, p)

        val text = "0:%02d".format(secs)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.typeface(context, Fonts.Face.DISPLAY_SEMIBOLD)
            textSize = (if (sizeDp > 40) 15f else 13f) * d
            color = Palette.Muted.argb()
            textAlign = Paint.Align.CENTER
        }
        val fm = tp.fontMetrics
        canvas.drawText(text, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, tp)
        return bmp
    }

    private fun idleFrame(context: Context, sizeDp: Float, cornerDp: Float): Bitmap {
        val d = context.resources.displayMetrics.density
        val px = (sizeDp * d).roundToInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inset = 4f * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.Lime.argb() }
        canvas.drawRoundRect(RectF(inset, inset, px - inset, px - inset), cornerDp * d, cornerDp * d, p)
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_refresh)?.mutate()
        if (icon != null) {
            val iconPx = ((if (sizeDp > 40) 20f else 16f) * d).roundToInt()
            val l = (px - iconPx) / 2
            icon.setBounds(l, l, l + iconPx, l + iconPx)
            icon.setTint(Palette.OnLime.argb())
            icon.draw(canvas)
        }
        return bmp
    }

    private fun Color.argb(): Int = android.graphics.Color.argb(
        (alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(),
    )
}
