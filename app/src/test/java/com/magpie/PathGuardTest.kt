package com.magpie

import com.magpie.domain.PathGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathGuardTest {

    private val root = "/storage/emulated/0"
    private val sd = "/storage/1234-ABCD"
    private val guard = PathGuard(listOf(root, sd))

    @Test
    fun normalizeCollapsesTraversal() {
        assertEquals("/a/c", PathGuard.normalize("/a/b/../c/"))
        assertEquals("/a/b", PathGuard.normalize("/a//./b"))
        assertEquals("", PathGuard.normalize("/../evil"))
    }

    @Test
    fun deniesOutsideRoots() {
        assertTrue(guard.isDenied("/data/data/com.magpie/secret"))
        assertTrue(guard.isDenied("/system/bin"))
        assertFalse(guard.isDenied("$root/Download/file.pdf"))
        assertFalse(guard.isDenied("$sd/Music/song.mp3"))
    }

    @Test
    fun deniesAndroidAndHiddenPaths() {
        assertTrue(guard.isDenied("$root/Android/data/com.x/file"))
        assertTrue(guard.isDenied("$root/android/media/x"))
        assertTrue(guard.isDenied("$root/Download/.hidden/file"))
        assertTrue(guard.isDenied("$root/.trash/file"))
    }

    @Test
    fun traversalCannotEscapeIntoAndroid() {
        assertTrue(guard.isDenied("$root/Download/../Android/data/x"))
    }

    @Test
    fun destinationMustBeAllowedOrDirectChild() {
        val presets = listOf("$root/Documents/Invoices", "$root/Music")
        assertTrue(guard.isAllowedDestination("$root/Documents/Invoices", presets))
        // New folder directly under an allowed parent is fine
        assertTrue(guard.isAllowedDestination("$root/Music/Jazz", presets))
        assertFalse(guard.isAllowedDestination("$root/Movies", presets))
        assertFalse(guard.isAllowedDestination("$root/Android/data", presets))
    }

    @Test
    fun modelSuggestionsOutsidePresetsAreDropped() {
        val presets = listOf("$root/Documents/Invoices", "$root/Pictures")
        val filtered = guard.filterSuggestions(
            listOf(
                "$root/Documents/Invoices",
                "$root/Documents/Invoices/", // trailing slash normalises to same
                "$root/Android/data",        // denied
                "/etc/passwd",               // outside roots
                "$root/NotAPreset",          // not in preset list
            ),
            presets,
        )
        assertEquals(listOf("$root/Documents/Invoices"), filtered)
    }

    @Test
    fun sourceMustBeUnderWatchedFolder() {
        val watched = listOf("$root/Download")
        assertTrue(guard.isAllowedSource("$root/Download/x.pdf", watched))
        assertTrue(guard.isAllowedSource("$root/Download/sub/x.pdf", watched))
        assertFalse(guard.isAllowedSource("$root/Documents/x.pdf", watched))
    }
}
