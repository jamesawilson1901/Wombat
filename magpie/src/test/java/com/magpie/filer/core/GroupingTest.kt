package com.magpie.filer.core

import com.magpie.filer.watch.SpottedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping a backlog by when things arrived.
 *
 * The point of the whole thing is that one event becomes one decision, so the
 * cases that matter are the ones where a run could be broken up wrongly or two
 * separate runs could be run together.
 */
class GroupingTest {

    private val minute = 60L * 1000
    private val hour = 60 * minute

    private var next = 0
    private fun at(millis: Long, name: String = "file${next++}.jpg") = SpottedFile(
        path = "/storage/emulated/0/Download/$name",
        name = name,
        size = 1_000L,
        source = "Downloads",
        spottedAt = millis,
    )

    private fun sizes(groups: List<TimeGroup>) = groups.map { it.size }

    // ---- the shape of a backlog ---------------------------------------------

    @Test
    fun `a run of photos from one afternoon stays together`() {
        val shoot = (0..39).map { at(10 * hour + it * minute) }

        val groups = Grouping.byTime(shoot)

        assertEquals("forty photos, one event, one decision", 1, groups.size)
        assertEquals(40, groups.single().size)
    }

    @Test
    fun `two afternoons a day apart are two groups`() {
        val monday = (0..9).map { at(10 * hour + it * minute) }
        val tuesday = (0..4).map { at(34 * hour + it * minute) }

        val groups = Grouping.byTime(monday + tuesday)

        assertEquals(listOf(10, 5), sizes(groups))
    }

    @Test
    fun `the gap decides, not the total length of the run`() {
        // Eight hours of steady work is still one session if nothing stops.
        val long = (0..47).map { at(9 * hour + it * 10 * minute) }
        assertEquals(1, Grouping.byTime(long).size)
    }

    @Test
    fun `a quiet spell longer than the gap starts a new group`() {
        val before = listOf(at(0), at(minute))
        val after = listOf(at(3 * hour), at(3 * hour + minute))

        assertEquals(listOf(2, 2), sizes(Grouping.byTime(before + after)))
    }

    @Test
    fun `a gap exactly the threshold does not split`() {
        // Splitting on "longer than", not "at least", so the boundary is not a
        // coin toss for files that land exactly on it.
        val files = listOf(at(0), at(Grouping.DEFAULT_GAP_MILLIS))
        assertEquals(1, Grouping.byTime(files).size)
    }

    @Test
    fun `one millisecond past the threshold does split`() {
        val files = listOf(at(0), at(Grouping.DEFAULT_GAP_MILLIS + 1))
        assertEquals(listOf(1, 1), sizes(Grouping.byTime(files)))
    }

