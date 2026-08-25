package com.magpie.filer.watch

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import com.magpie.filer.core.Safety
import java.io.File

/** A folder Magpie watches, and the words the user sees for it. */
data class WatchRoot(val directory: File, val label: String, val removable: Boolean) {
    val path: String get() = directory.absolutePath
}

/**
 * Works out which folders to watch, every time it is asked, so a card inserted
 * after the service started is picked up and a card pulled out simply stops
 * appearing. Nothing here is hardcoded to a device.
 */
object WatchRoots {

    /** SD cards mount under a FAT volume id: 1A2B-3C4D. */
    private val VOLUME_ID = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")

    /**
     * The ordinary folders a memory card tends to have. Offered for sorting
     * when they exist, skipped silently when they do not, so this works the
     * same on a card laid out any way.
     */
    private val CARD_FOLDERS = listOf(
        "Download", "Downloads", "DCIM", "Pictures", "Movies", "Music",
        "Documents", "Books", "Podcasts", "Recordings", "Bluetooth",
    )

    fun discover(context: Context): List<WatchRoot> {
        val found = LinkedHashMap<String, WatchRoot>()

        fun offer(directory: File?, label: String, removable: Boolean) {
            if (directory == null || !directory.isDirectory) return
            found.putIfAbsent(directory.absolutePath, WatchRoot(directory, label, removable))
        }

        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        @Suppress("DEPRECATION")
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)

        offer(downloads, "Downloads", removable = false)
        // Which of these two exists varies by manufacturer, so watch both.
        offer(File(pictures, "Screenshots"), "Screenshots", removable = false)
        offer(File(dcim, "Screenshots"), "Screenshots", removable = false)

        for (card in removableRoots(context)) {
            offer(File(card, "Download"), "SD card", removable = true)
            offer(File(card, "Downloads"), "SD card", removable = true)
        }

        // Worked out once, not once per folder: it asks Android about storage.
        val volumes = volumePaths(context)
        return found.values.filter { Safety.allows(it.path, volumes) }
    }

    /**
     * The folders offered for sorting by hand, which is a wider set than the
     * folders watched for new arrivals.
     *
     * Watching a folder means a notification every time something lands in it,
     * so that stays deliberately narrow. Sorting is something the user goes
     * looking for, so this reaches across the whole memory card: its ordinary
     * top-level folders, and the card's root itself for anything loose on it.
     * Nothing here is hardcoded to a device, and every path is put through the
     * same safety check as everything else.
     */
    fun sortable(context: Context): List<WatchRoot> {
        val found = LinkedHashMap<String, WatchRoot>()
        discover(context).forEach { found.putIfAbsent(it.path, it) }

        val volumes = volumePaths(context)
        fun offer(directory: File, label: String) {
            if (!directory.isDirectory) return
            if (!Safety.allows(directory.absolutePath, volumes)) return
            found.putIfAbsent(directory.absolutePath, WatchRoot(directory, label, removable = true))
        }

        for (card in removableRoots(context)) {
            offer(card, "SD card")
            for (name in CARD_FOLDERS) offer(File(card, name), "SD card · $name")
        }

        return found.values.toList()
    }

    /**
     * The storage volumes Android says exist: internal storage, and any mounted
     * card. [Safety] treats these as the only places Magpie may touch at all,
     * so everything outside them is refused without having to be listed.
     */
    fun volumePaths(context: Context): List<String> {
        val paths = LinkedHashSet<String>()
        @Suppress("DEPRECATION")
        Environment.getExternalStorageDirectory()?.let { paths += it.absolutePath }
        removableRoots(context).forEach { paths += it.absolutePath }
        return paths.toList()
    }

    private fun removableRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()

        val storage = context.getSystemService(StorageManager::class.java)
        storage?.storageVolumes?.forEach { volume ->
            if (!volume.isRemovable) return@forEach
            if (volume.state != Environment.MEDIA_MOUNTED) return@forEach
            volume.directory?.let { roots += it }
        }

        // Backstop for devices whose StorageVolume reports no directory: every
        // volume the app can touch has a private folder on it, four levels down
        // from the volume root (/storage/1A2B-3C4D/Android/data/<pkg>/files).
        for (directory in context.getExternalFilesDirs(null)) {
            val root = directory?.parentFile?.parentFile?.parentFile?.parentFile ?: continue
            if (root.name.matches(VOLUME_ID) && root.isDirectory) roots += root
        }

        return roots.toList()
    }
}
