package dev.nurec.widget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
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
import dev.nurec.widget.ui.Fonts.Face
import dev.nurec.widget.ui.Palette
import dev.nurec.widget.ui.RingBitmap
import dev.nurec.widget.ui.TextBitmap
import dev.nurec.widget.ui.TextRun
import dev.nurec.widget.work.RefreshWorker
import kotlin.math.roundToInt

private val SMALL = DpSize(110.dp, 110.dp)
private val LARGE = DpSize(250.dp, 110.dp)

class RecWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

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

/**
 * Text in the bundled fonts, drawn as an image (Glance only supports system fonts).
 * [description] is read by TalkBack in place of the image.
 */
@Composable
private fun FontText(vararg runs: TextRun, maxWidth: Dp? = null, description: String? = null) {
    val context = LocalContext.current
    val r = TextBitmap.render(context, runs.toList(), maxWidth?.value)
    Image(
        provider = ImageProvider(r.bitmap),
        contentDescription = description ?: runs.joinToString("") { it.text },
        modifier = GlanceModifier.size(r.widthDp.dp, r.heightDp.dp),
    )
}

@Composable
private fun WidgetBody(state: UiState) {
    val size = LocalSize.current
    val large = size.width >= LARGE.width
    val stale = state.isStale()
    val pad = if (large) 16.dp else 12.dp
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Palette.Card)
            .cornerRadius(28.dp)
            .padding(pad),
    ) {
        Header(state, stale, large)
        Spacer(GlanceModifier.height(if (large) 12.dp else 8.dp))
        val rooms = state.snapshot?.rooms.orEmpty()
        val inner = size.width - pad * 2
        when {
            rooms.isEmpty() -> EmptyBody()
            large -> Row(GlanceModifier.fillMaxWidth()) {
                val gap = 8.dp
                val tileWidth = (inner - gap * (rooms.size - 1)) / rooms.size
                rooms.forEachIndexed { i, room ->
                    if (i > 0) Spacer(GlanceModifier.width(gap))
                    Tile(room, stale, tileWidth - 8.dp, GlanceModifier.defaultWeight())
                }
            }
            else -> Column(GlanceModifier.fillMaxWidth()) {
                state.snapshot?.busiest(3).orEmpty().forEach { room ->
                    CompactRow(room, stale, inner - 52.dp)
                    Spacer(GlanceModifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(state: UiState, stale: Boolean, large: Boolean) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FontText(TextRun("NU REC", Face.DISPLAY, if (large) 24f else 18f, Palette.Text, tracking = 0.06f))
                Spacer(GlanceModifier.width(8.dp))
                val (label, bg, fg) = when {
                    state.snapshot == null -> Triple("NO DATA", Palette.Chip, Palette.Text)
                    stale -> Triple("OFFLINE", Palette.Chip, Palette.Text)
                    else -> Triple("LIVE", Palette.Lime, Palette.OnLime)
                }
                if (large || label != "LIVE") {
                    Box(
                        modifier = GlanceModifier.background(bg).cornerRadius(10.dp)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        FontText(TextRun(label, Face.BODY_BOLD, 10f, fg, tracking = 0.14f))
                    }
                } else {
                    Box(GlanceModifier.size(6.dp).background(Palette.Lime).cornerRadius(3.dp)) {}
                }
            }
            if (large || state.snapshot == null || stale) {
                Spacer(GlanceModifier.height(3.dp))
                FontText(TextRun(state.updatedText(), Face.BODY, 12f, Palette.Muted))
            }
        }
        RefreshButton(state, if (large) 48.dp else 36.dp)
    }
}

@Composable
private fun RefreshButton(state: UiState, size: Dp) {
    val context = LocalContext.current
    val left = state.cooldownRemaining()
    val cooling = !left.isZero
    val px = (size.value * context.resources.displayMetrics.density).roundToInt()
    Box(
        modifier = GlanceModifier.size(size).clickable(actionRunCallback<RefreshAction>()),
        contentAlignment = Alignment.Center,
    ) {
        if (cooling) {
            val fraction = left.toMillis().toFloat() / UiState.COOLDOWN.toMillis()
            Image(
                provider = ImageProvider(RingBitmap.draw(px, px * 0.05f, fraction, Palette.Lime)),
                contentDescription = null,
                modifier = GlanceModifier.size(size),
            )
        }
        Box(
            modifier = GlanceModifier
                .size(size - 8.dp)
                .background(if (cooling) Palette.Tile else Palette.Lime)
                .cornerRadius(if (size > 40.dp) 20.dp else 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (cooling) {
                val mins = (left.seconds + 59) / 60
                FontText(
                    TextRun("${mins}m", Face.DISPLAY_SEMIBOLD, 15f, Palette.Muted),
                    description = "Refresh available in $mins minutes",
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Refresh now",
                    colorFilter = ColorFilter.tint(ColorProvider(Palette.OnLime)),
                    modifier = GlanceModifier.size(if (size > 40.dp) 20.dp else 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Tile(room: Room, stale: Boolean, textWidth: Dp, modifier: GlanceModifier) {
    val context = LocalContext.current
    val ring = 68.dp
    val px = (ring.value * context.resources.displayMetrics.density).roundToInt()
    val pct = room.percent
    val color = Palette.forLevel(levelFor(pct, room.closed), stale)
    Column(
        modifier = modifier.background(Palette.Tile).cornerRadius(18.dp).padding(top = 12.dp, bottom = 10.dp, start = 4.dp, end = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FontText(TextRun(room.facility.tag.uppercase(), Face.BODY_SEMIBOLD, 9.5f, Palette.Muted, tracking = 0.14f))
        Spacer(GlanceModifier.height(7.dp))
        Box(GlanceModifier.size(ring), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(RingBitmap.draw(px, px * 0.085f, ((pct ?: 0.0) / 100).toFloat(), color)),
                contentDescription = null,
                modifier = GlanceModifier.size(ring),
            )
            PercentText(room, 25f)
        }
        Spacer(GlanceModifier.height(7.dp))
        FontText(TextRun(room.shortLabel, Face.BODY_SEMIBOLD, 12.5f, Palette.Text), maxWidth = textWidth)
        Spacer(GlanceModifier.height(2.dp))
        FontText(TextRun(countText(room), Face.BODY, 11.5f, Palette.Muted), maxWidth = textWidth)
    }
}

/** "17" in big display type with a smaller muted "%", as in the mockup. */
@Composable
private fun PercentText(room: Room, sizeDp: Float) {
    val pct = room.percent
    when {
        room.closed -> FontText(TextRun("—", Face.DISPLAY, sizeDp, Palette.Text), description = "Closed")
        pct == null -> FontText(TextRun("?", Face.DISPLAY, sizeDp, Palette.Text), description = "Unknown")
        else -> FontText(
            TextRun("${pct.roundToInt()}", Face.DISPLAY, sizeDp, Palette.Text),
            TextRun("%", Face.DISPLAY_SEMIBOLD, sizeDp * 0.5f, Palette.Muted),
        )
    }
}

@Composable
private fun CompactRow(room: Room, stale: Boolean, nameWidth: Dp) {
    val color = Palette.forLevel(levelFor(room.percent, room.closed), stale)
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.width(48.dp)) {
            FontText(TextRun(pctText(room), Face.DISPLAY, 26f, color))
        }
        Column(GlanceModifier.defaultWeight()) {
            FontText(TextRun(room.shortLabel, Face.BODY_SEMIBOLD, 12f, Palette.Text), maxWidth = nameWidth)
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = ((room.percent ?: 0.0) / 100).toFloat().coerceIn(0.02f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
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
        FontText(TextRun("Tap refresh to load occupancy", Face.BODY, 12f, Palette.Muted))
    }
}

private fun pctText(room: Room): String = when {
    room.closed -> "—"
    room.percent == null -> "?"
    else -> "${room.percent.roundToInt()}%"
}

private fun countText(room: Room): String = when {
    room.closed -> "Closed"
    room.count != null && room.capacity != null -> "${room.count} of ${room.capacity}"
    room.count != null -> "${room.count} people"
    else -> "No count"
}
