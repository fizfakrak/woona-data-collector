package com.example.myapplication.ble

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.localization.AppTextResolver
import com.example.myapplication.protocol.V2Frame
import com.example.myapplication.protocol.WoonaV2Codec
import com.example.myapplication.protocol.WoonaV2StorageCodec
import com.example.myapplication.protocol.WoonaV2TimeSync
import com.example.myapplication.protocol.WoonaV2Discovery
import com.example.myapplication.protocol.WoonaV2LiveSession
import com.example.myapplication.protocol.WoonaV2StreamDecoder
import java.util.ArrayDeque
import java.util.Locale
import java.util.IdentityHashMap
import java.util.UUID

interface BleSessionListener {
    fun onDeviceFound(device: BleDevice)
    fun onPacketReceived(packetFragment: ByteArray)
    fun onPacketReceived(
        packetFragment: ByteArray,
        receivedAtWallClockMillis: Long,
        receivedAtMonotonicNs: Long,
    ) = onPacketReceived(packetFragment)
    fun onDiagnosticMessage(message: String)
    fun onCaptureReady()
    fun onV2DiscoveryStarted() = Unit
    fun onV2DeviceBusy(sessionMode: Int) = Unit
    fun onV2FrameRejections(total: Long) = Unit
    /** Called only after explicit V2 discovery has completed successfully. */
    fun onV2DiscoveryReady(device: WoonaV2Discovery.Device) = Unit
    fun onV2LiveStarted(sessionId: Long, streamMask: Long) = Unit
    /** Fully validated frame, still containing original LE sample bytes. */
    fun onV2LiveSamples(event: WoonaV2LiveSession.Event.Samples) = Unit
    fun onV2LiveStopped(sessionId: Long) = Unit
    fun onV2StorageFrame(frame: V2Frame) = Unit
    fun onV2StorageFailure(reason: String) = Unit
    fun onV2TimeSyncStatus(status: WoonaV2TimeSync.Diagnostics) = Unit
    fun onV2SdCaptureStarted(sessionId: Long, storageSeq: Long, streamMask: Long, durationSeconds: Int) = Unit
    fun onV2SdCaptureStopped(sessionId: Long, storageSeq: Long) = Unit
    fun onV2SdCaptureFailure(message: String) = Unit
    fun onStateChanged(state: BleSessionState)
    fun onError(message: String, throwable: Throwable? = null)
}

interface BleSessionController {
    fun currentState(): BleSessionState
    fun currentTransportProfile(): BleTransportProfile
    fun updateTransportProfile(profile: BleTransportProfile)
    fun startScanning()
    fun stopScanning()
    fun connect(address: String)
    /** Advance UI ownership while the transport excludes old GATT callbacks. */
    fun connect(address: String, onConnectionStarting: () -> Unit) { onConnectionStarting(); connect(address) }
    /** Explicit preview intent; this must not create a persistent recording. */
    fun connectForV2Preview(address: String) = connect(address)
    fun connectForV2Preview(address: String, onConnectionStarting: () -> Unit) { onConnectionStarting(); connectForV2Preview(address) }
    /** Explicit opt-in; never probe Legacy automatically (HELLO switches firmware to V2). */
    fun beginV2Discovery(): Boolean = false
    /** Only after explicit discovery, never during an ordinary Legacy capture. */
    fun startV2Live(streamMask: Long): Boolean = false
    fun stopV2Live(): Boolean = false
    fun sendV2StorageCommand(type: Int, payload: ByteArray): Boolean = false
    fun beginV2TimeSync(): Boolean = false
    fun startV2SdCapture(streamMask: Long, durationSeconds: Int): Boolean = false
    fun stopV2SdCapture(): Boolean = false
    fun setCaptureActive(active: Boolean) = Unit
    fun disconnect()
    fun close()
}

