package com.magpie.filer.watch

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
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

        return found.values.toList()
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
