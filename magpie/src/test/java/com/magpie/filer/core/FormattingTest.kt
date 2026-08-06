package com.magpie.filer.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormattingTest {

    private val uk = Locale.UK

    @Test
    fun `small files are counted in bytes`() {
        assertEquals("0 B", Formatting.fileSize(0, uk))
        assertEquals("999 B", Formatting.fileSize(999, uk))
    }

    @Test
    fun `larger files pick a unit and lose the false precision`() {
        assertEquals("1.0 kB", Formatting.fileSize(1_000, uk))
        assertEquals("9.5 kB", Formatting.fileSize(9_500, uk))
        assertEquals("34 kB", Formatting.fileSize(34_000, uk))
        assertEquals("1.2 MB", Formatting.fileSize(1_200_000, uk))
        assertEquals("2.4 GB", Formatting.fileSize(2_400_000_000, uk))
    }

    @Test
    fun `a size that makes no sense says so rather than lying`() {
        assertEquals("size unknown", Formatting.fileSize(-1, uk))
    }

    @Test
    fun `common types read as words`() {
        assertEquals("PDF", Formatting.typeLabel("report.pdf"))
        assertEquals("JPEG image", Formatting.typeLabel("holiday.JPG"))
        assertEquals("ZIP archive", Formatting.typeLabel("backup.zip"))
        assertEquals("Android app", Formatting.typeLabel("thing.apk"))
    }

    @Test
    fun `unknown types fall back to the extension, not to nothing`() {
        assertEquals("QQQ file", Formatting.typeLabel("mystery.qqq"))
        assertEquals("File", Formatting.typeLabel("mystery"))
    }

    @Test
    fun `content types match the extension so providers do not rename files`() {
        assertEquals("application/pdf", Formatting.mimeType("report.pdf"))
        assertEquals("image/jpeg", Formatting.mimeType("holiday.jpeg"))
        assertEquals("application/octet-stream", Formatting.mimeType("mystery.qqq"))
    }
}
