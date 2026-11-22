package dev.nurec.widget.widget

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import dev.nurec.widget.R
import dev.nurec.widget.data.RecRepository
import dev.nurec.widget.data.Room
import dev.nurec.widget.data.UiState
import dev.nurec.widget.data.levelFor
import dev.nurec.widget.ui.Palette
import dev.nurec.widget.work.RefreshWorker
import kotlin.math.roundToInt

/** Below this width the widget switches to the compact rows layout. */
private val COMPACT_BELOW = 250.dp

class RecWidget : GlanceAppWidget() {
    // Exact: lay out for the real size (resizing re-renders), and only one layout is sent.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read-only: never fetch here. Fetching happens in RefreshWorker.
        val repo = RecRepository(context)
        val initial = repo.current()
        provideContent {
            val state by repo.state.collectAsState(initial)
            WidgetBody(state)
        }
    }
}

class RecWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshWorker.cancelAll(context)
    }
}

/** Tap-to-refresh. Respects the shared cooldown so repeated taps never hit the API. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (RecRepository(context).tryStartAttempt()) {
            RefreshWorker.enqueueManual(context)
        }
        RecWidget().updateAll(context)
    }
}

// ---- Text in the bundled fonts --------------------------------------------------------------
// Glance's Text only knows system fonts, so text is a plain TextView from an XML layout that sets
// android:fontFamily="@font/…". No bitmaps, so the update stays tiny.

private enum class Face(@LayoutRes val layout: Int) {
    DISPLAY(R.layout.text_display),
    DISPLAY_SEMIBOLD(R.layout.text_display_semibold),
    TITLE(R.layout.text_title),
    BODY(R.layout.text_body),
    BODY_SEMIBOLD(R.layout.text_body_semibold),
    TAG(R.layout.text_tag),
}

@Composable
private fun WText(
    text: CharSequence,
    face: Face,
    sizeDp: Float,
    color: Color,
    modifier: GlanceModifier = GlanceModifier,
    center: Boolean = false,
) {
    val context = LocalContext.current
    val rv = RemoteViews(context.packageName, face.layout).apply {
        setTextViewText(R.id.text, text)
        setTextViewTextSize(R.id.text, TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        setTextColor(R.id.text, color.toArgb())
        if (center) setInt(R.id.text, "setGravity", Gravity.CENTER_HORIZONTAL)
    }
    AndroidRemoteViews(remoteViews = rv, modifier = modifier)
}

// ---- Layout ---------------------------------------------------------------------------------

@Composable
private fun WidgetBody(state: UiState) {
    val size = LocalSize.current
    val stale = state.isStale()
    if (size.width < COMPACT_BELOW) Compact(state, stale, size.height) else Wide(state, stale, size.width, size.height)
}

/** 4×2 and up: one header line, then a ring tile per room. Ring size follows the height. */
@Composable
private fun Wide(state: UiState, stale: Boolean, width: Dp, height: Dp) {
    val pad = 12.dp
    val header = 36.dp
    val gap = 8.dp
    val rooms = state.snapshot?.rooms.orEmpty()
    // Tile = 8 pad + ring + 4 + name 14 + 1 + count 12 + 8 pad
    val tileExtra = 47.dp
    val tileW = (width - pad * 2 - gap * 2) / 3
    val ring = minOf(height - pad * 2 - header - gap - tileExtra, tileW - 16.dp, 72.dp).coerceAtLeast(28.dp)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Palette.Card).cornerRadius(24.dp).padding(pad),
    ) {
        Row(GlanceModifier.fillMaxWidth().height(header), verticalAlignment = Alignment.CenterVertically) {
            WText("NU REC", Face.TITLE, 20f, Palette.Text)
            Status(state, stale, wide = true)
            Spacer(GlanceModifier.defaultWeight())
            WText(state.updatedText(), Face.BODY, 11f, Palette.Muted)
            Spacer(GlanceModifier.width(8.dp))
            RefreshButton(state, 36.dp)
        }
        Spacer(GlanceModifier.height(gap))
        if (rooms.isEmpty()) {
            EmptyBody()
        } else Row(GlanceModifier.fillMaxSize()) {
            rooms.forEachIndexed { i, room ->
                if (i > 0) Spacer(GlanceModifier.width(gap))
                Tile(room, stale, ring, GlanceModifier.defaultWeight().fillMaxHeight())
            }
        }
    }
}

/** 2×2: header, then busiest-first rows that share the remaining height. */
@Composable
private fun Compact(state: UiState, stale: Boolean, height: Dp) {
    val pad = 10.dp
    val header = 30.dp
    val rows = state.snapshot?.busiest(3).orEmpty()
    val rowGap = 4.dp
    val rowH = ((height - pad * 2 - header - 6.dp - rowGap * 2) / 3).coerceIn(20.dp, 40.dp)
    val numDp = (rowH.value * 0.8f).coerceAtMost(24f)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Palette.Card).cornerRadius(22.dp).padding(pad),
    ) {
        Row(GlanceModifier.fillMaxWidth().height(header), verticalAlignment = Alignment.CenterVertically) {
            WText("NU REC", Face.TITLE, 16f, Palette.Text)
            Status(state, stale, wide = false)
            Spacer(GlanceModifier.defaultWeight())
            RefreshButton(state, 30.dp)
        }
        Spacer(GlanceModifier.height(6.dp))
        if (rows.isEmpty()) EmptyBody()
        rows.forEachIndexed { i, room ->
            if (i > 0) Spacer(GlanceModifier.height(rowGap))
            CompactRow(room, stale, rowH, numDp)
        }
    }
}

