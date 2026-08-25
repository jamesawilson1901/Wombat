package com.magpie.filer.move

import com.magpie.filer.core.FolderPlan
import com.magpie.filer.core.PlanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Building a folder tree, with real directories on disk.
 *
 * The two things worth proving are that it makes what was asked for, and that
 * running it over a folder that already has things in it leaves them alone.
 */
class FolderBuilderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: FakeDocumentStore

    private val brief = """
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

    @Before
    fun setUp() {
        root = temp.newFolder("Chosen")
        store = FakeDocumentStore(root, label = "Chosen")
    }

    private fun build(text: String): BuildReport {
        val plan = FolderPlan.parse(text)
        assertTrue(plan.toString(), plan is PlanResult.Ready)
        return FolderBuilder.build((plan as PlanResult.Ready).folders, store)
    }

    @Test
    fun `the whole tree from the brief really appears on disk`() {
        val report = build(brief)

        assertTrue(report.failed.toString(), report.allWell)
        assertEquals(16, report.made.size)
        assertEquals(0, report.reused.size)

        val top = File(root, "TRIAD_SHORT")
        assertTrue(top.isDirectory)
        for (name in listOf(
            "01_CHARACTER_MASTERS", "02_LOCATION_MASTERS", "03_PROP_MASTERS",
            "04_SHOT_STILLS", "05_VIDEO_TESTS", "06_VIDEO_FINALS", "07_SOUND", "08_EDITS",
        )) {
            assertTrue(name, File(top, name).isDirectory)
        }
        val stills = File(top, "04_SHOT_STILLS")
        for (shot in listOf(
            "S01_ARRIVAL", "S02_TOWN", "S03_OLD_PATH", "S04_RESTRAINT",
            "S05_MISTAKE", "S06_RESCUE", "S07_IRON_RING",
        )) {
            assertTrue(shot, File(stills, shot).isDirectory)
        }
    }

    @Test
    fun `building the same tree twice makes nothing new and breaks nothing`() {
        build(brief)
        val second = build(brief)

        assertTrue(second.failed.toString(), second.allWell)
        assertEquals("nothing should be made the second time", 0, second.made.size)
        assertEquals(16, second.reused.size)
    }

    @Test
    fun `a folder that already has files in it keeps them`() {
        val stills = File(File(root, "TRIAD_SHORT"), "04_SHOT_STILLS")
        stills.mkdirs()
        val precious = File(stills, "already-here.png")
        precious.writeText("do not lose me")

        val report = build(brief)

        assertTrue(report.failed.toString(), report.allWell)
        assertTrue("the existing folder must be reused", "TRIAD_SHORT/04_SHOT_STILLS" in report.reused)
        assertTrue(precious.isFile)
        assertEquals("do not lose me", precious.readText())
        assertTrue(File(stills, "S01_ARRIVAL").isDirectory)
    }

    @Test
    fun `filling in a partly built tree only adds what is missing`() {
        File(File(root, "TRIAD_SHORT"), "07_SOUND").mkdirs()

        val report = build(brief)

        assertTrue(report.allWell)
        assertTrue("TRIAD_SHORT" in report.reused)
        assertTrue("TRIAD_SHORT/07_SOUND" in report.reused)
        assertTrue("TRIAD_SHORT/08_EDITS" in report.made)
    }

    @Test
    fun `a folder that refuses is reported and its children are not attempted`() {
        store.refuseFolders = true

        val report = build("A/\n  B/\n    C/")

        assertFalse(report.allWell)
        assertEquals(0, report.made.size)
        assertEquals(3, report.failed.size)
        assertTrue(report.failed.first().second.contains("refused"))
        // B and C could not even be tried, and say so rather than repeating the
        // top-level reason as though each had been attempted.
        assertTrue(report.failed[1].second.contains("parent"))
    }

    @Test
    fun `a folder that cannot be listed is reported rather than written into blindly`() {
        store.listFails = "permission withdrawn"

        val report = build("A/")

        assertFalse(report.allWell)
        assertTrue(report.failed.single().second.contains("permission withdrawn"))
    }

    @Test
    fun `an empty plan does nothing at all`() {
        val report = FolderBuilder.build(emptyList(), store)

        assertTrue(report.allWell)
        assertEquals(0, report.total)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun `nothing outside the chosen folder is ever touched`() {
        val sibling = temp.newFolder("Sibling")
        File(sibling, "untouched.txt").writeText("still here")

        build(brief)

        assertEquals("still here", File(sibling, "untouched.txt").readText())
        assertEquals(
            "everything made must be inside the chosen folder",
            listOf("TRIAD_SHORT"),
            root.listFiles()!!.map { it.name },
        )
    }
}
