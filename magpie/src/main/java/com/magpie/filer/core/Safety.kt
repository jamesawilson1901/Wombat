package com.magpie.filer.core

/**
 * The rules about what Magpie is allowed to touch at all.
 *
 * Two things are guaranteed here, and they are guaranteed by there being no
 * code that could do otherwise rather than by a setting anyone can flip:
 *
 *  - **Magpie removes exactly one thing, ever:** the original of a move it has
 *    just verified byte for byte. Filing is copy, verify, then remove — a move
 *    that could not be verified leaves the original untouched. Nothing at a
 *    destination can be deleted at all: the destination interface has no
 *    delete method, so there is no call to reach for.
 *  - **Magpie only ever touches ordinary files on your own storage.** Anything
 *    outside the storage volumes Android reports — and the parts of those
 *    volumes that belong to Android and to other apps — is refused before a
 *    single byte is read or written.
 *
 * The path rules are plain string work on purpose, so they can be unit tested
 * without a device. [refuse] is the one function that decides, and both the
 * copier and the folder listing ask it.
 */
object Safety {

    /** Where a copy goes when something of that name is already there. */
    const val DUPLICATES_FOLDER = "Duplicates"

    /**
     * Top-level directories that belong to Android itself. Nothing Magpie does
     * has any business here. This is a backstop: the volume check below should
     * already have refused these, and it stays because being wrong about this
     * once is worse than the cost of checking twice.
     */
    private val SYSTEM_ROOTS = listOf(
        "/system", "/vendor", "/product", "/apex", "/odm", "/oem", "/efs",
        "/persist", "/metadata", "/proc", "/sys", "/dev", "/config", "/boot",
        "/cache", "/root", "/bin", "/sbin", "/etc", "/init", "/lib", "/lib64",
        "/linkerconfig", "/mnt/vendor", "/d", "/debug_ramdisk", "/data_mirror",
    )

    /**
     * Inside a storage volume these still are not yours to move about: they are
     * other apps' private storage, and Android's own bookkeeping. Matched as
     * whole path segments, so a folder honestly called "Androidology" is fine.
     */
    private val PROTECTED_WITHIN_VOLUME = listOf(
        listOf("Android", "data"),
        listOf("Android", "obb"),
        listOf("Android", "media"),
        listOf(".android_secure"),
        listOf("LOST.DIR"),
    )

    /**
     * Why [path] must not be touched, or null when it is an ordinary file of
     * the user's that Magpie may read and copy.
     *
     * [volumes] are the storage roots Android reported — internal storage and
     * any mounted card. The check is an allowlist: a path has to be inside one
     * of them to get anywhere, so a route nobody thought of is refused by
     * default rather than allowed by default.
     */
    fun refuse(path: String, volumes: List<String>): String? {
        if (path.isBlank()) return "that is an empty path"
        if (!path.startsWith("/")) return "\"$path\" is not a full path"

        val segments = segmentsOf(path)
        if (segments.isEmpty()) return "\"$path\" is the root of the device"
        if (segments.any { it == ".." }) {
            return "\"$path\" points back up out of itself, which Magpie will not follow"
        }

        for (root in SYSTEM_ROOTS) {
            if (path == root || path.startsWith("$root/")) {
                return "$root belongs to Android — it is part of what makes the phone work"
            }
        }

        val volume = volumes
            .map { segmentsOf(it) }
            .filter { it.isNotEmpty() && segments.size > it.size && segments.startsWith(it) }
            .maxByOrNull { it.size }
            ?: return "\"$path\" is not on your internal storage or a memory card"

        val rest = segments.drop(volume.size)
        for (guarded in PROTECTED_WITHIN_VOLUME) {
            if (rest.startsWith(guarded)) {
                return "${guarded.joinToString("/")} belongs to Android and to your other " +
                    "apps, so Magpie leaves it alone"
            }
        }

        return null
    }

    /** True when every file involved is one Magpie may touch. */
    fun allows(path: String, volumes: List<String>): Boolean = refuse(path, volumes) == null

    private fun segmentsOf(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() && it != "." }

    private fun List<String>.startsWith(prefix: List<String>): Boolean {
        if (prefix.size > size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
