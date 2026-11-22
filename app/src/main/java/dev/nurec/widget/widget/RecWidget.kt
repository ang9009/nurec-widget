package dev.nurec.widget.widget

import android.content.Context
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
import dev.nurec.widget.data.Facility
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

// ---- Text in the bundled fonts (drawn as tinted masks, see Glyphs) -------------------------

private typealias Face = Glyphs.Face

@Composable
private fun WText(
    text: String,
    face: Face,
    sizeDp: Float,
    color: Color,
    maxWidth: Dp? = null,
    tracking: Float = 0f,
    description: String? = text,
) {
    val context = LocalContext.current
    val m = Glyphs.render(context, text, face, sizeDp, tracking, maxWidth?.value)
    Image(
        provider = ImageProvider(m.bitmap),
        contentDescription = description,
        colorFilter = ColorFilter.tint(ColorProvider(color)),
        modifier = GlanceModifier.size(m.widthDp.dp, m.heightDp.dp),
    )
}

/** "17" with a half-size "%" on the same baseline. */
@Composable
private fun PercentText(room: Room, sizeDp: Float, color: Color, pctColor: Color, smallPct: Boolean) {
    val context = LocalContext.current
    val pct = room.percent
    when {
        room.closed -> WText("—", Face.DISPLAY, sizeDp, color, description = "Closed")
        pct == null -> WText("?", Face.DISPLAY, sizeDp, color, description = "Unknown")
        else -> Row(verticalAlignment = Alignment.Top) {
            val n = Glyphs.render(context, "${pct.roundToInt()}", Face.DISPLAY, sizeDp)
            val p = Glyphs.render(
                context, "%", if (smallPct) Face.DISPLAY_SEMIBOLD else Face.DISPLAY,
                if (smallPct) sizeDp * 0.5f else sizeDp, lineFace = Face.DISPLAY, lineSizeDp = sizeDp,
            )
            Image(
                ImageProvider(n.bitmap), "${pct.roundToInt()} percent full",
                GlanceModifier.size(n.widthDp.dp, n.heightDp.dp), colorFilter = ColorFilter.tint(ColorProvider(color)),
            )
            Image(
                ImageProvider(p.bitmap), null,
                GlanceModifier.size(p.widthDp.dp, p.heightDp.dp), colorFilter = ColorFilter.tint(ColorProvider(pctColor)),
            )
        }
    }
}

// ---- Layout ---------------------------------------------------------------------------------

@Composable
private fun WidgetBody(state: UiState) {
    val size = LocalSize.current
    val stale = state.isStale()
    if (size.width < COMPACT_BELOW) Compact(state, stale, size.width, size.height) else Wide(state, stale, size.width, size.height)
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
    val tagExtra = 16.dp // facility label under each card (11) + gap (5)
    val tileW = (width - pad * 2 - gap * 2) / 3
    fun ringFor(extra: Dp) = minOf(height - pad * 2 - header - gap - extra, tileW - 16.dp, 72.dp)
    // Show the facility label only if the ring can stay a readable size.
    val showTag = ringFor(tileExtra + tagExtra) >= 44.dp
    val ring = ringFor(if (showTag) tileExtra + tagExtra else tileExtra).coerceAtLeast(28.dp)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Palette.Card).cornerRadius(24.dp).padding(pad),
    ) {
        Row(GlanceModifier.fillMaxWidth().height(header), verticalAlignment = Alignment.CenterVertically) {
            // Measure the header; shorten "Updated 11:02" → "11:02" (then drop "LIVE") if it won't fit.
            val context = LocalContext.current
            val avail = width - pad * 2 - 36.dp - 8.dp - 8.dp
            val title = Glyphs.widthDp(context, "NU REC", Face.DISPLAY, 21f, 0.06f)
            val live = 25f + Glyphs.widthDp(context, "LIVE", Face.BODY_BOLD, 10f, 0.14f)
            val full = state.updatedText()
            val short = full.substringAfterLast(' ')
            val fullW = Glyphs.widthDp(context, full, Face.BODY, 11f)
            val shortW = Glyphs.widthDp(context, short, Face.BODY, 11f)
            val (updated, showLive) = when {
                title + live + fullW <= avail.value -> full to true
                title + live + shortW <= avail.value -> short to true
                else -> short to false
            }
            WText("NU REC", Face.DISPLAY, 21f, Palette.Text, tracking = 0.06f)
            Status(state, stale, wide = showLive)
            Spacer(GlanceModifier.defaultWeight())
            WText(updated, Face.BODY, 11f, Palette.Muted, description = full)
            Spacer(GlanceModifier.width(8.dp))
            RefreshButton(state, 36.dp)
        }
        Spacer(GlanceModifier.height(gap))
        if (rooms.isEmpty()) {
            EmptyBody()
        } else Row(GlanceModifier.fillMaxSize()) {
            val context = LocalContext.current
            val textW = (tileW - 6.dp).value
            val nameDp = Glyphs.fitSize(context, rooms.map { tileName(it, showTag) }, Face.BODY_SEMIBOLD, 12f, 9f, textW)
            val tagDp = Glyphs.fitSize(context, rooms.map { it.facility.tileTag() }, Face.BODY_SEMIBOLD, 9.5f, 7f, textW + 6f, tracking = 0.14f)
            val countDp = Glyphs.fitSize(context, rooms.map(::countText), Face.BODY, 10.5f, 8.5f, textW)
            rooms.forEachIndexed { i, room ->
                if (i > 0) Spacer(GlanceModifier.width(gap))
                Column(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Tile(room, stale, ring, textW.dp, nameDp, countDp, showTag, GlanceModifier.fillMaxWidth().defaultWeight())
                    if (showTag) {
                        // Facility label under the card.
                        Spacer(GlanceModifier.height(5.dp))
                        WText(room.facility.tileTag(), Face.BODY_SEMIBOLD, tagDp, Palette.Muted, tracking = 0.14f)
                    }
                }
            }
        }
    }
}

