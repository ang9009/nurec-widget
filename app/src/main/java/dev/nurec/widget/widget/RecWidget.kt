package dev.nurec.widget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.nurec.widget.R
import dev.nurec.widget.data.RecRepository
import dev.nurec.widget.data.Room
import dev.nurec.widget.data.UiState
import dev.nurec.widget.data.levelFor
import dev.nurec.widget.ui.Palette
import dev.nurec.widget.ui.RingBitmap
import dev.nurec.widget.work.RefreshWorker
import kotlin.math.roundToInt

private val SMALL = DpSize(110.dp, 110.dp)
private val LARGE = DpSize(250.dp, 110.dp)
private val Condensed = FontFamily("sans-serif-condensed")

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

private fun c(color: Color) = ColorProvider(color)

@Composable
private fun WidgetBody(state: UiState) {
    val size = LocalSize.current
    val large = size.width >= LARGE.width
    val stale = state.isStale()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Palette.Card)
            .cornerRadius(28.dp)
            .padding(if (large) 16.dp else 12.dp),
    ) {
        Header(state, stale, large)
        Spacer(GlanceModifier.height(if (large) 12.dp else 8.dp))
        val rooms = state.snapshot?.rooms.orEmpty()
        when {
            rooms.isEmpty() -> EmptyBody()
            large -> Row(GlanceModifier.fillMaxWidth()) {
                rooms.forEachIndexed { i, room ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    Tile(room, stale, GlanceModifier.defaultWeight())
                }
            }
            else -> Column(GlanceModifier.fillMaxWidth()) {
                state.snapshot?.busiest(3).orEmpty().forEach { room ->
                    CompactRow(room, stale)
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
                Text(
                    "NU REC",
                    style = TextStyle(
                        color = c(Palette.Text), fontSize = if (large) 22.sp else 17.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Condensed,
                    ),
                )
                Spacer(GlanceModifier.width(8.dp))
                val (label, bg, fg) = when {
                    state.snapshot == null -> Triple("NO DATA", Palette.Chip, Palette.Text)
                    stale -> Triple("OFFLINE", Palette.Chip, Palette.Text)
                    else -> Triple("LIVE", Palette.Lime, Palette.OnLime)
                }
                if (large || label != "LIVE") {
                    Text(
                        label,
                        modifier = GlanceModifier.background(bg).cornerRadius(10.dp)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                        style = TextStyle(color = c(fg), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    )
                } else {
                    Box(GlanceModifier.size(6.dp).background(Palette.Lime).cornerRadius(3.dp)) {}
                }
            }
            if (large || state.snapshot == null || stale) {
                Text(
                    state.updatedText(),
                    style = TextStyle(color = c(Palette.Muted), fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        }
        RefreshButton(state, if (large) 44.dp else 36.dp)
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
                provider = ImageProvider(RingBitmap.draw(px, px * 0.06f, fraction, Palette.Lime)),
                contentDescription = null,
                modifier = GlanceModifier.size(size),
            )
        }
        Box(
            modifier = GlanceModifier
                .size(size - 8.dp)
                .background(if (cooling) Palette.Tile else Palette.Lime)
                .cornerRadius(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (cooling) {
                val mins = (left.seconds + 59) / 60
                Text(
                    "${mins}m",
                    style = TextStyle(
                        color = c(Palette.Muted), fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Condensed,
                    ),
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Refresh now",
                    colorFilter = ColorFilter.tint(c(Palette.OnLime)),
                    modifier = GlanceModifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun Tile(room: Room, stale: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val ring = 64.dp
    val px = (ring.value * context.resources.displayMetrics.density).roundToInt()
    val pct = room.percent
    val color = Palette.forLevel(levelFor(pct, room.closed), stale)
    Column(
        modifier = modifier.background(Palette.Tile).cornerRadius(18.dp).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            room.facility.tag.uppercase(),
            style = TextStyle(color = c(Palette.Muted), fontSize = 9.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(6.dp))
        Box(GlanceModifier.size(ring), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(RingBitmap.draw(px, px * 0.09f, ((pct ?: 0.0) / 100).toFloat(), color)),
                contentDescription = null,
                modifier = GlanceModifier.size(ring),
            )
            Text(
                pctText(room),
                style = TextStyle(
                    color = c(Palette.Text), fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Condensed,
                ),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(
            room.shortLabel,
            style = TextStyle(color = c(Palette.Text), fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            maxLines = 1,
        )
        Text(
            countText(room),
            style = TextStyle(color = c(Palette.Muted), fontSize = 11.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactRow(room: Room, stale: Boolean) {
    val color = Palette.forLevel(levelFor(room.percent, room.closed), stale)
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            pctText(room),
            modifier = GlanceModifier.width(44.dp),
            style = TextStyle(color = c(color), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed),
        )
        Column(GlanceModifier.defaultWeight()) {
            Text(
                room.shortLabel,
                style = TextStyle(color = c(Palette.Text), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(3.dp))
            LinearProgressIndicator(
                progress = ((room.percent ?: 0.0) / 100).toFloat().coerceIn(0.02f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                color = c(color),
                backgroundColor = c(Palette.Track),
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
        Text(
            "Tap refresh to load occupancy",
            style = TextStyle(color = c(Palette.Muted), fontSize = 12.sp, textAlign = TextAlign.Center),
        )
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
