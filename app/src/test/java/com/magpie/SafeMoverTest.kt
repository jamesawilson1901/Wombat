package com.magpie

import com.magpie.domain.SafeMover
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeMoverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun movesAndDeletesOriginal() {
        val src = tmp.newFile("report.pdf").apply { writeText("hello magpie") }
        val dest = tmp.newFolder("dest")

        val moved = SafeMover.move(src, dest)

        assertEquals("report.pdf", moved.finalFile.name)
        assertEquals("hello magpie", moved.finalFile.readText())
        assertFalse(src.exists())
        // No temp litter left behind
        assertTrue(dest.listFiles()!!.none { it.name.endsWith(SafeMover.TMP_SUFFIX) })
    }

    @Test
    fun clashAppendsCounterAndNeverOverwrites() {
        val dest = tmp.newFolder("dest")
        File(dest, "report.pdf").writeText("first")
        File(dest, "report (2).pdf").writeText("second")
        val src = tmp.newFile("report.pdf").apply { writeText("third") }

        val moved = SafeMover.move(src, dest)

        assertEquals("report (3).pdf", moved.finalFile.name)
        assertEquals("first", File(dest, "report.pdf").readText())
        assertEquals("second", File(dest, "report (2).pdf").readText())
    }

    @Test
    fun desiredNameIsUsed() {
        val src = tmp.newFile("a3f9c2e1.pdf").apply { writeText("x") }
        val dest = tmp.newFolder("dest")
        val moved = SafeMover.move(src, dest, desiredName = "tax invoice.pdf")
        assertEquals("tax invoice.pdf", moved.finalFile.name)
    }

    @Test
    fun missingSourceFailsLoudlyAndLeavesNoPartials() {
        val dest = tmp.newFolder("dest")
        val ghost = File(tmp.root, "ghost.pdf")
        try {
            SafeMover.move(ghost, dest)
            assert(false) { "expected IOException" }
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ghost.pdf"))
        }
        assertEquals(0, dest.listFiles()!!.size)
    }

    @Test
    fun largeFileSurvivesRoundTrip() {
        val src = tmp.newFile("big.bin")
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        src.writeBytes(bytes)
        val dest = tmp.newFolder("dest")

        val moved = SafeMover.move(src, dest)

        assertTrue(moved.finalFile.readBytes().contentEquals(bytes))
    }
}
