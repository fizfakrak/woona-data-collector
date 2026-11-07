package com.example.myapplication.storage

import com.example.myapplication.protocol.WoonaV2StorageCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * Converts one durable V2 STORAGE record into the same packet/timeline semantics
 * used by the ordinary Live recording pipeline.
 *
 * The server never sees the storage transport. Original V2 .frame files stay in
 * the private spool; this class builds the canonical recording artifacts.
 */
object V2SdCanonicalizer {
    data class TimePoint(
        val phoneMonotonicNs: Long,
        val wallClockMillis: Long,
        val deviceTimerMillis: Long,
    )

    class ClockMapping(json: JSONObject) {
        private val deviceAnchorUs = json.getLong("deviceAnchorUs")
        private val androidAnchorNs = json.getLong("androidAnchorElapsedNs")
        private val startPhoneNs = json.getLong("startAcknowledgedAndroidElapsedNs")
        private val startWallMs = json.getLong("startAcknowledgedWallEpochMs")
        private val anchorWallMs = Math.subtractExact(
            startWallMs,
            Math.floorDiv(Math.subtractExact(startPhoneNs, androidAnchorNs), 1_000_000L),
        )

        fun at(timestampUs: Long): TimePoint {
            require(timestampUs >= 0L)
            val phoneNs = Math.addExact(
                androidAnchorNs,
                Math.multiplyExact(Math.subtractExact(timestampUs, deviceAnchorUs), 1_000L),
            )
            val wallMs = Math.addExact(
                anchorWallMs,
                Math.floorDiv(Math.subtractExact(phoneNs, androidAnchorNs), 1_000_000L),
            )
            return TimePoint(
                phoneMonotonicNs = phoneNs,
                wallClockMillis = wallMs,
                deviceTimerMillis = (timestampUs / 1_000L) and 0xffff_ffffL,
            )
        }
    }

    fun sensorType(streamId: Int): Int = when (streamId) {
        1 -> 2 // accel
        2 -> 3 // gyro
        3 -> 4 // contact microphone
        4 -> 5 // ambient microphone
        5 -> 6 // piezo
        6 -> 7 // main-board temperature
        7 -> 8 // ribbon temperature
        else -> error("Unsupported V2 stream $streamId")
    }

    /**
     * Builds one valid legacy-compatible packet containing one sensor block.
     * V2 samples are instant-major/interleaved; the legacy block layout is
     * channel-major, so bytes are transposed without changing 16-bit values.
     */
    fun packet(record: WoonaV2StorageCodec.StoredRecord): ByteArray {
        require(record.streamId in 1..7)
        require(record.channelCount in 1..16)
        require(record.sampleCount > 0)
        require(record.samples.size == record.sampleCount * record.channelCount * 2)
        require(record.flags and 1 != 0) { "Stored block has no valid device timestamp" }

        val payloadBytes = record.samples.size
        val totalBytes = HEADER_SIZE + BLOCK_HEADER_SIZE + payloadBytes
        require(totalBytes <= 16_384)
        val out = ByteArray(totalBytes)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        out[0] = 0x33
        out[1] = 0x99.toByte()
        out[2] = 0xAA.toByte()
        out[3] = 0x55
        b.putShort(4, totalBytes.toShort())
        out[6] = 1 // one measurement block per stored record
        b.putInt(7, record.recordSequence.toInt())
        b.putInt(11, ((record.timestampUs / 1_000L) and 0xffff_ffffL).toInt())
        out[15] = 0

        val block = HEADER_SIZE
        out[block] = sensorType(record.streamId).toByte()
        out[block + 1] = record.channelCount.toByte()
        b.putShort(block + 2, record.sampleCount.toShort())
        out[block + 4] = 0
        out[block + 5] = 0

        val data = block + BLOCK_HEADER_SIZE
        for (channel in 0 until record.channelCount) {
            for (sample in 0 until record.sampleCount) {
                val source = (sample * record.channelCount + channel) * 2
                val target = data + (channel * record.sampleCount + sample) * 2
                out[target] = record.samples[source]
                out[target + 1] = record.samples[source + 1]
            }
        }
        return out
    }

    fun lastSampleTimestampUs(record: WoonaV2StorageCodec.StoredRecord): Long {
        require(record.nativeRateHz > 0L && record.sampleCount > 0)
        val durationUs = Math.multiplyExact((record.sampleCount - 1).toLong(), 1_000_000L) /
            record.nativeRateHz
        return Math.addExact(record.timestampUs, durationUs)
    }

    const val HEADER_SIZE = 16
    const val BLOCK_HEADER_SIZE = 6
    val RAW_MAGIC = byteArrayOf('B'.code.toByte(),'L'.code.toByte(),'E'.code.toByte(),
        'R'.code.toByte(),'A'.code.toByte(),'W'.code.toByte(),'2'.code.toByte(),0)
    val TIMELINE_MAGIC = "BLETIME1".toByteArray(Charsets.US_ASCII)
}
