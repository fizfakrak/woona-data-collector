package com.example.myapplication

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.example.myapplication.R
import com.example.myapplication.ble.BleDevice
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.ble.BleSessionListener
import com.example.myapplication.ble.BleSessionManager
import com.example.myapplication.ble.BleSessionState
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.ble.PolarSessionManager
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.data.Recording
import com.example.myapplication.feature.device.DeviceFeatureController
import com.example.myapplication.feature.device.DebugPacketReplayController
import com.example.myapplication.feature.device.DeviceUiStateHolder
import com.example.myapplication.feature.device.BatchingPacketCaptureController
import com.example.myapplication.feature.device.PacketCaptureController
import com.example.myapplication.feature.device.PacketCaptureProcessor
import com.example.myapplication.feature.device.PacketProcessingUpdateBatcher
import com.example.myapplication.feature.device.ChartPoint
import com.example.myapplication.feature.device.ChartStreamKey
import com.example.myapplication.feature.device.WoonaV2ChartMapper
import com.example.myapplication.feature.device.PacketReplayController
import com.example.myapplication.feature.device.PacketSubmitResult
import com.example.myapplication.storage.BleFileShareIntentFactory
import com.example.myapplication.storage.BleDiagnosticLogFileStore
import com.example.myapplication.storage.FileShareIntentFactory
import com.example.myapplication.storage.BlePacketFileStore
import com.example.myapplication.storage.BlePacketTimelineFileStore
import com.example.myapplication.storage.BleRawFragmentFileStore
import com.example.myapplication.storage.PolarCsvFileStore
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppTextResolver
import com.example.myapplication.protocol.ConnectionQuality
import com.example.myapplication.feature.device.V2PreviewTimeline
import com.example.myapplication.protocol.WoonaV2Discovery
import com.example.myapplication.protocol.WoonaV2LiveSession
import com.example.myapplication.video.AndroidVideoRecorder
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

