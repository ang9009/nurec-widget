package dev.nurec.widget.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class UiState(
    val snapshot: Snapshot?,
    /** When we last got a good response (device clock). */
    val lastSuccess: Instant?,
    val lastFetchFailed: Boolean,
    /** When a fetch was last started (manual or automatic). Drives the cooldown. */
    val lastAttempt: Instant?,
) {
    /** Offline/stale: last fetch failed, or the data itself is > 2 hours old. */
    fun isStale(now: Instant = Instant.now()): Boolean {
        val snap = snapshot ?: return false
        if (lastFetchFailed) return true
        val updated = snap.lastUpdated ?: return true
        return Duration.between(updated, now) > STALE_AFTER
    }

    fun cooldownRemaining(now: Instant = Instant.now()): Duration {
        val last = lastAttempt ?: return Duration.ZERO
        val left = COOLDOWN - Duration.between(last, now)
        return if (left.isNegative) Duration.ZERO else left
    }

    fun updatedText(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
        val t = snapshot?.lastUpdated ?: return "Waiting for first fetch"
        val hhmm = TIME.withZone(zone).format(t)
        return if (isStale(now)) "Last seen $hhmm" else "Updated $hhmm"
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(2)
        val COOLDOWN: Duration = Duration.ofMinutes(2)
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val EMPTY = UiState(null, null, false, null)

        fun formatCountdown(d: Duration): String {
            val s = d.seconds.coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
