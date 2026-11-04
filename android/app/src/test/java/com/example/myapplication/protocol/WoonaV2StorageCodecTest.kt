package com.example.myapplication.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WoonaV2StorageCodecTest {
    private fun payload(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun frame(type: Int, payload: ByteArray, requestId: Long = 4) =
        V2Frame(type, 0, 101, requestId, payload)

    @Test fun catalogueAndPagination() {
        val p = payload(16 + 64)
        p.put(0).put(2).put(32).put(1).putInt(0).putLong(12)
        p.putLong(14).putLong(2500).putInt(60).putInt(3).putInt(20).put(1).put(1).putShort(0)
        p.putLong(12).putLong(1400).putInt(20).putInt(1).putInt(6).put(3).put(5).putShort(0)
        val page = WoonaV2StorageCodec.parsePage(frame(0x87, p.array()))!!
        assertEquals(listOf(14L, 12L), page.sessions.map { it.sequence })
        assertTrue(page.hasMore)
        assertEquals(12L, page.nextBeforeSequence)
        assertArrayEquals(payload(8).putLong(12).array(), WoonaV2StorageCodec.listRequest(12))
        assertNull(WoonaV2StorageCodec.parsePage(frame(0x87, p.array().copyOfRange(0, 79))))
        val corrupt = p.array().clone()
        corrupt[16 + 32] = 15 // second sequence no longer descending
        assertNull(WoonaV2StorageCodec.parsePage(frame(0x87, corrupt)))
    }

    @Test fun recordsAreValidatedAndAdvanceOnlyWhenDurable() {
        val b = payload(46)
        b.putLong(14).putInt(0).putInt(33).putLong(12345).putInt(1000)
            .putShort(1).putShort(0).put(1).put(1).put(3).put(0).putShort(6).putShort(0)
            .putShort(5).putShort(6).putShort(7)
        val record = WoonaV2StorageCodec.parseRecord(frame(0x41, b.array(), 0))!!
        assertEquals(0L, record.recordSequence)
        assertEquals(6, record.samples.size)
        val tracker = WoonaV2StorageCodec.PersistedSequenceGuard(14)
        assertTrue(tracker.isExpected(record))
        assertEquals(0L, tracker.firstMissingRecord)
        tracker.markDurable(record)
        assertEquals(1L, tracker.firstMissingRecord)
        assertFalse(tracker.isExpected(record))
        val malformed = b.array().clone()
        malformed[36] = 7 // incorrect payload length
        assertNull(WoonaV2StorageCodec.parseRecord(frame(0x41, malformed, 0)))
    }

    @Test fun endDoesNotMeanServerAck() {
        val b = payload(24).put(0).put(1).putShort(0).putInt(1).putLong(14).putLong(1)
        val end = WoonaV2StorageCodec.parseTransferEnd(frame(0x42, b.array(), 0))!!
        assertTrue(end.completedSuccessfully)
        assertTrue(WoonaV2StorageCodec.PersistedSequenceGuard(14, 1).isComplete(end))
        assertFalse(WoonaV2StorageCodec.PersistedSequenceGuard(14, 0).isComplete(end))
        assertThrows(IllegalArgumentException::class.java) {
            WoonaV2StorageCodec.confirmSessionRequest(14, 0) // no server receipt
        }
    }

    @Test fun recordingAndResumeRequests() {
        val start = WoonaV2StorageCodec.startStorageRequest(3, 120)
        assertEquals(12, start.size)
        assertEquals(2, start[0].toInt())
        assertEquals(120, ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN).getInt(8))
        val resume = WoonaV2StorageCodec.startTransferRequest(14, 90)
        assertEquals(90, ByteBuffer.wrap(resume).order(ByteOrder.LITTLE_ENDIAN).getInt(8))
        assertEquals(0, ByteBuffer.wrap(resume).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
    }
}
