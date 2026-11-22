package dev.nurec.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class LiveParserTest {

    private fun sample(): String =
        requireNotNull(javaClass.classLoader?.getResource("sample_live.json")) { "sample missing" }.readText()

    @Test fun `parses every room in the real sample`() {
        val snap = LiveParser.parse(sample())
        assertEquals(8, snap.allRooms.size)
        assertEquals(Instant.parse("2026-11-22T15:32:56.926Z"), snap.fetched)
        val gym = snap.allRooms.single { it.name == "Marino Center - Gymnasium" }
        assertEquals(Facility.MARINO, gym.facility)
        assertEquals(5, gym.count)
        assertEquals(60, gym.capacity)
        assertEquals(8.333, gym.percent!!, 0.01)
        assertFalse(gym.closed)
    }

    @Test fun `filter keeps only the three chosen rooms in order`() {
        val rooms = LiveParser.parse(sample()).rooms
        assertEquals(listOf("2nd Floor", "3rd Floor Weight Room", "4th Floor"), rooms.map { it.label })
        assertEquals(listOf(17, 11, 11), rooms.map { it.count })
        assertEquals(listOf(105, 65, 60), rooms.map { it.capacity })
        assertEquals(
            listOf(Facility.MARINO, Facility.MARINO, Facility.SQUASHBUSTERS),
            rooms.map { it.facility },
        )
    }

    @Test fun `filter does not confuse 1st floor weight room or select and cardio`() {
        val names = LiveParser.parse(sample()).rooms.map { it.name }
        assertFalse(names.any { "1st Floor" in it })
        assertFalse(names.any { "Select" in it })
        assertFalse(names.any { "Gymnasium" in it })
    }

    @Test fun `busiest sorts by percent`() {
        val top = LiveParser.parse(sample()).busiest(3).map { it.shortLabel }
        assertEquals(listOf("SB 4th Floor", "3rd Fl Weights", "2nd Floor"), top)
    }

    @Test fun `offsetless updated time is Boston local time`() {
        val t = LiveParser.parseTime("2026-11-22T10:11:44.42")
        assertEquals(Instant.parse("2026-11-22T15:11:44.420Z"), t)
        // Summer: EDT is UTC-4.
        assertEquals(Instant.parse("2026-07-01T14:00:00Z"), LiveParser.parseTime("2026-07-01T10:00:00"))
    }

    @Test fun `lastUpdated is the newest shown room`() {
        val snap = LiveParser.parse(sample())
        // 2nd Floor updated 10:11:44.42 Boston is the newest of the three shown rooms.
        assertEquals(Instant.parse("2026-11-22T15:11:44.420Z"), snap.lastUpdated)
    }

    @Test fun `missing and null fields do not crash`() {
        val body = """
            {"rooms":[
              {"name":"Marino Center- 2nd Floor","facility":null,"count":null,"capacity":105},
              {"name":null,"count":3},
              null,
              {"name":"Marino Center - 3rd Floor Weight Room","count":13,"capacity":65,"pct":null,"updated":"garbage"},
              {"name":"SquashBusters - 4th Floor","closed":true,"extra":"ignored"}
            ]}
        """.trimIndent()
        val snap = LiveParser.parse(body)
        assertEquals(3, snap.allRooms.size)
        assertNull(snap.fetched)
        val second = snap.rooms[0]
        assertNull(second.count)
        assertNull(second.percent)
        assertEquals(Facility.MARINO, second.facility) // inferred from the name
        val weights = snap.rooms[1]
        assertEquals(20.0, weights.percent!!, 0.001) // computed from count / capacity
        assertNull(weights.updated)
        assertTrue(snap.rooms[2].closed)
        assertEquals(Level.UNKNOWN, levelFor(snap.rooms[2].percent, snap.rooms[2].closed))
    }

    @Test fun `empty or missing rooms array gives empty snapshot`() {
        assertTrue(LiveParser.parse("{}").allRooms.isEmpty())
        assertTrue(LiveParser.parse("""{"rooms":null}""").allRooms.isEmpty())
    }

    @Test(expected = Exception::class)
    fun `malformed json throws so the cache is kept`() {
        LiveParser.parse("<html>502 Bad Gateway</html>")
    }

    @Test fun `levels match the color thresholds`() {
        assertEquals(Level.LOW, levelFor(39.9))
        assertEquals(Level.MEDIUM, levelFor(40.0))
        assertEquals(Level.MEDIUM, levelFor(70.0))
        assertEquals(Level.HIGH, levelFor(70.1))
        assertEquals(Level.UNKNOWN, levelFor(null))
    }
}

class UiStateTest {
    private val snap = LiveParser.parse(
        requireNotNull(javaClass.classLoader?.getResource("sample_live.json")).readText(),
    )
    private val updated = snap.lastUpdated!!

    @Test fun `fresh data is not stale`() {
        val s = UiState(snap, updated, false, null)
        assertFalse(s.isStale(updated.plus(Duration.ofMinutes(90))))
    }

    @Test fun `data older than two hours is stale`() {
        val s = UiState(snap, updated, false, null)
        assertTrue(s.isStale(updated.plus(Duration.ofMinutes(121))))
    }

    @Test fun `failed fetch is stale even when data is fresh`() {
        assertTrue(UiState(snap, updated, true, null).isStale(updated))
    }

    @Test fun `cooldown counts down from fifteen seconds`() {
        val t0 = Instant.parse("2026-11-22T15:00:00Z")
        val s = UiState(null, null, false, t0)
        assertEquals(Duration.ofSeconds(15), s.cooldownRemaining(t0))
        assertEquals(Duration.ofSeconds(3), s.cooldownRemaining(t0.plusSeconds(12)))
        assertTrue(s.cooldownRemaining(t0.plusSeconds(15)).isZero)
        assertTrue(UiState.EMPTY.cooldownRemaining(t0).isZero)
        assertEquals("1:42", UiState.formatCountdown(Duration.ofSeconds(102)))
    }

    @Test fun `updated text uses HH mm and flags stale data`() {
        val zone = ZoneId.of("America/New_York")
        assertEquals("Updated 10:11", UiState(snap, updated, false, null).updatedText(zone, updated))
        assertEquals("Last seen 10:11", UiState(snap, updated, true, null).updatedText(zone, updated))
        assertEquals("Waiting for first fetch", UiState.EMPTY.updatedText(zone))
    }
}
