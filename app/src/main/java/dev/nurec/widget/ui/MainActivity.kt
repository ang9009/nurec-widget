package dev.nurec.widget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nurec.widget.data.RecRepository
import dev.nurec.widget.data.Room
import dev.nurec.widget.data.UiState
import dev.nurec.widget.data.levelFor
import dev.nurec.widget.widget.RecWidgetReceiver
import dev.nurec.widget.work.RefreshWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

private val Display = Fonts.Lato

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
            CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Fonts.Lato)) {
                Screen(state, now) {
                    scope.launch {
                        if (repo.tryStartAttempt()) RefreshWorker.enqueueManual(applicationContext)
                    }
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
                Text("NU Rec", color = Palette.Text, fontSize = 40.sp, fontFamily = Display, fontWeight = FontWeight.Black)
                Spacer(Modifier.size(10.dp))
                val (label, bg, fg) = when {
                    state.snapshot == null -> Triple("NO DATA", Palette.Chip, Palette.Body)
                    stale -> Triple("OFFLINE", Palette.Chip, Palette.Body)
                    else -> Triple("LIVE", Palette.LivePill, Palette.RedLight)
                }
                Text(
                    label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp,
                    modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text("How busy your floors are right now", color = Palette.Muted, fontSize = 15.sp)
        }

        val left = state.cooldownRemaining(now)
        val cooling = !left.isZero
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onRefresh,
                enabled = !cooling,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Red, contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = Palette.Tile, disabledContentColor = Palette.Muted,
                ),
                modifier = Modifier.height(48.dp),
            ) {
                if (!cooling) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(dev.nurec.widget.R.drawable.ic_refresh),
                        contentDescription = null,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color.White),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    if (cooling) "Wait ${UiState.formatCountdown(left)}" else "Refresh",
                    fontSize = 15.sp, fontWeight = FontWeight.Black,
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
                .background(Palette.Card, RoundedCornerShape(24.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Add the widget", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text(
                "Long-press your home screen → Widgets → NU Rec. Shrink it to 2×2 for the compact view.",
                color = Palette.Body, fontSize = 14.sp, lineHeight = 21.sp,
            )
            val context = LocalContext.current
            val awm = remember { AppWidgetManager.getInstance(context) }
            if (awm.isRequestPinAppWidgetSupported) {
                Spacer(Modifier.size(4.dp))
                OutlinedButton(
                    onClick = { awm.requestPinAppWidget(ComponentName(context, RecWidgetReceiver::class.java), null, null) },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.5.dp, Palette.Red),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.RedLight),
                ) { Text("Add widget to home screen", fontWeight = FontWeight.Black) }
            }
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
            .background(Palette.Card, RoundedCornerShape(26.dp))
            .padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            Ring(fraction = ((pct ?: 0.0) / 100).toFloat(), color = color)
            Text(
                buildAnnotatedString {
                    when {
                        room.closed -> append("—")
                        pct == null -> append("?")
                        else -> {
                            append("${pct.roundToInt()}")
                            withStyle(SpanStyle(fontSize = 12.sp, color = Palette.Muted, fontWeight = FontWeight.Bold)) {
                                append("%")
                            }
                        }
                    }
                },
                color = Palette.Text, fontSize = 24.sp, fontFamily = Display, fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(room.facility.label.uppercase(), color = Palette.Tag, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
            Text(room.label, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Ring(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(76.dp)) {
        val stroke = 7.6.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Palette.Track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(
            color, -90f, 360f * fraction.coerceIn(0.02f, 1f), false, Offset(inset, inset), arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
