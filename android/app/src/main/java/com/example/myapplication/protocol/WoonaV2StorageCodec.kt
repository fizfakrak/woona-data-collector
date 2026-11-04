package com.example.myapplication.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure V2 STORAGE payload codec. Input [V2Frame] MUST already be CRC/COBS validated
 * by WoonaV2StreamDecoder. This layer does not perform BLE I/O or acknowledge data.
 * Source: firmware docs/protocols/v2-application-protocol.md, §§10–12.
 */
object WoonaV2StorageCodec {
    const val SESSIONS_RESPONSE = 0x87
    const val SESSION_INFO_RESPONSE = 0x88
    const val START_TRANSFER_RESPONSE = 0x89
    const val STOP_TRANSFER_RESPONSE = 0x8A
    const val CONFIRM_SESSION_RESPONSE = 0x8B
    private const val MAX_U32 = 0xffff_ffffL

    data class Session(
        val sequence: Long,
        val startTimestampUs: Long,
        val requestedDurationSeconds: Long,
        val streamMask: Long,
        val recordCount: Long,
        val state: Int,
        val endReason: Int,
    )
    data class Page(val sessions: List<Session>, val hasMore: Boolean, val nextBeforeSequence: Long)
    data class SessionInfo(
        val sequence: Long,
        val state: Int,
        val endReason: Int,
        val streamMask: Long,
        val startTimestampUs: Long,
        val endTimestampUs: Long,
        val requestedDurationSeconds: Long,
        val droppedBlocks: Long,
        val recordCount: Long,
        val samplePayloadBytes: Long,
        val serverReceiptId: Long,
        val ackTimestampUs: Long,
    )
    data class StorageStarted(val sessionId: Long, val streamMask: Long,
                              val durationSeconds: Long, val persistentSequence: Long)
    data class TransferAccepted(val sequence: Long, val startRecordSequence: Long, val totalRecords: Long)
    data class StoredRecord(
        val sessionSequence: Long,
        val recordSequence: Long,
        val sourceSequence: Long,
        val timestampUs: Long,
        val nativeRateHz: Long,
        val sampleCount: Int,
        val flags: Int,
        val streamId: Int,
        val format: Int,
        val channelCount: Int,
        val samples: ByteArray,
    )
    data class TransferEnd(val sessionSequence: Long, val nextRecordSequence: Long, val totalRecords: Long,
                           val status: Int, val reason: Int) {
        val completedSuccessfully: Boolean get() = status == 0 && reason == 1 && nextRecordSequence == totalRecords
    }
    data class Confirmation(val sessionSequence: Long, val receiptId: Long)

