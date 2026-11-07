package com.example.myapplication.storage

import java.io.File

/**
 * App-private V2 SD spool browser. This index is intentionally independent of
 * BLE connectivity. Sessions that have been promoted into WoonaDatabase carry
 * recordingId and can be hidden from the legacy local-spool section.
 */
object V2LocalSessionIndex {
    data class Entry(
        val deviceHash: String,
        val sequence: Long,
        val recordCount: Int,
        val sensorBytes: Long,
        val totalBytes: Long,
        val hasVideo: Boolean,
        val hasTimeSync: Boolean,
        val recordingId: String?,
        val path: File,
    )

    fun scan(root: File): List<Entry> {
        val devices = File(root, "v2-stored").listFiles().orEmpty().filter { it.isDirectory }
        return devices.flatMap { device ->
            device.listFiles().orEmpty().mapNotNull { dir ->
                if (!dir.isDirectory || !dir.name.startsWith("session-")) return@mapNotNull null
                val seq = dir.name.removePrefix("session-").toLongOrNull()?.takeIf { it > 0L }
                    ?: return@mapNotNull null
                val records = dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.matches(Regex("record-[0-9]{10}\\.frame")) }
                val sensorBytes = records.sumOf(File::length)
                val video = File(dir, "video.mp4")
                val sync = File(dir, "time-sync.json")
                val marker = File(dir, "recording-id.txt")
                val recordingId = marker.takeIf(File::isFile)?.readText(Charsets.UTF_8)
                    ?.trim()?.takeIf(String::isNotEmpty)
                val totalBytes = sensorBytes +
                    video.takeIf(File::isFile)?.length().orZero() +
                    sync.takeIf(File::isFile)?.length().orZero()
                Entry(
                    deviceHash = device.name,
                    sequence = seq,
                    recordCount = records.size,
                    sensorBytes = sensorBytes,
                    totalBytes = totalBytes,
                    hasVideo = video.isFile && video.length() > 0L,
                    hasTimeSync = sync.isFile,
                    recordingId = recordingId,
                    path = dir,
                )
            }
        }.sortedWith(compareByDescending<Entry> { it.path.lastModified() }.thenByDescending { it.sequence })
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
