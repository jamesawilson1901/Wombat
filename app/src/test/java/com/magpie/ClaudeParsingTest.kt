package com.magpie

import com.magpie.api.ClaudeParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeParsingTest {

    @Test
    fun parsesCleanJson() {
        val out = ClaudeParsing.parseSuggestion(
            """{"suggestions": ["/s/A", "/s/B", "/s/C"], "new_folder_name": null,
               "rename": {"a3f9.pdf": "invoice.pdf", "ok.pdf": null}}"""
        )!!
        assertEquals(listOf("/s/A", "/s/B", "/s/C"), out.suggestions)
        assertNull(out.newFolderName)
        assertEquals("invoice.pdf", out.renames["a3f9.pdf"])
        assertNull(out.renames["ok.pdf"])
    }

    @Test
    fun stripsMarkdownFences() {
        val out = ClaudeParsing.parseSuggestion(
            "```json\n{\"suggestions\": [\"/s/A\"], \"new_folder_name\": \"Receipts\", \"rename\": {}}\n```"
        )!!
        assertEquals(listOf("/s/A"), out.suggestions)
        assertEquals("Receipts", out.newFolderName)
    }

    @Test
    fun toleratesSurroundingProse() {
        val out = ClaudeParsing.parseSuggestion(
            "Here you go:\n{\"suggestions\": [\"/s/A\"]}\nHope that helps!"
        )!!
        assertEquals(listOf("/s/A"), out.suggestions)
    }

    @Test
    fun garbageReturnsNullNotThrow() {
        assertNull(ClaudeParsing.parseSuggestion("I cannot help with that."))
        assertNull(ClaudeParsing.parseSuggestion(""))
        assertNull(ClaudeParsing.parseSuggestion("{broken json"))
    }

    @Test
    fun parsesFolderDescriptions() {
        val out = ClaudeParsing.parseFolderDescriptions(
            """{"folders": [
                 {"path": "/s/Docs", "description": "Documents", "recommended": true},
                 {"path": "/s/Cache", "description": "App litter", "recommended": false}
               ]}"""
        )!!
        assertEquals(2, out.size)
        assertTrue(out[0].recommended)
        assertEquals("App litter", out[1].description)
    }
}
