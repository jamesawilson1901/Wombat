package com.magpie.filer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings file has one hard rule — no credential ever goes in it — and
 * one soft one: whatever another version of Magpie wrote, read what is
 * readable rather than refusing the lot.
 */
class SettingsFileTest {

    private val portable = SettingsFile.Portable(
        rules = listOf(
            Rule(extension = ".pdf", word = "invoice", folder = "Invoices"),
            Rule(extension = ".jpg", word = "", folder = "Photos"),
        ),
        watchedFolders = listOf("/storage/emulated/0/Pictures/Scans"),
        groupGapMinutes = 120,
    )

    @Test
    fun `settings survive a round trip unchanged`() {
        val back = SettingsFile.read(SettingsFile.write(portable))
        assertEquals(portable, back)
    }

    @Test
    fun `the api key never appears in the file`() {
        // Nothing here even accepts a key, and this pins that: a settings file
        // gets copied to cloud drives, and a credential does not belong in it.
        val text = SettingsFile.write(portable).lowercase()
        assertFalse(text, text.contains("apikey"))
        assertFalse(text, text.contains("sk-ant"))
    }

    @Test
    fun `garbage is null, not a crash and not an empty settings object`() {
        assertNull(SettingsFile.read(null))
        assertNull(SettingsFile.read(""))
        assertNull(SettingsFile.read("not json"))
        // Valid JSON that is not a Magpie settings file must also be refused,
        // or importing the wrong file would silently do nothing forever.
        assertNull(SettingsFile.read("""{"some":"other file"}"""))
    }

    @Test
    fun `a file with only some of the pieces yields the pieces it has`() {
        val sparse = SettingsFile.read("""{"magpie":1,"groupGapMinutes":60}""")

        assertEquals(emptyList<Rule>(), sparse?.rules)
        assertEquals(emptyList<String>(), sparse?.watchedFolders)
        assertEquals(60L, sparse?.groupGapMinutes)
    }

    @Test
    fun `a rule that would match everything is dropped on import`() {
        val text = """{"magpie":1,"rules":[
            {"extension":"","word":"","folder":"Everything"},
            {"extension":".pdf","word":"","folder":"Papers"}
        ]}"""

        val back = SettingsFile.read(text)

        assertEquals(1, back?.rules?.size)
        assertEquals("Papers", back?.rules?.single()?.folder)
    }

    @Test
    fun `a folder entry that is not a path is dropped`() {
        val text = """{"magpie":1,"watchedFolders":["/real/path","not a path",""]}"""
        assertEquals(listOf("/real/path"), SettingsFile.read(text)?.watchedFolders)
    }

    @Test
    fun `a nonsense gap is dropped rather than imported`() {
        assertNull(SettingsFile.read("""{"magpie":1,"groupGapMinutes":-5}""")?.groupGapMinutes)
        assertNull(SettingsFile.read("""{"magpie":1}""")?.groupGapMinutes)
    }

    @Test
    fun `no gap in the file does not become a gap of zero`() {
        val back = SettingsFile.read("""{"magpie":1,"rules":[]}""")
        assertTrue(back != null)
        assertNull(back?.groupGapMinutes)
    }

    @Test
    fun `a newer version's file still yields what this version understands`() {
        val text = """{"magpie":9,"rules":[{"extension":".pdf","word":"","folder":"P"}],
            "somethingNewer":{"deep":true}}"""
        assertEquals(1, SettingsFile.read(text)?.rules?.size)
    }
}