/** Pulsing dot (+ "LIVE" when wide), or a grey OFFLINE / NO DATA chip. */
@Composable
private fun Status(state: UiState, stale: Boolean, wide: Boolean) {
    val context = LocalContext.current
    val off = when {
        state.snapshot == null -> "NO DATA"
        stale -> "OFFLINE"
        else -> null
    }
    if (off != null) {
        Spacer(GlanceModifier.width(6.dp))
        Box(GlanceModifier.background(Palette.Chip).cornerRadius(8.dp).padding(horizontal = 6.dp, vertical = 2.dp)) {
            WText(off, Face.TAG, if (wide) 9.5f else 8.5f, Palette.Text)
        }
        return
    }
    // The pulse box includes room for the halo (mockup: 8px dot, 7px spread).
    AndroidRemoteViews(remoteViews = Animated.pulse(context), modifier = GlanceModifier.size(if (wide) 20.dp else 16.dp))
    if (wide) WText("LIVE", Face.TAG, 10f, Palette.Lime)
}

@Composable
private fun RefreshButton(state: UiState, size: Dp) {
    val context = LocalContext.current
    val left = state.cooldownRemaining()
    Box(
        modifier = GlanceModifier.size(size).clickable(actionRunCallback<RefreshAction>()),
        contentAlignment = Alignment.Center,
    ) {
        if (!left.isZero) {
            // Ticks "0:15" → "0:01" with a draining ring, then shows the idle button.
            AndroidRemoteViews(
                remoteViews = Animated.countdown(context, left, if (size >= 36.dp) 14f else 12f),
                modifier = GlanceModifier.size(size),
            )
        } else {
            Box(
                modifier = GlanceModifier.size(size - 8.dp).background(Palette.Lime).cornerRadius(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Refresh now",
                    colorFilter = ColorFilter.tint(ColorProvider(Palette.OnLime)),
                    modifier = GlanceModifier.size(if (size >= 36.dp) 16.dp else 14.dp),
                )
            }
        }
    }
}

@Composable
private fun Tile(room: Room, stale: Boolean, ring: Dp, modifier: GlanceModifier) {
    val context = LocalContext.current
    val pct = room.percent
    val color = Palette.forLevel(levelFor(pct, room.closed), stale)
    Column(
        modifier = modifier.background(Palette.Tile).cornerRadius(16.dp).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(ring), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ring_track), contentDescription = null, modifier = GlanceModifier.size(ring))
            Image(
                provider = ImageProvider(Animated.ring(context, (pct ?: 0.0).roundToInt())),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorProvider(color)),
                modifier = GlanceModifier.size(ring),
            )
            WText(percentText(room, ring.value * 0.36f), Face.DISPLAY, ring.value * 0.36f, Palette.Text)
        }
        Spacer(GlanceModifier.height(4.dp))
        WText(room.shortLabel, Face.BODY_SEMIBOLD, 12f, Palette.Text, GlanceModifier.fillMaxWidth(), center = true)
        Spacer(GlanceModifier.height(1.dp))
        WText(countText(room), Face.BODY, 10.5f, Palette.Muted, GlanceModifier.fillMaxWidth(), center = true)
    }
}

@Composable
private fun CompactRow(room: Room, stale: Boolean, rowH: Dp, numDp: Float) {
    val color = Palette.forLevel(levelFor(room.percent, room.closed), stale)
    Row(GlanceModifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.width(38.dp)) {
            WText(room.percent?.takeIf { !room.closed }?.let { "${it.roundToInt()}%" } ?: if (room.closed) "—" else "?", Face.DISPLAY, numDp, color)
        }
        Column(GlanceModifier.defaultWeight()) {
            WText(room.shortLabel, Face.BODY_SEMIBOLD, 11f, Palette.Text, GlanceModifier.fillMaxWidth())
            Spacer(GlanceModifier.height(3.dp))
            LinearProgressIndicator(
                progress = ((room.percent ?: 0.0) / 100).toFloat().coerceIn(0.02f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(3.dp),
                color = ColorProvider(color),
                backgroundColor = ColorProvider(Palette.Track),
            )
        }
    }
}

@Composable
private fun EmptyBody() {
    Box(
        modifier = GlanceModifier.fillMaxSize().background(Palette.Tile).cornerRadius(16.dp)
            .clickable(actionRunCallback<RefreshAction>()),
        contentAlignment = Alignment.Center,
    ) {
        WText("Tap refresh to load occupancy", Face.BODY, 12f, Palette.Muted, center = true)
    }
}

/** "17" with a half-size "%" (muted in tiles, as in the mockup). */
private fun percentText(room: Room, sizeDp: Float): CharSequence {
    val pct = room.percent
    return when {
        room.closed -> "—"
        pct == null -> "?"
        else -> SpannableStringBuilder("${pct.roundToInt()}").apply {
            val start = length
            append("%")
            setSpan(AbsoluteSizeSpan((sizeDp * 0.5f).roundToInt(), true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Palette.Muted.toArgb()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

private fun countText(room: Room): String = when {
    room.closed -> "Closed"
    room.count != null && room.capacity != null -> "${room.count} of ${room.capacity}"
    room.count != null -> "${room.count} people"
    else -> "No count"
}
