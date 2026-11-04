package com.example.myapplication.storage

import com.example.myapplication.protocol.V2Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class V2StoredRecordStoreTest {
    private fun frame(seq: Long = 0, sample: Short = 12): V2Frame {
        val b = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(99).putInt(seq.toInt()).putInt(1).putLong(123).putInt(1000)
            .putShort(1).putShort(0).put(5).put(1).put(1).put(0).putShort(2).putShort(0)
            .putShort(sample)
        return V2Frame(0x41, 0, seq + 100, 0, b.array())
    }
    @Test fun persistsAndResumesWithoutInventingMissingRecords() {
        val root = Files.createTempDirectory("v2spool-test").toFile()
        try {
            val store = V2StoredRecordStore(root, "FA:21:44:01:19:E2")
            assertEquals(0L, store.firstMissingRecord(99))
            store.save(frame(0))
            assertEquals(1L, store.firstMissingRecord(99))
            store.save(frame(2))
            assertEquals(1L, V2StoredRecordStore(root, "FA:21:44:01:19:E2").firstMissingRecord(99))
            store.save(frame(1))
            assertEquals(3L, store.firstMissingRecord(99))
            store.save(frame(1)) // identical transfer replay
            assertThrows(IllegalArgumentException::class.java) { store.save(frame(1, 13)) }
        } finally { root.deleteRecursively() }
    }
}
