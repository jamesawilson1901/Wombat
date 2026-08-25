package com.magpie.filer.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clear-up list survives being written down and read back.
 *
 * It is stored as JSON in the app's preferences, which means it has to cope
 * with what an older or newer version of Magpie might have left there. Getting
 * this wrong would either lose the list or, far worse, resurrect an entry
 * claiming a file is safe to remove when the copy is not there.
 */
class FiledTest {

    private val entry = Filed(
        originalPath = "/storage/emulated/0/Download/invoice.pdf",
        originalName = "invoice.pdf",
        size = 248_000L,
        destination = "Invoices",
        savedAs = "Acme invoice Q3.pdf",
        tree = "content://com.android.externalstorage.documents/tree/primary%3AInvoices",
        document = "primary:Invoices/Acme invoice Q3.pdf",
    )

    @Test
    fun `an entry survives a round trip unchanged`() {
        val back = Filed.listFromJson(Filed.listToJson(listOf(entry)))
        assertEquals(listOf(entry), back)
    }

    @Test
    fun `several entries keep their order`() {
        val second = entry.copy(originalPath = "/storage/emulated/0/Download/two.pdf")
        val back = Filed.listFromJson(Filed.listToJson(listOf(entry, second)))
        assertEquals(2, back.size)
        assertEquals(entry.originalPath, back[0].originalPath)
        assertEquals(second.originalPath, back[1].originalPath)
    }

    @Test
    fun `an empty list round trips`() {
        assertEquals(emptyList<Filed>(), Filed.listFromJson(Filed.listToJson(emptyList())))
    }

    @Test
    fun `nothing stored reads as nothing, not as a crash`() {
        assertEquals(emptyList<Filed>(), Filed.listFromJson(null))
        assertEquals(emptyList<Filed>(), Filed.listFromJson(""))
        assertEquals(emptyList<Filed>(), Filed.listFromJson("   "))
    }

    @Test
    fun `text that is not JSON reads as nothing`() {
        assertEquals(emptyList<Filed>(), Filed.listFromJson("not json at all"))
        assertEquals(emptyList<Filed>(), Filed.listFromJson("{\"a\":1}"))
    }

    @Test
    fun `an entry with no path is dropped rather than half read`() {
        // Without the original's path there is nothing to tell the user to
        // clear, and a half-built entry is worse than none.
        assertNull(Filed.fromJson(org.json.JSONObject("""{"name":"x.pdf"}""")))
    }

    @Test
    fun `an entry with no copy to point at is dropped`() {
        // The whole claim is "there is a verified copy over there". Without the
        // tree and document there is no copy to check, so the claim cannot be
        // made and must not be shown.
        assertNull(Filed.fromJson(org.json.JSONObject("""{"path":"/a/b.pdf","tree":"t"}""")))
        assertNull(Filed.fromJson(org.json.JSONObject("""{"path":"/a/b.pdf","document":"d"}""")))
    }

    @Test
    fun `a good entry among bad ones still comes back`() {
        val stored = """
            [{"nonsense":true},
             ${entry.toJson()},
             {"path":"/only/a/path.pdf"}]
        """.trimIndent()

        val back = Filed.listFromJson(stored)

        assertEquals(1, back.size)
        assertEquals(entry.originalPath, back.single().originalPath)
    }

    @Test
    fun `a missing name falls back to the tail of the path`() {
        val json = org.json.JSONObject(
            """{"path":"/storage/emulated/0/Download/thing.pdf","tree":"t","document":"d"}"""
        )

        val back = Filed.fromJson(json)

        assertEquals("thing.pdf", back?.originalName)
        assertEquals("thing.pdf", back?.savedAs)
        assertTrue(back?.destination?.isNotBlank() == true)
    }
}