class BleSessionManager(
    private val context: Context,
    private val serviceUuid: UUID,
    private val characteristicUuid: UUID,
    private val descriptorUuid: UUID,
    private val listener: BleSessionListener,
    private val appTextResolver: AppTextResolver,
) : BleSessionController {
    private val scanHandler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val v2WriteUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val v2GattWrites = WoonaV2GattWriteQueue()
    private val v2PendingFrames = ArrayDeque<ByteArray>()
    private var v2Discovery: WoonaV2Discovery? = null
    private var v2Live: WoonaV2LiveSession? = null
    private var v2LiveReceiver: WoonaV2StreamDecoder? = null
    private var v2SupportsLive = false
    private var v2SupportsStorage = false
    private var v2SupportsTimeSync = false
    private enum class V2SdPhase { IDLE, WAIT_START, RECORDING, WAIT_STOP }
    private var v2SdPhase = V2SdPhase.IDLE
    private var v2SdPendingId = 0L
    private var v2SdExpectedType = 0
    private var v2SdSessionId = 0L
    private var v2SdSessionSeq = 0L
    private var v2SdStreamMask = 0L
    private var v2SdDurationSeconds = 0
    private var v2SdAckTimeout: Runnable? = null
    private var v2SdAutoStop: Runnable? = null
    private var v2TimeSyncActive = false
    private var v2TimeSyncPendingId = 0L
    private var v2TimeSyncNonce = 0L
    private var v2TimeSyncT1Ns = 0L
    private var v2TimeSyncSamples = mutableListOf<WoonaV2TimeSync.Sample>()
    private var v2TimeSyncTimeout: Runnable? = null
    private var v2StorageRequestId = 0L
    private var v2StorageExpectedResponse = 0
    private var v2StorageTransferActive = false
    private var v2StorageTimeoutRunnable: Runnable? = null
    private var v2StoragePendingWire = ByteArray(0)
    private var v2StorageRetryCount = 0
    private val v2StorageEarlyFrames = ArrayDeque<V2Frame>()
    private var v2WriteCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var v2Requested = false
    @Volatile private var v2ConnectionIntent = false
    private var v2Failed = false
    private var v2ReportedRejectedFrames = 0L
    // Each armed timeout has its own identity; removing a Handler callback cannot
    // cancel a callback already dequeued on another thread.
    private var v2ResponseTimeoutRunnable: Runnable? = null
    private var v2ChunkTimeoutRunnable: Runnable? = null

    @Synchronized
    private fun cancelV2ResponseTimeout() {
        v2ResponseTimeoutRunnable?.let(scanHandler::removeCallbacks)
        v2ResponseTimeoutRunnable = null
    }

    @Synchronized
    private fun cancelV2ChunkTimeout() {
        v2ChunkTimeoutRunnable?.let(scanHandler::removeCallbacks)
        v2ChunkTimeoutRunnable = null
    }

    @Synchronized
    private fun cancelV2SdAckTimeout() {
        v2SdAckTimeout?.let(scanHandler::removeCallbacks)
        v2SdAckTimeout = null
    }

    private fun cancelV2SdAutoStop() {
        v2SdAutoStop?.let(scanHandler::removeCallbacks)
        v2SdAutoStop = null
    }

    private fun scheduleV2SdAutoStop() {
        cancelV2SdAutoStop()
        val seq = v2SdSessionSeq
        var retries = 0
        lateinit var task: Runnable
        task = Runnable { synchronized(this) {
            if (v2SdAutoStop !== task || v2SdSessionSeq != seq) return@synchronized
            if (v2SdPhase == V2SdPhase.WAIT_STOP || v2SdPhase == V2SdPhase.IDLE) {
                v2SdAutoStop = null
                return@synchronized
            }
            if (v2SdPhase != V2SdPhase.RECORDING) return@synchronized
            v2SdAutoStop = null
            if (!stopV2SdCapture()) {
                if (++retries <= 40 && v2SdPhase == V2SdPhase.RECORDING) {
                    // A control/GATT write can be busy exactly on the duration
                    // boundary. Retry for up to ~10 seconds; do not turn a
                    // transient queue conflict into a false capture failure.
                    v2SdAutoStop = task
                    scanHandler.postDelayed(task, 250L)
                } else {
                    failV2SdCapture("Automatic SD STOP could not be queued after retries; reconcile device catalog")
                }
            }
        } }
        v2SdAutoStop = task
        // Device independently enforces this finite duration. Android sends
        // idempotent STOP to close its video/metadata lifecycle deterministically.
        scanHandler.postDelayed(task, v2SdDurationSeconds.toLong() * 1_000L)
    }

    @Synchronized
    private fun failV2SdCapture(message: String) {
        if (v2SdPhase == V2SdPhase.IDLE) return
        cancelV2SdAckTimeout()
        cancelV2SdAutoStop()
        v2SdPhase = V2SdPhase.IDLE
        v2SdExpectedType = 0
        v2SdPendingId = 0L
        listener.onV2SdCaptureFailure(message)
    }

    @Synchronized
    private fun armV2SdAckTimeout(gatt: BluetoothGatt) {
        cancelV2SdAckTimeout()
        if (v2SdExpectedType == 0) return
        lateinit var task: Runnable
        task = Runnable { synchronized(this) {
            if (v2SdAckTimeout !== task || !isCurrentGatt(gatt) || v2SdExpectedType == 0)
                return@synchronized
            v2SdAckTimeout = null
            failV2SdCapture("SD session response timeout; device state unknown. Reconnect/reconcile before restarting")
        } }
        v2SdAckTimeout = task
        scanHandler.postDelayed(task, 6_000L)
    }

    @Synchronized
    private fun onV2SdAck(frame: V2Frame) {
        val b = frame.payload
        if (frame.type == WoonaV2Codec.ERROR_RESPONSE) {
            val status = b.firstOrNull()?.toInt()?.and(255) ?: -1
            failV2SdCapture("SD session command rejected: $status; verify device state")
            return
        }
        if (frame.type == 0x84 && v2SdPhase == V2SdPhase.WAIT_START) {
            if (b.size != 24) { failV2SdCapture("Malformed SD START response"); return }
            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val status = b[0].toInt() and 255
            val id = bb.getInt(4).toLong() and 0xffff_ffffL
            val mask = bb.getInt(8).toLong() and 0xffff_ffffL
            val duration = bb.getInt(12).toLong() and 0xffff_ffffL
            val seq = bb.getLong(16)
            if (status != 0 || b[1].toInt() != 2 || bb.getShort(2).toInt() != 0 ||
                id == 0L || seq <= 0L || mask != v2SdStreamMask ||
                duration != v2SdDurationSeconds.toLong()) {
                failV2SdCapture("SD START rejected/mismatched; status=$status"); return
            }
            v2SdSessionId = id
            v2SdSessionSeq = seq
            v2SdPhase = V2SdPhase.RECORDING
            listener.onV2SdCaptureStarted(id, seq, mask, v2SdDurationSeconds)
            scheduleV2SdAutoStop()
        } else if (frame.type == 0x85 && v2SdPhase == V2SdPhase.WAIT_STOP) {
            if (b.size != 8) { failV2SdCapture("Malformed SD STOP response"); return }
            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val status = b[0].toInt() and 255
            val sessionId = bb.getInt(4).toLong() and 0xffff_ffffL
            if (status != 0 || b[1].toInt() != 0 || bb.getShort(2).toInt() != 0 ||
                sessionId != v2SdSessionId) {
                failV2SdCapture("SD STOP rejected/mismatched; status=$status"); return
            }
            val seq = v2SdSessionSeq
            v2SdPhase = V2SdPhase.IDLE
            cancelV2SdAutoStop()
            listener.onV2SdCaptureStopped(sessionId, seq)
        } else {
            failV2SdCapture("Unexpected SD session response")
        }
    }

    @Synchronized
    private fun cancelV2StorageTimeout() {
        v2StorageTimeoutRunnable?.let(scanHandler::removeCallbacks)
        v2StorageTimeoutRunnable = null
    }

    @Synchronized
    private fun armV2StorageTimeout(gatt: BluetoothGatt) {
        cancelV2StorageTimeout()
        if (v2StorageExpectedResponse == 0 && !v2StorageTransferActive) return
        val task = object : Runnable {
            override fun run() {
                synchronized(this@BleSessionManager) {
                    if (v2StorageTimeoutRunnable !== this || !isCurrentGatt(gatt) ||
                        v2Failed || !v2Requested) return@synchronized
                    v2StorageTimeoutRunnable = null
                    val waitingFor = v2StorageExpectedResponse
                    if (waitingFor != 0 && v2StorageRetryCount < 2 &&
                        v2StoragePendingWire.isNotEmpty()) {
                        ++v2StorageRetryCount
                        // Retry the byte-identical request: same request_id,
                        // therefore firmware replay cache can answer without
                        // executing the mutating command twice.
                        enqueueV2Frame(v2StoragePendingWire.clone())
                        return@synchronized
                    }
                    v2StorageExpectedResponse = 0
                    v2StorageTransferActive = false
                    v2StoragePendingWire = ByteArray(0)
                    v2StorageRetryCount = 0
                    v2StorageEarlyFrames.clear()
                    listener.onV2StorageFailure(if (waitingFor != 0)
                        "Storage response 0x${waitingFor.toString(16)} timed out after retries"
                        else "Stored record stream timed out")
                }
            }
        }
        v2StorageTimeoutRunnable = task
        val timeoutMs = when (v2StorageExpectedResponse) {
            WoonaV2StorageCodec.START_TRANSFER_RESPONSE -> 1_500L
            0 -> 10_000L
            else -> 6_000L
        }
        scanHandler.postDelayed(task, timeoutMs)
    }

    @Synchronized
    private fun cancelV2TimeSyncTimeout() {
        v2TimeSyncTimeout?.let(scanHandler::removeCallbacks)
        v2TimeSyncTimeout = null
    }

    @Synchronized
    private fun publishV2TimeSync(reason: String? = null) {
        val best = WoonaV2TimeSync.bestSample(v2TimeSyncSamples)
        listener.onV2TimeSyncStatus(WoonaV2TimeSync.Diagnostics(
            collected = v2TimeSyncSamples.size,
            total = WoonaV2TimeSync.DIAGNOSTIC_SAMPLES,
            running = v2TimeSyncActive,
            bestRttMs = best?.correctedRttNs?.div(1_000_000.0),
            uncertaintyMs = best?.correctedRttNs?.div(2_000_000.0),
            offsetMs = best?.let { (it.phoneMidNs / 1000.0 - it.deviceMidUs) / 1000.0 },
            clockEpochId = best?.clockEpochId,
            bestSample = best,
            error = reason,
        ))
    }

    @Synchronized
    private fun failV2TimeSync(reason: String) {
        if (!v2TimeSyncActive) return
        v2TimeSyncActive = false
        v2TimeSyncPendingId = 0L
        v2TimeSyncNonce = 0L
        v2TimeSyncT1Ns = 0L
        cancelV2TimeSyncTimeout()
        publishV2TimeSync(reason)
    }

    @Synchronized
    private fun armV2TimeSyncTimeout(gatt: BluetoothGatt) {
        cancelV2TimeSyncTimeout()
        if (!v2TimeSyncActive || v2TimeSyncT1Ns == 0L || v2TimeSyncPendingId == 0L) return
        lateinit var task: Runnable
        task = Runnable { synchronized(this) {
            if (v2TimeSyncTimeout !== task || !isCurrentGatt(gatt) || !v2TimeSyncActive)
                return@synchronized
            v2TimeSyncTimeout = null
            failV2TimeSync("TIME_SYNC response timeout (5 s)")
        } }
        v2TimeSyncTimeout = task
        scanHandler.postDelayed(task, 5_000L)
    }

    @Synchronized
    private fun enqueueNextV2TimeSync(): Boolean {
        if (!v2TimeSyncActive || v2TimeSyncPendingId != 0L) return false
        val (seq, id) = try { v2Live?.reserveExternalCommandIds() ?: return false }
                        catch (_: IllegalStateException) { return false }
        val nonce = (SystemClock.elapsedRealtimeNanos() xor (id shl 19)).let {
            if (it == 0L) id else it
        }
        v2TimeSyncNonce = nonce
        v2TimeSyncPendingId = id
        v2TimeSyncT1Ns = 0L
        enqueueV2Frame(WoonaV2TimeSync.requestWire(seq, id, nonce))
        return !v2Failed
    }

    @Synchronized
    private fun armV2ResponseTimeout(gatt: BluetoothGatt) {
        cancelV2ResponseTimeout()
        lateinit var timeout: Runnable
        timeout = Runnable { synchronized(this) {
            if (v2ResponseTimeoutRunnable !== timeout || !isCurrentGatt(gatt) ||
                manualDisconnectRequested || !v2Requested || v2Failed) return@synchronized
            v2ResponseTimeoutRunnable = null
            val discovery = v2Discovery ?: return@synchronized
            if (discovery.phase == WoonaV2Discovery.Phase.READY) {
                val live = v2Live ?: return@synchronized
                if (live.phase == WoonaV2LiveSession.Phase.WAIT_START ||
                    live.phase == WoonaV2LiveSession.Phase.WAIT_STOP) dispatchV2LiveEvent(live.onResponseTimeout())
            } else if (discovery.phase != WoonaV2Discovery.Phase.FAILED) {
                emitDiagnostic("V2 response timeout in ${discovery.phase}; retrying if possible", DiagnosticLevel.WARNING)
                dispatchV2Event(discovery.onResponseTimeout())
            }
        } }
        v2ResponseTimeoutRunnable = timeout
        scanHandler.postDelayed(timeout, V2_RESPONSE_TIMEOUT_MS)
    }

    @Synchronized
    private fun armV2ChunkTimeout(gatt: BluetoothGatt) {
        cancelV2ChunkTimeout()
        lateinit var timeout: Runnable
        timeout = Runnable { synchronized(this) {
            if (v2ChunkTimeoutRunnable !== timeout || !isCurrentGatt(gatt) || manualDisconnectRequested) return@synchronized
            v2ChunkTimeoutRunnable = null
            if (v2Requested && !v2Failed && v2GattWrites.isBusy) failV2Discovery("FFF2 GATT write timed out")
        } }
        v2ChunkTimeoutRunnable = timeout
        scanHandler.postDelayed(timeout, V2_CHUNK_WRITE_TIMEOUT_MS)
    }

    private val discoveredAddresses = linkedSetOf<String>()
    private val stopScanRunnable = Runnable { stopScanning() }

    private var bluetoothGatt: BluetoothGatt? = null
    private val gattGenerations = IdentityHashMap<BluetoothGatt, Long>()
    private var nextGattGeneration = 0L
    private var activeGattGeneration: Long? = null
    private var activeDeviceAddress: String? = null
    private var pendingNotificationDescriptorUuid: UUID? = null
    private var notificationsReady = false
    private var sessionState = BleSessionState.IDLE
    @Volatile
    private var transportProfile = BleTransportProfile.COMPATIBILITY
    @Volatile
    private var captureActive = false
    @Volatile
    private var activeTransportProfile: BleTransportProfile? = null
    private var negotiatedMtu: Int? = null
    private var negotiatedTxPhy: Int? = null
    private var negotiatedRxPhy: Int? = null
    private var negotiatedConnectionIntervalUnits: Int? = null
    private var negotiatedConnectionLatency: Int? = null
    private var negotiatedSupervisionTimeoutUnits: Int? = null
    private var notificationCount = 0L
    private var notificationGapSampleCount = 0L
    private var notificationGapTotalMillis = 0L
    private var notificationGapMaxMillis = 0L
    private var longNotificationGapCount = 0L
    private var lastNotificationElapsedRealtimeMs: Long? = null
    private var notificationMonitoringStartedElapsedRealtimeMs: Long? = null
    private var lastLongGapLoggedElapsedRealtimeMs = 0L
    private var manualDisconnectRequested = false
    private var recoveryInProgress = false
    private var awaitingRecoveryNotification = false
    private var recoveryAttempt = 0
    private var recoveryStartedElapsedRealtimeMs: Long? = null
    private val recoveryRunnable = Runnable {
        val address = activeDeviceAddress
        if (!manualDisconnectRequested && recoveryInProgress && address != null) {
            openGatt(address)
        }
    }
    private val notificationWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!isCurrentGattConnected()) return

            val now = SystemClock.elapsedRealtime()
            val lastActivity = lastNotificationElapsedRealtimeMs
                ?: notificationMonitoringStartedElapsedRealtimeMs
                ?: now
            if (now - lastActivity >= NOTIFICATION_SILENCE_TIMEOUT_MS) {
                beginTransportRecovery(now - lastActivity)
                return
            }
            scanHandler.postDelayed(this, NOTIFICATION_WATCHDOG_INTERVAL_MS)
        }
    }
    private val gattConnectionTimeoutRunnable = Runnable {
        val gatt = bluetoothGatt
        if (gatt == null || sessionState != BleSessionState.CONNECTING || !isCurrentGatt(gatt)) return@Runnable

        val wasRecovering = recoveryInProgress
        clearGattReference(gatt)
        safeCloseGatt(gatt)
        if (wasRecovering) {
            scheduleNextRecoveryAttempt()
        } else {
            transition(BleSessionEvent.Failure)
            listener.onError("BLE connection setup timed out")
        }
    }
    private val gattSetupTimeoutRunnable = Runnable {
        val gatt = bluetoothGatt
        if (gatt == null || notificationsReady || !isCurrentGatt(gatt)) return@Runnable
        handleGattSetupFailure(gatt, "BLE notification setup timed out")
    }
    private val recoveryConfirmationRunnable = Runnable {
        if (!recoveryInProgress || !awaitingRecoveryNotification) return@Runnable
        val gatt = bluetoothGatt
        if (gatt != null && isCurrentGatt(gatt)) {
            clearGattReference(gatt)
            safeCloseGatt(gatt)
        }
        awaitingRecoveryNotification = false
        scheduleNextRecoveryAttempt()
    }
    private val transportSummaryRunnable = object : Runnable {
        override fun run() {
            if (sessionState != BleSessionState.CONNECTED || bluetoothGatt == null) return
            emitDiagnostic(
                message = buildTransportSummaryMessage(),
                level = DiagnosticLevel.INFO,
            )
            scanHandler.postDelayed(this, TRANSPORT_SUMMARY_INTERVAL_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_scan_result))
                return
            }

            val address = result.device.address
            if (!discoveredAddresses.add(address)) return

            listener.onDeviceFound(
                BleDevice(
                    name = result.device.name ?: appTextResolver.getString(R.string.ble_unknown_device),
                    address = address,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanHandler.removeCallbacks(stopScanRunnable)
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_scan_failed, describeScanFailure(errorCode)))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(this@BleSessionManager) {
            if (!isCurrentGatt(gatt)) {
                Log.w(TAG, "Ignoring stale GATT state callback generation=${gattGenerations[gatt]}")
                return
            }

            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    stopTransportSummaryLoop()
                    stopNotificationWatchdog()
                    val wasRecovering = recoveryInProgress
                    clearGattReference(gatt)
                    safeCloseGatt(gatt)
                    when (bleDisconnectAction(manualDisconnectRequested, wasRecovering,
                        captureActive && activeDeviceAddress != null)) {
                        BleDisconnectAction.DISCONNECTED -> transition(BleSessionEvent.Disconnected)
                        BleDisconnectAction.RETRY -> scheduleNextRecoveryAttempt()
                        BleDisconnectAction.RECOVER -> beginTransportRecovery(0, "GATT error $status")
                        BleDisconnectAction.FAIL -> {
                            transition(BleSessionEvent.Failure)
                            listener.onError(appTextResolver.getString(R.string.ble_gatt_connection_error, status))
                        }
                    }
                }

                newState == BluetoothProfile.STATE_CONNECTED -> {
                    scanHandler.removeCallbacks(gattConnectionTimeoutRunnable)
                    scanHandler.removeCallbacks(gattSetupTimeoutRunnable)
                    if (manualDisconnectRequested) {
                        clearGattReference(gatt)
                        safeCloseGatt(gatt)
                        transition(BleSessionEvent.Disconnected)
                        return
                    }
                    transition(BleSessionEvent.Connected)
                    resetTransportDiagnostics()
                    scanHandler.postDelayed(gattSetupTimeoutRunnable, GATT_SETUP_TIMEOUT_MS)
                    val activeProfile = activeTransportProfile ?: transportProfile
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        handleGattSetupFailure(
                            gatt,
                            appTextResolver.getString(R.string.ble_missing_connect_permission_before_mtu),
                        )
                        return
                    }

                    emitDiagnostic(
                        message = appTextResolver.getString(
                            R.string.ble_transport_profile_message,
                            appTextResolver.getString(activeProfile.titleRes),
                            appTextResolver.getString(activeProfile.shortDescriptionRes),
                        ),
                        level = DiagnosticLevel.INFO,
                    )
                    emitDiagnostic(
                        message = appTextResolver.getString(R.string.ble_best_effort_connection_logging),
                        level = DiagnosticLevel.INFO,
                    )

                    if (activeProfile.requestsHighConnectionPriority) {
                        val priorityRequested = gatt.requestConnectionPriority(
                            BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                        )
                        if (priorityRequested) {
                            emitDiagnostic(
                                message = appTextResolver.getString(R.string.ble_requested_high_priority),
                                level = DiagnosticLevel.INFO,
                            )
                        } else {
                            emitDiagnostic(
                                message = appTextResolver.getString(R.string.ble_failed_high_priority),
                                level = DiagnosticLevel.WARNING,
                            )
                        }
                    } else {
                        emitDiagnostic(
                            message = appTextResolver.getString(R.string.ble_skipped_priority_profile),
                            level = DiagnosticLevel.INFO,
                        )
                    }

                    val requestedMtu = activeProfile.requestedMtu
                    if (requestedMtu != null) {
                        val mtuRequested = gatt.requestMtu(requestedMtu)
                        if (mtuRequested) {
                            emitDiagnostic(
                                message = appTextResolver.getString(R.string.ble_requested_mtu, requestedMtu),
                                level = DiagnosticLevel.INFO,
                            )
                        } else {
                            emitDiagnostic(
                                message = appTextResolver.getString(R.string.ble_failed_mtu_continue, requestedMtu),
                                level = DiagnosticLevel.WARNING,
                            )
                            requestPreferredPhy(gatt, activeProfile)
                            gatt.discoverServices()
                        }
                    } else {
                        emitDiagnostic(
                            message = appTextResolver.getString(R.string.ble_skipped_explicit_mtu),
                            level = DiagnosticLevel.INFO,
                        )
                        requestPreferredPhy(gatt, activeProfile)
                        gatt.discoverServices()
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    stopTransportSummaryLoop()
                    stopNotificationWatchdog()
                    val wasRecovering = recoveryInProgress
                    clearGattReference(gatt)
                    safeCloseGatt(gatt)
                    when (bleDisconnectAction(manualDisconnectRequested, wasRecovering,
                        captureActive && activeDeviceAddress != null)) {
                        BleDisconnectAction.RETRY -> scheduleNextRecoveryAttempt()
                        BleDisconnectAction.RECOVER -> beginTransportRecovery(0, "Unexpected GATT disconnect")
                        else -> transition(BleSessionEvent.Disconnected)
                    }
                }
            }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(this@BleSessionManager) {
            if (!isCurrentGatt(gatt)) return

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                handleGattSetupFailure(
                    gatt,
                    appTextResolver.getString(R.string.ble_missing_connect_permission_after_mtu),
                )
                return
            }

            emitDiagnostic(
                message = appTextResolver.getString(R.string.ble_mtu_changed, mtu, status),
                level = DiagnosticLevel.INFO,
            )
            negotiatedMtu = mtu
            emitDiagnostic(
                message = buildTransportSnapshotMessage(),
                level = DiagnosticLevel.INFO,
            )
            if (status == BluetoothGatt.GATT_SUCCESS) {
                requestPreferredPhy(gatt, activeTransportProfile ?: transportProfile)
            }
            gatt.discoverServices()
            }
        }

        override fun onPhyUpdate(
            gatt: BluetoothGatt,
            txPhy: Int,
            rxPhy: Int,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt)) return

            emitDiagnostic(
                message = appTextResolver.getString(R.string.ble_phy_updated, txPhy, rxPhy, status),
                level = DiagnosticLevel.INFO,
            )
            negotiatedTxPhy = txPhy
            negotiatedRxPhy = rxPhy
            emitDiagnostic(
                message = buildTransportSnapshotMessage(),
                level = DiagnosticLevel.INFO,
            )
        }

        // This callback is hidden from the public Android SDK stubs, so this is a
        // best-effort runtime hook for debug builds rather than a guaranteed override.
        @Suppress("unused")
        fun onConnectionUpdated(
            gatt: BluetoothGatt,
            interval: Int,
            latency: Int,
            timeout: Int,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt)) return

            negotiatedConnectionIntervalUnits = interval
            negotiatedConnectionLatency = latency
            negotiatedSupervisionTimeoutUnits = timeout
            emitDiagnostic(
                message = appTextResolver.getString(
                    R.string.ble_connection_updated,
                    formatConnectionIntervalMillis(interval),
                    latency,
                    timeout * 10,
                    status,
                ),
                level = DiagnosticLevel.INFO,
            )
            emitDiagnostic(
                message = buildTransportSnapshotMessage(),
                level = DiagnosticLevel.INFO,
            )
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(this@BleSessionManager) {
            if (!isCurrentGatt(gatt)) return
            if (manualDisconnectRequested) {
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                return
            }

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                handleGattSetupFailure(
                    gatt,
                    appTextResolver.getString(R.string.ble_missing_connect_permission_service_discovery),
                )
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattSetupFailure(
                    gatt,
                    appTextResolver.getString(R.string.ble_service_discovery_failed, status),
                )
                return
            }

            val service = gatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(characteristicUuid)
            val descriptor = characteristic?.getDescriptor(descriptorUuid)

            if (service == null || characteristic == null || descriptor == null) {
                handleGattSetupFailure(
                    gatt,
                    appTextResolver.getString(R.string.ble_required_service_missing),
                )
                return
            }

            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                pendingNotificationDescriptorUuid = null
                handleGattSetupFailure(
                    gatt,
                    appTextResolver.getString(R.string.ble_failed_register_notification_callback),
                )
                return
            }

            pendingNotificationDescriptorUuid = descriptor.uuid
            val writeResult = writeNotificationDescriptor(gatt, descriptor)

            if (writeResult != BluetoothStatusCodes.SUCCESS) {
                pendingNotificationDescriptorUuid = null
                stopTransportSummaryLoop()
                stopNotificationWatchdog()
                val wasRecovering = recoveryInProgress
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                if (wasRecovering) {
                    scheduleNextRecoveryAttempt()
                } else {
                    transition(BleSessionEvent.Failure)
                    listener.onError(appTextResolver.getString(R.string.ble_failed_enable_notifications, writeResult))
                }
                return
            }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            synchronized(this@BleSessionManager) {
            if (!isCurrentGatt(gatt)) return
            if (manualDisconnectRequested) {
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                return
            }

            val pendingDescriptorUuid = pendingNotificationDescriptorUuid
            if (pendingDescriptorUuid == null || descriptor.uuid != pendingDescriptorUuid) {
                return
            }
            pendingNotificationDescriptorUuid = null

            if (!hasConnectPermission()) {
                stopTransportSummaryLoop()
                stopNotificationWatchdog()
                val wasRecovering = recoveryInProgress
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                if (wasRecovering) {
                    scheduleNextRecoveryAttempt()
                } else {
                    transition(BleSessionEvent.Failure)
                    listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_notification_setup))
                }
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                stopTransportSummaryLoop()
                stopNotificationWatchdog()
                val wasRecovering = recoveryInProgress
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                if (wasRecovering) {
                    scheduleNextRecoveryAttempt()
                } else {
                    transition(BleSessionEvent.Failure)
                    listener.onError(appTextResolver.getString(R.string.ble_descriptor_write_failed, status))
                }
                return
            }

            val wasRecovering = recoveryInProgress
            if (wasRecovering) {
                awaitingRecoveryNotification = true
                startRecoveryConfirmation()
            } else {
                recoveryAttempt = 0
                recoveryStartedElapsedRealtimeMs = null
            }
            notificationsReady = true
            scanHandler.removeCallbacks(gattSetupTimeoutRunnable)
            startTransportSummaryLoop()
            if (!wasRecovering && captureActive) startNotificationWatchdog()
            if (v2ConnectionIntent) {
                listener.onV2DiscoveryStarted()
                if (!beginV2Discovery()) failV2Discovery("V2 negotiation is unavailable on this connection")
            } else {
                listener.onCaptureReady()
            }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt) || characteristic.uuid != v2WriteUuid) return
            synchronized(this@BleSessionManager) {
                if (!isCurrentGatt(gatt) || !v2Requested || v2Failed) return@synchronized
                cancelV2ChunkTimeout()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failV2Discovery("FFF2 GATT write failed (status=$status)")
                } else {
                    try {
                        val nextChunk = v2GattWrites.acknowledgeChunk()
                        if (nextChunk != null) writeV2Chunk(gatt, nextChunk)
                        else startNextV2Frame(gatt)
                    } catch (exception: IllegalStateException) {
                        failV2Discovery("Unexpected FFF2 write callback: ${exception.message}")
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!isCurrentGatt(gatt) || characteristic.uuid != characteristicUuid) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value?.clone() ?: return
            handleCharacteristicChanged(gatt, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!isCurrentGatt(gatt) || characteristic.uuid != characteristicUuid) return
            handleCharacteristicChanged(gatt, value)
        }
    }

    override fun currentState(): BleSessionState = sessionState

    override fun currentTransportProfile(): BleTransportProfile = transportProfile

    override fun updateTransportProfile(profile: BleTransportProfile) {
        transportProfile = profile
    }

    @Synchronized
    override fun beginV2Discovery(): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (v2Requested || captureActive || recoveryInProgress || !notificationsReady ||
            sessionState != BleSessionState.CONNECTED || !isCurrentGatt(gatt) || !hasConnectPermission()
        ) return false

        val writeCharacteristic = gatt.getService(serviceUuid)?.getCharacteristic(v2WriteUuid)
        if (writeCharacteristic == null ||
            writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        ) {
            emitDiagnostic("V2 requires FFF2 with acknowledged GATT WRITE", DiagnosticLevel.WARNING)
            return false
        }

        val discovery = WoonaV2Discovery()
        v2WriteCharacteristic = writeCharacteristic
        v2Discovery = discovery
        v2Failed = false
        v2Requested = true
        emitDiagnostic("V2 discovery explicitly requested; Legacy packets are now ignored", DiagnosticLevel.INFO)
        dispatchV2Event(discovery.begin())
        return true
    }

    @Synchronized
    override fun startV2Live(streamMask: Long): Boolean {
        val gatt = bluetoothGatt ?: return false
        val live = v2Live ?: return false
        if (!v2Requested || v2Failed || v2SdPhase != V2SdPhase.IDLE || v2TimeSyncActive || v2StorageExpectedResponse != 0 ||
            v2StorageTransferActive || !v2SupportsLive || !isCurrentGatt(gatt) || !notificationsReady ||
            !hasConnectPermission() || captureActive || recoveryInProgress ||
            v2Discovery?.phase != WoonaV2Discovery.Phase.READY ||
            live.phase !in setOf(WoonaV2LiveSession.Phase.IDLE, WoonaV2LiveSession.Phase.STOPPED)
        ) return false
        return try {
            dispatchV2LiveEvent(live.start(streamMask))
            true
        } catch (exception: IllegalArgumentException) {
            emitDiagnostic("V2 LIVE start rejected: ${exception.message}", DiagnosticLevel.WARNING)
            false
        }
    }

    @Synchronized
    override fun startV2SdCapture(streamMask: Long, durationSeconds: Int): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (!v2Requested || v2Failed || !notificationsReady || !v2SupportsStorage ||
            !hasConnectPermission() || !isCurrentGatt(gatt) || v2TimeSyncActive ||
            v2Discovery?.phase != WoonaV2Discovery.Phase.READY ||
            v2Live?.phase !in setOf(WoonaV2LiveSession.Phase.IDLE, WoonaV2LiveSession.Phase.STOPPED) ||
            v2SdPhase != V2SdPhase.IDLE || v2StorageExpectedResponse != 0 ||
            v2StorageTransferActive || v2PendingFrames.isNotEmpty() || v2GattWrites.isBusy ||
            streamMask == 0L || streamMask > 0xffff_ffffL || durationSeconds !in 10..1800)
            return false
        val (sequence, requestId) = try { v2Live?.reserveExternalCommandIds() ?: return false }
            catch (_: IllegalStateException) { return false }
        val payload = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(2).put(0).put(0).put(0).putInt(streamMask.toInt())
            .putInt(durationSeconds).array()
        v2SdPendingId = requestId
        v2SdExpectedType = 0x84
        v2SdPhase = V2SdPhase.WAIT_START
        v2SdStreamMask = streamMask
        v2SdDurationSeconds = durationSeconds
        enqueueV2Frame(WoonaV2Codec.encodeWire(WoonaV2Codec.START_SESSION,
            sequence, requestId, payload))
        return !v2Failed
    }

    @Synchronized
    override fun stopV2SdCapture(): Boolean {
        val gatt = bluetoothGatt ?: return false
        // UI timer and BLE safety timer may fire together. Once WAIT_STOP is
        // entered, STOP has already been accepted into the transport queue.
        if (v2SdPhase == V2SdPhase.WAIT_STOP) return true
        if (v2SdPhase != V2SdPhase.RECORDING || !isCurrentGatt(gatt) ||
            !hasConnectPermission() || v2Failed || v2TimeSyncActive ||
            v2StorageExpectedResponse != 0 || v2StorageTransferActive ||
            v2PendingFrames.isNotEmpty() || v2GattWrites.isBusy) return false
        val (sequence, requestId) = try { v2Live?.reserveExternalCommandIds() ?: return false }
            catch (_: IllegalStateException) { return false }
        val payload = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(v2SdSessionId.toInt()).array()
        v2SdPendingId = requestId
        v2SdExpectedType = 0x85
        v2SdPhase = V2SdPhase.WAIT_STOP
        cancelV2SdAutoStop()
        enqueueV2Frame(WoonaV2Codec.encodeWire(WoonaV2Codec.STOP_SESSION,
            sequence, requestId, payload))
        return !v2Failed
    }

    @Synchronized
    override fun beginV2TimeSync(): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (!v2Requested || v2Failed || v2SdPhase != V2SdPhase.IDLE || !v2SupportsTimeSync || !notificationsReady ||
            !hasConnectPermission() || !isCurrentGatt(gatt) || v2TimeSyncActive ||
            v2Discovery?.phase != WoonaV2Discovery.Phase.READY ||
            v2Live?.phase !in setOf(WoonaV2LiveSession.Phase.IDLE, WoonaV2LiveSession.Phase.STOPPED) ||
            v2StorageExpectedResponse != 0 || v2StorageTransferActive ||
            v2PendingFrames.isNotEmpty() || v2GattWrites.isBusy) return false
        v2TimeSyncSamples.clear()
        v2TimeSyncActive = true
        publishV2TimeSync()
        if (!enqueueNextV2TimeSync()) {
            failV2TimeSync("Cannot queue TIME_SYNC command")
            return false
        }
        return true
    }

    @Synchronized
    override fun sendV2StorageCommand(type: Int, payload: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (!v2Requested || v2Failed || v2SdPhase != V2SdPhase.IDLE || v2TimeSyncActive || !v2SupportsStorage || !notificationsReady ||
            !hasConnectPermission() || !isCurrentGatt(gatt) ||
            v2Discovery?.phase != WoonaV2Discovery.Phase.READY ||
            v2Live?.phase !in setOf(WoonaV2LiveSession.Phase.IDLE, WoonaV2LiveSession.Phase.STOPPED) ||
            v2StorageExpectedResponse != 0 || v2PendingFrames.isNotEmpty() || v2GattWrites.isBusy ||
            type !in setOf(WoonaV2Codec.GET_STORED_SESSIONS, WoonaV2Codec.START_STORED_TRANSFER,
                WoonaV2Codec.STOP_STORED_TRANSFER) ||
            (v2StorageTransferActive && type != WoonaV2Codec.STOP_STORED_TRANSFER) ||
            (!v2StorageTransferActive && type == WoonaV2Codec.STOP_STORED_TRANSFER)) return false
        val expected = when (type) {
            WoonaV2Codec.GET_STORED_SESSIONS -> WoonaV2StorageCodec.SESSIONS_RESPONSE
            WoonaV2Codec.START_STORED_TRANSFER -> WoonaV2StorageCodec.START_TRANSFER_RESPONSE
            else -> WoonaV2StorageCodec.STOP_TRANSFER_RESPONSE
        }
        // LIVE and STORAGE share command IDs; firmware rejects backwards IDs.
        val ids = try { v2Live?.reserveExternalCommandIds() ?: return false }
                  catch (_: IllegalStateException) { return false }
        val (sequence, requestId) = ids
        v2StorageRequestId = requestId
        v2StorageExpectedResponse = expected
        v2StorageRetryCount = 0
        if (type == WoonaV2Codec.START_STORED_TRANSFER) v2StorageEarlyFrames.clear()
        val wire = WoonaV2Codec.encodeWire(type, sequence, requestId, payload)
        v2StoragePendingWire = wire.clone()
        enqueueV2Frame(wire)
        return !v2Failed
    }

    @Synchronized
    override fun stopV2Live(): Boolean {
        val gatt = bluetoothGatt ?: return false
        val live = v2Live ?: return false
        if (!v2Requested || v2Failed || !isCurrentGatt(gatt) || !hasConnectPermission() ||
            live.phase != WoonaV2LiveSession.Phase.RUNNING) return false
        dispatchV2LiveEvent(live.stop())
        return true
    }

    override fun setCaptureActive(active: Boolean) {
        if (active && v2Requested) {
            listener.onError("Legacy capture is not available after V2 HELLO; disconnect or use the V2 capture path")
            return
        }
        captureActive = active
        if (!active) {
            stopNotificationWatchdog()
        } else if (sessionState == BleSessionState.CONNECTED && !recoveryInProgress) {
            startNotificationWatchdog()
        }
    }

    override fun startScanning() {
        if (!hasScanPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_missing_scan_permission))
            return
        }

        if (!hasConnectPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission))
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.bluetooth_adapter_unavailable))
            return
        }

        if (!adapter.isEnabled) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.bluetooth_off))
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_scanner_unavailable))
            return
        }

        try {
            discoveredAddresses.clear()
            scanner.startScan(scanCallback)
            scanHandler.removeCallbacks(stopScanRunnable)
            scanHandler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS)
            transition(BleSessionEvent.ScanStarted)
        } catch (exception: SecurityException) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_failed_start_scan), exception)
        }
    }

    override fun stopScanning() {
        scanHandler.removeCallbacks(stopScanRunnable)
        if (!hasScanPermission()) {
            // Stopping an already-inactive scan is not a capture failure. A fresh
            // install may not have Nearby-devices permission yet; replay must not
            // be finalized just because this best-effort cleanup cannot run.
            transition(BleSessionEvent.ScanStopped)
            return
        }

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (exception: SecurityException) {
            listener.onError(appTextResolver.getString(R.string.ble_failed_stop_scan), exception)
        }
        transition(BleSessionEvent.ScanStopped)
    }

    override fun connect(address: String) = connectWithIntent(address, false)
    override fun connect(address: String, onConnectionStarting: () -> Unit) = connectWithIntent(address, false, onConnectionStarting)

    override fun connectForV2Preview(address: String) = connectWithIntent(address, true)
    override fun connectForV2Preview(address: String, onConnectionStarting: () -> Unit) = connectWithIntent(address, true, onConnectionStarting)

    @Synchronized
    private fun connectWithIntent(address: String, preview: Boolean, onConnectionStarting: () -> Unit = {}) {
        forceCloseCurrentGatt()
        onConnectionStarting()
        manualDisconnectRequested = false
        recoveryInProgress = false
        awaitingRecoveryNotification = false
        recoveryAttempt = 0
        recoveryStartedElapsedRealtimeMs = null
        scanHandler.removeCallbacks(recoveryRunnable)
        if (!hasConnectPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission))
            return
        }

        v2ConnectionIntent = preview
        activeDeviceAddress = address
        openGatt(address)
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(address: String) {
        activeTransportProfile = transportProfile

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            activeTransportProfile = null
            if (recoveryInProgress) {
                scheduleNextRecoveryAttempt()
            } else {
                transition(BleSessionEvent.Failure)
                listener.onError(appTextResolver.getString(R.string.ble_device_not_found, address))
            }
            return
        }

        try {
            val gatt = device.connectGatt(context, false, gattCallback)
            val generation = ++nextGattGeneration
            gattGenerations[gatt] = generation
            activeGattGeneration = generation
            bluetoothGatt = gatt
            notificationsReady = false
            transition(BleSessionEvent.ConnectRequested)
            scanHandler.postDelayed(gattConnectionTimeoutRunnable, GATT_CONNECTION_TIMEOUT_MS)
        } catch (exception: SecurityException) {
            activeTransportProfile = null
            if (recoveryInProgress) {
                scheduleNextRecoveryAttempt()
            } else {
                transition(BleSessionEvent.Failure)
                listener.onError(appTextResolver.getString(R.string.ble_failed_connect_device), exception)
            }
        }
    }

    @Synchronized
    override fun disconnect() {
        cancelV2ResponseTimeout()
        cancelV2ChunkTimeout()
        v2PendingFrames.clear()
        v2GattWrites.reset()
        if (v2Requested) v2Failed = true
        captureActive = false
        manualDisconnectRequested = true
        recoveryInProgress = false
        awaitingRecoveryNotification = false
        recoveryAttempt = 0
        recoveryStartedElapsedRealtimeMs = null
        scanHandler.removeCallbacks(recoveryRunnable)
        stopNotificationWatchdog()
        activeDeviceAddress = null
        val gatt = bluetoothGatt ?: return
        if (!hasConnectPermission()) {
            clearGattReference(gatt)
            safeCloseGatt(gatt)
            transition(BleSessionEvent.Disconnected)
            listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_disconnect))
            return
        }

        try {
            gatt.disconnect()
        } catch (exception: SecurityException) {
            clearGattReference(gatt)
            safeCloseGatt(gatt)
            transition(BleSessionEvent.Disconnected)
            listener.onError(appTextResolver.getString(R.string.ble_failed_disconnect_device), exception)
        }
    }

    @Synchronized
    override fun close() {
        captureActive = false
        manualDisconnectRequested = true
        recoveryInProgress = false
        awaitingRecoveryNotification = false
        recoveryAttempt = 0
        recoveryStartedElapsedRealtimeMs = null
        scanHandler.removeCallbacks(recoveryRunnable)
        stopNotificationWatchdog()
        activeDeviceAddress = null
        stopTransportSummaryLoop()
        stopScanning()
        forceCloseCurrentGatt()
    }

    private fun transition(event: BleSessionEvent) {
        sessionState = BleSessionStateMachine.transition(sessionState, event)
        listener.onStateChanged(sessionState)
    }

    @Synchronized
    private fun clearGattReference(gatt: BluetoothGatt) {
        if (bluetoothGatt === gatt) {
            scanHandler.removeCallbacks(gattConnectionTimeoutRunnable)
            scanHandler.removeCallbacks(gattSetupTimeoutRunnable)
            resetV2OnDisconnect()
            bluetoothGatt = null
            gattGenerations.remove(gatt)
            activeGattGeneration = null
            pendingNotificationDescriptorUuid = null
            activeTransportProfile = null
            notificationsReady = false
            awaitingRecoveryNotification = false
            notificationMonitoringStartedElapsedRealtimeMs = null
            resetTransportDiagnostics()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPreferredPhy(
        gatt: BluetoothGatt,
        profile: BleTransportProfile,
    ) {
        when (profile.preferredPhy) {
            PreferredPhyMode.LE_2M -> {
                if (!hasConnectPermission()) {
                    listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission))
                    return
                }

                emitDiagnostic(
                    message = appTextResolver.getString(R.string.ble_requested_preferred_phy),
                    level = DiagnosticLevel.INFO,
                )
                try {
                    gatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                    )
                } catch (exception: SecurityException) {
                    listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission), exception)
                }
            }

            PreferredPhyMode.SYSTEM_DEFAULT -> {
                emitDiagnostic(
                    message = appTextResolver.getString(R.string.ble_skipped_preferred_phy),
                    level = DiagnosticLevel.INFO,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeNotificationDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
    ): Int {
        if (!hasConnectPermission()) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                )
            } else {
                if (!descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    BluetoothStatusCodes.ERROR_UNKNOWN
                } else if (gatt.writeDescriptor(descriptor)) {
                    BluetoothStatusCodes.SUCCESS
                } else {
                    BluetoothStatusCodes.ERROR_UNKNOWN
                }
            }
        } catch (_: SecurityException) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }
    }

    @Synchronized
    private fun handleCharacteristicChanged(sourceGatt: BluetoothGatt, value: ByteArray) {
        if (!isCurrentGatt(sourceGatt)) return
        val timeSyncT4Ns = SystemClock.elapsedRealtimeNanos()
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val previousNotificationElapsedRealtimeMs = lastNotificationElapsedRealtimeMs
        lastNotificationElapsedRealtimeMs = nowElapsedRealtimeMs
        notificationCount++
        if (recoveryInProgress && awaitingRecoveryNotification) {
            awaitingRecoveryNotification = false
            recoveryInProgress = false
            recoveryAttempt = 0
            recoveryStartedElapsedRealtimeMs = null
            scanHandler.removeCallbacks(recoveryConfirmationRunnable)
            emitDiagnostic(
                message = "BLE transport recovered after notification silence",
                level = DiagnosticLevel.INFO,
            )
            if (captureActive) startNotificationWatchdog()
        }
        if (previousNotificationElapsedRealtimeMs != null) {
            val gapMillis = (nowElapsedRealtimeMs - previousNotificationElapsedRealtimeMs).coerceAtLeast(0L)
            notificationGapSampleCount++
            notificationGapTotalMillis += gapMillis
            notificationGapMaxMillis = maxOf(notificationGapMaxMillis, gapMillis)
            val longGapThresholdMillis = longNotificationGapThresholdMillis()
            if (gapMillis >= longGapThresholdMillis) {
                longNotificationGapCount++
                if (nowElapsedRealtimeMs - lastLongGapLoggedElapsedRealtimeMs >= LONG_GAP_LOG_COOLDOWN_MS) {
                    lastLongGapLoggedElapsedRealtimeMs = nowElapsedRealtimeMs
                    emitDiagnostic(
                        message = appTextResolver.getString(
                            R.string.ble_notification_gap,
                            gapMillis,
                            longGapThresholdMillis,
                            notificationCount,
                        ),
                        level = DiagnosticLevel.WARNING,
                    )
                }
            }
        }
        if (v2Requested) {
            // The firmware leaves Legacy mode as soon as it accepts HELLO. Do not
            // feed any V2 bytes to the Legacy PacketAssembler, even on failure.
            val gatt = sourceGatt
            if (isCurrentGatt(gatt) && v2Requested && !v2Failed) {
                    if (v2Discovery?.phase == WoonaV2Discovery.Phase.READY) {
                        val live = v2Live
                        val frames = v2LiveReceiver?.append(value.clone()).orEmpty()
                        for (frame in frames) {
                            if (v2StorageExpectedResponse == WoonaV2StorageCodec.START_TRANSFER_RESPONSE &&
                                frame.requestId == 0L && frame.type in setOf(
                                    WoonaV2Codec.STORED_DATA, WoonaV2Codec.STORED_TRANSFER_END)) {
                                // A CRC/COBS-validated stored frame with request_id=0 can only be
                                // emitted after START_STORED_TRANSFER was accepted. Do not discard
                                // a real transfer merely because its small 0x89 control ACK was lost.
                                cancelV2StorageTimeout()
                                v2StorageExpectedResponse = 0
                                v2StoragePendingWire = ByteArray(0)
                                v2StorageRetryCount = 0
                                v2StorageEarlyFrames.clear()
                                v2StorageTransferActive = frame.type == WoonaV2Codec.STORED_DATA
                                listener.onV2StorageFrame(frame)
                                if (v2StorageTransferActive) armV2StorageTimeout(gatt)
                                continue
                            }
                            val storageResponse = v2StorageExpectedResponse != 0 &&
                                frame.requestId == v2StorageRequestId &&
                                (frame.type == v2StorageExpectedResponse ||
                                 frame.type == WoonaV2Codec.ERROR_RESPONSE)
                            if (storageResponse) {
                                val responseType = frame.type
                                v2StorageExpectedResponse = 0
                                v2StoragePendingWire = ByteArray(0)
                                v2StorageRetryCount = 0
                                if (responseType == WoonaV2StorageCodec.START_TRANSFER_RESPONSE &&
                                    frame.payload.firstOrNull() == 0.toByte()) {
                                    v2StorageTransferActive = true
                                } else if (responseType == WoonaV2StorageCodec.STOP_TRANSFER_RESPONSE ||
                                    responseType == WoonaV2Codec.ERROR_RESPONSE) {
                                    v2StorageTransferActive = false
                                    v2StorageEarlyFrames.clear()
                                }
                                armV2StorageTimeout(gatt)
                                listener.onV2StorageFrame(frame)
                                if (responseType == WoonaV2StorageCodec.START_TRANSFER_RESPONSE &&
                                    v2StorageTransferActive) {
                                    while (v2StorageEarlyFrames.isNotEmpty()) {
                                        val early = v2StorageEarlyFrames.removeFirst()
                                        if (early.type == WoonaV2Codec.STORED_TRANSFER_END)
                                            v2StorageTransferActive = false
                                        listener.onV2StorageFrame(early)
                                    }
                                    if (v2StorageTransferActive) armV2StorageTimeout(gatt)
                                    else cancelV2StorageTimeout()
                                }
                                continue
                            }
                            if (v2StorageTransferActive && frame.requestId == 0L &&
                                frame.type in setOf(WoonaV2Codec.STORED_DATA,
                                    WoonaV2Codec.STORED_TRANSFER_END)) {
                                if (frame.type == WoonaV2Codec.STORED_TRANSFER_END) {
                                    v2StorageTransferActive = false
                                }
                                armV2StorageTimeout(gatt)
                                listener.onV2StorageFrame(frame)
                                continue
                            }
                            if (v2TimeSyncActive && frame.requestId == v2TimeSyncPendingId &&
                                frame.type in setOf(WoonaV2TimeSync.RESPONSE, WoonaV2Codec.ERROR_RESPONSE)) {
                                if (frame.type == WoonaV2Codec.ERROR_RESPONSE) {
                                    failV2TimeSync("Device rejected TIME_SYNC (0xFF)")
                                    continue
                                }
                                val reply = WoonaV2TimeSync.parseReply(frame,
                                    v2TimeSyncPendingId, v2TimeSyncNonce)
                                val sample = reply?.let {
                                    WoonaV2TimeSync.makeSample(it, v2TimeSyncT1Ns, timeSyncT4Ns)
                                }
                                if (sample == null || v2TimeSyncSamples.any {
                                    it.clockEpochId != sample.clockEpochId
                                }) {
                                    failV2TimeSync("Invalid TIME_SYNC response or clock epoch changed")
                                    continue
                                }
                                cancelV2TimeSyncTimeout()
                                v2TimeSyncSamples.add(sample)
                                v2TimeSyncPendingId = 0L
                                v2TimeSyncNonce = 0L
                                v2TimeSyncT1Ns = 0L
                                if (v2TimeSyncSamples.size == WoonaV2TimeSync.DIAGNOSTIC_SAMPLES) {
                                    v2TimeSyncActive = false
                                    publishV2TimeSync()
                                } else {
                                    publishV2TimeSync()
                                    if (!enqueueNextV2TimeSync())
                                        failV2TimeSync("Cannot enqueue next TIME_SYNC sample")
                                }
                                continue
                            }
                            if (v2SdExpectedType != 0 && frame.requestId == v2SdPendingId &&
                                (frame.type == v2SdExpectedType ||
                                    frame.type == WoonaV2Codec.ERROR_RESPONSE)) {
                                cancelV2SdAckTimeout()
                                v2SdPendingId = 0L
                                v2SdExpectedType = 0
                                onV2SdAck(frame)
                                continue
                            }
                            val events = live?.receive(frame).orEmpty()
                            if (events.any { it !is WoonaV2LiveSession.Event.Samples }) {
                                cancelV2ResponseTimeout()
                            }
                            events.forEach(::dispatchV2LiveEvent)
                        }
                    } else {
                        val events = v2Discovery?.receive(value.clone()).orEmpty()
                        if (events.isNotEmpty()) cancelV2ResponseTimeout()
                        events.forEach(::dispatchV2Event)
                    }
                    val rejected = (v2Discovery?.rejectedFrames ?: 0) + (v2LiveReceiver?.rejectedFrames ?: 0)
                    if (rejected != v2ReportedRejectedFrames) {
                        v2ReportedRejectedFrames = rejected
                        listener.onV2FrameRejections(rejected)
                    }
            }
            return
        }
        if (v2ConnectionIntent) return // Legacy bytes queued before HELLO are not preview samples.
        listener.onPacketReceived(
            packetFragment = value.clone(),
            receivedAtWallClockMillis = System.currentTimeMillis(),
            receivedAtMonotonicNs = SystemClock.elapsedRealtimeNanos(),
        )
    }

    @Synchronized
    private fun dispatchV2Event(event: WoonaV2Discovery.Event) {
        if (v2Failed) return
        when (event) {
            is WoonaV2Discovery.Event.Transmit -> {
                emitDiagnostic("V2 command queued (${v2Discovery?.phase}, ${event.bytes.size} bytes COBS)",
                    DiagnosticLevel.INFO)
                enqueueV2Frame(event.bytes)
            }
            is WoonaV2Discovery.Event.Complete -> {
                cancelV2ResponseTimeout()
                emitDiagnostic("V2 ready: streams=${event.device.streams.descriptors.size}, " +
                    "capabilities=0x${event.device.hello.capabilities.toString(16)}", DiagnosticLevel.INFO)
                v2SupportsLive = event.device.hello.hasCapability(0)
                v2SupportsStorage = event.device.hello.hasCapability(7) &&
                    event.device.hello.hasCapability(8)
                v2SupportsTimeSync = event.device.hello.hasCapability(WoonaV2TimeSync.CAPABILITY_BIT)
                v2Live = WoonaV2LiveSession(event.device.streams)
                v2LiveReceiver = WoonaV2StreamDecoder()
                listener.onV2DiscoveryReady(event.device)
            }
            is WoonaV2Discovery.Event.Failure -> {
                event.busySessionMode?.let(listener::onV2DeviceBusy)
                failV2Discovery(event.reason)
            }
        }
    }

    @Synchronized
    private fun dispatchV2LiveEvent(event: WoonaV2LiveSession.Event) {
        if (v2Failed) return
        when (event) {
            is WoonaV2LiveSession.Event.Transmit -> {
                enqueueV2Frame(event.bytes)
            }
            is WoonaV2LiveSession.Event.Started -> {
                emitDiagnostic("V2 LIVE started: id=${event.sessionId} mask=0x${event.streamMask.toString(16)}", DiagnosticLevel.INFO)
                listener.onV2LiveStarted(event.sessionId, event.streamMask)
            }
            is WoonaV2LiveSession.Event.Samples -> listener.onV2LiveSamples(event)
            is WoonaV2LiveSession.Event.Stopped -> {
                emitDiagnostic("V2 LIVE stopped: id=${event.sessionId}", DiagnosticLevel.INFO)
                listener.onV2LiveStopped(event.sessionId)
            }
            is WoonaV2LiveSession.Event.Failure -> failV2Discovery(event.reason)
        }
    }

    @Synchronized
    private fun enqueueV2Frame(bytes: ByteArray) {
        if (manualDisconnectRequested || v2Failed) return
        if (v2PendingFrames.size >= 4 || bytes.size > WoonaV2GattWriteQueue.MAX_FRAME_BYTES) {
            failV2Discovery("V2 command queue exceeded its bounded capacity")
            return
        }
        v2PendingFrames.addLast(bytes.clone())
        bluetoothGatt?.let(::startNextV2Frame)
    }

    @Synchronized
    private fun startNextV2Frame(gatt: BluetoothGatt) {
        if (v2Failed || manualDisconnectRequested || !v2Requested || v2GattWrites.isBusy || !isCurrentGatt(gatt)) return
        if (v2PendingFrames.isEmpty()) {
            if (v2SdExpectedType != 0) armV2SdAckTimeout(gatt)
            if (v2TimeSyncActive && v2TimeSyncPendingId != 0L) armV2TimeSyncTimeout(gatt)
            if (v2StorageExpectedResponse != 0 || v2StorageTransferActive)
                armV2StorageTimeout(gatt)
            val phase = v2Discovery?.phase
            val livePhase = v2Live?.phase
            if (phase in setOf(
                    WoonaV2Discovery.Phase.WAIT_HELLO,
                    WoonaV2Discovery.Phase.WAIT_STREAMS,
                    WoonaV2Discovery.Phase.WAIT_STATUS,
                ) || livePhase in setOf(
                    WoonaV2LiveSession.Phase.WAIT_START,
                    WoonaV2LiveSession.Phase.WAIT_STOP,
                )
            ) {
                armV2ResponseTimeout(gatt)
            }
            return
        }
        cancelV2ResponseTimeout()
        val wire = v2PendingFrames.removeFirst()
        try {
            writeV2Chunk(gatt, v2GattWrites.begin(wire, negotiatedMtu ?: 23))
        } catch (exception: IllegalArgumentException) {
            failV2Discovery("Invalid V2 frame/MTU: ${exception.message}")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun writeV2Chunk(gatt: BluetoothGatt, chunk: ByteArray) {
        val characteristic = v2WriteCharacteristic
        if (characteristic == null || !hasConnectPermission() || !isCurrentGatt(gatt)) {
            failV2Discovery("FFF2 is unavailable while sending V2")
            return
        }
        if (v2TimeSyncActive && v2TimeSyncPendingId != 0L && v2TimeSyncT1Ns == 0L)
            v2TimeSyncT1Ns = SystemClock.elapsedRealtimeNanos()
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = chunk
                if (gatt.writeCharacteristic(characteristic)) BluetoothStatusCodes.SUCCESS
                else BluetoothStatusCodes.ERROR_UNKNOWN
            }
        } catch (_: SecurityException) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }
        if (result != BluetoothStatusCodes.SUCCESS) {
            failV2Discovery("FFF2 rejected write (status=$result)")
        } else {
            armV2ChunkTimeout(gatt)
        }
    }

    @Synchronized
    private fun failV2Discovery(reason: String) {
        if (v2Failed) return
        v2Failed = true
        failV2SdCapture("BLE V2 transport failed: $reason")
        failV2TimeSync("BLE V2 transport failed: $reason")
        val storageWasActive = v2StorageExpectedResponse != 0 || v2StorageTransferActive
        cancelV2StorageTimeout()
        v2StorageExpectedResponse = 0
        v2StorageTransferActive = false
        v2StoragePendingWire = ByteArray(0)
        v2StorageRetryCount = 0
        v2StorageEarlyFrames.clear()
        if (storageWasActive) listener.onV2StorageFailure(reason)
        v2PendingFrames.clear()
        v2GattWrites.reset()
        cancelV2ResponseTimeout()
        cancelV2ChunkTimeout()
        emitDiagnostic("V2 discovery failed: $reason; connection retained; disconnect may stop active SD recording", DiagnosticLevel.WARNING)
        listener.onError(appTextResolver.getString(R.string.v2_connection_failed) + ": " + reason + "\n" +
            appTextResolver.getString(R.string.v2_sd_disconnect_warning))
    }

    @Synchronized
    private fun resetV2OnDisconnect() {
        v2ConnectionIntent = false
        v2ReportedRejectedFrames = 0
        failV2SdCapture("BLE disconnected during SD recording; device may continue until timeout")
        v2SdSessionId = 0L
        v2SdSessionSeq = 0L
        failV2TimeSync("BLE disconnected during TIME_SYNC")
        v2SupportsTimeSync = false
        cancelV2TimeSyncTimeout()
        v2TimeSyncSamples.clear()
        cancelV2StorageTimeout()
        v2StorageExpectedResponse = 0
        v2StorageTransferActive = false
        v2StoragePendingWire = ByteArray(0)
        v2StorageRetryCount = 0
        v2StorageEarlyFrames.clear()
        v2SupportsStorage = false
        cancelV2ResponseTimeout()
        cancelV2ChunkTimeout()
        v2GattWrites.reset()
        v2PendingFrames.clear()
        v2Discovery?.resetForNewConnection()
        v2Discovery = null
        v2Live?.reset()
        v2Live = null
        v2LiveReceiver?.reset()
        v2LiveReceiver = null
        v2SupportsLive = false
        v2WriteCharacteristic = null
        v2Failed = false
        v2Requested = false
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean {
        return bluetoothGatt === gatt && activeGattGeneration != null &&
            gattGenerations[gatt] == activeGattGeneration
    }

    private fun isCurrentGattConnected(): Boolean {
        val gatt = bluetoothGatt ?: return false
        return captureActive && sessionState == BleSessionState.CONNECTED && isCurrentGatt(gatt) && !recoveryInProgress
    }

    private fun handleGattSetupFailure(gatt: BluetoothGatt, message: String) {
        stopTransportSummaryLoop()
        stopNotificationWatchdog()
        scanHandler.removeCallbacks(gattConnectionTimeoutRunnable)
        val wasRecovering = recoveryInProgress
        clearGattReference(gatt)
        safeCloseGatt(gatt)
        if (manualDisconnectRequested) {
            transition(BleSessionEvent.Disconnected)
        } else if (wasRecovering) {
            emitDiagnostic(message = message, level = DiagnosticLevel.WARNING)
            scheduleNextRecoveryAttempt()
        } else {
            transition(BleSessionEvent.Failure)
            listener.onError(message)
        }
    }

    private fun startNotificationWatchdog() {
        notificationMonitoringStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        scanHandler.removeCallbacks(notificationWatchdogRunnable)
        scanHandler.postDelayed(notificationWatchdogRunnable, NOTIFICATION_WATCHDOG_INTERVAL_MS)
    }

    private fun stopNotificationWatchdog() {
        scanHandler.removeCallbacks(notificationWatchdogRunnable)
        scanHandler.removeCallbacks(recoveryConfirmationRunnable)
        notificationMonitoringStartedElapsedRealtimeMs = null
    }

    private fun startRecoveryConfirmation() {
        scanHandler.removeCallbacks(recoveryConfirmationRunnable)
        scanHandler.postDelayed(recoveryConfirmationRunnable, RECOVERY_CONFIRMATION_TIMEOUT_MS)
    }

    private fun beginTransportRecovery(silenceMillis: Long, reason: String = "Notification silence: $silenceMillis ms") {
        if (manualDisconnectRequested || recoveryInProgress || activeDeviceAddress == null) return

        recoveryInProgress = true
        awaitingRecoveryNotification = false
        recoveryStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        recoveryAttempt = 0
        stopTransportSummaryLoop()
        stopNotificationWatchdog()
        emitDiagnostic(
            message = "$reason; starting bounded BLE transport recovery",
            level = DiagnosticLevel.WARNING,
        )

        val gatt = bluetoothGatt
        if (gatt != null && isCurrentGatt(gatt)) {
            clearGattReference(gatt)
            safeCloseGatt(gatt)
        }
        scheduleNextRecoveryAttempt()
    }

    private fun scheduleNextRecoveryAttempt() {
        if (manualDisconnectRequested || !recoveryInProgress) return

        val now = SystemClock.elapsedRealtime()
        val started = recoveryStartedElapsedRealtimeMs ?: now.also { recoveryStartedElapsedRealtimeMs = it }
        if (recoveryAttempt >= MAX_RECOVERY_ATTEMPTS || now - started >= RECOVERY_BUDGET_MS) {
            recoveryInProgress = false
            awaitingRecoveryNotification = false
            transition(BleSessionEvent.Failure)
            listener.onError("BLE transport recovery exhausted")
            return
        }

        recoveryAttempt++
        val delayMillis = when (recoveryAttempt) {
            1 -> 1_000L
            2 -> 3_000L
            else -> 10_000L
        }
        emitDiagnostic(
            message = "BLE transport recovery attempt $recoveryAttempt/$MAX_RECOVERY_ATTEMPTS scheduled in ${delayMillis} ms",
            level = DiagnosticLevel.WARNING,
        )
        scanHandler.removeCallbacks(recoveryRunnable)
        scanHandler.postDelayed(recoveryRunnable, delayMillis)
    }

    private fun emitDiagnostic(
        message: String,
        level: DiagnosticLevel,
    ) {
        when (level) {
            DiagnosticLevel.INFO -> Log.i(TAG, message)
            DiagnosticLevel.WARNING -> Log.w(TAG, message)
        }
        listener.onDiagnosticMessage(message)
    }

    private fun resetTransportDiagnostics() {
        negotiatedMtu = null
        negotiatedTxPhy = null
        negotiatedRxPhy = null
        negotiatedConnectionIntervalUnits = null
        negotiatedConnectionLatency = null
        negotiatedSupervisionTimeoutUnits = null
        notificationCount = 0L
        notificationGapSampleCount = 0L
        notificationGapTotalMillis = 0L
        notificationGapMaxMillis = 0L
        longNotificationGapCount = 0L
        lastNotificationElapsedRealtimeMs = null
        notificationMonitoringStartedElapsedRealtimeMs = null
        lastLongGapLoggedElapsedRealtimeMs = 0L
    }

    private fun startTransportSummaryLoop() {
        scanHandler.removeCallbacks(transportSummaryRunnable)
        scanHandler.postDelayed(transportSummaryRunnable, TRANSPORT_SUMMARY_INTERVAL_MS)
    }

    private fun stopTransportSummaryLoop() {
        scanHandler.removeCallbacks(transportSummaryRunnable)
    }

    private fun buildTransportSnapshotMessage(): String {
        val interval = negotiatedConnectionIntervalUnits?.let(::formatConnectionIntervalMillis)
            ?: appTextResolver.getString(R.string.value_not_available)
        val latency = negotiatedConnectionLatency?.toString() ?: appTextResolver.getString(R.string.value_not_available)
        val timeout = negotiatedSupervisionTimeoutUnits?.let { "${it * 10}" }
            ?: appTextResolver.getString(R.string.value_not_available)
        val mtu = negotiatedMtu?.toString() ?: "?"
        val phy = when {
            negotiatedTxPhy == null || negotiatedRxPhy == null -> "?"
            else -> "${describePhy(negotiatedTxPhy)} / ${describePhy(negotiatedRxPhy)}"
        }
        return appTextResolver.getString(
            R.string.ble_transport_snapshot,
            appTextResolver.getString((activeTransportProfile ?: transportProfile).titleRes),
            mtu,
            phy,
            interval,
            latency,
            timeout,
        )
    }

    private fun buildTransportSummaryMessage(): String {
        val averageGapMillis = if (notificationGapSampleCount == 0L) {
            appTextResolver.getString(R.string.value_not_available)
        } else {
            formatMillis(notificationGapTotalMillis.toDouble() / notificationGapSampleCount.toDouble())
        }
        val maxGapMillis = if (notificationGapSampleCount == 0L) {
            appTextResolver.getString(R.string.value_not_available)
        } else {
            "${notificationGapMaxMillis}"
        }
        return appTextResolver.getString(
            R.string.ble_transport_summary,
            buildTransportSnapshotMessage(),
            notificationCount,
            averageGapMillis,
            maxGapMillis,
            longNotificationGapCount,
        )
    }

    private fun longNotificationGapThresholdMillis(): Long {
        val negotiatedIntervalMs = negotiatedConnectionIntervalUnits?.let { it * 1.25 } ?: return DEFAULT_LONG_NOTIFICATION_GAP_MS
        return maxOf(DEFAULT_LONG_NOTIFICATION_GAP_MS, (negotiatedIntervalMs * 4).toLong())
    }

    private fun formatConnectionIntervalMillis(intervalUnits: Int): String {
        return formatMillis(intervalUnits * 1.25)
    }

    private fun formatMillis(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun describePhy(phy: Int?): String {
        return when (phy) {
            BluetoothDevice.PHY_LE_1M -> "1M"
            BluetoothDevice.PHY_LE_2M -> "2M"
            BluetoothDevice.PHY_LE_CODED -> "CODED"
            null -> "?"
            else -> phy.toString()
        }
    }

    @Synchronized
    private fun forceCloseCurrentGatt() {
        val gatt = bluetoothGatt ?: return
        clearGattReference(gatt)
        safeCloseGatt(gatt)
        transition(BleSessionEvent.Disconnected)
    }

    @SuppressLint("MissingPermission")
    private fun safeCloseGatt(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Failed to close GATT after permission loss", exception)
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to close GATT", exception)
        }
    }

    private fun hasScanPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun describeScanFailure(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
                appTextResolver.getString(R.string.ble_scan_failure_already_started)
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                appTextResolver.getString(R.string.ble_scan_failure_app_registration)
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                appTextResolver.getString(R.string.ble_scan_failure_feature_unsupported)
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                appTextResolver.getString(R.string.ble_scan_failure_internal_error)
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
                appTextResolver.getString(R.string.ble_scan_failure_out_of_hw)
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                appTextResolver.getString(R.string.ble_scan_failure_too_frequent)
            else -> appTextResolver.getString(R.string.ble_scan_failure_code, errorCode)
        }
    }

    private companion object {
        const val TAG = "BLE_SESSION"
        const val V2_RESPONSE_TIMEOUT_MS = 3_000L
        const val V2_CHUNK_WRITE_TIMEOUT_MS = 3_000L
        const val SCAN_TIMEOUT_MS = 10_000L
        const val TRANSPORT_SUMMARY_INTERVAL_MS = 5_000L
        const val DEFAULT_LONG_NOTIFICATION_GAP_MS = 100L
        const val LONG_GAP_LOG_COOLDOWN_MS = 5_000L
        const val NOTIFICATION_WATCHDOG_INTERVAL_MS = 1_000L
        const val NOTIFICATION_SILENCE_TIMEOUT_MS = 12_000L
        const val RECOVERY_CONFIRMATION_TIMEOUT_MS = 5_000L
        const val GATT_CONNECTION_TIMEOUT_MS = 10_000L
        const val GATT_SETUP_TIMEOUT_MS = 10_000L
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val RECOVERY_BUDGET_MS = 60_000L
    }
}

private enum class DiagnosticLevel {
    INFO,
    WARNING,
}