fun createDeviceFeatureController(
    context: Context,
    filesDir: File,
    fileProviderAuthority: String,
    serviceUuid: UUID,
    characteristicUuid: UUID,
    descriptorUuid: UUID,
    initialTransportProfile: BleTransportProfile? = null,
    packetCaptureControllerFactory: ((DeviceUiStateHolder, Handler) -> PacketCaptureController)? = null,
    bleSessionControllerFactory: ((BleSessionListener) -> BleSessionController)? = null,
    fileShareIntentFactory: FileShareIntentFactory? = null,
    woonaDatabase: WoonaDatabase? = null,
    snapshotDirectory: File? = null,
    onRecordingChanged: () -> Unit = {},
    onCaptureLifecycleChanged: (Boolean) -> Unit = {},
    appTextResolver: AppTextResolver = AppTextResolver(context.applicationContext) {
        AppLanguage.defaultFrom()
    },
    polarFileStoreFactory: () -> PolarCsvFileStore = { PolarCsvFileStore() },
): DeviceFeatureController {
    val uiStateHolder = DeviceUiStateHolder()
    val mainHandler = Handler(Looper.getMainLooper())
    var deviceFeatureController: DeviceFeatureController? = null
    var bleSessionController: BleSessionController? = null
    var updateBatcher: PacketProcessingUpdateBatcher? = null
    updateBatcher = PacketProcessingUpdateBatcher(
        dispatchIntervalMillis = UI_PACKET_UPDATE_INTERVAL_MS,
        schedule = { runnable, delayMillis -> mainHandler.postDelayed(runnable, delayMillis) },
        cancel = { runnable -> mainHandler.removeCallbacks(runnable) },
        dispatch = { generation, update ->
            mainHandler.post {
                if (updateBatcher?.isGenerationCurrent(generation) == true) {
                    deviceFeatureController?.onPacketUpdate(update) ?: uiStateHolder.applyPacketUpdate(update)
                }
            }
        },
    )
    val packetCaptureController: PacketCaptureController =
        packetCaptureControllerFactory?.invoke(uiStateHolder, mainHandler) ?: BatchingPacketCaptureController(
            delegate = PacketCaptureProcessor(
                packetFileStore = BlePacketFileStore(directory = filesDir),
                rawFragmentFileStore = BleRawFragmentFileStore(directory = filesDir),
                diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = filesDir),
                packetTimelineFileStore = BlePacketTimelineFileStore(directory = filesDir),
                appTextResolver = appTextResolver,
                onPacketProcessed = updateBatcher::submit,
                onAcceptedPacketTimestamp = { wallClockMillis, monotonicNs, deviceTimerMillis ->
                    deviceFeatureController?.onSensorFragmentReceived(
                        receivedAtMillis = wallClockMillis,
                        receivedAtMonotonicNs = monotonicNs,
                        deviceTimerMillis = deviceTimerMillis,
                    )
                },
                onError = { message, throwable ->
                    val owner = deviceFeatureController?.captureIdentity()
                    updateBatcher.clearPending()
                    mainHandler.post {
                        if (deviceFeatureController?.captureIdentity() == owner) {
                            deviceFeatureController?.onSessionError(message, throwable) ?: uiStateHolder.showError(message)
                            bleSessionController?.close()
                        }
                    }
                    Log.e("BLE_PROCESSOR", message, throwable)
                },
            ),
            batcher = updateBatcher,
        )
    packetCaptureController.updateSelection(
        sensorType = uiStateHolder.uiState.selectedSensorType,
        channel = uiStateHolder.uiState.selectedChannel,
    )

    val submitPacketFragment: (ByteArray, Long, Long) -> PacketSubmitResult = {
            packetFragment,
            receivedAtMillis,
            receivedAtMonotonicNs,
        ->
        when (
            val submitResult = packetCaptureController.submit(
                packetFragment,
                receivedAtMillis,
                receivedAtMonotonicNs,
            )
        ) {
            PacketSubmitResult.ACCEPTED -> submitResult
            PacketSubmitResult.REJECTED -> {
                Log.d("BLE_QUEUE", "Dropping packet fragment after capture shutdown")
                submitResult
            }

            PacketSubmitResult.OVERFLOW -> {
                val message = appTextResolver.getString(R.string.packet_queue_overflow_stopped)
                val owner = deviceFeatureController?.captureIdentity()
                packetCaptureController.stopCapture()
                mainHandler.post {
                    if (deviceFeatureController?.captureIdentity() == owner) {
                        deviceFeatureController?.onSessionError(message) ?: uiStateHolder.showError(message)
                        bleSessionController?.close()
                    }
                }
                Log.e("BLE_QUEUE", message)
                submitResult
            }
        }
    }
    // UI-only bounded buffer. It must not become the durable V2 acquisition path.
    val v2Timeline = V2PreviewTimeline()
    val v2PreviewLock = Any()
    val v2PreviewEvents = ArrayDeque<WoonaV2LiveSession.Event.Samples>()
    var v2PreviewScheduled = false
    var v2ReceivedPending = 0
    var v2GapsPending = 0
    var v2RejectedPendingTotal = 0L
    var v2DroppedPending = 0
    val flushV2Preview = Runnable {
        val pending = synchronized(v2PreviewLock) {
            v2PreviewScheduled = false
            val events = v2PreviewEvents.toList()
            v2PreviewEvents.clear()
            val counters = intArrayOf(v2ReceivedPending, v2GapsPending, v2DroppedPending)
            v2ReceivedPending = 0; v2GapsPending = 0; v2DroppedPending = 0
            Triple(events, counters, v2RejectedPendingTotal)
        }
        val (batch, counters, rejectedTotal) = pending
        if (deviceFeatureController?.isV2PreviewForeground() == true) uiStateHolder.onV2FrameRejections(rejectedTotal)
        if (batch.isNotEmpty() && deviceFeatureController?.isV2PreviewForeground() == true) {
            val charts = linkedMapOf<ChartStreamKey, MutableList<ChartPoint>>()
            var unplottable = 0
            batch.forEach { event ->
                val mapped = v2Timeline.map(event)
                if (mapped.isEmpty()) unplottable++
                mapped.forEach { (key, points) ->
                    charts.getOrPut(key) { mutableListOf() }.addAll(points)
                }
            }
            uiStateHolder.onV2LivePreview(
                samplesByStream = charts,
                receivedBlocks = counters[0],
                sequenceGaps = counters[1],
                chartSkippedBlocks = counters[2] + unplottable,
            )
        }
    }
    fun resetV2PreviewQueue(resetRejected: Boolean = false) {
        mainHandler.removeCallbacks(flushV2Preview)
        synchronized(v2PreviewLock) {
            v2PreviewEvents.clear(); v2PreviewScheduled = false
            if (resetRejected) v2RejectedPendingTotal = 0
            v2ReceivedPending = 0; v2GapsPending = 0; v2DroppedPending = 0
        }
    }
    fun captureOnMain(action: () -> Unit) {
        val owner = deviceFeatureController?.captureIdentity()
        mainHandler.post {
            if (deviceFeatureController?.captureIdentity() == owner) action()
        }
    }
    fun transportOnMain(action: () -> Unit) {
        val generation = deviceFeatureController?.connectionUiGeneration()
        captureOnMain {
            if (deviceFeatureController?.connectionUiGeneration() == generation) action()
        }
    }
    fun v2OnMain(action: () -> Unit) {
        val generation = deviceFeatureController?.connectionUiGeneration()
        fun applyCurrent() {
            if (deviceFeatureController?.isV2PreviewConnection() == true &&
                deviceFeatureController?.connectionUiGeneration() == generation) action()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) applyCurrent()
        else mainHandler.post { applyCurrent() }
    }
    val listener = object : BleSessionListener {
            override fun onDeviceFound(device: BleDevice) {
                mainHandler.post {
                    uiStateHolder.addFoundDevice(device)
                }
            }

            override fun onPacketReceived(packetFragment: ByteArray) {
                if (deviceFeatureController?.isV2PreviewConnection() == true) return
                submitPacketFragment(
                    packetFragment,
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtimeNanos(),
                )
            }

            override fun onPacketReceived(
                packetFragment: ByteArray,
                receivedAtWallClockMillis: Long,
                receivedAtMonotonicNs: Long,
            ) {
                if (deviceFeatureController?.isV2PreviewConnection() == true) return
                submitPacketFragment(packetFragment, receivedAtWallClockMillis, receivedAtMonotonicNs)
            }

            override fun onDiagnosticMessage(message: String) {
                transportOnMain {
                    deviceFeatureController?.onTransportDiagnostic(message)
                }
            }

            override fun onCaptureReady() {
                transportOnMain {
                    deviceFeatureController?.onCaptureReady()
                }
            }

            override fun onV2DiscoveryReady(device: WoonaV2Discovery.Device) {
                v2OnMain {
                    uiStateHolder.onV2DiscoveryReady(
                        device.streams.streamMask, device.hello.capabilities, device.status.sessionMode,
                    )
                }
            }

            override fun onV2StorageFrame(frame: com.example.myapplication.protocol.V2Frame) {
                deviceFeatureController?.onV2StorageFrame(frame)
            }

            override fun onV2StorageFailure(reason: String) {
                deviceFeatureController?.onV2StorageFailure(reason)
            }

            override fun onV2TimeSyncStatus(status: com.example.myapplication.protocol.WoonaV2TimeSync.Diagnostics) {
                v2OnMain { deviceFeatureController?.onV2TimeSyncStatus(status) }
            }

            override fun onV2SdCaptureStarted(sessionId: Long, storageSeq: Long,
                                              streamMask: Long, durationSeconds: Int) {
                v2OnMain { deviceFeatureController?.onV2SdCaptureStarted(
                    sessionId, storageSeq, streamMask, durationSeconds) }
            }
            override fun onV2SdCaptureStopped(sessionId: Long, storageSeq: Long) {
                v2OnMain { deviceFeatureController?.onV2SdCaptureStopped(sessionId, storageSeq) }
            }
            override fun onV2SdCaptureFailure(message: String) {
                v2OnMain { deviceFeatureController?.onV2SdCaptureFailure(message) }
            }

            override fun onV2FrameRejections(total: Long) {
                if (deviceFeatureController?.isV2PreviewForeground() != true) return
                val schedule = synchronized(v2PreviewLock) {
                    v2RejectedPendingTotal = total
                    if (v2PreviewScheduled) false else { v2PreviewScheduled = true; true }
                }
                if (schedule) mainHandler.postDelayed(flushV2Preview, 100L)
            }

            override fun onV2DeviceBusy(sessionMode: Int) {
                v2OnMain { uiStateHolder.onV2DeviceBusy(sessionMode) }
            }

            override fun onV2DiscoveryStarted() {
                resetV2PreviewQueue(resetRejected = true)
                v2OnMain {
                    v2Timeline.reset()
                    uiStateHolder.onV2DiscoveryRequested()
                }
            }

            override fun onV2LiveStarted(sessionId: Long, streamMask: Long) {
                resetV2PreviewQueue()
                v2OnMain {
                    v2Timeline.reset()
                    deviceFeatureController?.onV2LiveStarted()
                }
            }

            override fun onV2LiveSamples(event: WoonaV2LiveSession.Event.Samples) {
                if (deviceFeatureController?.isV2PreviewForeground() != true) return
                val schedulePreview = synchronized(v2PreviewLock) {
                    v2ReceivedPending++
                    if (event.sequenceGap) v2GapsPending++
                    if (v2PreviewEvents.size >= 64) {
                        v2PreviewEvents.removeFirst()
                        v2DroppedPending++
                    }
                    v2PreviewEvents.addLast(event)
                    if (v2PreviewScheduled) false else {
                        v2PreviewScheduled = true
                        true
                    }
                }
                if (schedulePreview) mainHandler.postDelayed(flushV2Preview, 100L)
            }

            override fun onV2LiveStopped(sessionId: Long) {
                v2OnMain { uiStateHolder.onV2LiveStopped() }
            }

            override fun onStateChanged(state: BleSessionState) {
                transportOnMain {
                    if (state in setOf(BleSessionState.DISCONNECTED, BleSessionState.FAILED)) resetV2PreviewQueue()
                    deviceFeatureController?.onBleStateChanged(state)
                        ?: uiStateHolder.onSessionStateChanged(state)
                }
            }

            override fun onError(message: String, throwable: Throwable?) {
                transportOnMain {
                    if (message.startsWith("V2 discovery failed:")) {
                        // Diagnostic V2 negotiation is not a Legacy capture failure.
                        uiStateHolder.onV2DiscoveryFailed(message)
                    } else {
                        deviceFeatureController?.onSessionError(message, throwable) ?: uiStateHolder.showError(message)
                    }
                }
                Log.e("BLE_SESSION", message, throwable)
            }
        }
    bleSessionController = bleSessionControllerFactory?.invoke(listener) ?: BleSessionManager(
        context = context,
        serviceUuid = serviceUuid,
        characteristicUuid = characteristicUuid,
        descriptorUuid = descriptorUuid,
        listener = listener,
        appTextResolver = appTextResolver,
    )
    uiStateHolder.onTransportProfileSelected(requireNotNull(bleSessionController).currentTransportProfile())
    val packetReplayController: PacketReplayController = DebugPacketReplayController(
        submitFragment = { bytes ->
            submitPacketFragment(
                bytes,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos(),
            )
        },
        onReplayPreparing = {
            deviceFeatureController?.onReplayPreparing()
        },
        onReplayStarted = {
            deviceFeatureController?.onReplayStarted()
        },
        onReplayCompleted = {
            captureOnMain { deviceFeatureController?.onReplayCompleted() }
        },
        onReplayStopped = {
            captureOnMain { deviceFeatureController?.onReplayStopped() }
        },
        onError = { message, throwable ->
            captureOnMain {
                deviceFeatureController?.onReplayError(message, throwable) ?: uiStateHolder.showError(message)
            }
            Log.e("BLE_REPLAY", message, throwable)
        },
        appTextResolver = appTextResolver,
        callbackDispatcher = { callback -> mainHandler.post(callback) },
        replayTempFileFactory = {
            File.createTempFile("debug-replay-", ".bin", context.cacheDir)
        },
    )

    val polarFileStore = polarFileStoreFactory()

    // Declared before construction so status/error callbacks can verify that
    // their generation is still current when the main-thread action runs.
    var polarSessionManagerOrNull: PolarSessionManager? = null
    val polarSessionManager = PolarSessionManager(
        context = context,
        onDeviceFound = { device -> mainHandler.post { uiStateHolder.addFoundPolarDevice(device) } },
        onStatus = { status, statusGeneration ->
            captureOnMain {
                // The generation is re-checked when the queued action runs: a
                // reconnect between post and apply must discard the old event.
                if (polarSessionManagerOrNull?.isGenerationCurrent(statusGeneration) == true) {
                    deviceFeatureController?.onPolarStatusChanged(status) ?: uiStateHolder.updatePolarStatus(status)
                }
            }
        },
        onHeartRate = { frame, now, recordingId -> deviceFeatureController?.onPolarHeartRate(frame, now, recordingId) },
        onEcg = { frame, now, recordingId -> deviceFeatureController?.onPolarEcg(frame, now, recordingId) },
        onAcceleration = { frame, now, recordingId -> deviceFeatureController?.onPolarAcceleration(frame, now, recordingId) },
        onError = { message, throwable, errorGeneration ->
            captureOnMain {
                if (polarSessionManagerOrNull?.isGenerationCurrent(errorGeneration) == true) {
                    deviceFeatureController?.showErrorOnMainThread(message, throwable) ?: uiStateHolder.showError(message)
                }
            }
            Log.e("POLAR_SESSION", message, throwable)
        },
    )
    polarSessionManagerOrNull = polarSessionManager

    deviceFeatureController = DeviceFeatureController(
        bleSessionController = requireNotNull(bleSessionController),
        packetCaptureController = packetCaptureController,
        fileShareIntentFactory = fileShareIntentFactory
            ?: BleFileShareIntentFactory(
                authority = fileProviderAuthority,
                appTextResolver = appTextResolver,
            ),
        appTextResolver = appTextResolver,
        packetReplayController = packetReplayController,
        uiStateHolder = uiStateHolder,
        runOnUiThread = { action ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                mainHandler.post(action)
            }
        },
        woonaDatabase = woonaDatabase,
        snapshotDirectory = snapshotDirectory,
        v2StorageRootDir = filesDir,
        onRecordingChanged = onRecordingChanged,
        videoRecorder = AndroidVideoRecorder(context),
        polarSessionManager = polarSessionManager,
        polarFileStore = polarFileStore,
        schedule = { runnable, delayMillis -> mainHandler.postDelayed(runnable, delayMillis) },
        cancel = mainHandler::removeCallbacks,
        onConnectionAlarm = { quality -> playConnectionAlarm(context, mainHandler, quality) },
        onRecoveryRetryNeeded = { CaptureServiceBridge.onStopFailed?.invoke() },
        onCaptureLifecycleChanged = onCaptureLifecycleChanged,
    )

    initialTransportProfile?.let { profile ->
        deviceFeatureController.onTransportProfileSelected(profile)
    }
    return requireNotNull(deviceFeatureController)
}

