package com.example.myapplication.storage

import com.example.myapplication.protocol.V2Frame
import com.example.myapplication.protocol.WoonaV2Codec
import com.example.myapplication.protocol.WoonaV2StorageCodec

/**
 * Serialized, transport-independent V2 storage state machine.
 * Invoke every method on the same dedicated IO executor. The BLE callback must
 * never perform fsync or firstMissing() directly.
 */
class V2StorageTransferCoordinator(
    private val sendRequest: (type: Int, payload: ByteArray) -> Unit,
    private val firstMissing: (sessionSequence: Long) -> Long,
    private val persist: (V2Frame) -> Unit,
    private val onUpdate: (Snapshot) -> Unit,
) {
    data class Snapshot(
        val sessions: List<WoonaV2StorageCodec.Session> = emptyList(),
        val busy: Boolean = false,
        val downloading: Long? = null,
        val savedRecords: Long = 0,
        val totalRecords: Long = 0,
        val completed: Boolean = false,
        val error: String? = null,
    )

    private enum class Phase { IDLE, CATALOG, WAIT_START, TRANSFERRING, WAIT_STOP }
    private var phase = Phase.IDLE
    private var state = Snapshot()
    private var guard: WoonaV2StorageCodec.PersistedSequenceGuard? = null
    private var cursor = 0L
    private var cancelAfterStart = false
    private val maxCatalogPages = 1024
    private var pageCount = 0

    private fun publish(next: Snapshot) { state = next; onUpdate(next) }
    private fun transmit(type: Int, payload: ByteArray) {
        // sendRequest throws if the GATT transport cannot accept the command.
        sendRequest(type, payload)
    }

    fun refresh() = protect {
        check(phase == Phase.IDLE) { "Storage is busy" }
        phase = Phase.CATALOG
        cursor = 0L
        pageCount = 0
        publish(Snapshot(busy = true))
        transmit(WoonaV2Codec.GET_STORED_SESSIONS, WoonaV2StorageCodec.listRequest())
    }

    fun download(sessionSequence: Long) = protect {
        check(phase == Phase.IDLE) { "Storage is busy" }
        require(sessionSequence > 0L)
        val start = firstMissing(sessionSequence)
        guard = WoonaV2StorageCodec.PersistedSequenceGuard(sessionSequence, start)
        phase = Phase.WAIT_START
        cancelAfterStart = false
        publish(state.copy(busy = true, downloading = sessionSequence,
            savedRecords = start, totalRecords = 0, completed = false, error = null))
        transmit(WoonaV2Codec.START_STORED_TRANSFER,
            WoonaV2StorageCodec.startTransferRequest(sessionSequence, start))
    }

    fun stop() = protect {
        when (phase) {
            Phase.WAIT_START -> cancelAfterStart = true
            Phase.TRANSFERRING -> requestStop()
            else -> Unit
        }
    }

    private fun requestStop() {
        val session = requireNotNull(guard).sessionSequence
        phase = Phase.WAIT_STOP
        transmit(WoonaV2Codec.STOP_STORED_TRANSFER,
            WoonaV2StorageCodec.stopTransferRequest(session))
    }

    fun onFrame(frame: V2Frame) = protect {
        when (frame.type) {
            WoonaV2Codec.ERROR_RESPONSE -> if (phase != Phase.IDLE) {
                val status = frame.payload.firstOrNull()?.toInt()?.and(0xff)
                error("Device refused Storage request (status=${status ?: "unknown"})")
            }
            WoonaV2StorageCodec.SESSIONS_RESPONSE -> if (phase == Phase.CATALOG) {
                val page = requireNotNull(WoonaV2StorageCodec.parsePage(frame)) { "Invalid SD catalog response" }
                val old = state.sessions
                require(page.sessions.none { entry -> old.any { it.sequence == entry.sequence } }) {
                    "Repeated SD session in paginated catalog"
                }
                val all = old + page.sessions
                if (page.hasMore) {
                    check(++pageCount <= maxCatalogPages) { "SD catalog exceeds page limit" }
                    require(page.nextBeforeSequence != 0L &&
                        (cursor == 0L || page.nextBeforeSequence < cursor)) { "Invalid SD catalog cursor" }
                    cursor = page.nextBeforeSequence
                    publish(state.copy(sessions = all))
                    transmit(WoonaV2Codec.GET_STORED_SESSIONS,
                        WoonaV2StorageCodec.listRequest(cursor))
                } else {
                    phase = Phase.IDLE
                    publish(state.copy(sessions = all, busy = false))
                }
            }
            WoonaV2StorageCodec.START_TRANSFER_RESPONSE -> if (phase == Phase.WAIT_START) {
                val accepted = requireNotNull(WoonaV2StorageCodec.parseTransferAccepted(frame)) {
                    "Invalid SD transfer acceptance"
                }
                val g = requireNotNull(guard)
                require(accepted.sequence == g.sessionSequence &&
                    accepted.startRecordSequence == g.firstMissingRecord) { "Unexpected SD resume cursor" }
                require(accepted.totalRecords >= g.firstMissingRecord) { "Invalid total record count" }
                phase = Phase.TRANSFERRING
                publish(state.copy(totalRecords = accepted.totalRecords))
                if (cancelAfterStart) requestStop()
            }
            WoonaV2Codec.STORED_DATA -> if (phase == Phase.TRANSFERRING || phase == Phase.WAIT_START) {
                val record = requireNotNull(WoonaV2StorageCodec.parseRecord(frame)) {
                    "Malformed SD record"
                }
                val g = requireNotNull(guard)
                require(g.isExpected(record)) { "SD sequence gap or wrong session: got ${record.recordSequence}" }
                if (phase == Phase.WAIT_START) {
                    val total = state.sessions.firstOrNull { it.sequence == g.sessionSequence }?.recordCount ?: 0L
                    require(total == 0L || total >= g.firstMissingRecord) { "Invalid SD catalog record count" }
                    phase = Phase.TRANSFERRING
                    publish(state.copy(totalRecords = total))
                }
                persist(frame) // fsync + atomic replacement, before advancing sequence
                g.markDurable(record)
                publish(state.copy(savedRecords = g.firstMissingRecord))
                if (cancelAfterStart) requestStop()
            }
            WoonaV2Codec.STORED_TRANSFER_END -> if (phase == Phase.TRANSFERRING || phase == Phase.WAIT_START) {
                val end = requireNotNull(WoonaV2StorageCodec.parseTransferEnd(frame)) {
                    "Malformed SD transfer end"
                }
                val g = requireNotNull(guard)
                require(g.isComplete(end)) { "Incomplete SD transfer: ${g.firstMissingRecord} persisted" }
                phase = Phase.IDLE
                guard = null
                cancelAfterStart = false
                publish(state.copy(busy = false, downloading = null, totalRecords = end.totalRecords, completed = true))
                // Never send CONFIRM_STORED_SESSION (0x0B) without a server receipt.
            }
            WoonaV2StorageCodec.STOP_TRANSFER_RESPONSE -> if (phase == Phase.WAIT_STOP) {
                val p = frame.payload
                require(p.size == 16 && (p[0].toInt() and 0xff) == 0 &&
                    java.nio.ByteBuffer.wrap(p).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(8) == guard?.sessionSequence) {
                    "Invalid SD stop acknowledgement"
                }
                phase = Phase.IDLE
                guard = null
                cancelAfterStart = false
                publish(state.copy(busy = false, downloading = null))
            }
        }
    }

    fun interrupted(reason: String) {
        phase = Phase.IDLE
        guard = null
        cancelAfterStart = false
        publish(state.copy(busy = false, downloading = null, completed = false, error = reason))
    }

    private inline fun protect(block: () -> Unit) {
        try { block() }
        catch (e: Exception) { interrupted(e.message ?: "V2 Storage failed") }
    }
}
