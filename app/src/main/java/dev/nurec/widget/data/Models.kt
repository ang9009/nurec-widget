package dev.nurec.widget.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Raw shape of GET https://nu-rec-board.netlify.app/api/live (inspected 2026-11-22):
 *
 * {"fetched":"2026-11-22T15:32:51.701Z",
 *  "rooms":[{"name":"Marino Center- 2nd Floor","facility":"Marino Recreation Center",
 *            "count":17,"capacity":105,"pct":16.19,"closed":false,
 *            "updated":"2026-11-22T10:11:44.42"}, ...]}
 *
 * `updated` has no offset; it is Boston local time. Every field is nullable because the API is
 * an unofficial third-party project.
 */
@Serializable
data class LiveResponseDto(
    val fetched: String? = null,
    val rooms: List<RoomDto?>? = null,
)

@Serializable
data class RoomDto(
    val name: String? = null,
    val facility: String? = null,
    val count: Int? = null,
    val capacity: Int? = null,
    val pct: Double? = null,
    val closed: Boolean? = null,
    val updated: String? = null,
)

enum class Facility(val label: String, val tag: String) {
    MARINO("Marino Center", "Marino"),
    SQUASHBUSTERS("SquashBusters", "Squash"),
    OTHER("Other", "Other"),
}

data class Room(
    val name: String,
    val facility: Facility,
    val count: Int?,
    val capacity: Int?,
    /** 0–100, or null if unknown. */
    val percent: Double?,
    val closed: Boolean,
    val updated: Instant?,
    /** Display names; set for rooms on the allowlist. */
    val label: String = name,
    /** 2×2 rows, e.g. "MRN 3rd Floor". */
    val shortLabel: String = name,
    /** 4×2 tiles, shown above the facility label, e.g. "3rd Floor". */
    val tileLabel: String = name,
)

/**
 * The rooms the widget shows, in display order. Matching ignores case, spaces and punctuation,
 * so "Marino Center- 2nd Floor" and "Marino Center - 2nd Floor" both match.
 */
object RoomFilter {
    data class Shown(
        val facility: Facility,
        val keyword: String,
        val label: String,
        val shortLabel: String,
        val tileLabel: String,
    )

    val SHOWN = listOf(
        Shown(Facility.MARINO, "2nd Floor", "2nd Floor", "MRN 2nd Floor", "2nd Floor"),
        Shown(Facility.MARINO, "3rd Floor Weight Room", "3rd Floor Weight Room", "MRN 3rd Floor", "3rd Floor"),
        Shown(Facility.SQUASHBUSTERS, "4th Floor", "4th Floor", "SB 4th Floor", "4th Floor"),
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun apply(rooms: List<Room>): List<Room> = SHOWN.mapNotNull { shown ->
        rooms.firstOrNull { it.facility == shown.facility && norm(shown.keyword) in norm(it.name) }
            ?.copy(label = shown.label, shortLabel = shown.shortLabel, tileLabel = shown.tileLabel)
    }
}

data class Snapshot(
    /** Every room the API returned. */
    val allRooms: List<Room>,
    val fetched: Instant?,
) {
    /** Only the allowlisted rooms, in display order. */
    val rooms: List<Room> get() = RoomFilter.apply(allRooms)

    /** Newest update among shown rooms, falling back to the server's fetch time. */
    val lastUpdated: Instant? get() = rooms.mapNotNull { it.updated }.maxOrNull() ?: fetched

    fun busiest(n: Int): List<Room> = rooms.sortedByDescending { it.percent ?: -1.0 }.take(n)
}

enum class Level { LOW, MEDIUM, HIGH, UNKNOWN }

fun levelFor(percent: Double?, closed: Boolean = false): Level = when {
    closed || percent == null -> Level.UNKNOWN
    percent < 40 -> Level.LOW
    percent <= 70 -> Level.MEDIUM
    else -> Level.HIGH
}

object LiveParser {
    val BOSTON: ZoneId = ZoneId.of("America/New_York")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    /** Throws on malformed JSON so callers can keep the cached copy. */
    fun parse(body: String): Snapshot {
        val dto = json.decodeFromString(LiveResponseDto.serializer(), body)
        val rooms = dto.rooms.orEmpty().filterNotNull().mapNotNull(::toRoom)
        return Snapshot(allRooms = rooms, fetched = parseTime(dto.fetched))
    }

    private fun toRoom(dto: RoomDto): Room? {
        val name = dto.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pct = dto.pct?.takeIf { it.isFinite() }
            ?: if (dto.count != null && dto.capacity != null && dto.capacity > 0) {
                dto.count * 100.0 / dto.capacity
            } else null
        return Room(
            name = name,
            facility = facilityOf(dto.facility, name),
            count = dto.count,
            capacity = dto.capacity,
            percent = pct?.coerceAtLeast(0.0),
            closed = dto.closed ?: false,
            updated = parseTime(dto.updated),
        )
    }

    fun facilityOf(facility: String?, name: String): Facility {
        val text = "${facility.orEmpty()} $name".lowercase()
        return when {
            "marino" in text -> Facility.MARINO
            "squash" in text -> Facility.SQUASHBUSTERS
            else -> Facility.OTHER
        }
    }

    /** Accepts ISO instants ("...Z"), offset times, or offset-less Boston local times. */
    fun parseTime(raw: String?): Instant? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            OffsetDateTime.parse(s).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(s).atZone(BOSTON).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