    @Test
    fun `files arrive in any order and come back in time order`() {
        val jumbled = listOf(at(3 * minute, "c.jpg"), at(minute, "a.jpg"), at(2 * minute, "b.jpg"))

        val group = Grouping.byTime(jumbled).single()

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), group.files.map { it.name })
        assertEquals(minute, group.earliest)
        assertEquals(3 * minute, group.latest)
    }

    @Test
    fun `an empty backlog is no groups, not one empty group`() {
        assertEquals(emptyList<TimeGroup>(), Grouping.byTime(emptyList()))
    }

    @Test
    fun `a single file is a group of one`() {
        assertEquals(listOf(1), sizes(Grouping.byTime(listOf(at(0)))))
    }

    @Test
    fun `files sharing a timestamp stay in one group`() {
        val same = (0..4).map { at(5 * hour, "same$it.jpg") }
        assertEquals(1, Grouping.byTime(same).size)
    }

    @Test
    fun `a tighter gap splits a session into its bursts`() {
        val burstOne = (0..2).map { at(it * minute) }
        val burstTwo = (0..2).map { at(30 * minute + it * minute) }

        // At the default two hours these are one session.
        assertEquals(1, Grouping.byTime(burstOne + burstTwo).size)
        // At ten minutes they are the two bursts they really were.
        assertEquals(listOf(3, 3), sizes(Grouping.byTime(burstOne + burstTwo, gap = 10 * minute)))
    }

    @Test
    fun `an absurdly small gap is floored rather than making one group per file`() {
        val steady = (0..9).map { at(it * 10 * 1000L) }
        // Zero would put every file in its own group; the floor keeps ten
        // seconds apart together.
        assertEquals(1, Grouping.byTime(steady, gap = 0).size)
    }

    @Test
    fun `the timestamp used is the caller's to choose`() {
        // Photos carry a taken-at time in their EXIF that is better than the
        // download time, so grouping must not hardcode where time comes from.
        val files = listOf(at(0, "a.jpg"), at(0, "b.jpg"), at(0, "c.jpg"))
        val taken = mapOf("a.jpg" to 0L, "b.jpg" to 5 * hour, "c.jpg" to 5 * hour + minute)

        val groups = Grouping.byTime(files) { taken.getValue(it.name) }

        assertEquals(listOf(1, 2), sizes(groups))
    }

    @Test
    fun `span reports how long the run took`() {
        val group = Grouping.byTime(listOf(at(0), at(90 * minute))).single()
        assertEquals(90 * minute, group.span)
    }

    // ---- fixing a guess that came out wrong ---------------------------------

    @Test
    fun `two groups can be merged when one event spanned a break`() {
        val morning = (0..2).map { at(9 * hour + it * minute) }
        val afternoon = (0..3).map { at(14 * hour + it * minute) }
        val groups = Grouping.byTime(morning + afternoon)
        assertEquals(listOf(3, 4), sizes(groups))

        val merged = Grouping.merge(groups, 0)

        assertEquals(listOf(7), sizes(merged))
        assertEquals(9 * hour, merged.single().earliest)
        assertEquals(14 * hour + 3 * minute, merged.single().latest)
    }

    @Test
    fun `merging keeps every file and keeps them in order`() {
        val groups = Grouping.byTime((0..2).map { at(it * minute) } + (0..2).map { at(5 * hour + it * minute) })

        val merged = Grouping.merge(groups, 0).single()

        assertEquals(6, merged.size)
        assertEquals(merged.files.sortedBy { it.spottedAt }, merged.files)
    }

    @Test
    fun `merging past the end changes nothing rather than throwing`() {
        val groups = Grouping.byTime(listOf(at(0), at(5 * hour)))
        assertEquals(groups, Grouping.merge(groups, 1))
        assertEquals(groups, Grouping.merge(groups, 9))
        assertEquals(groups, Grouping.merge(groups, -1))
    }

    @Test
    fun `a group can be split when two things shared an afternoon`() {
        val group = Grouping.byTime((0..5).map { at(it * minute) }).single()

        val parts = Grouping.split(group, at = 2)

        assertEquals(listOf(2, 4), parts.map { it.size })
        assertEquals(0L, parts[0].earliest)
        assertEquals(2 * minute, parts[1].earliest)
    }

    @Test
    fun `splitting at either end changes nothing`() {
        val group = Grouping.byTime((0..3).map { at(it * minute) }).single()
        assertEquals(listOf(group), Grouping.split(group, at = 0))
        assertEquals(listOf(group), Grouping.split(group, at = group.size))
        assertEquals(listOf(group), Grouping.split(group, at = 99))
    }

    // ---- naming a group -----------------------------------------------------

    @Test
    fun `a group is numbered in time order under one stem`() {
        val files = (0..2).map { at(it * minute, "gen_447120$it.png") }

        val names = Grouping.numbered("ARRIVAL", files)

        assertEquals("ARRIVAL_01.png", names[files[0].path])
        assertEquals("ARRIVAL_02.png", names[files[1].path])
        assertEquals("ARRIVAL_03.png", names[files[2].path])
    }

    @Test
    fun `numbering is padded to the width of the group so it sorts`() {
        val files = (1..120).map { at(it.toLong(), "f$it.jpg") }

        val names = Grouping.numbered("SHOOT", files)

        assertEquals("SHOOT_001.jpg", names[files[0].path])
        assertEquals("SHOOT_120.jpg", names[files[119].path])
    }

    @Test
    fun `every file keeps its own extension, so a mixed group survives`() {
        val files = listOf(at(0, "a.png"), at(minute, "b.mp4"), at(2 * minute, "c.jpg"))

        val names = Grouping.numbered("TEST", files)

        assertEquals("TEST_01.png", names[files[0].path])
        assertEquals("TEST_02.mp4", names[files[1].path])
        assertEquals("TEST_03.jpg", names[files[2].path])
    }

    @Test
    fun `a stem with illegal characters is cleaned rather than refused`() {
        val files = listOf(at(0, "a.png"))
        val name = Grouping.numbered("My: Shoot?", files).values.single()
        assertTrue(name, name.startsWith("My Shoot"))
        assertTrue(name, !name.contains(":"))
    }

    @Test
    fun `a stem given with an extension does not end up with two`() {
        val files = listOf(at(0, "a.png"))
        assertEquals("ARRIVAL_01.png", Grouping.numbered("ARRIVAL.png", files).values.single())
    }

    @Test
    fun `an unusable stem numbers nothing rather than making hidden files`() {
        val files = listOf(at(0, "a.png"))
        assertEquals(emptyMap<String, String>(), Grouping.numbered("", files))
        assertEquals(emptyMap<String, String>(), Grouping.numbered("   ", files))
        assertEquals(emptyMap<String, String>(), Grouping.numbered("***", files))
    }

    @Test
    fun `no files means no names`() {
        assertEquals(emptyMap<String, String>(), Grouping.numbered("ANYTHING", emptyList()))
    }
}

/** The wording of a group's date range, with the zone pinned so it is stable. */
class TimeRangeTest {

    private val london: java.time.ZoneId = java.time.ZoneId.of("Europe/London")

    private fun at(text: String): Long =
        java.time.LocalDateTime.parse(text).atZone(london).toInstant().toEpochMilli()

    @org.junit.Test
    fun `a run within one day gives the day once and both times`() {
        assertEquals(
            "15 June 2024, 14:02–17:41",
            Formatting.timeRange(at("2024-06-15T14:02"), at("2024-06-15T17:41"), london),
        )
    }

    @org.junit.Test
    fun `a single moment does not read as a range from itself`() {
        assertEquals(
            "15 June 2024, 14:02",
            Formatting.timeRange(at("2024-06-15T14:02"), at("2024-06-15T14:02"), london),
        )
    }

    @org.junit.Test
    fun `a run across midnight names both days`() {
        assertEquals(
            "15 June 2024 23:40 – 16 June 2024 00:20",
            Formatting.timeRange(at("2024-06-15T23:40"), at("2024-06-16T00:20"), london),
        )
    }
}
