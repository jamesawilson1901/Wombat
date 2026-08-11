package com.magpie

import com.magpie.domain.RenameHeuristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameHeuristicsTest {

    @Test
    fun junkDetection() {
        assertTrue(RenameHeuristics.isJunk("a3f9c2e1.pdf"))
        assertTrue(RenameHeuristics.isJunk("1738294857.jpg"))
        assertTrue(RenameHeuristics.isJunk("file.pdf"))
        assertTrue(RenameHeuristics.isJunk("download.bin"))
        assertTrue(RenameHeuristics.isJunk("Untitled.docx"))
        assertTrue(RenameHeuristics.isJunk("report%20final%20v2.pdf"))
        assertTrue(RenameHeuristics.isJunk("2024-01-15_133702.png"))
        assertTrue(RenameHeuristics.isJunk("550e8400-e29b-41d4-a716-446655440000.png"))
    }

    @Test
    fun realWordsAreNeverJunk() {
        assertFalse(RenameHeuristics.isJunk("invoice-march.pdf"))
        assertFalse(RenameHeuristics.isJunk("Holiday Photos 2024.zip"))
        assertFalse(RenameHeuristics.isJunk("bank_statement_jan.pdf"))
        // Browser duplicate of a real name isn't junk either
        assertFalse(RenameHeuristics.isJunk("invoice(3).pdf"))
    }

    @Test
    fun duplicateSuffixProposal() {
        assertEquals("invoice.pdf", RenameHeuristics.proposeRename("invoice(3).pdf"))
        assertEquals("invoice.pdf", RenameHeuristics.proposeRename("invoice (2).pdf"))
        // Clash with a sibling → no proposal
        assertNull(RenameHeuristics.proposeRename("invoice(3).pdf", setOf("invoice.pdf")))
        // Junk base → local heuristics leave it for the AI
        assertNull(RenameHeuristics.proposeRename("a3f9c2e1(2).pdf"))
    }

    @Test
    fun urlEncodedProposal() {
        assertEquals("annual report.pdf", RenameHeuristics.proposeRename("annual%20report.pdf"))
    }

    @Test
    fun aiRenameGate() {
        // Original not junk → rejected even if AI proposed something
        assertNull(RenameHeuristics.acceptAiRename("invoice-march.pdf", "renamed.pdf"))
        // Extension change → rejected
        assertNull(RenameHeuristics.acceptAiRename("a3f9c2e1.pdf", "invoice.docx"))
        // Path smuggling → rejected
        assertNull(RenameHeuristics.acceptAiRename("a3f9c2e1.pdf", "../../evil.pdf"))
        // Legit
        assertEquals("tax invoice.pdf", RenameHeuristics.acceptAiRename("a3f9c2e1.pdf", "tax invoice.pdf"))
    }

    @Test
    fun extensionSplit() {
        assertEquals("archive.tar" to ".gz", RenameHeuristics.splitExtension("archive.tar.gz"))
        assertEquals("noext" to "", RenameHeuristics.splitExtension("noext"))
        assertEquals(".hidden" to "", RenameHeuristics.splitExtension(".hidden"))
    }
}
