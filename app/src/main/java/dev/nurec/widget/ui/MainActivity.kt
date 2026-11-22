package dev.nurec.widget.ui

import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nurec.widget.data.RecRepository
import dev.nurec.widget.data.Room
import dev.nurec.widget.data.UiState
import dev.nurec.widget.data.levelFor
import dev.nurec.widget.work.RefreshWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = RecRepository(this)
        RefreshWorker.schedulePeriodic(this)
        setContent {
            val state by repo.state.collectAsState(UiState.EMPTY)
            val scope = rememberCoroutineScope()
            // Ticks once a second so the cooldown countdown stays current.
            var now by remember { mutableStateOf(Instant.now()) }
            LaunchedEffect(Unit) {
                // First launch with nothing cached: load once (cooldown still applies).
                if (repo.current().snapshot == null && repo.tryStartAttempt()) {
                    RefreshWorker.enqueueManual(applicationContext)
                }
                while (true) { now = Instant.now(); delay(1000) }
            }
            Screen(state, now) {
                scope.launch {
                    if (repo.tryStartAttempt()) RefreshWorker.enqueueManual(applicationContext)
                }
            }
        }
    }
}

@Composable
private fun Screen(state: UiState, now: Instant, onRefresh: () -> Unit) {
    val stale = state.isStale(now)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NU REC", color = Palette.Text, fontSize = 44.sp, fontFamily = Condensed, letterSpacing = 1.5.sp)
                Spacer(Modifier.size(10.dp))
                val (label, bg, fg) = when {
                    state.snapshot == null -> Triple("NO DATA", Palette.Chip, Palette.Text)
                    stale -> Triple("OFFLINE", Palette.Chip, Palette.Text)
                    else -> Triple("LIVE", Palette.Lime, Palette.OnLime)
                }
                Text(
                    label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                    modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            Text("How busy your floors are right now", color = Palette.Muted, fontSize = 14.sp)
        }

        val left = state.cooldownRemaining(now)
        val cooling = !left.isZero
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onRefresh,
                enabled = !cooling,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Lime, contentColor = Palette.OnLime,
                    disabledContainerColor = Palette.Tile, disabledContentColor = Palette.Muted,
                ),
                modifier = Modifier.height(48.dp),
            ) {
                Text(
                    if (cooling) "Wait ${UiState.formatCountdown(left)}" else "Refresh",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(14.dp))
            Text(state.updatedText(now = now), color = Palette.Muted, fontSize = 13.sp)
        }

        val rooms = state.snapshot?.rooms.orEmpty()
        if (rooms.isEmpty()) {
            Text(
                if (state.lastFetchFailed) "Couldn't reach the server. Check your connection and tap Refresh."
                else "Loading occupancy…",
                color = Palette.Muted, fontSize = 14.sp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rooms.forEach { RoomCard(it, stale) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.5.dp, Palette.Track), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("ADD THE WIDGET", color = Palette.Text, fontSize = 18.sp, fontFamily = Condensed, letterSpacing = 1.sp)
            Text(
                "Long-press your home screen → Widgets → NU Rec, then drag it out. " +
                    "Shrink it to 2×2 for the compact view.",
                color = Palette.Body, fontSize = 13.sp, lineHeight = 20.sp,
            )
        }
    }
}


@Composable
private fun RoomCard(room: Room, stale: Boolean) {
    val pct = room.percent
    val color = Palette.forLevel(levelFor(pct, room.closed), stale)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Card, RoundedCornerShape(22.dp))
            .padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            Ring(fraction = ((pct ?: 0.0) / 100).toFloat(), color = color)
            Text(
                when {
                    room.closed -> "—"
                    pct == null -> "?"
                    else -> "${pct.roundToInt()}%"
                },
                color = Palette.Text, fontSize = 24.sp, fontFamily = Condensed,
            )
        }
        Spacer(Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(room.facility.label.uppercase(), color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Text(room.label, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    room.closed -> "Closed"
                    room.count != null && room.capacity != null -> "${room.count} of ${room.capacity} people"
                    room.count != null -> "${room.count} people"
                    else -> "No count available"
                },
                color = Palette.Muted, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Ring(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(76.dp)) {
        val stroke = 7.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Palette.Track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(
            color, -90f, 360f * fraction.coerceIn(0.02f, 1f), false, Offset(inset, inset), arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
