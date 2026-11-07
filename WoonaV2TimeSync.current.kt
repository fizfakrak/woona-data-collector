package com.example.myapplication.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol-only extension. The BLE layer must timestamp t1 immediately before
 * issuing the first ATT write, and t4 on completed CRC-validated V2 receive.
 * Does NOT change the existing Android capture/video sync until integrated.
 */
object WoonaV2TimeSync {
    const val COMMAND = 0x06
    const val RESPONSE = 0x86
    const val CAPABILITY_BIT = 11
    const val CLOCK_DOMAIN_BOOT_MONOTONIC = 1
    const val RESPONSE_PAYLOAD_SIZE = 36

    data class Reply(
        val requestId: Long,
        val nonce: Long,
        val clockEpochId: Long,
        val deviceRxUs: Long,
        val deviceTxUs: Long,
    )

    data class Sample(
        val clockEpochId: Long,
        val deviceMidUs: Long,
        val phoneMidNs: Long,
        val correctedRttNs: Long,
        val requestId: Long,
    ) {
        /** Maps device monotonic microseconds to Android elapsedRealtimeNanos. */
        fun toAndroidElapsedNs(deviceTimestampUs: Long): Long =
            Math.addExact(phoneMidNs,
                Math.multiplyExact(deviceTimestampUs - deviceMidUs, 1000L))
    }

    /** Nonce is client-generated and unique per attempt; request_id must be globally monotonic per V2 connection. */
    fun requestPayload(nonce: Long): ByteArray {
        require(nonce != 0L)
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(nonce).array()
    }

    fun requestWire(sequence: Long, requestId: Long, nonce: Long): ByteArray {
        require(requestId > 0)
        return WoonaV2Codec.encodeWire(COMMAND, sequence, requestId, requestPayload(nonce))
    }

    /** Invalid, unrelated, stale, error or malformed replies are ignored. */
    fun parseReply(frame: V2Frame, requestId: Long, nonce: Long): Reply? {
        if (frame.type != RESPONSE || requestId <= 0 || frame.requestId != requestId ||
            nonce == 0L || frame.payload.size != RESPONSE_PAYLOAD_SIZE) return null
        val p = frame.payload
        if (p[0].toInt() != 0 || (p[1].toInt() and 0xff) != CLOCK_DOMAIN_BOOT_MONOTONIC ||
            p[2].toInt() != 0 || p[3].toInt() != 0) return null
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getLong(4) != nonce) return null
        val epoch = b.getLong(12)
        val rx = b.getLong(20)
        val tx = b.getLong(28)
        if (epoch == 0L || rx < 0L || tx < rx) return null
        return Reply(requestId, nonce, epoch, rx, tx)
    }

    /** Treat t1 and t4 as actual BLE boundary samples, not queued-command/UI times. */
    fun makeSample(reply: Reply, t1AndroidNs: Long, t4AndroidNs: Long): Sample? {
        if (t1AndroidNs < 0L || t4AndroidNs <= t1AndroidNs) return null
        val deviceElapsedUs = reply.deviceTxUs - reply.deviceRxUs
        if (deviceElapsedUs < 0L || deviceElapsedUs > Long.MAX_VALUE / 1000L) return null
        val correctedRttNs = (t4AndroidNs - t1AndroidNs) - deviceElapsedUs * 1000L
        if (correctedRttNs < 0L) return null
        return Sample(
            clockEpochId = reply.clockEpochId,
            deviceMidUs = reply.deviceRxUs + deviceElapsedUs / 2L,
            phoneMidNs = t1AndroidNs + (t4AndroidNs - t1AndroidNs) / 2L,
            correctedRttNs = correctedRttNs,
            requestId = reply.requestId,
        )
    }

    /** All attempts must belong to the same epoch; prefer minimum transport delay. */
    fun bestSample(samples: List<Sample>): Sample? {
        if (samples.isEmpty() || samples.any { it.clockEpochId != samples[0].clockEpochId }) return null
        return samples.minByOrNull { it.correctedRttNs }
    }
}
