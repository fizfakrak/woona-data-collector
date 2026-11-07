package com.example.myapplication.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LIVE session controller for an already negotiated V2 connection.
 * This class has no Android or GATT dependencies. Its Transmit events must be
 * serialized by the GATT write queue; response timeouts begin only after the
 * last ATT Write Request has been acknowledged.
 */
class WoonaV2LiveSession(private val streams: V2Streams) {
    init { require(WoonaV2Codec.validStreams(streams)) { "Invalid V2 stream descriptors" } }
    enum class Phase { IDLE, WAIT_START, RUNNING, WAIT_STOP, STOPPED, FAILED }

    sealed interface Event {
        data class Transmit(val bytes: ByteArray) : Event
        data class Started(val sessionId: Long, val streamMask: Long) : Event
        data class Samples(
            val frameSequence: Long,
            val block: V2LiveData,
            val descriptor: V2Stream,
            val sequenceGap: Boolean,
        ) : Event
        data class Stopped(val sessionId: Long) : Event
        data class Failure(val reason: String) : Event
    }

    var phase = Phase.IDLE
        private set
    var sessionId = 0L
        private set
    private var activeMask = 0L
    private var pendingRequestId = 0L
    private var pendingCommandType = 0
    private var pendingWire = ByteArray(0)
    private var retries = 0
    private var nextSequence = 4L // HELLO/STREAMS/STATUS have already used 1..3.
    private var nextRequestId = 4L
    private val lastStreamSequence = mutableMapOf<Int, Long>()
    private var lastFrameSequence: Long? = null
    private var pendingWireGap = false

    /** Reserves IDs for a STORAGE command on the same negotiated V2 connection. */
    fun reserveExternalCommandIds(): Pair<Long, Long> {
        check(phase == Phase.IDLE || phase == Phase.STOPPED) { "LIVE is active" }
        check(nextRequestId <= U32_MAX && nextSequence <= U32_MAX) {
            "Reconnect before V2 command ID rollover"
        }
        return Pair(nextSequence++, nextRequestId++)
    }

