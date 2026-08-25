package com.magpie.filer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fail-safe is the one part of Magpie where being wrong costs the user
 * their files, so the rules are tested rather than reasoned about.
 */
class SafetyTest {

    private val volumes = listOf("/storage/emulated/0", "/storage/1A2B-3C4D")

    private fun refuse(path: String) = Safety.refuse(path, volumes)

    // ---- ordinary files are allowed ----------------------------------------

    @Test
    fun `a download on internal storage is allowed`() {
        assertNull(refuse("/storage/emulated/0/Download/invoice.pdf"))
    }

    @Test
    fun `a file on the memory card is allowed`() {
        assertNull(refuse("/storage/1A2B-3C4D/Download/holiday.jpg"))
    }

    @Test
    fun `a file deep inside a user folder is allowed`() {
        assertNull(refuse("/storage/emulated/0/Documents/Tax/2025/return.pdf"))
    }

    @Test
    fun `a folder honestly starting with Android is not mistaken for Android data`() {
        assertNull(refuse("/storage/emulated/0/Androidology/notes.txt"))
    }

    // ---- the phone's own files are refused ---------------------------------

    @Test
    fun `system partitions are refused`() {
        for (path in listOf(
            "/system/app/Settings/Settings.apk",
            "/vendor/lib/libc.so",
            "/product/etc/config.xml",
            "/apex/com.android.runtime/lib/bionic",
            "/proc/1/maps",
            "/sys/class/power_supply/battery/capacity",
            "/dev/block/sda1",
            "/boot/kernel",
            "/data/data/com.whatsapp/databases/msgstore.db",
        )) {
            assertNotNull("should have refused $path", refuse(path))
        }
    }

    @Test
    fun `a system path names what it is protecting`() {
        val why = refuse("/system/build.prop")
        assertNotNull(why)
        assertTrue(why!!, why.contains("makes the phone work"))
    }

    @Test
    fun `other apps' private storage is refused`() {
        assertNotNull(refuse("/storage/emulated/0/Android/data/com.other.app/files/thing.db"))
        assertNotNull(refuse("/storage/emulated/0/Android/obb/com.game/main.obb"))
        assertNotNull(refuse("/storage/emulated/0/Android/media/com.app/clip.mp4"))
        assertNotNull(refuse("/storage/1A2B-3C4D/Android/data/com.other.app/x"))
    }

    @Test
    fun `Android's own bookkeeping folders are refused`() {
        assertNotNull(refuse("/storage/1A2B-3C4D/LOST.DIR/file000.chk"))
        assertNotNull(refuse("/storage/emulated/0/.android_secure/thing"))
    }

    // ---- anything unrecognised is refused, not allowed ----------------------

    @Test
    fun `a path outside every known volume is refused`() {
        assertNotNull(refuse("/storage/9999-9999/Download/thing.pdf"))
        assertNotNull(refuse("/mnt/somewhere/else/thing.pdf"))
        assertNotNull(refuse("/thing.pdf"))
    }

    @Test
    fun `a relative path is refused`() {
        assertNotNull(refuse("Download/thing.pdf"))
        assertNotNull(refuse(""))
        assertNotNull(refuse("   "))
    }

    @Test
    fun `climbing back out of a volume is refused`() {
        assertNotNull(refuse("/storage/emulated/0/Download/../../../system/build.prop"))
        assertNotNull(refuse("/storage/emulated/0/../../system"))
    }

    @Test
    fun `the volume root itself is refused`() {
        // Nothing is a file at the root of a volume, and treating the whole
        // volume as a thing to copy would be a mistake worth refusing.
        assertNotNull(refuse("/storage/emulated/0"))
        assertNotNull(refuse("/storage/1A2B-3C4D"))
    }

    @Test
    fun `a volume that only shares a name prefix is not treated as inside it`() {
        // /storage/emulated/01 is a different volume from /storage/emulated/0.
        assertNotNull(refuse("/storage/emulated/01/Download/thing.pdf"))
    }

    @Test
    fun `with no volumes at all nothing is allowed`() {
        assertNotNull(Safety.refuse("/storage/emulated/0/Download/thing.pdf", emptyList()))
    }

    // ---- allows mirrors refuse ---------------------------------------------

    @Test
    fun `allows is the inverse of refuse`() {
        assertTrue(Safety.allows("/storage/emulated/0/Download/a.pdf", volumes))
        assertEquals(false, Safety.allows("/system/build.prop", volumes))
    }
}
