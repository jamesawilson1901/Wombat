package com.magpie.filer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules decide where a file goes without asking anyone, so they have to be
 * predictable. The two failures that would matter are a rule that quietly
 * matches everything, and a rule that matches nothing you would expect.
 */
class RulesTest {

    private val invoices = Rule(extension = ".pdf", word = "invoice", folder = "Invoices")
    private val allPdfs = Rule(extension = ".pdf", word = "", folder = "Papers")

    // ---- matching ----------------------------------------------------------

    @Test
    fun `a rule with an extension and a word needs both`() {
        assertEquals(invoices, Rules.match("acme invoice march.pdf", listOf(invoices)))
        assertNull("wrong extension", Rules.match("acme invoice march.docx", listOf(invoices)))
        assertNull("no such word", Rules.match("acme receipt march.pdf", listOf(invoices)))
    }

    @Test
    fun `matching ignores case in the filename`() {
        assertEquals(invoices, Rules.match("ACME INVOICE.PDF", listOf(invoices)))
    }

    @Test
    fun `an extension only rule takes anything of that type`() {
        assertEquals(allPdfs, Rules.match("anything at all.pdf", listOf(allPdfs)))
        assertNull(Rules.match("anything at all.jpg", listOf(allPdfs)))
    }

    @Test
    fun `a word only rule takes any type`() {
        val payslips = Rule(extension = "", word = "payslip", folder = "Work")
        assertEquals(payslips, Rules.match("payslip june.pdf", listOf(payslips)))
        assertEquals(payslips, Rules.match("payslip june.png", listOf(payslips)))
    }

    @Test
    fun `the first matching rule wins, so order is the user's to control`() {
        val order = listOf(invoices, allPdfs)
        assertEquals(invoices, Rules.match("an invoice.pdf", order))
        assertEquals(allPdfs, Rules.match("something else.pdf", order))

        val reversed = listOf(allPdfs, invoices)
        assertEquals(allPdfs, Rules.match("an invoice.pdf", reversed))
    }

    @Test
    fun `no rules means no match, not a crash`() {
        assertNull(Rules.match("thing.pdf", emptyList()))
    }

    @Test
    fun `a rule that would match everything is never applied`() {
        // Both fields empty means "any name, any type", which would file the
        // whole world into one folder the first time it was reached.
        val everything = Rule(extension = "", word = "", folder = "Everything")
        assertFalse(everything.usable)
        assertNull(Rules.match("anything.pdf", listOf(everything)))
    }

    @Test
    fun `a rule with no folder is never applied`() {
        val nowhere = Rule(extension = ".pdf", word = "", folder = "")
        assertFalse(nowhere.usable)
        assertNull(Rules.match("thing.pdf", listOf(nowhere)))
    }

    // ---- what gets offered -------------------------------------------------

    @Test
    fun `the word offered comes from the name and is the longest real word`() {
        val rule = Rules.suggestFor("acme_invoice_2024_q3.pdf", "Invoices")
        assertEquals(".pdf", rule?.extension)
        assertEquals("invoice", rule?.word)
        assertEquals("Invoices", rule?.folder)
    }

    @Test
    fun `numbers and dates are never taken as the word`() {
        val rule = Rules.suggestFor("20240317_142233.jpg", "Photos")
        // Nothing in that name is a word, so the rule is extension-only.
        assertEquals("", rule?.word)
        assertEquals(".jpg", rule?.extension)
    }

    @Test
    fun `a name with nothing distinctive still offers the extension`() {
        val rule = Rules.suggestFor("a1.pdf", "Papers")
        assertEquals(".pdf", rule?.extension)
        assertEquals("", rule?.word)
        assertTrue(rule?.usable == true)
    }

    @Test
    fun `nothing is offered when there is no folder to file into`() {
        assertNull(Rules.suggestFor("invoice.pdf", ""))
    }

    @Test
    fun `nothing is offered for a name with neither word nor extension`() {
        assertNull(Rules.suggestFor("12345", "Somewhere"))
    }

    @Test
    fun `an offered rule matches the file it came from`() {
        val name = "acme_invoice_2024_q3.pdf"
        val rule = requireNotNull(Rules.suggestFor(name, "Invoices"))
        assertEquals(rule, Rules.match(name, listOf(rule)))
    }

    // ---- storage -----------------------------------------------------------

    @Test
    fun `rules survive a round trip`() {
        val rules = listOf(invoices, allPdfs)
        assertEquals(rules, Rules.fromJson(Rules.toJson(rules)))
    }

    @Test
    fun `nothing stored reads as no rules`() {
        assertEquals(emptyList<Rule>(), Rules.fromJson(null))
        assertEquals(emptyList<Rule>(), Rules.fromJson(""))
        assertEquals(emptyList<Rule>(), Rules.fromJson("not json"))
    }

    @Test
    fun `a stored rule that matches everything is dropped on the way in`() {
        // Belt and braces: even if one is somehow written down, it does not
        // come back out and start filing things.
        val stored = """[{"extension":"","word":"","folder":"Everything"}]"""
        assertEquals(emptyList<Rule>(), Rules.fromJson(stored))
    }

    @Test
    fun `a description says what the rule will actually do`() {
        assertTrue(invoices.describe().contains("invoice"))
        assertTrue(invoices.describe().contains(".pdf"))
        assertTrue(invoices.describe().contains("Invoices"))
    }
}