private fun playConnectionAlarm(
    context: Context,
    handler: Handler,
    quality: ConnectionQuality,
) {
    val vibration = when (quality) {
        ConnectionQuality.LOST -> longArrayOf(0L, 350L, 180L, 350L)
        ConnectionQuality.WEAK -> longArrayOf(0L, 120L, 80L, 120L)
        else -> longArrayOf(0L, 80L)
    }
    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        ?.vibrate(VibrationEffect.createWaveform(vibration, -1))

    val durationMillis = if (quality == ConnectionQuality.LOST) 700 else 250
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, if (quality == ConnectionQuality.LOST) 80 else 55)
        tone.startTone(
            if (quality == ConnectionQuality.GOOD) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP2,
            durationMillis,
        )
        handler.postDelayed(tone::release, durationMillis + 100L)
    }
}

object DeviceFeatureModuleEntryPoint {
    fun create(
        context: Context,
        filesDir: File,
        fileProviderAuthority: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        initialTransportProfile: BleTransportProfile? = null,
        packetCaptureControllerFactory: ((DeviceUiStateHolder, Handler) -> PacketCaptureController)? = null,
        bleSessionControllerFactory: ((BleSessionListener) -> BleSessionController)? = null,
        fileShareIntentFactory: FileShareIntentFactory? = null,
        woonaDatabase: WoonaDatabase? = null,
        snapshotDirectory: File? = null,
        onRecordingChanged: () -> Unit = {},
        onCaptureLifecycleChanged: (Boolean) -> Unit = {},
        appTextResolver: AppTextResolver = AppTextResolver(context.applicationContext) {
            AppLanguage.defaultFrom()
        },
    ): DeviceFeatureController {
        return createDeviceFeatureController(
            context = context,
            filesDir = filesDir,
            fileProviderAuthority = fileProviderAuthority,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            descriptorUuid = descriptorUuid,
            initialTransportProfile = initialTransportProfile,
            packetCaptureControllerFactory = packetCaptureControllerFactory,
            bleSessionControllerFactory = bleSessionControllerFactory,
            fileShareIntentFactory = fileShareIntentFactory,
            woonaDatabase = woonaDatabase,
            snapshotDirectory = snapshotDirectory,
            onRecordingChanged = onRecordingChanged,
            onCaptureLifecycleChanged = onCaptureLifecycleChanged,
            appTextResolver = appTextResolver,
        )
    }
}

