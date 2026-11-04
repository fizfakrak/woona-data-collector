package com.example.myapplication.storage

import com.example.myapplication.protocol.V2Frame
import com.example.myapplication.protocol.WoonaV2StorageCodec

/**
 * Transport-neutral orchestration for V2 SD catalogue and resumable download.
 * All methods must run on ONE serialized worker; persist and firstMissing must
 * execute on an IO worker, never directly on the BLE notification callback.
 * The caller owns request IDs, response timeouts and BLE frame validation.
 */
class V2StorageTransferCoordinator(
    private val sendRequest: (type: Int, payload: ByteArray) -> Unit,
    private val firstMissing: (sessionSequence: Long) -> Long,
    private val persist: (V2Frame) -> Unit,
    private val onUpdate: (Snapshot) -> Unit,
) {
    data class Snapshot(
        val sessions: List<WoonaV2StorageCodec.Session> = emptyList(),
        val downloading: Long? = null,
        val savedRecords: Long = 0,
        val totalRecords: Long = 0,
        val completed: Boolean = false,
        val error: String? = null,
    )

    private var state = Snapshot()
    private var guard: WoonaV2StorageCodec.PersistedSequenceGuard? = null
    private var catalogCursor = 0L
    private var waitingForPage = false
    private var waitingForStart = false
    private fun publish(next: Snapshot) { state = next; onUpdate(next) }

    fun refresh() {
        require(state.downloading == null) { "Cannot refresh during download" }
        catalogCursor = 0L
        waitingForPage = true
        publish(Snapshot())
        sendRequest(0x07, WoonaV2StorageCodec.listRequest())
    }

    fun download(sessionSequence: Long) {
        require(state.downloading == null && !waitingForPage) { "Storage operation busy" }
        val cursor = firstMissing(sessionSequence)
        guard = WoonaV2StorageCodec.PersistedSequenceGuard(sessionSequence, cursor)
        waitingForStart = true
        publish(state.copy(downloading = sessionSequence, savedRecords = cursor,
            totalRecords = 0, completed = false, error = null))
        sendRequest(0x09, WoonaV2StorageCodec.startTransferRequest(sessionSequence, cursor))
    }

    fun stop() {
        val session = state.downloading ?: return
        sendRequest(0x0A, WoonaV2StorageCodec.stopTransferRequest(session))
        guard = null
        waitingForStart = false
        publish(state.copy(downloading = null))
    }

    fun onFrame(frame: V2Frame) {
        try {
            when (frame.type) {
                0x87 -> {
                    if (!waitingForPage) return
                    val page = requireNotNull(WoonaV2StorageCodec.parsePage(frame)) { "Bad SD catalog page" }
                    val entries = state.sessions + page.sessions
                    if (page.hasMore) {
                        require(page.nextBeforeSequence != catalogCursor) { "Non-advancing SD cursor" }
                        catalogCursor = page.nextBeforeSequence
                        publish(state.copy(sessions = entries))
                        sendRequest(0x07, WoonaV2StorageCodec.listRequest(catalogCursor))
                    } else {
                        waitingForPage = false
                        publish(state.copy(sessions = entries))
                    }
                }
                0x89 -> {
                    if (!waitingForStart) return
                    val accepted = requireNotNull(WoonaV2StorageCodec.parseTransferAccepted(frame)) { "Bad transfer acceptance" }
                    val g = requireNotNull(guard)
                    require(accepted.sequence == g.sessionSequence &&
                        accepted.startRecordSequence == g.firstMissingRecord) { "Wrong transfer cursor" }
                    waitingForStart = false
                    publish(state.copy(totalRecords = accepted.totalRecords))
                }
                0x41 -> {
                    if (waitingForStart) return
                    val record = requireNotNull(WoonaV2StorageCodec.parseRecord(frame)) { "Bad stored record" }
                    val g = guard ?: return
                    require(g.isExpected(record)) { "Gap, duplicate or wrong session" }
                    persist(frame) // MUST durably fsync before returning
                    g.markDurable(record)
                    publish(state.copy(savedRecords = g.firstMissingRecord))
                }
                0x42 -> {
                    val g = guard ?: return
                    val end = requireNotNull(WoonaV2StorageCodec.parseTransferEnd(frame)) { "Bad transfer end" }
                    require(g.isComplete(end)) { "Incomplete SD transfer" }
                    guard = null
                    publish(state.copy(downloading = null, completed = true))
                    // Never confirm 0x0B here. Only a verified server receipt can authorize it.
                }
            }
        } catch (e: Exception) {
            waitingForPage = false
            waitingForStart = false
            guard = null
            publish(state.copy(downloading = null, error = e.message ?: "SD transfer failed"))
        }
    }
}