    private fun view(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 0xff
    private fun u16(b: ByteBuffer, at: Int) = b.getShort(at).toInt() and 0xffff
    private fun u32(b: ByteBuffer, at: Int) = b.getInt(at).toLong() and MAX_U32
    // Valid deployed storage sequences are non-negative signed Long; avoid silently
    // presenting unsigned overflow as a negative session or record identity.
    private fun positiveU64(b: ByteBuffer, at: Int): Long? = b.getLong(at).takeIf { it >= 0L }

    fun startStorageRequest(streamMask: Long, durationSeconds: Long): ByteArray {
        require(streamMask in 1..MAX_U32 && durationSeconds in 1..MAX_U32)
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(2).put(0).putShort(0).putInt(streamMask.toInt())
            .putInt(durationSeconds.toInt()).array()
    }

    fun parseStorageStarted(frame: V2Frame): StorageStarted? {
        if (frame.type != 0x84 || frame.payload.size != 24 || u8(frame.payload, 0) != 0 ||
            u8(frame.payload, 1) != 2) return null
        val b = view(frame.payload)
        val id = u32(b, 4); val mask = u32(b, 8); val duration = u32(b, 12)
        val persistent = positiveU64(b, 16) ?: return null
        if (id == 0L || mask == 0L || duration == 0L || persistent == 0L) return null
        return StorageStarted(id, mask, duration, persistent)
    }

    fun listRequest(beforeSequence: Long = 0): ByteArray = u64Payload(beforeSequence)
    fun infoRequest(sessionSequence: Long): ByteArray = u64Payload(sessionSequence, nonzero = true)
    fun stopTransferRequest(sessionSequence: Long): ByteArray = u64Payload(sessionSequence, nonzero = true)

    fun startTransferRequest(sessionSequence: Long, firstMissingRecord: Long): ByteArray {
        require(sessionSequence > 0 && firstMissingRecord in 0..MAX_U32)
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(sessionSequence).putInt(firstMissingRecord.toInt()).putInt(0).array()
    }

    /**
     * Caller MUST receive a durable validated server receipt before using this.
     * A successful BLE transfer or locally created file is NOT proof of delivery.
     */
    fun confirmSessionRequest(sessionSequence: Long, verifiedServerReceiptId: Long): ByteArray {
        require(sessionSequence > 0 && verifiedServerReceiptId > 0)
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(sessionSequence).putLong(verifiedServerReceiptId).array()
    }

    private fun u64Payload(value: Long, nonzero: Boolean = false): ByteArray {
        require(value >= 0 && (!nonzero || value > 0))
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }

    fun parsePage(frame: V2Frame): Page? {
        if (frame.type != SESSIONS_RESPONSE || frame.payload.size < 16) return null
        val p = frame.payload
        val n = u8(p, 1)
        if (u8(p, 0) != 0 || n !in 0..3 || u8(p, 2) != 32 || u8(p, 3) !in 0..1 ||
            p.size != 16 + n * 32) return null
        val b = view(p)
        val cursor = positiveU64(b, 8) ?: return null
        val entries = mutableListOf<Session>()
        for (i in 0 until n) {
            val at = 16 + i * 32
            val seq = positiveU64(b, at) ?: return null
            val started = positiveU64(b, at + 8) ?: return null
            if (seq == 0L || (entries.isNotEmpty() && seq >= entries.last().sequence)) return null
            val state = u8(p, at + 28)
            val reason = u8(p, at + 29)
            if (state !in 1..3 || reason !in 0..5) return null
            entries.add(Session(seq, started, u32(b, at + 16), u32(b, at + 20),
                u32(b, at + 24), state, reason))
        }
        val more = u8(p, 3) == 1
        if (more && (entries.isEmpty() || cursor == 0L || cursor > entries.last().sequence)) return null
        return Page(entries, more, cursor)
    }

    fun parseInfo(frame: V2Frame): SessionInfo? {
        if (frame.type != SESSION_INFO_RESPONSE || frame.payload.size != 104 || u8(frame.payload, 0) != 0) return null
        val p = frame.payload; val b = view(p)
        val seq = positiveU64(b, 8) ?: return null
        val start = positiveU64(b, 16) ?: return null
        val end = positiveU64(b, 24) ?: return null
        val count = positiveU64(b, 40) ?: return null
        val bytes = positiveU64(b, 48) ?: return null
        val receipt = positiveU64(b, 56) ?: return null
        val ackAt = positiveU64(b, 64) ?: return null
        if (seq == 0L || u8(p, 1) !in 1..3 || u8(p, 2) !in 0..5) return null
        return SessionInfo(seq, u8(p, 1), u8(p, 2), u32(b, 4), start, end, u32(b, 32),
            u32(b, 36), count, bytes, receipt, ackAt)
    }

    fun parseTransferAccepted(frame: V2Frame): TransferAccepted? {
        if (frame.type != START_TRANSFER_RESPONSE || frame.payload.size != 24 || u8(frame.payload, 0) != 0) return null
        val b = view(frame.payload)
        val seq = positiveU64(b, 8) ?: return null
        val total = positiveU64(b, 16) ?: return null
        val first = u32(b, 4)
        if (seq == 0L || first > total) return null
        return TransferAccepted(seq, first, total)
    }

    fun parseRecord(frame: V2Frame): StoredRecord? {
        if (frame.type != WoonaV2Codec.STORED_DATA || frame.requestId != 0L || frame.payload.size < 40) return null
        val p = frame.payload; val b = view(p)
        val seq = positiveU64(b, 0) ?: return null
        val record = u32(b, 8)
        val timestamp = positiveU64(b, 16) ?: return null
        val rate = u32(b, 24)
        val count = u16(b, 28)
        val stream = u8(p, 32); val format = u8(p, 33); val channels = u8(p, 34)
        val size = u16(b, 36)
        if (seq == 0L || stream !in 1..32 || format !in 1..2 || channels !in 1..16 ||
            rate == 0L || count == 0 || size != p.size - 40 ||
            size.toLong() != count.toLong() * channels * 2L) return null
        return StoredRecord(seq, record, u32(b, 12), timestamp, rate, count, u16(b, 30),
            stream, format, channels, p.copyOfRange(40, p.size))
    }

    fun parseTransferEnd(frame: V2Frame): TransferEnd? {
        if (frame.type != WoonaV2Codec.STORED_TRANSFER_END || frame.requestId != 0L || frame.payload.size != 24) return null
        val p = frame.payload; val b = view(p)
        val seq = positiveU64(b, 8) ?: return null
        val total = positiveU64(b, 16) ?: return null
        if (seq == 0L || u8(p, 1) !in 1..3) return null
        return TransferEnd(seq, u32(b, 4), total, u8(p, 0), u8(p, 1))
    }

    fun parseConfirmation(frame: V2Frame): Confirmation? {
        if (frame.type != CONFIRM_SESSION_RESPONSE || frame.payload.size != 24 || u8(frame.payload, 0) != 0) return null
        val b = view(frame.payload)
        val seq = positiveU64(b, 8) ?: return null
        val receipt = positiveU64(b, 16) ?: return null
        if (seq <= 0L || receipt <= 0L) return null
        return Confirmation(seq, receipt)
    }

    /**
     * Minimal sequence guard. The caller advances it ONLY after an atomic durable
     * save of the record; on restart read firstMissingRecord from the saved manifest.
     */
    class PersistedSequenceGuard(val sessionSequence: Long, firstMissingRecord: Long = 0L) {
        var firstMissingRecord: Long = firstMissingRecord
            private set
        init { require(sessionSequence > 0 && firstMissingRecord in 0..MAX_U32) }
        fun isExpected(record: StoredRecord): Boolean =
            record.sessionSequence == sessionSequence && record.recordSequence == firstMissingRecord
        fun markDurable(record: StoredRecord) {
            require(isExpected(record)) { "Non-contiguous or foreign stored record" }
            firstMissingRecord++
        }
        fun isComplete(end: TransferEnd): Boolean = end.sessionSequence == sessionSequence &&
            end.completedSuccessfully && end.totalRecords == firstMissingRecord
    }
}
