package com.magpie

import com.magpie.data.Decision
import com.magpie.data.PresetFolder
import com.magpie.domain.LocalSuggester
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSuggesterTest {

    private val docs = PresetFolder(path = "/s/Documents", name = "Documents", useCount = 1, lastUsed = 100)
    private val invoices = PresetFolder(path = "/s/Invoices", name = "Invoices", useCount = 10, lastUsed = 50)
    private val music = PresetFolder(path = "/s/Music", name = "Music", useCount = 3, lastUsed = 200)
    private val presets = listOf(docs, invoices, music)

    @Test
    fun exactExtensionAndTokenMatchWinsFirst() {
        val decisions = listOf(
            Decision(extension = "pdf", tokens = "invoice acme", chosenFolder = "/s/Invoices", timestamp = 1),
            Decision(extension = "pdf", tokens = "report", chosenFolder = "/s/Documents", timestamp = 2),
        )
        val out = LocalSuggester.suggest("invoice-feb.pdf", decisions, presets)
        assertEquals("/s/Invoices", out.first())
    }

    @Test
    fun extensionOnlyMatchBeatsPresetRecency() {
        val decisions = listOf(
            Decision(extension = "mp3", tokens = "song", chosenFolder = "/s/Music", timestamp = 5),
        )
        val out = LocalSuggester.suggest("track99.mp3", decisions, presets)
        assertEquals("/s/Music", out.first())
    }

    @Test
    fun noDecisionsFallsBackToRecencyThenFrequency() {
        val out = LocalSuggester.suggest("whatever.xyz", emptyList(), presets)
        // music has the most recent lastUsed, then docs, then invoices by frequency
        assertEquals(listOf("/s/Music", "/s/Documents", "/s/Invoices"), out)
    }

    @Test
    fun decisionsPointingAtDeletedPresetsAreSkipped() {
        val decisions = listOf(
            Decision(extension = "pdf", tokens = "invoice", chosenFolder = "/s/Gone", timestamp = 9),
        )
        val out = LocalSuggester.suggest("invoice.pdf", decisions, presets)
        assertEquals(3, out.size)
        assert(out.none { it == "/s/Gone" })
    }

    @Test
    fun tokenising() {
        assertEquals(listOf("bank", "statement", "jan"), LocalSuggester.tokens("bank_statement-jan2024.pdf"))
        assertEquals("pdf", LocalSuggester.extensionOf("Invoice.PDF"))
    }
}
