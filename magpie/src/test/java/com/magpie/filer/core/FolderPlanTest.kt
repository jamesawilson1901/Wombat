package com.magpie.filer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a pasted folder tree.
 *
 * The failure that would matter is a name escaping into somewhere it was not
 * meant to go, so the cleaning is checked as hard as the nesting.
 */
class FolderPlanTest {

    private fun paths(text: String): List<String> {
        val result = FolderPlan.parse(text)
        assertTrue(result.toString(), result is PlanResult.Ready)
        return (result as PlanResult.Ready).folders.map { it.path }
    }

    private fun rejection(text: String): String {
        val result = FolderPlan.parse(text)
        assertTrue(result.toString(), result is PlanResult.Rejected)
        return (result as PlanResult.Rejected).reason
    }

    // ---- the shape people actually paste ------------------------------------

    @Test
    fun `the tree from the brief comes out whole and in order`() {
        val text = """
            TRIAD_SHORT/
              01_CHARACTER_MASTERS/
              02_LOCATION_MASTERS/
              03_PROP_MASTERS/
              04_SHOT_STILLS/
                S01_ARRIVAL/
                S02_TOWN/
                S03_OLD_PATH/
                S04_RESTRAINT/
                S05_MISTAKE/
                S06_RESCUE/
                S07_IRON_RING/
              05_VIDEO_TESTS/
              06_VIDEO_FINALS/
              07_SOUND/
              08_EDITS/
        """.trimIndent()

        assertEquals(
            listOf(
                "TRIAD_SHORT",
                "TRIAD_SHORT/01_CHARACTER_MASTERS",
                "TRIAD_SHORT/02_LOCATION_MASTERS",
                "TRIAD_SHORT/03_PROP_MASTERS",
                "TRIAD_SHORT/04_SHOT_STILLS",
                "TRIAD_SHORT/04_SHOT_STILLS/S01_ARRIVAL",
                "TRIAD_SHORT/04_SHOT_STILLS/S02_TOWN",
                "TRIAD_SHORT/04_SHOT_STILLS/S03_OLD_PATH",
                "TRIAD_SHORT/04_SHOT_STILLS/S04_RESTRAINT",
                "TRIAD_SHORT/04_SHOT_STILLS/S05_MISTAKE",
                "TRIAD_SHORT/04_SHOT_STILLS/S06_RESCUE",
                "TRIAD_SHORT/04_SHOT_STILLS/S07_IRON_RING",
                "TRIAD_SHORT/05_VIDEO_TESTS",
                "TRIAD_SHORT/06_VIDEO_FINALS",
                "TRIAD_SHORT/07_SOUND",
                "TRIAD_SHORT/08_EDITS",
            ),
            paths(text),
        )
    }

    @Test
    fun `a parent always comes before its children`() {
        val order = paths(
            """
            A/
              B/
                C/
            """.trimIndent()
        )
        assertEquals(listOf("A", "A/B", "A/B/C"), order)
    }

    @Test
    fun `trailing slashes are optional`() {
        assertEquals(listOf("A", "A/B"), paths("A\n  B"))
    }

    @Test
    fun `blank lines and comments are skipped`() {
        assertEquals(
            listOf("A", "A/B"),
            paths("# a note\n\nA/\n\n  # another\n  B/\n\n"),
        )
    }

    @Test
    fun `tabs indent as well as spaces, and the two can be mixed`() {
        assertEquals(listOf("A", "A/B", "A/B/C"), paths("A/\n\tB/\n\t  C/"))
    }

    @Test
    fun `the indent width does not have to be consistent`() {
        // Pasted text often is not. Only "further in than the line above"
        // matters.
        assertEquals(
            listOf("A", "A/B", "A/B/C", "A/D"),
            paths("A/\n    B/\n         C/\n    D/"),
        )
    }

    @Test
    fun `coming back out lands in the right parent`() {
        assertEquals(
            listOf("A", "A/B", "A/B/C", "A/D", "E", "E/F"),
            paths("A/\n  B/\n    C/\n  D/\nE/\n  F/"),
        )
    }

    @Test
    fun `a line can carry its own slashes and still nest`() {
        assertEquals(
            listOf("A", "A/B", "A/B/C"),
            paths("A/B/C/"),
        )
    }

    @Test
    fun `slashes on a line combine with the indenting above it`() {
        assertEquals(
            listOf("TOP", "TOP/MID", "TOP/MID/LEAF"),
            paths("TOP/\n  MID/LEAF/"),
        )
    }

    @Test
    fun `the same folder named twice is only made once`() {
        assertEquals(listOf("A", "A/B"), paths("A/\n  B/\nA/\n  B/"))
    }

    @Test
    fun `a flat list with no indenting is a set of top level folders`() {
        assertEquals(listOf("One", "Two", "Three"), paths("One/\nTwo/\nThree/"))
    }

    // ---- names that must not get through ------------------------------------

    @Test
    fun `a name that would climb out is refused`() {
        assertTrue(rejection("A/\n  ../\n").contains("cannot be"))
        assertTrue(rejection("../../etc/").contains("cannot be"))
    }

    @Test
    fun `a hidden folder name is refused`() {
        assertTrue(rejection(".secret/").contains("dot"))
        assertTrue(rejection("A/\n  .hidden/").contains("dot"))
    }

    @Test
    fun `a name of nothing but illegal characters is refused`() {
        assertTrue(rejection("A/\n  ***/").isNotBlank())
    }

    @Test
    fun `illegal characters inside a name are cleaned out rather than refused`() {
        // The name is still recognisable, so it is worth keeping.
        val made = paths("""My: Folder?/""")
        assertEquals(1, made.size)
        assertTrue(made.single(), made.single().contains("My"))
        assertTrue(made.single(), !made.single().contains(":"))
        assertTrue(made.single(), !made.single().contains("?"))
    }

    // ---- limits --------------------------------------------------------------

    @Test
    fun `an empty box is refused with something to act on`() {
        assertTrue(rejection("").contains("nothing to make"))
        assertTrue(rejection("   \n  \n").contains("nothing to make"))
    }

    @Test
    fun `text with no readable names is refused`() {
        assertTrue(rejection("#just a comment\n#and another").contains("No folder names"))
    }

    @Test
    fun `too deep is refused, naming the offending path`() {
        val deep = (1..FolderPlan.MAX_DEPTH + 2).joinToString("/") { "L$it" }
        assertTrue(rejection(deep).contains("deep"))
    }

    @Test
    fun `too many is refused rather than half made`() {
        val many = (1..FolderPlan.MAX_FOLDERS + 5).joinToString("\n") { "F$it/" }
        assertTrue(rejection(many).contains("more than"))
    }

    @Test
    fun `right up to the limits is still accepted`() {
        val many = (1..FolderPlan.MAX_FOLDERS).joinToString("\n") { "F$it/" }
        assertEquals(FolderPlan.MAX_FOLDERS, paths(many).size)
    }
}