/** Retains the capture owner when an Activity is recreated while recording. */
object DeviceFeatureControllerHolder {
    private var controller: DeviceFeatureController? = null
    private val stopExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "capture-stop") }

    @Synchronized
    fun hasActiveCapture(): Boolean = controller?.isCaptureActive() == true

    fun requestStop(candidate: DeviceFeatureController? = null) {
        val activeController = synchronized(this) { controller?.takeIf { candidate == null || it === candidate } }
        activeController?.requestDisconnect(stopExecutor)?.whenComplete { _, failure ->
            if (failure != null) CaptureServiceBridge.onStopFailed?.invoke()
        }
    }

    @Synchronized
    fun obtain(
        onRecordingChanged: () -> Unit,
        onRecordingFinalizing: ((Recording) -> Unit)? = null,
        onRecordingFinalized: ((Recording) -> Unit)? = null,
        onCaptureLifecycleChanged: ((Boolean) -> Unit)? = null,
        create: () -> DeviceFeatureController,
    ): DeviceFeatureController {
        val existing = controller
        val selected = if (existing != null && existing.shouldRetainConnection()) existing else {
            existing?.close()
            create().also { controller = it }
        }
        selected.setOnRecordingChanged(onRecordingChanged)
        onRecordingFinalizing?.let(selected::setOnRecordingFinalizing)
        onRecordingFinalized?.let(selected::setOnRecordingFinalized)
        onCaptureLifecycleChanged?.let(selected::setOnCaptureLifecycleChanged)
        return selected
    }

    @Synchronized
    fun releaseIfInactive(candidate: DeviceFeatureController) {
        if (controller !== candidate || candidate.shouldRetainConnection()) return
        candidate.close()
        controller = null
    }
}

private const val UI_PACKET_UPDATE_INTERVAL_MS = 100L
