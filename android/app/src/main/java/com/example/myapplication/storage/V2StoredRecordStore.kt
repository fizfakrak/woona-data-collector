package com.example.myapplication.storage

import com.example.myapplication.protocol.V2Frame
import com.example.myapplication.protocol.WoonaV2Codec
import com.example.myapplication.protocol.WoonaV2StorageCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable, replay-safe raw-frame spool. No app recording DB or server ACK here.
 * Use a dedicated IO executor; never run save()/firstMissing() on the BLE callback
 * or Compose main thread. The app must keep the device identity stable.
 */
class V2StoredRecordStore(root: File, deviceIdentity: String) {
    private val directory: File
    init {
        require(deviceIdentity.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceIdentity.trim().lowercase().toByteArray(Charsets.UTF_8))
        val safeId = digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        directory = File(root, "v2-stored/$safeId")
    }

    private fun sessionDir(sessionSequence: Long): File {
        require(sessionSequence > 0L)
        return File(directory, "session-$sessionSequence")
    }
    data class LocalSession(val sequence: Long, val records: Int,
                            val hasVideo: Boolean, val hasTimeSync: Boolean)

    /** Scan app-private files; never delete already downloaded V2 records. */
    fun localSessions(): List<LocalSession> {
        val files = directory.listFiles().orEmpty()
        return files.mapNotNull { item ->
            if (!item.isDirectory) return@mapNotNull null
            val seq = item.name.removePrefix("session-").takeIf {
                item.name.startsWith("session-") && it.matches(Regex("[0-9]+"))
            }?.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
            val records = item.listFiles().orEmpty().count { file ->
                file.isFile && file.name.matches(Regex("record-[0-9]{10}\\.frame"))
            }
            LocalSession(seq, records, File(item,"video.mp4").let { it.isFile && it.length()>0L },
                File(item,"time-sync.json").isFile)
        }.sortedByDescending { it.sequence }
    }

    /** Shared media/record directory keyed by the same BLE identity and durable session sequence. */
    fun mediaDirectory(sessionSequence: Long): File = sessionDir(sessionSequence).also {
        Files.createDirectories(it.toPath())
    }

    private fun recordFile(sessionSequence: Long, recordSequence: Long): File {
        require(recordSequence in 0..0xffff_ffffL)
        return File(sessionDir(sessionSequence), "record-${recordSequence.toString().padStart(10, '0')}.frame")
    }

    /**
     * Keep original validated V2 bytes (reconstructed raw header and CRC).
     * If this record already exists, it must contain byte-identical samples and
     * metadata; a duplicate with changed contents is an integrity failure.
     */
    @Synchronized
    fun save(frame: V2Frame): WoonaV2StorageCodec.StoredRecord {
        val record = requireNotNull(WoonaV2StorageCodec.parseRecord(frame)) { "Invalid stored V2 record" }
        val raw = WoonaV2Codec.encodeRaw(frame.type, frame.sequence, frame.requestId, frame.payload, frame.flags)
        val target = recordFile(record.sessionSequence, record.recordSequence)
        val targetDirectory = requireNotNull(target.parentFile) { "Stored record has no parent directory" }
        Files.createDirectories(targetDirectory.toPath())
        if (target.exists()) {
            val previous = readValidated(target, record.sessionSequence, record.recordSequence)
            require(previous.samples.contentEquals(record.samples) &&
                previous.timestampUs == record.timestampUs &&
                previous.sourceSequence == record.sourceSequence &&
                previous.streamId == record.streamId && previous.format == record.format &&
                previous.channelCount == record.channelCount && previous.sampleCount == record.sampleCount &&
                previous.flags == record.flags && previous.nativeRateHz == record.nativeRateHz) {
                "Conflicting payload for existing stored record"
            }
            return record
        }
        val tmp = File.createTempFile(".pending-", ".tmp", targetDirectory)
        try {
            FileOutputStream(tmp).use { output ->
                output.write(raw)
                output.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath())
            }
        } finally {
            tmp.delete()
        }
        return record
    }

    /** Resume from first missing valid record; never trust a volatile BLE cursor. */
    @Synchronized
    fun firstMissingRecord(sessionSequence: Long): Long {
        var recordSequence = 0L
        while (recordSequence <= 0xffff_ffffL) {
            val file = recordFile(sessionSequence, recordSequence)
            if (!file.isFile) return recordSequence
            readValidated(file, sessionSequence, recordSequence)
            recordSequence++
        }
        return recordSequence
    }

    private fun readValidated(file: File, sessionSequence: Long, recordSequence: Long):
        WoonaV2StorageCodec.StoredRecord {
        require(file.length() in 24L..WoonaV2Codec.MAX_RAW.toLong()) { "Stored record size corrupt" }
        val decoded = requireNotNull(WoonaV2Codec.decodeRaw(file.readBytes())) { "Stored frame CRC invalid" }
        val record = requireNotNull(WoonaV2StorageCodec.parseRecord(decoded)) { "Stored record malformed" }
        require(record.sessionSequence == sessionSequence && record.recordSequence == recordSequence) {
            "Stored record key mismatch"
        }
        return record
    }
}