    fun start(streamMask: Long): Event.Transmit {
        require(phase == Phase.IDLE || phase == Phase.STOPPED) { "Cannot start LIVE in $phase" }
        require(streamMask in 1..U32_MAX && (streamMask and streams.streamMask) == streamMask) { "Unsupported stream mask" }
        require(streams.descriptors.any { (streamMask and (1L shl (it.id - 1))) != 0L })
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).put(0).put(0).put(0)
            .putInt(streamMask.toInt()).putInt(0).array()
        activeMask = streamMask
        sessionId = 0
        lastStreamSequence.clear()
        phase = Phase.WAIT_START
        return command(WoonaV2Codec.START_SESSION, payload)
    }

    fun stop(): Event.Transmit {
        require(phase == Phase.RUNNING) { "Cannot stop LIVE in $phase" }
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sessionId.toInt()).array()
        phase = Phase.WAIT_STOP
        return command(WoonaV2Codec.STOP_SESSION, payload)
    }

    /** Processes already CRC-checked V2 frames; unsolicited DATA has request_id = 0. */
    fun receive(frame: V2Frame): List<Event> {
        if (phase == Phase.IDLE || phase == Phase.FAILED) return emptyList()
        if (frame.sequence !in 0..U32_MAX) return emptyList()
        val previousFrame = lastFrameSequence
        val frameDelta = previousFrame?.let { (frame.sequence - it) and U32_MAX }
        // Half-range ordering permits a genuine uint32 wrap, not duplicates or
        // backwards/replayed DATA. Count all wire frames, including control ACKs.
        val freshFrame = frameDelta == null || frameDelta in 1 until 0x8000_0000L
        if (freshFrame) {
            lastFrameSequence = frame.sequence
            if (frameDelta != null && frameDelta > 1) pendingWireGap = true
        }
        // STOP_ACK can overtake already-queued DATA; retain late blocks for the same
        // session ID until the next START or disconnect, as specified by firmware.
        if (frame.type == WoonaV2Codec.DATA && frame.requestId == 0L &&
            (phase == Phase.RUNNING || phase == Phase.WAIT_STOP || phase == Phase.STOPPED)) {
            if (!freshFrame) return emptyList()
            return onData(frame)?.let(::listOf) ?: emptyList()
        }
        if (phase !in setOf(Phase.WAIT_START, Phase.WAIT_STOP) ||
            pendingRequestId == 0L || frame.requestId != pendingRequestId) return emptyList()
        val error = WoonaV2Codec.error(frame)
        if (error != null && error.offendingType == pendingCommandType) {
            return listOf(fail("V2 command 0x${pendingCommandType.toString(16)} rejected: status=${error.status} (${WoonaV2Codec.statusDescription(error.status)})"))
        }
        return when (phase) {
            Phase.WAIT_START -> startResponse(frame)?.let(::listOf) ?: emptyList()
            Phase.WAIT_STOP -> stopResponse(frame)?.let(::listOf) ?: emptyList()
            else -> emptyList()
        }
    }

    fun onResponseTimeout(): Event {
        check(phase == Phase.WAIT_START || phase == Phase.WAIT_STOP)
        return if (++retries <= 2) Event.Transmit(pendingWire.clone())
        else fail("V2 LIVE command 0x${pendingCommandType.toString(16)} timed out; disconnect to recover")
    }

    fun reset() {
        phase = Phase.IDLE
        sessionId = 0
        activeMask = 0
        pendingRequestId = 0
        pendingCommandType = 0
        pendingWire = ByteArray(0)
        retries = 0
        // A reset does not prove BLE disconnected. Never reuse an ID that may
        // still be in this connection's firmware replay cache.
        lastStreamSequence.clear()
        lastFrameSequence = null
        pendingWireGap = false
    }

    private fun startResponse(frame: V2Frame): Event? {
        if (frame.type != 0x84 || frame.payload.size != 24) return null
        val p = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        val status = frame.payload[0].toInt() and 0xff
        val mode = frame.payload[1].toInt() and 0xff
        val id = p.getInt(4).toLong() and U32_MAX
        val mask = p.getInt(8).toLong() and U32_MAX
        val duration = p.getInt(12).toLong() and U32_MAX
        val storageSequence = p.getLong(16)
        if (status != 0) return fail("START_SESSION rejected: status=$status (${WoonaV2Codec.statusDescription(status)})")
        if (mode != 1 || p.getShort(2).toInt() != 0 || id == 0L ||
            mask != activeMask || duration != 0L || storageSequence != 0L) {
            return fail("Malformed V2 LIVE START_SESSION_RESPONSE")
        }
        sessionId = id
        phase = Phase.RUNNING
        clearPending()
        return Event.Started(id, mask)
    }

    private fun stopResponse(frame: V2Frame): Event? {
        if (frame.type != 0x85 || frame.payload.size != 8) return null
        val p = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        val status = frame.payload[0].toInt() and 0xff
        val modeAfter = frame.payload[1].toInt() and 0xff
        val id = p.getInt(4).toLong() and U32_MAX
        if (status != 0) return fail("STOP_SESSION rejected: status=$status (${WoonaV2Codec.statusDescription(status)})")
        if (modeAfter != 0 || p.getShort(2).toInt() != 0 || id != sessionId) {
            return fail("Malformed V2 LIVE STOP_SESSION_RESPONSE")
        }
        phase = Phase.STOPPED
        clearPending()
        return Event.Stopped(id)
    }

    private fun onData(frame: V2Frame): Event.Samples? {
        val data = WoonaV2Codec.liveData(frame) ?: return null
        if (data.sessionId != sessionId || data.streamId !in 1..32 ||
            (activeMask and (1L shl (data.streamId - 1))) == 0L ||
            data.count == 0 || data.rateHz == 0) return null
        val descriptor = streams.descriptors.firstOrNull { it.id == data.streamId } ?: return null
        if (descriptor.channels !in 1..16 || descriptor.format !in 1..2 || data.rateHz > descriptor.nativeRateHz) return null
        val expectedSize = data.count.toLong() * descriptor.channels * 2L
        if (data.samples.size.toLong() != expectedSize) return null
        val previous = lastStreamSequence[data.streamId]
        val delta = previous?.let { (data.streamSequence - it) and U32_MAX }
        if (delta == 0L || (delta != null && delta >= 0x8000_0000L)) return null
        lastStreamSequence[data.streamId] = data.streamSequence
        val gap = (delta != null && delta > 1) || pendingWireGap
        pendingWireGap = false
        return Event.Samples(frame.sequence, data, descriptor, gap)
    }

    private fun command(type: Int, payload: ByteArray): Event.Transmit {
        check(nextRequestId <= U32_MAX && nextSequence <= U32_MAX) { "Reconnect before sequence rollover" }
        pendingCommandType = type
        pendingRequestId = nextRequestId++
        pendingWire = WoonaV2Codec.encodeWire(type, nextSequence++, pendingRequestId, payload)
        retries = 0
        return Event.Transmit(pendingWire.clone())
    }

    private fun fail(message: String): Event.Failure {
        phase = Phase.FAILED
        clearPending()
        return Event.Failure(message)
    }

    private fun clearPending() {
        pendingRequestId = 0
        pendingCommandType = 0
        pendingWire = ByteArray(0)
    }

    private companion object { const val U32_MAX = 0xffff_ffffL }
}
