package com.magpie.filer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {

    @Test
    fun `part written downloads are skipped`() {
        assertTrue(Naming.isTemporary("holiday.zip.crdownload"))
        assertTrue(Naming.isTemporary("film.mkv.part"))
        assertTrue(Naming.isTemporary("thing.partial"))
        assertTrue(Naming.isTemporary("scratch.tmp"))
        assertTrue(Naming.isTemporary("book.download"))
        assertTrue(Naming.isTemporary("page.opdownload"))
        assertTrue(Naming.isTemporary("linux.iso.!ut"))
        assertTrue(Naming.isTemporary(".hidden-thing.pdf"))
    }

    @Test
    fun `finished downloads are not skipped`() {
        assertFalse(Naming.isTemporary("holiday.zip"))
        assertFalse(Naming.isTemporary("Screenshot_20240115-120000.png"))
        assertFalse(Naming.isTemporary("no-extension-at-all"))
    }

    @Test
    fun `extension is found, or absent`() {
        assertEquals(".pdf", Naming.extension("report.pdf"))
        assertEquals(".gz", Naming.extension("archive.tar.gz"))
        assertEquals("", Naming.extension("no-extension"))
        assertEquals("", Naming.extension("trailing."))
        assertEquals("", Naming.extension(".gitignore"))
        // Not an extension: a full stop mid-sentence in a long name.
        assertEquals("", Naming.extension("notes from the meeting. final draft"))
    }

    @Test
    fun `separators become spaces and the extension survives`() {
        assertEquals("annual report 2024.pdf", Naming.tidy("annual_report_2024.pdf", deep = false))
        assertEquals("holiday photos.zip", Naming.tidy("holiday-photos.zip", deep = false))
    }

    @Test
    fun `query strings and url escapes are dropped`() {
        assertEquals(
            "invoice.pdf",
            Naming.tidy("invoice.pdf?utm_source=email&utm_campaign=jan", deep = false),
        )
        assertEquals("my report.docx", Naming.tidy("my%20report.docx", deep = false))
    }

    @Test
    fun `deep tidy drops hashes and ids but keeps the words`() {
        assertEquals(
            "Quarterly Results.xlsx",
            Naming.tidy("quarterly_results_a1b2c3d4e5f6.xlsx", deep = true),
        )
        assertEquals("Screenshot.png", Naming.tidy("Screenshot_20240115-120000.png", deep = true))
    }

    @Test
    fun `deep tidy leaves a word with a year in it alone`() {
        assertEquals("Screenshot2024 Draft.png", Naming.tidy("Screenshot2024_draft.png", deep = true))
    }

    @Test
    fun `a name that is nothing but an id keeps the id`() {
        assertEquals("a1b2c3d4e5f6.bin", Naming.tidy("a1b2c3d4e5f6.bin", deep = true))
    }

    @Test
    fun `capitalisation is evened out without mangling real spellings`() {
        assertEquals("Final Draft.txt", Naming.tidy("FINAL DRAFT.txt", deep = true))
        assertEquals("iPhone Backup.zip", Naming.tidy("iPhone_backup.zip", deep = true))
    }

    @Test
    fun `an already clean name offers no duplicate suggestion`() {
        assertEquals(listOf("photo.jpg"), Naming.suggestions("photo.jpg"))
    }

    @Test
    fun `suggestions lead with the original and stay distinct`() {
        val suggestions = Naming.suggestions("quarterly_results_a1b2c3d4e5f6.xlsx")
        assertEquals("quarterly_results_a1b2c3d4e5f6.xlsx", suggestions.first())
        assertEquals(suggestions.distinct(), suggestions)
        assertTrue(suggestions.size in 2..3)
        assertTrue(suggestions.all { it.endsWith(".xlsx") })
    }

    @Test
    fun `an edited name keeps the original extension`() {
        assertEquals("new name.pdf", Naming.withExtensionOf("new name", "old.pdf"))
        assertEquals("new name.pdf", Naming.withExtensionOf("new name.pdf", "old.pdf"))
        assertEquals("new name", Naming.withExtensionOf("new name", "old"))
    }

    @Test
    fun `a name has to have something in it that is not the extension`() {
        assertTrue(Naming.isUsable("report.pdf"))
        assertTrue(Naming.isUsable("no extension"))
        assertFalse(Naming.isUsable(""))
        assertFalse(Naming.isUsable("   "))
        // Typing only illegal characters, or only an extension, would file the
        // user's one copy as a hidden dotfile.
        assertFalse(Naming.isUsable(".pdf"))
        assertFalse(Naming.isUsable(Naming.withExtensionOf(Naming.sanitise("?"), "report.pdf")))
    }

    @Test
    fun `characters no filesystem accepts are removed`() {
        assertEquals("a b c.txt", Naming.sanitise("a/b:c.txt"))
        assertEquals("quotes and pipes", Naming.sanitise("\"quotes\" and |pipes|"))
    }
}