/** 2×2: header, then busiest-first rows that share the remaining height. */
@Composable
private fun Compact(state: UiState, stale: Boolean, width: Dp, height: Dp) {
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
            WText("NU REC", Face.DISPLAY, 17f, Palette.Text, tracking = 0.06f)
            Status(state, stale, wide = false)
            Spacer(GlanceModifier.defaultWeight())
            RefreshButton(state, 30.dp)
        }
        Spacer(GlanceModifier.height(6.dp))
        if (rows.isEmpty()) EmptyBody()
        val context = LocalContext.current
        val numW = rows.maxOfOrNull { Glyphs.widthDp(context, "${(it.percent ?: 0.0).roundToInt()}%", Face.DISPLAY, numDp) } ?: 0f
        val numCol = (numW + 6f).dp
        val nameW = (width - pad * 2 - numCol).value
        val nameDp = Glyphs.fitSize(context, rows.map { it.shortLabel }, Face.BODY_SEMIBOLD, 11f, 9f, nameW)
        rows.forEachIndexed { i, room ->
            if (i > 0) Spacer(GlanceModifier.height(rowGap))
            CompactRow(room, stale, rowH, numDp, numCol, nameW.dp, nameDp)
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
            WText(off, Face.BODY_BOLD, if (wide) 9.5f else 8.5f, Palette.Text, tracking = 0.14f)
        }
        return
    }
    // The pulse box includes room for the halo (mockup: 8px dot, 7px spread).
    Spacer(GlanceModifier.width(if (wide) 5.dp else 3.dp))
    AndroidRemoteViews(remoteViews = Animated.pulse(context), modifier = GlanceModifier.size(if (wide) 20.dp else 16.dp))
    if (wide) WText("LIVE", Face.BODY_BOLD, 10f, Palette.Lime, tracking = 0.14f)
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
private fun Tile(room: Room, stale: Boolean, ring: Dp, tileText: Dp, nameDp: Float, countDp: Float, tagged: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val pct = room.percent
    val color = Palette.forLevel(levelFor(pct, room.closed), stale)
    Column(
        modifier = modifier.background(Palette.Tile).cornerRadius(16.dp).padding(vertical = 8.dp, horizontal = 3.dp),
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
            PercentText(room, ring.value * 0.36f, Palette.Text, Palette.Muted, smallPct = true)
        }
        Spacer(GlanceModifier.height(4.dp))
        WText(tileName(room, tagged), Face.BODY_SEMIBOLD, nameDp, Palette.Text, maxWidth = tileText)
        Spacer(GlanceModifier.height(1.dp))
        WText(countText(room), Face.BODY, countDp, Palette.Muted, maxWidth = tileText)
    }
}

@Composable
private fun CompactRow(room: Room, stale: Boolean, rowH: Dp, numDp: Float, numCol: Dp, nameWidth: Dp, nameDp: Float) {
    val color = Palette.forLevel(levelFor(room.percent, room.closed), stale)
    Row(GlanceModifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.width(numCol)) {
            PercentText(room, numDp, color, color, smallPct = false)
        }
        Column(GlanceModifier.defaultWeight()) {
            WText(room.shortLabel, Face.BODY_SEMIBOLD, nameDp, Palette.Text, maxWidth = nameWidth)
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
        WText("Tap refresh to load occupancy", Face.BODY, 12f, Palette.Muted)
    }
}

/** With the facility label above, "SB 4th Floor" can drop its prefix. */
private fun tileName(room: Room, tagged: Boolean): String = if (tagged) room.label.takeIf { room.facility == Facility.SQUASHBUSTERS } ?: room.shortLabel else room.shortLabel

private fun Facility.tileTag(): String = when (this) {
    Facility.MARINO -> "MARINO"
    Facility.SQUASHBUSTERS -> "SQUASH"
    Facility.OTHER -> "OTHER"
}

private fun countText(room: Room): String = when {
    room.closed -> "Closed"
    room.count != null && room.capacity != null -> "${room.count} of ${room.capacity}"
    room.count != null -> "${room.count} people"
    else -> "No count"
}
