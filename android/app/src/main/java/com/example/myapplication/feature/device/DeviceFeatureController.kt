package com.example.myapplication.feature.device

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.myapplication.R
import com.example.myapplication.ble.BleSessionState
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.ble.PolarConnectionState
import com.example.myapplication.ble.PolarSessionManager
import com.example.myapplication.ble.PolarStatus
import com.example.myapplication.data.ArtifactType
import com.example.myapplication.data.CaptureSyncMetadata
import com.example.myapplication.data.Recording
import com.example.myapplication.data.RecordingSource
import com.example.myapplication.data.RecordingStatus
import com.example.myapplication.data.SessionQuestionnaire
import com.example.myapplication.data.validate
import com.example.myapplication.data.SyncClockAnchor
import com.example.myapplication.data.VideoSyncMetadata
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.data.absoluteInstantForMonotonic
import com.example.myapplication.data.monotonicOffsetNs
import com.example.myapplication.data.toJson
import com.example.myapplication.localization.EnglishTextResolver
import com.example.myapplication.localization.TextResolver
import com.example.myapplication.protocol.V2Frame
import com.example.myapplication.protocol.WoonaV2StorageCodec
import com.example.myapplication.protocol.WoonaV2TimeSync
import com.example.myapplication.storage.V2StoredRecordStore
import com.example.myapplication.storage.V2StorageTransferCoordinator
import com.example.myapplication.storage.V2SdSessionMedia
import com.example.myapplication.storage.V2SdRecordingImporter
import com.example.myapplication.protocol.ConnectionQuality
import com.example.myapplication.protocol.ConnectionQualitySnapshot
import com.example.myapplication.protocol.ConnectionQualityTracker
import com.example.myapplication.protocol.PolarAccelerationFrame
import com.example.myapplication.protocol.PolarEcgFrame
import com.example.myapplication.protocol.PolarHeartRateFrame
import com.example.myapplication.storage.BleSessionCsvExporter
import com.example.myapplication.storage.FileShareIntentFactory
import com.example.myapplication.storage.SessionArchiveExporter
import com.example.myapplication.storage.SessionArchiveMetadata
import com.example.myapplication.storage.PolarCsvFileStore
import com.example.myapplication.storage.PolarPersistenceException
import com.example.myapplication.video.AndroidVideoRecorder
import com.example.myapplication.video.AndroidVideoStartInfo
import com.example.myapplication.video.AndroidVideoStopInfo
import com.example.myapplication.video.VideoStopPendingException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DeviceFeatureController(
    private val bleSessionController: BleSessionController,
    private val packetCaptureController: PacketCaptureController,
    private val fileShareIntentFactory: FileShareIntentFactory,
    private val appTextResolver: TextResolver = EnglishTextResolver,
    private val packetReplayController: PacketReplayController? = null,
    private val uiStateHolder: DeviceUiStateHolder = DeviceUiStateHolder(),
    private val sessionCsvExporter: BleSessionCsvExporter = BleSessionCsvExporter(),
    private val sessionArchiveExporter: SessionArchiveExporter = SessionArchiveExporter(),
    private val wallClockMillisProvider: () -> Long = System::currentTimeMillis,
    private val runOnUiThread: (() -> Unit) -> Unit = { action -> action() },
    private val woonaDatabase: WoonaDatabase? = null,
    private val snapshotDirectory: File? = null,
    private val v2StorageRootDir: File? = null,
    private val onRecordingChanged: () -> Unit = {},
    private val videoRecorder: AndroidVideoRecorder? = null,
    private val polarSessionManager: PolarSessionManager? = null,
    private val polarFileStore: PolarCsvFileStore? = null,
    private val monotonicNanosProvider: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val schedule: (Runnable, Long) -> Unit = { _, _ -> },
    private val cancel: (Runnable) -> Unit = {},
    private val onConnectionAlarm: (ConnectionQuality) -> Unit = {},
    private val onRecoveryRetryNeeded: () -> Unit = {},
    onCaptureLifecycleChanged: (Boolean) -> Unit = {},
) : AutoCloseable {
    // Storage commands, decoded records and fsync share the same serialized IO worker.
    private val v2StorageWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "v2-storage-io").apply { isDaemon = true }
    }
    private val v2StorageEpoch = AtomicLong(0L)
    @Volatile private var v2StorageAddress: String? = null
    private var v2StorageCoordinator: V2StorageTransferCoordinator? = null
    private var v2StorageLastDownloadSequence: Long? = null
    @Volatile private var v2StoragePendingCatalogDownload: Long? = null
    private var v2StorageStateValue by androidx.compose.runtime.mutableStateOf(V2StorageUiState())
    val v2StorageState: V2StorageUiState get() = v2StorageStateValue
    private var v2TimeSyncValue by androidx.compose.runtime.mutableStateOf(WoonaV2TimeSync.Diagnostics())
    val v2TimeSyncState: WoonaV2TimeSync.Diagnostics get() = v2TimeSyncValue
    private var v2SdCaptureValue by androidx.compose.runtime.mutableStateOf(V2SdCaptureUiState())
    val v2SdCaptureState: V2SdCaptureUiState get() = v2SdCaptureValue
    private var sdChosenInConnectionDialog by androidx.compose.runtime.mutableStateOf(false)
    val v2SdModeSelected: Boolean get() = sdChosenInConnectionDialog
    val v2SdVideoUiState: VideoCaptureState
        get() {
            val sd = v2SdCaptureValue
            if (!sdChosenInConnectionDialog || !sd.videoEnabled) return VideoCaptureState.IDLE
            if (sd.phase == "stopping" || sd.phase == "finalizing") return VideoCaptureState.STOPPING
            if (!sd.running) return VideoCaptureState.IDLE
            val status = sd.videoStatus.lowercase()
            return when {
                status.contains("failed") || status.contains("unavailable") || status.contains("error") ->
                    VideoCaptureState.DEGRADED
                status.contains("starting video") -> VideoCaptureState.STARTING
                else -> VideoCaptureState.RECORDING
            }
        }
    fun onV2SdModeSelected(selected: Boolean) { sdChosenInConnectionDialog = selected }
    private var v2SdProfileId: String? = null
    private var v2SdQuestionnaire: SessionQuestionnaire? = null
    fun onV2SdQuestionnaireSelected(profileId: String, questionnaire: SessionQuestionnaire) {
        require(profileId.isNotBlank())
        require(questionnaire.validate().isValid) { "Session questionnaire is incomplete" }
        if (!v2SdCaptureValue.running && !v2SdCaptureValue.busy) {
            v2SdProfileId = profileId
            v2SdQuestionnaire = questionnaire
            sdState(v2SdCaptureValue.copy(videoEnabled = questionnaire.videoRequested, error = null))
        }
    }
    private val sdTickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sdTickStartNs: Long? = null
    private val sdTick = object : Runnable {
        override fun run() {
            val start = sdTickStartNs ?: return
            if (!v2SdCaptureValue.running) return
            val elapsed = ((monotonicNanosProvider()-start).coerceAtLeast(0L)/1_000_000_000L)
            val requested = v2SdCaptureValue.durationSeconds.toLong()
            if (elapsed >= requested) {
                v2SdCaptureValue = v2SdCaptureValue.copy(elapsedSeconds = requested)
                // Device also has its own finite duration. This request obtains
                // the STOP acknowledgement and closes Android video/metadata.
                onV2SdStopRequested()
                // A full GATT queue at the exact deadline is transient. Retry
                // until the BLE layer accepts the idempotent STOP request.
                if (v2SdCaptureValue.running && !v2SdCaptureValue.busy) {
                    sdTickHandler.postDelayed(this, 250L)
                }
                return
            }
            v2SdCaptureValue = v2SdCaptureValue.copy(elapsedSeconds = elapsed)
            sdTickHandler.postDelayed(this, 250L)
        }
    }
    private fun stopSdTick() { sdTickHandler.removeCallbacks(sdTick); sdTickStartNs = null }
    private fun startSdTick() {
        stopSdTick()
        sdTickStartNs = monotonicNanosProvider()
        sdTickHandler.post(sdTick)
    }
    private var sdAutoSyncPending = false
    private var sdSyncAnchor: WoonaV2TimeSync.Sample? = null
    private var sdMedia: V2SdSessionMedia? = null
    private var sdSessionGeneration = 0L
    private var sdFinishing = false
    private fun sdState(state: V2SdCaptureUiState) { v2SdCaptureValue = state }

    fun onV2SdDurationChanged(seconds: Int) {
        if (!v2SdCaptureValue.busy && !v2SdCaptureValue.running)
            sdState(v2SdCaptureValue.copy(durationSeconds = seconds.coerceIn(60, 10_800)))
    }
    fun onV2SdVideoChanged(enabled: Boolean) {
        if (!v2SdCaptureValue.busy && !v2SdCaptureValue.running)
            sdState(v2SdCaptureValue.copy(videoEnabled = enabled))
    }

    fun onV2SdStartRequested() {
        val current = v2SdCaptureValue
        if (v2SdProfileId == null || v2SdQuestionnaire == null) {
            sdState(current.copy(phase = "failed", error = "Заполните анкету сессии перед SD-записью"))
            return
        }
        if (current.busy || current.running || v2StorageStateValue.busy ||
            (uiState.v2SelectedStreamMask and uiState.v2StreamMask) == 0L ||
            !canUseV2Storage() ||
            (uiState.v2Capabilities and (1L shl WoonaV2TimeSync.CAPABILITY_BIT)) == 0L) {
            sdState(current.copy(phase = "failed", error = "V2 SD requires idle BLE, SD and TIME_SYNC"))
            return
        }
        sdAutoSyncPending = true
        sdSyncAnchor = null
        sdState(current.copy(phase = "syncing", busy = true, elapsedSeconds = 0L, error = null, sessionSequence = null,
            videoStatus = "Synchronizing MCU and Android clocks"))
        if (!bleSessionController.beginV2TimeSync()) {
            sdAutoSyncPending = false
            sdState(current.copy(phase = "failed", error = "Unable to start TIME_SYNC"))
        }
    }

    fun onV2SdStopRequested() {
        if (!v2SdCaptureValue.running || v2SdCaptureValue.busy) return
        if (bleSessionController.stopV2SdCapture()) {
            sdState(v2SdCaptureValue.copy(phase = "stopping", busy = true))
        } else sdState(v2SdCaptureValue.copy(error = "Unable to send SD STOP"))
    }

    /** This is a test capture, separate from legacy recording history. */
    fun onV2SdCaptureStarted(id: Long, seq: Long, mask: Long, durationSeconds: Int) {
        if (!v2PreviewConnection || sdSyncAnchor == null || !sdAutoSyncPending) return
        sdAutoSyncPending = false
        val root = v2StorageRootDir ?: return
        val address = v2StorageAddress ?: return
        val sample = sdSyncAnchor ?: return
        val videoEnabled = v2SdCaptureValue.videoEnabled
        val media = try {
            val questionnaire = requireNotNull(v2SdQuestionnaire)
            require(videoEnabled == questionnaire.videoRequested) { "SD video choice differs from questionnaire" }
            V2SdSessionMedia(root, address, seq, id, mask, durationSeconds, sample,
                monotonicNanosProvider(), wallClockMillisProvider(), requireNotNull(v2SdProfileId),
                questionnaire).also { it.writeStarted() }
        } catch (error: Exception) {
            sdState(v2SdCaptureValue.copy(phase = "failed", busy = false, running = true,
                error = "SD started, but metadata persistence FAILED: ${error.message}. Stop manually"))
            return
        }
        val generation = ++sdSessionGeneration
        sdFinishing = false
        sdMedia = media
        startSdTick()
        sdState(v2SdCaptureValue.copy(phase = "recording", busy = false, running = true,
            sessionSequence = seq, error = null,
            videoStatus = if (videoEnabled) "Starting video…" else "SD recording (video off)"))
        if (videoEnabled) {
            val started = try {
                videoRecorder?.start(media.videoFile,
                    onStarted = { info -> runOnUiThread {
                        if (sdSessionGeneration == generation && sdMedia === media) {
                            try {
                                media.videoStarted(info)
                                sdState(v2SdCaptureValue.copy(videoStatus = "Video recording; timestamps linked"))
                            } catch (error: Exception) {
                                sdState(v2SdCaptureValue.copy(videoStatus = "Video metadata error: ${error.message}"))
                            }
                        }
                    } },
                    onError = { message, _ -> runOnUiThread {
                        if (sdSessionGeneration == generation && sdMedia === media) {
                            runCatching { media.videoError(message) }
                            sdState(v2SdCaptureValue.copy(videoStatus = "Camera failed: $message"))
                        }
                    } }) ?: false
            } catch (_: Exception) { false }
            if (!started) {
                runCatching { media.videoError("Camera not started") }
                sdState(v2SdCaptureValue.copy(videoStatus = "Camera unavailable — SD continues"))
            }
        }
    }

    fun onV2SdCaptureStopped(id: Long, seq: Long) {
        stopSdTick()
        if (sdFinishing) return
        val media = sdMedia
        if (media == null || media.sessionId != id || media.sequence != seq) {
            sdState(v2SdCaptureValue.copy(phase = "failed", busy = false, running = false,
                error = "STOP received, but media session identity mismatch"))
            return
        }
        sdFinishing = true
        sdState(v2SdCaptureValue.copy(phase = "finalizing", busy = true))
        finishV2SdMedia(media, "completed", null)
    }

    fun onV2SdCaptureFailure(message: String) {
        stopSdTick()
        if (sdFinishing) return
        sdAutoSyncPending = false
        val media = sdMedia
        if (media != null) {
            sdFinishing = true
            sdState(v2SdCaptureValue.copy(phase = "interrupted", busy = true, error = message))
            finishV2SdMedia(media, "interrupted", message)
        } else {
            sdState(v2SdCaptureValue.copy(phase = "failed", busy = false, running = false,
                error = message))
        }
    }

    private fun finishV2SdMedia(media: V2SdSessionMedia, status: String, failure: String?) {
        val generation = sdSessionGeneration
        if (v2StorageWorker.isShutdown) {
            sdState(v2SdCaptureValue.copy(phase = "interrupted", busy = false,
                error = "Capture storage worker closed before video finalization"))
            return
        }
        v2StorageWorker.execute {
            val result = if (media.videoRequested) runCatching { videoRecorder?.stop() }
                else Result.success(null)
            val issue = result.exceptionOrNull()?.message ?: if (media.videoRequested && result.getOrNull() == null)
                "Video did not produce a completed recording" else null
            runCatching {
                media.videoStopped(result.getOrNull())
                media.finish(status, failure ?: issue)
            }.onFailure { error ->
                runOnUiThread {
                    if (sdSessionGeneration == generation)
                        sdState(v2SdCaptureValue.copy(error = "SD/video metadata persistence: ${error.message}"))
                }
            }
            runOnUiThread {
                if (sdSessionGeneration == generation && sdMedia === media) {
                    sdMedia = null
                    sdFinishing = false
                    sdSyncAnchor = null
                    v2SdProfileId = null
                    v2SdQuestionnaire = null
                    sdState(v2SdCaptureValue.copy(phase = status, busy = false, running = false,
                        videoStatus = if (!media.videoRequested) "SD sync metadata saved (video disabled)"
                            else if (issue == null) "Video and sync metadata saved locally"
                            else "Video stop issue: $issue", error = failure ?: issue))
                }
            }
        }
    }

    fun onV2TimeSyncStatus(status: WoonaV2TimeSync.Diagnostics) {
        if (!v2PreviewConnection) return
        v2TimeSyncValue = status
        if (!sdAutoSyncPending || v2SdCaptureValue.phase != "syncing" || status.running) return
        if (status.error != null || status.collected != status.total || status.bestSample == null) {
            sdAutoSyncPending = false
            sdState(v2SdCaptureValue.copy(phase = "failed", busy = false,
                error = status.error ?: "Incomplete TIME_SYNC"))
            return
        }
        sdSyncAnchor = status.bestSample
        val mask = uiState.v2SelectedStreamMask and uiState.v2StreamMask
        if (mask == 0L || !bleSessionController.startV2SdCapture(mask,
                v2SdCaptureValue.durationSeconds)) {
            sdAutoSyncPending = false
            sdState(v2SdCaptureValue.copy(phase = "failed", busy = false,
                error = "Cannot send SD START — select streams and retry"))
            return
        }
        sdState(v2SdCaptureValue.copy(phase = "starting", busy = true,
            videoStatus = "TIME_SYNC done; waiting for SD START acknowledgement"))
    }

    fun onV2TimeSyncRequested() {
        if (!v2PreviewConnection || !uiState.v2Ready ||
            (uiState.v2Capabilities and (1L shl WoonaV2TimeSync.CAPABILITY_BIT)) == 0L ||
            !bleSessionController.beginV2TimeSync()) {
            v2TimeSyncValue = WoonaV2TimeSync.Diagnostics(error =
                "TIME_SYNC unavailable: stop LIVE/Storage, check V2 connection and firmware")
        }
    }

    private var lastV2StoragePublishMs = 0L
    private fun publishV2Storage(epoch: Long, state: V2StorageTransferCoordinator.Snapshot) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (state.downloading != null && state.busy &&
            nowMs - lastV2StoragePublishMs < 250L) return
        lastV2StoragePublishMs = nowMs
        val pendingCatalogDownload = v2StoragePendingCatalogDownload
        if (pendingCatalogDownload != null && !state.busy && state.downloading == null && !state.completed) {
            v2StoragePendingCatalogDownload = null
            if (state.sessions.any { it.sequence == pendingCatalogDownload }) {
                v2StorageLastDownloadSequence = pendingCatalogDownload
                if (!v2StorageWorker.isShutdown) v2StorageWorker.execute {
                    if (epoch != v2StorageEpoch.get()) return@execute
                    try {
                        ensureV2Storage(epoch).download(pendingCatalogDownload)
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (epoch == v2StorageEpoch.get()) {
                                v2StorageStateValue = v2StorageStateValue.copy(
                                    busy = false, downloadingSession = null,
                                    error = error.message ?: "Не удалось начать загрузку SD")
                            }
                        }
                    }
                }
            } else {
                v2StorageLastDownloadSequence = null
                runOnUiThread {
                    if (epoch == v2StorageEpoch.get()) {
                        v2StorageStateValue = v2StorageStateValue.copy(
                            busy = false, downloadingSession = null,
                            error = "Сессия #$pendingCatalogDownload не найдена после обновления каталога SD")
                    }
                }
            }
        }
        var importedRecordingId: String? = null
        var integrationError: String? = null
        if (state.completed) {
            val sequence = v2StorageLastDownloadSequence
            val root = v2StorageRootDir
            val identity = v2StorageAddress
            val database = woonaDatabase
            if (sequence != null && root != null && identity != null && database != null) {
                try {
                    importedRecordingId = V2SdRecordingImporter.importCompleted(root, identity, sequence, database)
                } catch (error: Exception) {
                    integrationError = "Данные скачаны, но добавление в общую историю не завершено: ${error.message}"
                } finally {
                    v2StorageLastDownloadSequence = null
                }
            }
        }
        val local = if (state.completed || (!state.busy && state.downloading == null)) {
            runCatching {
                val root = requireNotNull(v2StorageRootDir)
                val identity = requireNotNull(v2StorageAddress)
                V2StoredRecordStore(root, identity).localSessions()
            }.getOrNull()
        } else null
        runOnUiThread {
            if (epoch == v2StorageEpoch.get()) {
                v2StorageStateValue = v2StorageStateValue.copy(
                    localSessions = local ?: v2StorageStateValue.localSessions,
                    busy = state.busy, sessions = state.sessions,
                    downloadingSession = state.downloading,
                    savedRecords = state.savedRecords, totalRecords = state.totalRecords,
                    completed = state.completed, error = integrationError ?: state.error)
                if (importedRecordingId != null) recordingChangedListener()
            }
        }
    }

    /** Only call on v2StorageWorker, never the BLE callback thread. */
    private fun ensureV2Storage(epoch: Long): V2StorageTransferCoordinator {
        v2StorageCoordinator?.let { return it }
        val identity = requireNotNull(v2StorageAddress) { "BLE device address unavailable" }
        val root = requireNotNull(v2StorageRootDir) { "Persistent SD storage directory unavailable" }
        val store = V2StoredRecordStore(root, identity)
        return V2StorageTransferCoordinator(
            sendRequest = { type, payload ->
                if (epoch != v2StorageEpoch.get() ||
                    !bleSessionController.sendV2StorageCommand(type, payload))
                    error("BLE V2 Storage command rejected")
            },
            firstMissing = store::firstMissingRecord,
            persist = { frame ->
                val record = store.save(frame)
                runCatching {
                    V2SdSessionMedia.firstStoredBlockOffsetMs(root, identity,
                        record.sessionSequence, record.timestampUs)
                }.onSuccess { offset ->
                    if (offset != null) runOnUiThread {
                        if (epoch == v2StorageEpoch.get())
                            v2SdCaptureValue = v2SdCaptureValue.copy(videoStatus =
                                String.format(java.util.Locale.US,
                                    "First SD block relative to first video frame: %.1f ms", offset))
                    }
                }
            },
            onUpdate = { publishV2Storage(epoch, it) },
        ).also { v2StorageCoordinator = it }
    }

    private fun canUseV2Storage() = v2PreviewConnection && !v2SdCaptureValue.busy &&
        !v2SdCaptureValue.running && uiState.v2Ready &&
        uiState.v2DeviceSessionMode == 0 && !uiState.v2LiveRunning &&
        (uiState.v2Capabilities and ((1L shl 7) or (1L shl 8))) ==
            ((1L shl 7) or (1L shl 8))

    private fun storageAction(action: (V2StorageTransferCoordinator) -> Unit) {
        if (!canUseV2Storage() || v2StorageWorker.isShutdown) return
        val epoch = v2StorageEpoch.get()
        v2StorageWorker.execute {
            if (epoch != v2StorageEpoch.get()) return@execute
            try { action(ensureV2Storage(epoch)) }
            catch (error: Exception) {
                runOnUiThread {
                    if (epoch == v2StorageEpoch.get())
                        v2StorageStateValue = v2StorageStateValue.copy(busy = false,
                            downloadingSession = null, error = error.message ?: "V2 Storage error")
                }
            }
        }
    }

    fun onV2StorageRefreshRequested() = storageAction {
        it.refresh()
    }
    fun onV2LocalSessionRefreshRequested() {
        if (v2StorageWorker.isShutdown) return
        val identity = v2StorageAddress ?: return
        val root = v2StorageRootDir ?: return
        v2StorageWorker.execute {
            val local = runCatching { V2StoredRecordStore(root, identity).localSessions() }
                .getOrDefault(emptyList())
            runOnUiThread { v2StorageStateValue = v2StorageStateValue.copy(localSessions = local) }
        }
    }
    fun onV2StorageDownloadRequested(sequence: Long) {
        if (sequence <= 0L || v2StorageStateValue.busy) return
        v2StoragePendingCatalogDownload = sequence
        storageAction { it.refresh() }
    }
    fun onV2StorageStopRequested() = storageAction { it.stop() }

    fun onV2StorageFrame(frame: V2Frame) {
        val epoch = v2StorageEpoch.get()
        if (!v2StorageWorker.isShutdown) v2StorageWorker.execute {
            if (epoch == v2StorageEpoch.get()) v2StorageCoordinator?.onFrame(frame)
        }
    }

    fun onV2StorageFailure(reason: String) {
        val epoch = v2StorageEpoch.get()
        if (!v2StorageWorker.isShutdown) v2StorageWorker.execute {
            if (epoch == v2StorageEpoch.get())
                v2StorageCoordinator?.interrupted(reason)
        }
    }

    private fun resetV2Storage(reason: String?) {
        v2StoragePendingCatalogDownload = null
        v2StorageLastDownloadSequence = null
        v2StorageEpoch.incrementAndGet()
        if (!v2StorageWorker.isShutdown) v2StorageWorker.execute {
            v2StorageCoordinator = null
        }
        runOnUiThread {
            v2StorageStateValue = V2StorageUiState(
                localSessions = v2StorageStateValue.localSessions, error = reason)
        }
    }

    @Volatile
    private var recordingChangedListener: () -> Unit = onRecordingChanged
    @Volatile
    private var recordingFinalizedListener: (Recording) -> Unit = {}
    @Volatile
    private var recordingFinalizingListener: (Recording) -> Unit = {}
    @Volatile
    private var captureLifecycleListener: (Boolean) -> Unit = onCaptureLifecycleChanged
    private val exportLock = Any()
    private var pendingTransportDiagnostics = mutableListOf<String>()
    private var awaitingCaptureReady = false
    private var exportSnapshot: SessionExportSnapshot? = null
    private var exportSessionStartMillis: Long? = null
    private var exportEpoch = 0L
    @Volatile private var currentRecordingId: String? = null
    private var currentRecording: Recording? = null
    private var currentRecordingDirectory: File? = null
    private var captureSyncMetadata: CaptureSyncMetadata? = null
    private var synchronizedCaptureStarted = false
    // Remains set after a failed finalization until a new recording is admitted.
    private var captureStopRequested = false
    private var terminalIntent: RecordingStatus? = null
    private data class PendingCaptureFailure(val code: String, val message: String)
    private var pendingCaptureFailure: PendingCaptureFailure? = null
    private var pendingPacketFailure: String? = null
    private var pendingPolarFailure: String? = null
    private var pendingOverflowFailure: String? = null
    private var pendingPolarCallbacks = 0
    private val polarFailureReported = AtomicBoolean(false)
    private var firstSensorFragmentRecorded = false
    private val connectionQualityTracker = ConnectionQualityTracker()
    private var linkMonitorRunning = false
    private var weakSinceMillis: Long? = null
    private var lastAlarmQuality: ConnectionQuality? = null
    @Volatile private var manualDisconnectPending = false
    private val disconnectInProgress = AtomicBoolean(false)
    @Volatile private var v2PreviewConnection = false
    @Volatile private var v2PreviewForeground = true
    private val connectionUiGeneration = AtomicLong()
    private data class FinalizationRequest(
        val recordingId: String,
        var status: RecordingStatus,
        val outcome: CompletableFuture<Unit>,
        var running: Boolean = false,
        var statusSealed: Boolean = false,
    )
    private var finalizationRequest: FinalizationRequest? = null
    private var disconnectOutcome: CompletableFuture<Unit>? = null
    private var closeRequested = false
    private var resourcesClosed = false
    @Volatile private var teardownPending = false
    @Volatile private var captureRequiresRestart = false
    private data class PendingVideoStop(val result: AndroidVideoStopInfo?, val stoppedAt: Long)
    // Serializes stop callers without holding the controller monitor while the
    // camera handler is blocked or MediaRecorder is stopping.
    private val videoStopLock = Any()
    private var pendingVideoStop: PendingVideoStop? = null
    private var videoCallbackGeneration = 0L
    private val finalizationRetryRunnable = Runnable {
        val request = synchronized(this) { finalizationRequest }
        if (request != null) dispatchFinalization(request)
    }
    private val linkMonitorRunnable: Runnable = object : Runnable {
        override fun run() {
            if (linkMonitorRunning) {
                updateConnectionQuality(connectionQualityTracker.snapshot(monotonicMillis()))
                schedule(this, LINK_MONITOR_INTERVAL_MILLIS)
            }
        }
    }
    @Volatile
    private var recordingFinalized = true
    @Volatile
    private var recordingFinalizing = false

    val uiState: DeviceUiState
        get() = uiStateHolder.uiState

    fun isVideoRequested(): Boolean = currentRecording?.videoRequested == true
    fun isLiveCapture(): Boolean = currentRecording?.source == RecordingSource.LIVE && isCaptureActive()

    fun isCaptureActive(): Boolean = !recordingFinalized || recordingFinalizing || disconnectInProgress.get() || teardownPending
    fun isV2PreviewConnection(): Boolean = v2PreviewConnection
    fun isV2PreviewForeground(): Boolean = v2PreviewConnection && v2PreviewForeground
    fun shouldRetainConnection(): Boolean = isCaptureActive() || v2PreviewConnection
    fun connectionUiGeneration(): Long = connectionUiGeneration.get()
    fun captureIdentity(): String? = currentRecordingId

    fun setOnRecordingChanged(listener: () -> Unit) {
        recordingChangedListener = listener
    }

    fun setOnRecordingFinalizing(listener: (Recording) -> Unit) {
        recordingFinalizingListener = listener
    }

    fun setOnRecordingFinalized(listener: (Recording) -> Unit) {
        recordingFinalizedListener = listener
    }

    fun setOnCaptureLifecycleChanged(listener: (Boolean) -> Unit) {
        captureLifecycleListener = listener
    }

    fun onStartScanRequested(
        hasPermissions: () -> Boolean,
        requestPermissions: () -> Unit,
    ) {
        uiStateHolder.prepareForScan()
        if (hasPermissions()) {
            bleSessionController.startScanning()
        } else {
            requestPermissions()
        }
    }

    fun onPermissionsResult(allGranted: Boolean) {
        if (allGranted) {
            bleSessionController.startScanning()
        } else {
            uiStateHolder.showError(appTextResolver.getString(R.string.permissions_required))
        }
    }

    fun onConnectRequested(address: String) {
        v2PreviewConnection = false
        manualDisconnectPending = false
        packetReplayController?.stop()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = true
        val staleSnapshot = synchronized(exportLock) {
            invalidateExportSnapshotLocked()
        }
        deleteSnapshotFiles(staleSnapshot)
        packetCaptureController.stopCapture()
        bleSessionController.stopScanning()
        bleSessionController.connect(address) { connectionUiGeneration.incrementAndGet() }
    }

    fun onV2PreviewConnectRequested(address: String) {
        if (isCaptureActive() || uiState.isReplayRunning || uiState.isReplayPreparing) {
            uiStateHolder.showError(appTextResolver.getString(R.string.v2_finish_capture_first))
            return
        }
        manualDisconnectPending = false
        packetReplayController?.stop()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = false
        packetCaptureController.stopCapture()
        resetV2Storage(null)
        v2TimeSyncValue = WoonaV2TimeSync.Diagnostics()
        sdAutoSyncPending = false
        sdSyncAnchor = null
        sdMedia = null
        stopSdTick()
        v2SdProfileId = null
        v2SdQuestionnaire = null
        sdSessionGeneration++
        sdFinishing = false
        sdState(V2SdCaptureUiState())
        v2StorageAddress = address
        onV2LocalSessionRefreshRequested()
        v2PreviewConnection = true
        v2PreviewForeground = true
        uiStateHolder.onV2PreviewConnecting()
        bleSessionController.stopScanning()
        bleSessionController.connectForV2Preview(address) { connectionUiGeneration.incrementAndGet() }
    }

    /** V2 is opt-in because HELLO permanently ends Legacy streaming for this BLE connection. */
    fun onV2DiscoveryRequested() {
        if (!v2PreviewConnection || isCaptureActive()) {
            uiStateHolder.showError(appTextResolver.getString(R.string.v2_finish_capture_first))
            return
        }
        if (bleSessionController.beginV2Discovery()) {
            uiStateHolder.onV2DiscoveryRequested()
        } else {
            uiStateHolder.showError("V2 discovery is unavailable. Check BLE connection and reconnect if necessary.")
        }
    }

    fun onV2StreamToggled(streamId: Int) = uiStateHolder.toggleV2Stream(streamId)

    /** Starts a diagnostic preview; no recording row, file writer or camera is started. */
    fun onV2LiveStartRequested() {
        if (!v2PreviewConnection || sdChosenInConnectionDialog || isCaptureActive() || v2SdCaptureValue.busy || v2SdCaptureValue.running || !uiState.v2Ready ||
            uiState.v2PreviewStage !in setOf(V2PreviewStage.READY, V2PreviewStage.STOPPED)) return
        val selectedMask = uiState.v2SelectedStreamMask and uiState.v2StreamMask
        uiStateHolder.onV2StartRequested()
        if (selectedMask == 0L || !bleSessionController.startV2Live(selectedMask)) {
            uiStateHolder.onV2DiscoveryFailed("Cannot start V2 LIVE: check selected streams and BLE state")
        }
    }

    fun onV2LiveStopRequested() {
        if (!v2PreviewConnection || uiState.v2PreviewStage != V2PreviewStage.RUNNING) return
        uiStateHolder.onV2StopRequested()
        if (!bleSessionController.stopV2Live()) {
            uiStateHolder.onV2DiscoveryFailed("Cannot stop V2 preview: session is not running")
        }
    }

    /** Reserve the idle controller before creating a row, so a concurrent stop
     * cannot leave a new PREPARING recording behind after rejecting the start. */
    @Synchronized
    fun createRecordingSession(create: () -> Recording): Recording {
        check(!closeRequested && recordingFinalized && !recordingFinalizing && !disconnectInProgress.get() && !teardownPending) {
            "Wait for the previous capture to finish before starting another recording"
        }
        check(!captureRequiresRestart) { "A capture storage failure requires closing and reopening Woona before starting another recording." }
        val recording = create()
        beginRecordingSession(recording)
        return recording
    }

    fun beginRecordingSession(recording: Recording) {
        // A stale start request must never stop whichever recording is active.
        // Callers must explicitly finish the prior capture before admitting this one.
        val staleSnapshot = synchronized(this) {
            check(!closeRequested) { "Capture controller is closing" }
            check(recordingFinalized && !recordingFinalizing && !disconnectInProgress.get() && !teardownPending) { "Previous recording is still finalizing" }
            check(!captureRequiresRestart) { "A capture storage failure requires closing and reopening Woona before recording again" }
            check(pendingPolarCallbacks == 0) { "Polar callbacks are still draining" }
            cancel(finalizationRetryRunnable)
            pendingVideoStop = null
            videoCallbackGeneration++
            currentRecordingId = recording.id
            currentRecording = recording
            recordingFinalized = false
            synchronizedCaptureStarted = false
            captureStopRequested = false
            terminalIntent = null
            pendingCaptureFailure = null
            pendingPacketFailure = null
            pendingPolarFailure = null
            pendingOverflowFailure = null
            polarFailureReported.set(false)
            firstSensorFragmentRecorded = false
            packetCaptureController.stopCapture()
            currentRecordingDirectory =
                requireNotNull(woonaDatabase).recordingDirectory(recording.relativeDirectory)
            packetCaptureController.useSessionDirectory(requireNotNull(currentRecordingDirectory))
            polarFileStore?.useSessionDirectory(requireNotNull(currentRecordingDirectory))
            captureSyncMetadata = CaptureSyncMetadata(
                recordingId = recording.id,
                profileId = recording.profileId,
                source = recording.source.value,
                timezone = recording.timezone,
                selectedSessionStartUtc = recording.startedAtUtc,
            )
            persistSyncMetadata()
            if (recording.source == RecordingSource.LIVE) {
                uiStateHolder.prepareVideo()
                captureLifecycleListener(true)
            }
            synchronized(exportLock) {
                val snapshot = invalidateExportSnapshotLocked()
                exportSessionStartMillis = Instant.parse(recording.startedAtUtc).toEpochMilli()
                snapshot
            }
        }
        deleteSnapshotFiles(staleSnapshot)
        notifyRecordingChanged()
    }

    /** The same process-owned outcome is returned to every concurrent stop caller. */
    fun requestDisconnect(executor: Executor): CompletableFuture<Unit> {
        var pendingRequest: FinalizationRequest? = null
        var created = false
        val outcome = synchronized(this) {
            disconnectOutcome?.takeUnless { it.isDone }?.also { pendingRequest = finalizationRequest } ?: CompletableFuture<Unit>().also {
                created = true
                disconnectOutcome = it
                disconnectInProgress.set(true)
                teardownPending = true
                if (!recordingFinalized) captureStopRequested = true
            }
        }
        if (!created) {
            pendingRequest?.let {
                cancel(finalizationRetryRunnable)
                dispatchFinalization(it)
            }
            return outcome
        }
        try {
            executor.execute { beginDisconnect(executor, outcome) }
        } catch (error: RuntimeException) {
            failDisconnect(outcome, error)
        }
        return outcome
    }

    fun onDisconnectRequested(): CompletableFuture<Unit> = requestDisconnect(Executor { it.run() })

    private fun beginDisconnect(executor: Executor, outcome: CompletableFuture<Unit>) {
        try {
            connectionUiGeneration.incrementAndGet()
            manualDisconnectPending = true
            if (v2PreviewConnection) bleSessionController.stopV2Live()
            v2PreviewConnection = false
            stopLinkMonitor()
            packetReplayController?.stop()
            pendingTransportDiagnostics.clear()
            awaitingCaptureReady = false
            val status = if (uiState.v2DiscoveryRequested) RecordingStatus.INTERRUPTED else RecordingStatus.COMPLETED
            finalizeRecording(status).whenComplete { _, failure ->
                if (failure != null) {
                    failDisconnect(outcome, failure)
                } else {
                    try { executor.execute { finishDisconnect(outcome) } }
                    catch (error: RuntimeException) { failDisconnect(outcome, error) }
                }
            }
        } catch (error: Exception) {
            failDisconnect(outcome, error)
        }
    }

    private fun finishDisconnect(outcome: CompletableFuture<Unit>) {
        try {
            // Never tear down a packet controller while a different caller is
            // still draining it or after a failed finalization.
            packetCaptureController.stopCapture()
            val staleSnapshot = synchronized(exportLock) { invalidateExportSnapshotLocked() }
            deleteSnapshotFiles(staleSnapshot)
            bleSessionController.disconnect()
            polarSessionManager?.disconnect()
            // Keep the start gate and foreground service until the old UI
            // transition has executed, otherwise it can reset a new session.
            runOnUiThread {
                try {
                    // Sensor-only capture also uses RECORDING as its session
                    // state, although no MediaRecorder exists to finish it.
                    if (uiState.videoState in setOf(VideoCaptureState.STARTING, VideoCaptureState.RECORDING, VideoCaptureState.STOPPING)) {
                        uiStateHolder.videoFinished()
                    }
                    uiStateHolder.stopCaptureSession()
                    FINALIZATION_EXECUTOR.execute {
                        try {
                            captureLifecycleListener(false)
                            val finishedRecording = synchronized(this) {
                                // Retire this outcome before opening the start gate. A
                                // stop for the next recording must never reuse it.
                                if (disconnectOutcome === outcome) disconnectOutcome = null
                                teardownPending = false
                                disconnectInProgress.set(false)
                                currentRecording?.takeIf { recordingFinalized && it.source == RecordingSource.LIVE }
                            }
                            finishedRecording?.let { recording ->
                                try { recordingFinalizedListener(recording) }
                                catch (error: Exception) { Log.e("WOONA_CAPTURE", "Post-capture scheduling failed; recording is preserved", error) }
                            }
                            outcome.complete(Unit)
                            closeResourcesWhenInactive()
                        } catch (error: Exception) { failDisconnect(outcome, error) }
                    }
                } catch (error: Exception) { failDisconnect(outcome, error) }
            }
        } catch (error: Exception) {
            failDisconnect(outcome, error)
        }
    }

    private fun failDisconnect(outcome: CompletableFuture<Unit>, error: Throwable) {
        Log.e("WOONA_CAPTURE", "Capture stop or teardown failed; retry is required", error)
        showErrorOnMainThread("Failed to stop capture; retry is required", error)
        synchronized(this) {
            if (disconnectOutcome === outcome) {
                disconnectOutcome = null
                disconnectInProgress.set(false)
            }
        }
        outcome.completeExceptionally(error)
    }

    fun onPolarScanRequested() {
        uiStateHolder.prepareForPolarScan()
        polarSessionManager?.startScanning()
    }

    fun onPolarConnectRequested(address: String, name: String) {
        polarSessionManager?.connect(address, name)
    }

    fun onPolarDisconnectRequested() {
        polarSessionManager?.disconnect()
    }

    @Synchronized
    fun onPolarStatusChanged(status: PolarStatus) {
        uiStateHolder.updatePolarStatus(status)
        // The stop gate and Polar start must be checked under the same lock:
        // READY can be posted by setRecordingActive(false) during finalization.
        val recordingCanAcceptPolar = synchronizedCaptureStarted && !recordingFinalized &&
            !captureStopRequested && !recordingFinalizing && !disconnectInProgress.get() && !teardownPending && !closeRequested
        if (status.state == PolarConnectionState.READY && recordingCanAcceptPolar) {
            startPolarRecording()
        }
        if (status.state in setOf(PolarConnectionState.LOST, PolarConnectionState.FAILED) && recordingCanAcceptPolar) {
            onConnectionAlarm(ConnectionQuality.LOST)
        }
    }

    fun onPolarHeartRate(frame: PolarHeartRateFrame, nowMillis: Long, expectedRecordingId: String? = captureIdentity()) =
        appendPolar(expectedRecordingId) { it.append(frame, nowMillis) }

    fun onPolarEcg(frame: PolarEcgFrame, nowMillis: Long, expectedRecordingId: String? = captureIdentity()) =
        appendPolar(expectedRecordingId) { it.append(frame, nowMillis) }

    fun onPolarAcceleration(frame: PolarAccelerationFrame, nowMillis: Long, expectedRecordingId: String? = captureIdentity()) =
        appendPolar(expectedRecordingId) { it.append(frame, nowMillis) }

    private fun appendPolar(expectedRecordingId: String?, action: (PolarCsvFileStore) -> Unit) {
        // Admission is serialized with stop; the filesystem operation itself
        // never holds the controller lock or blocks a stop request on the UI.
        val admitted = synchronized(this) {
            if (expectedRecordingId != null && expectedRecordingId == currentRecordingId &&
                synchronizedCaptureStarted && !recordingFinalized && !captureStopRequested
            ) {
                val store = polarFileStore
                val directory = currentRecordingDirectory
                val recordingId = currentRecordingId
                if (store != null && directory != null && recordingId != null) {
                    pendingPolarCallbacks++
                    Triple(store, directory, recordingId)
                } else null
            } else null
        } ?: return
        val (store, directory, recordingId) = admitted
        try {
            // An already-admitted old callback cannot append to the next
            // recording if it was delayed until after the store changed session.
            synchronized(store) { if (store.isSessionDirectory(directory)) action(store) }
        } catch (error: Exception) { reportPolarFailure(error, recordingId) }
        finally {
            val waiting = synchronized(this) {
                pendingPolarCallbacks--
                finalizationRequest?.takeIf { pendingPolarCallbacks == 0 && !it.running }
            }
            if (waiting != null) {
                cancel(finalizationRetryRunnable)
                dispatchFinalization(waiting)
            }
        }
    }

    private fun reportPolarFailure(error: Exception, recordingId: String?) {
        val message = "Polar storage failed. Original files are retained, but buffered Polar samples may be missing; close and reopen Woona before recording again."
        val admitted = synchronized(this) {
            if (recordingId == null || currentRecordingId != recordingId || recordingFinalized ||
                finalizationRequest?.statusSealed == true || !polarFailureReported.compareAndSet(false, true)
            ) false else {
                // Record the terminal intent before posting any UI work. Stop
                // may commit while the main thread is blocked.
                captureStopRequested = true
                captureRequiresRestart = true
                terminalIntent = RecordingStatus.FAILED
                pendingPolarFailure = message
                finalizationRequest?.let { it.status = RecordingStatus.FAILED }
                true
            }
        }
        if (!admitted) return
        showErrorOnMainThread(message, error)
        finalizeRecording(RecordingStatus.FAILED)
    }

    fun onSensorSelected(sensorType: Int) {
        uiStateHolder.onSensorTypeSelected(sensorType)
        syncProcessorSelection()
    }

    fun onTransportProfileSelected(profile: BleTransportProfile) {
        uiStateHolder.onTransportProfileSelected(profile)
        bleSessionController.updateTransportProfile(profile)

        if (uiState.showCaptureUi || awaitingCaptureReady) {
            packetCaptureController.recordDiagnosticEvent(
                type = PacketDiagnosticType.INFO,
                message = appTextResolver.getString(
                    R.string.transport_profile_selected_next_connection,
                    appTextResolver.getString(profile.titleRes),
                ),
            )
        }
    }

    fun onChannelSelected(channel: Int) {
        uiStateHolder.onChannelSelected(channel)
        syncProcessorSelection()
    }

    fun onTabSelected(tab: DeviceCaptureTab) {
        uiStateHolder.onTabSelected(tab)
    }

    fun onChartWindowSelected(windowPreset: ChartWindowPreset) {
        uiStateHolder.onChartWindowSelected(windowPreset)
    }

    fun onFollowLiveChanged(enabled: Boolean) {
        uiStateHolder.onFollowLiveChanged(enabled)
    }

    fun onChartPanLeftRequested() {
        uiStateHolder.panChartLeft()
    }

    fun onChartPanRightRequested() {
        uiStateHolder.panChartRight()
    }

    fun onChartJumpToLiveRequested() {
        uiStateHolder.jumpToLive()
    }

    fun onChartZoomInRequested() {
        uiStateHolder.zoomInChart()
    }

    fun onChartZoomOutRequested() {
        uiStateHolder.zoomOutChart()
    }

    fun onChartZoomResetRequested() {
        uiStateHolder.resetChartZoom()
    }

    fun onChartPanned(deltaFraction: Float) {
        uiStateHolder.panChartByFraction(deltaFraction)
    }

    fun onChartZoomChanged(
        scaleFactor: Float,
        anchorFractionY: Float = 0.5f,
    ) {
        uiStateHolder.zoomChart(scaleFactor, anchorFractionY)
    }

    fun onResume() { v2PreviewForeground = true }

    fun onV2LiveStarted() {
        if (!v2PreviewConnection) return
        uiStateHolder.onV2LiveStarted()
        if (!v2PreviewForeground) onV2LiveStopRequested()
    }

    fun onPause() {
        if (v2PreviewConnection) {
            v2PreviewForeground = false
            // Even an unknown/failed handshake may already have switched firmware
            // to V2 while STORAGE is active. Never disconnect implicitly. Stop
            // only the LIVE session whose START_ACK belongs to our controller.
            if (uiState.v2LiveRunning) onV2LiveStopRequested()
        }
        // Ordinary capture is owned by its foreground service and explicit Stop.
    }

    fun onBleStateChanged(state: BleSessionState) {
        if (state == BleSessionState.DISCONNECTED || state == BleSessionState.FAILED) {
            resetV2Storage("BLE disconnected — resume the transfer after reconnecting")
            v2TimeSyncValue = WoonaV2TimeSync.Diagnostics(error = "BLE disconnected")
            if (v2SdCaptureValue.busy || v2SdCaptureValue.running)
                onV2SdCaptureFailure("BLE disconnected during SD capture; sensor state unknown")
        }
        if (v2PreviewConnection) {
            uiStateHolder.onSessionStateChanged(state)
            if (state == BleSessionState.FAILED) {
                uiStateHolder.onV2DiscoveryFailed(appTextResolver.getString(R.string.v2_connection_failed))
            }
            if (state == BleSessionState.DISCONNECTED) v2PreviewConnection = false
            return
        }
        val unexpectedDisconnect =
            state in setOf(BleSessionState.FAILED, BleSessionState.DISCONNECTED) &&
                synchronizedCaptureStarted &&
                !manualDisconnectPending
        val disconnectedQuality = if (unexpectedDisconnect) {
            connectionQualityTracker.disconnect(monotonicMillis())
        } else {
            null
        }
        when (state) {
            BleSessionState.FAILED -> finalizeRecording(RecordingStatus.FAILED)
            BleSessionState.DISCONNECTED -> if (!manualDisconnectPending) finalizeRecording(RecordingStatus.INTERRUPTED)
            else -> Unit
        }
        uiStateHolder.onSessionStateChanged(state)
        if (disconnectedQuality != null) {
            stopLinkMonitor()
            updateConnectionQuality(disconnectedQuality)
        }
    }

    fun onPacketUpdate(update: PacketProcessingUpdate) {
        uiStateHolder.applyPacketUpdate(update)
        if (!synchronizedCaptureStarted || recordingFinalized) return
        updateConnectionQuality(
            connectionQualityTracker.record(
                nowMillis = monotonicMillis(),
                packetsReceived = update.packetsReceived,
                packetsLost = update.packetsLost,
                packetsRejected = update.packetsRejected,
            ),
        )
    }

    fun onLinkMonitorTick() {
        if (linkMonitorRunning) {
            updateConnectionQuality(connectionQualityTracker.snapshot(monotonicMillis()))
        }
    }

    fun createPacketShareIntent(context: Context): Intent? = createShareIntent(
        context = context,
        file = packetCaptureController.currentPacketFile(),
    )

    fun createRawShareIntent(context: Context): Intent? = createShareIntent(
        context = context,
        file = packetCaptureController.currentRawFile(),
    )

    fun createLogShareIntent(context: Context): Intent? = createShareIntent(
        context = context,
        file = packetCaptureController.currentLogFile(),
    )

    fun createCsvShareIntent(context: Context): Intent? {
        return try {
            showExportPhaseOnMainThread(ExportPhase.PREPARING_SNAPSHOTS)
            val snapshot = ensureExportSnapshot() ?: return null
            val csvSnapshot = ensureCsvSnapshot(snapshot) ?: return null
            showExportPhaseOnMainThread(ExportPhase.OPENING_SHARE_SHEET)
            fileShareIntentFactory.createChooserIntent(context, csvSnapshot)
        } catch (exception: Exception) {
            showErrorOnMainThread(appTextResolver.getString(R.string.share_file_failed), exception)
            Log.e("BLE_SHARE", "Failed to share file", exception)
            null
        }
    }

    fun createAllFilesShareIntent(context: Context): Intent? {
        return try {
            val targetDirectory = snapshotDirectory
                ?: packetCaptureController.currentPacketFile()?.parentFile
                ?: return null
            val archive = createSessionArchive(targetDirectory) ?: return null
            showExportPhaseOnMainThread(ExportPhase.OPENING_SHARE_SHEET)
            fileShareIntentFactory.createChooserIntent(context, archive)
        } catch (exception: Exception) {
            showErrorOnMainThread(appTextResolver.getString(R.string.share_file_failed), exception)
            Log.e("BLE_SHARE", "Failed to share file", exception)
            null
        }
    }

    fun createRecordingShareIntent(context: Context, recordingId: String): Intent? {
        val files = woonaDatabase?.artifactFiles(recordingId).orEmpty()
        val summary = woonaDatabase?.recordingSummary(recordingId) ?: return null
        if (files.isEmpty()) return null
        return runCatching {
            val syncJson = files.firstOrNull { it.name == "sync.json" }?.readText(Charsets.UTF_8)
            val archive = sessionArchiveExporter.export(
                files = files,
                targetDirectory = snapshotDirectory ?: File(context.cacheDir, "woona_recording_exports"),
                sessionStartMillis = Instant.parse(summary.recording.startedAtUtc).toEpochMilli(),
                createdAtMillis = wallClockMillisProvider(),
                metadata = archiveMetadata(summary, syncJson),
            )
            fileShareIntentFactory.createChooserIntent(context, archive)
        }.getOrElse { exception ->
            showErrorOnMainThread(appTextResolver.getString(R.string.share_file_failed), exception)
            Log.e("BLE_SHARE", "Failed to share recording archive", exception)
            null
        }
    }

    fun createSessionArchive(targetDirectory: File): File? {
        if (!canCreateSessionArchiveNow()) {
            showErrorOnMainThread(appTextResolver.getString(R.string.export_finish_capture_first))
            return null
        }
        return try {
            // Export never owns capture/video shutdown. Finalization must have
            // completed before this point, including when invoked without UI.
            showExportPhaseOnMainThread(ExportPhase.PREPARING_SNAPSHOTS)
            val snapshot = ensureExportSnapshot() ?: return null
            val files = buildAvailableExportFiles(snapshot)
            if (files.isEmpty()) return null

            showExportPhaseOnMainThread(ExportPhase.PACKAGING_ARCHIVE)
            sessionArchiveExporter.export(
                files = files,
                targetDirectory = targetDirectory,
                sessionStartMillis = snapshot.sessionStartMillis,
                createdAtMillis = wallClockMillisProvider(),
                metadata = currentRecordingId
                    ?.let { recordingId -> woonaDatabase?.recordingSummary(recordingId) }
                    ?.let { summary ->
                        archiveMetadata(
                            summary,
                            currentSessionFile("sync.json")?.takeIf(File::exists)?.readText(Charsets.UTF_8),
                        )
                    },
            )
        } catch (exception: Exception) {
            showErrorOnMainThread(appTextResolver.getString(R.string.export_prepare_failed), exception)
            Log.e("BLE_SHARE", "Failed to prepare session archive", exception)
            null
        }
    }

    fun canSharePacketFile(): Boolean = !v2PreviewConnection && packetCaptureController.currentPacketFile()?.exists() == true

    fun canShareRawFile(): Boolean = !v2PreviewConnection && packetCaptureController.currentRawFile()?.exists() == true

    fun canShareLogFile(): Boolean = !v2PreviewConnection && packetCaptureController.currentLogFile()?.exists() == true

    fun canShareCsvFile(): Boolean = !v2PreviewConnection && packetCaptureController.currentPacketFile()?.exists() == true

    private fun canCreateSessionArchiveNow(): Boolean = !v2PreviewConnection && !isCaptureActive() && videoRecorder?.isActive() != true &&
        uiState.videoState !in setOf(VideoCaptureState.STARTING, VideoCaptureState.RECORDING, VideoCaptureState.STOPPING)

    fun canShareAllFiles(): Boolean = canCreateSessionArchiveNow() && (
        canSharePacketFile() || canShareCsvFile() || canShareRawFile() || canShareLogFile() ||
            currentSessionFile("video.mp4")?.exists() == true ||
            currentSessionFile("sync.json")?.exists() == true ||
            PolarCsvFileStore.FILE_NAMES.any { currentSessionFile(it)?.exists() == true })

    fun clearExportProgress() {
        runOnUiThread {
            uiStateHolder.clearExportProgress()
        }
    }

    override fun close() {
        synchronized(this) { closeRequested = true }
        connectionUiGeneration.incrementAndGet()
        manualDisconnectPending = true
        stopLinkMonitor()
        packetReplayController?.close()
        finalizeRecording(RecordingStatus.INTERRUPTED).whenComplete { _, failure ->
            if (failure == null) closeResourcesWhenInactive()
        }
    }

    private fun closeResourcesWhenInactive() {
        synchronized(this) {
            if (!closeRequested || resourcesClosed || isCaptureActive()) return
            resourcesClosed = true
        }
        val staleSnapshot = synchronized(exportLock) {
            val snapshot = invalidateExportSnapshotLocked()
            exportSessionStartMillis = null
            snapshot
        }
        deleteSnapshotFiles(staleSnapshot)
        bleSessionController.close()
        v2StorageEpoch.incrementAndGet()
        stopSdTick()
        v2StorageWorker.shutdownNow()
        polarSessionManager?.close()
        try { polarFileStore?.close() }
        catch (error: PolarPersistenceException) { Log.e("WOONA_CAPTURE", "Polar file closure previously failed", error) }
        packetCaptureController.close()
        videoRecorder?.close()
    }

    fun onCaptureReady() {
        if (v2PreviewConnection) {
            // Compatibility for injected transports: the real GATT implementation
            // starts negotiation itself after the notification descriptor ACK.
            if (!uiState.v2Ready) onV2DiscoveryRequested()
            return
        }
        if (woonaDatabase != null && recordingFinalized) {
            awaitingCaptureReady = false
            return
        }
        if (!recordingFinalized) {
            if (synchronizedCaptureStarted) {
                packetCaptureController.recordDiagnosticEvent(
                    type = PacketDiagnosticType.INFO,
                    message = "BLE notifications re-enabled after transport recovery",
                )
            } else if (currentRecording?.source == RecordingSource.LIVE) {
                uiStateHolder.videoReady()
            } else {
                resetCaptureSession()
                currentRecordingId?.let { woonaDatabase?.markRecording(it) }
            }
        } else {
            resetCaptureSession()
        }
        notifyRecordingChanged()
        flushPendingTransportDiagnostics()
        awaitingCaptureReady = false
    }

    @Synchronized
    fun startSynchronizedSession() {
        if (uiState.v2DiscoveryRequested) {
            // A queued permission/capture callback can still arrive after V2 opt-in.
            // Do not start Legacy on a V2 connection and do not report a false error.
            return
        }
        val recording = currentRecording ?: return
        if (
            recordingFinalized ||
            recordingFinalizing || captureStopRequested ||
            disconnectInProgress.get() || teardownPending || closeRequested ||
            synchronizedCaptureStarted ||
            uiState.videoState !in setOf(VideoCaptureState.READY, VideoCaptureState.FAILED)
        ) return
        synchronizedCaptureStarted = true
        bleSessionController.setCaptureActive(true)
        startLinkMonitor()
        captureSyncMetadata = captureSyncMetadata?.copy(
            sensor = captureClockAnchor("capture_start"),
        )
        resetCaptureSession()
        currentRecordingId?.let { woonaDatabase?.markRecording(it) }
        persistSyncMetadata()
        if (recording.videoRequested) {
            startVideo()
        } else {
            uiStateHolder.videoStarted(0.0)
        }
        startPolarRecording()
        notifyRecordingChanged()
    }

    @Synchronized
    fun onSensorFragmentReceived(
        receivedAtMillis: Long,
        receivedAtMonotonicNs: Long,
        deviceTimerMillis: Long,
    ) {
        if (!synchronizedCaptureStarted || recordingFinalized) return
        captureSyncMetadata = captureSyncMetadata?.copy(
            lastSensorPacketMonotonicNs = receivedAtMonotonicNs,
        )
        if (firstSensorFragmentRecorded) return
        firstSensorFragmentRecorded = true
        val current = captureSyncMetadata ?: return
        val sensor = SyncClockAnchor(
            event = "first_accepted_sensor_packet",
            absoluteUtc = Instant.ofEpochMilli(receivedAtMillis).toString(),
            wallClockEpochMillis = receivedAtMillis,
            monotonicTimeNs = receivedAtMonotonicNs,
            monotonicClock = "android.elapsedRealtimeNanos",
            samplingUncertaintyNs = 0L,
        )
        val video = current.video?.let { existing ->
            existing.firstFrameMonotonicNs?.let { firstFrame ->
                existing.copy(
                    firstFrameAtUtc = absoluteInstantForMonotonic(sensor, firstFrame).toString(),
                    firstFrameEpochMillis = receivedAtMillis +
                        monotonicOffsetNs(sensor, firstFrame) / 1_000_000.0,
                    offsetFromSensorNs = monotonicOffsetNs(sensor, firstFrame),
                )
            } ?: existing
        }
        captureSyncMetadata = current.copy(
            sensor = sensor,
            firstSensorDeviceTimerMillis = deviceTimerMillis,
            lastSensorPacketMonotonicNs = receivedAtMonotonicNs,
            video = video,
        )
        persistSyncMetadata()
    }

    fun setVideoPreviewSurface(surface: Surface?) {
        videoRecorder?.setPreviewSurface(surface)
    }

    fun onTransportDiagnostic(message: String) {
        if (v2PreviewConnection) return // Never append preview diagnostics to an old recording.
        if (awaitingCaptureReady) {
            pendingTransportDiagnostics += message
            return
        }

        packetCaptureController.recordDiagnosticEvent(
            type = PacketDiagnosticType.INFO,
            message = message,
        )
    }

    fun onSessionError(message: String, throwable: Throwable? = null) {
        val details = if (throwable == null) message else "$message: $throwable"
        if (recordingFinalized && !v2PreviewConnection && currentRecordingId != null) {
            showErrorOnMainThread(details)
            return
        }
        if (v2PreviewConnection) {
            awaitingCaptureReady = false
            uiStateHolder.onV2DiscoveryFailed(details)
            return
        }
        // Remember the failure before touching SQLite or diagnostics. A stop
        // already in progress must see it even if persistence is locked.
        val failureRecordingId = stageCaptureFailure(captureErrorCode(message), details)
        try {
            val disconnectedQuality = if (synchronizedCaptureStarted && !manualDisconnectPending) {
                connectionQualityTracker.disconnect(monotonicMillis())
            } else {
                null
            }
            try { flushPendingTransportDiagnostics() }
            catch (error: Exception) { Log.e("WOONA_CAPTURE", "Could not flush transport diagnostics", error) }
            awaitingCaptureReady = false
            uiStateHolder.showError(details)
            if (disconnectedQuality != null) updateConnectionQuality(disconnectedQuality)
        } finally {
            failureRecordingId?.let { finalizeRecording(RecordingStatus.FAILED, it) }
        }
    }

    private fun stageCaptureFailure(code: String, details: String): String? = synchronized(this) {
        val recordingId = currentRecordingId
        if (recordingId == null || recordingFinalized || v2PreviewConnection) return@synchronized null
        // Keep the first generic cause; a storm of repeated callbacks must not
        // starve the sealing loop. Specific packet/Polar errors take precedence.
        if (!captureRequiresRestart && pendingCaptureFailure == null) {
            pendingCaptureFailure = PendingCaptureFailure(code, details)
        }
        captureStopRequested = true
        terminalIntent = RecordingStatus.FAILED
        finalizationRequest?.let { if (!it.statusSealed) it.status = RecordingStatus.FAILED }
        recordingId
    }

    fun startReplay(fileBytes: ByteArray) {
        startReplay { fileBytes.inputStream() }
    }

    fun startReplay(source: ReplayInputSource) {
        synchronized(this) {
            if (packetReplayController == null) {
                uiStateHolder.showError(appTextResolver.getString(R.string.replay_unavailable))
                return
            }
            if (woonaDatabase != null && (currentRecording?.source != RecordingSource.REPLAY ||
                    recordingFinalized || captureStopRequested || recordingFinalizing || disconnectInProgress.get() || teardownPending || closeRequested)) {
                return
            }
            packetReplayController.startReplay(source)
        }
    }

    fun onReplayPreparing() {
        bleSessionController.stopScanning()
        uiStateHolder.startReplayPreparation()
    }

    fun cancelReplayPreparation() {
        packetReplayController?.stop()
    }

    fun onReplayStarted() {
        if (woonaDatabase != null && recordingFinalized) return
        bleSessionController.close()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = false
        packetCaptureController.resetSession()
        val staleSnapshot = synchronized(exportLock) {
            val snapshot = invalidateExportSnapshotLocked()
            exportSessionStartMillis = wallClockMillisProvider()
            snapshot
        }
        deleteSnapshotFiles(staleSnapshot)
        uiStateHolder.startReplaySession()
        if (!recordingFinalized) {
            currentRecordingId?.let { woonaDatabase?.markRecording(it) }
            captureSyncMetadata = captureSyncMetadata?.copy(
                sensor = captureClockAnchor("replay_started"),
            )
            persistSyncMetadata()
        }
        notifyRecordingChanged()
    }

    fun onReplayCompleted() {
        finalizeRecording(RecordingStatus.COMPLETED)
        uiStateHolder.finishReplaySession()
    }

    fun onReplayStopped() {
        if (uiStateHolder.uiState.isReplayPreparing) {
            finalizeRecording(RecordingStatus.INTERRUPTED)
            uiStateHolder.cancelReplayPreparation()
        } else {
            finalizeRecording(RecordingStatus.COMPLETED)
            uiStateHolder.stopCaptureSession()
        }
    }

    fun onReplayError(message: String, throwable: Throwable? = null) {
        showErrorOnMainThread(message, throwable)
        finalizeRecording(RecordingStatus.FAILED)
    }

    private fun resetCaptureSession() {
        packetCaptureController.resetSession()
        val staleSnapshot = synchronized(exportLock) {
            val snapshot = invalidateExportSnapshotLocked()
            exportSessionStartMillis = wallClockMillisProvider()
            snapshot
        }
        deleteSnapshotFiles(staleSnapshot)
        uiStateHolder.resetCaptureSession()
    }

    private fun flushPendingTransportDiagnostics() {
        pendingTransportDiagnostics.forEach { message ->
            packetCaptureController.recordDiagnosticEvent(
                type = PacketDiagnosticType.INFO,
                message = message,
            )
        }
        pendingTransportDiagnostics.clear()
    }

    private fun createShareIntent(
        context: Context,
        file: File?,
    ): Intent? {
        if (file == null || !file.exists()) return null

        return try {
            showExportPhaseOnMainThread(ExportPhase.PREPARING_SNAPSHOTS)
            val snapshot = ensureExportSnapshot() ?: return null
            val snapshotFile = when (file.absolutePath) {
                snapshot.packetSourcePath -> snapshot.packetSnapshot
                snapshot.rawSourcePath -> snapshot.rawSnapshot
                snapshot.logSourcePath -> snapshot.logSnapshot
                else -> null
            } ?: return null

            showExportPhaseOnMainThread(ExportPhase.OPENING_SHARE_SHEET)
            fileShareIntentFactory.createChooserIntent(context, snapshotFile)
        } catch (exception: Exception) {
            showErrorOnMainThread(appTextResolver.getString(R.string.share_file_failed), exception)
            Log.e("BLE_SHARE", "Failed to share file", exception)
            null
        }
    }

    fun showErrorOnMainThread(message: String, throwable: Throwable? = null) {
        runOnUiThread {
            uiStateHolder.showError(if (throwable == null) message else "$message: $throwable")
        }
    }

    private fun showExportPhaseOnMainThread(phase: ExportPhase) {
        runOnUiThread {
            uiStateHolder.showExportProgress(phase)
        }
    }

    private fun ensureExportSnapshot(): SessionExportSnapshot? {
        if (v2PreviewConnection) return null
        val buildPlan = synchronized(exportLock) {
            val currentPacketFile = packetCaptureController.currentPacketFile()?.takeIf(File::exists)
            val currentTimelineFile = packetCaptureController.currentPacketTimelineFile()?.takeIf(File::exists)
            val currentRawFile = packetCaptureController.currentRawFile()?.takeIf(File::exists)
            val currentLogFile = packetCaptureController.currentLogFile()?.takeIf(File::exists)
            val sessionStartMillis = exportSessionStartMillis
            val existingSnapshot = exportSnapshot

            existingSnapshot?.takeIf {
                it.matches(currentPacketFile, currentTimelineFile, currentRawFile, currentLogFile, sessionStartMillis)
            }?.let { return it }

            if (
                currentPacketFile == null &&
                currentRawFile == null &&
                currentLogFile == null &&
                currentSessionFile("video.mp4")?.exists() != true &&
                currentSessionFile("sync.json")?.exists() != true &&
                PolarCsvFileStore.FILE_NAMES.none { currentSessionFile(it)?.exists() == true }
            ) {
                return null
            }

            val snapshotToDelete = exportSnapshot
            exportSnapshot = null
            ExportSnapshotBuildPlan(
                epoch = exportEpoch,
                sessionStartMillis = sessionStartMillis,
                packetFile = currentPacketFile,
                timelineFile = currentTimelineFile,
                rawFile = currentRawFile,
                logFile = currentLogFile,
                staleSnapshot = snapshotToDelete,
            )
        }

        deleteSnapshotFiles(buildPlan.staleSnapshot)
        packetCaptureController.flush()
        val snapshot = SessionExportSnapshot(
            packetSourcePath = buildPlan.packetFile?.absolutePath,
            timelineSourcePath = buildPlan.timelineFile?.absolutePath,
            rawSourcePath = buildPlan.rawFile?.absolutePath,
            logSourcePath = buildPlan.logFile?.absolutePath,
            sessionStartMillis = buildPlan.sessionStartMillis,
            packetSnapshot = buildPlan.packetFile?.let { createSnapshotCopy(it, "packet") },
            timelineSnapshot = buildPlan.timelineFile?.let { createSnapshotCopy(it, "timeline") },
            rawSnapshot = buildPlan.rawFile?.let { createSnapshotCopy(it, "raw") },
            logSnapshot = buildPlan.logFile?.let { createSnapshotCopy(it, "log") },
            csvSnapshot = null,
        )

        return synchronized(exportLock) {
            if (exportEpoch != buildPlan.epoch) {
                deleteSnapshotFiles(snapshot)
                null
            } else {
                exportSnapshot = snapshot
                snapshot
            }
        }
    }

    private fun buildAvailableExportFiles(snapshot: SessionExportSnapshot): List<File> {
        try { polarFileStore?.flush() }
        catch (error: PolarPersistenceException) {
            // A failed, closed store has no buffered data left to flush. Keep
            // its partial originals exportable with the FAILED session status.
            if (!recordingFinalized) throw error
            Log.w("WOONA_CAPTURE", "Exporting original Polar files after a recorded storage failure", error)
        }
        val csvSnapshot = ensureCsvSnapshot(snapshot)
        return listOfNotNull(
            snapshot.packetSnapshot,
            snapshot.timelineSnapshot,
            csvSnapshot,
            snapshot.rawSnapshot,
            snapshot.logSnapshot,
            currentSessionFile("video.mp4")?.takeIf(File::exists),
            currentSessionFile("sync.json")?.takeIf(File::exists),
            *PolarCsvFileStore.FILE_NAMES.mapNotNull { currentSessionFile(it)?.takeIf(File::exists) }.toTypedArray(),
        )
    }

    private fun ensureCsvSnapshot(snapshot: SessionExportSnapshot): File? {
        val buildEpoch = synchronized(exportLock) {
            val currentSnapshot = exportSnapshot
            if (currentSnapshot == null || !currentSnapshot.hasSameBase(snapshot)) {
                return null
            }

            currentSnapshot.csvSnapshot?.let { return it }
            exportEpoch
        }

        val packetSnapshot = snapshot.packetSnapshot ?: return null
        showExportPhaseOnMainThread(ExportPhase.GENERATING_CSV)
        val generatedCsv = createCsvSnapshot(
            source = packetSnapshot,
            timeline = snapshot.timelineSnapshot,
            sessionStartMillis = snapshot.sessionStartMillis ?: wallClockMillisProvider(),
        )

        return synchronized(exportLock) {
            val currentSnapshot = exportSnapshot
            if (exportEpoch != buildEpoch || currentSnapshot == null || !currentSnapshot.hasSameBase(snapshot)) {
                deleteSnapshotFiles(snapshot.copy(csvSnapshot = generatedCsv))
                currentSnapshot?.takeIf { it.hasSameBase(snapshot) }?.csvSnapshot
            } else {
                val updatedSnapshot = currentSnapshot.copy(csvSnapshot = generatedCsv)
                exportSnapshot = updatedSnapshot
                generatedCsv
            }
        }
    }

    private fun invalidateExportSnapshotLocked(): SessionExportSnapshot? {
        val snapshot = exportSnapshot
        exportSnapshot = null
        exportEpoch++
        return snapshot
    }

    private fun createSnapshotCopy(
        source: File,
        suffix: String,
    ): File {
        val targetDirectory = snapshotDirectory ?: source.parentFile
        targetDirectory.mkdirs()
        val target = File(
            targetDirectory,
            "${source.nameWithoutExtension}_snapshot_$suffix.${source.extension.ifBlank { "bin" }}",
        )
        Files.copy(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        return target
    }

    private fun createCsvSnapshot(
        source: File,
        timeline: File?,
        sessionStartMillis: Long,
    ): File {
        val canonicalTarget = currentSessionFile("channel.csv") ?: File(source.parentFile, "channel.csv")
        sessionCsvExporter.export(
            packetFile = source,
            sessionStartMillis = sessionStartMillis,
            targetFile = canonicalTarget,
            packetTimelineFile = timeline,
        )
        currentRecordingId?.let { woonaDatabase?.registerCsv(it, canonicalTarget) }
        notifyRecordingChanged()
        val targetDirectory = snapshotDirectory ?: source.parentFile
        targetDirectory.mkdirs()
        val sessionBaseName = source.nameWithoutExtension.removeSuffix("_snapshot_packet")
        val snapshot = File(targetDirectory, "${sessionBaseName}_snapshot_csv.csv")
        Files.copy(
            canonicalTarget.toPath(),
            snapshot.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        return snapshot
    }

    /** All callers share one outcome; no caller waits for packet or SQLite IO on the UI thread. */
    private fun finalizeRecording(status: RecordingStatus, expectedRecordingId: String? = null): CompletableFuture<Unit> {
        val request = synchronized(this) {
            val id = currentRecordingId ?: return CompletableFuture.completedFuture(Unit)
            if (recordingFinalized || (expectedRecordingId != null && id != expectedRecordingId)) return CompletableFuture.completedFuture(Unit)
            captureStopRequested = true
            val previous = terminalIntent
            if (previous == null || terminalPriority(status) > terminalPriority(previous)) terminalIntent = status
            val intended = requireNotNull(terminalIntent)
            finalizationRequest?.let { existing ->
                if (!existing.statusSealed && terminalPriority(intended) > terminalPriority(existing.status)) existing.status = intended
                return existing.outcome
            }
            FinalizationRequest(id, intended, CompletableFuture()).also {
                finalizationRequest = it
                recordingFinalizing = true
            }
        }
        dispatchFinalization(request)
        return request.outcome
    }

    private fun dispatchFinalization(request: FinalizationRequest) {
        synchronized(this) {
            if (finalizationRequest !== request || request.running) return
            request.running = true
        }
        try {
            FINALIZATION_EXECUTOR.execute { runFinalizationAttempt(request) }
        } catch (error: RuntimeException) {
            completeFinalization(request, error)
        }
    }

    private fun runFinalizationAttempt(request: FinalizationRequest) {
        try {
            stopLinkMonitor()
            // stop() is nonblocking; in-flight replay workers and lifecycle
            // callbacks must exit before this recording can commit or be replaced.
            if (packetReplayController?.hasPendingWork() == true) {
                synchronized(this) { request.running = false }
                schedule(finalizationRetryRunnable, FINALIZATION_RETRY_DELAY_MILLIS)
                return
            }
            stopVideo()
            val polarPending = synchronized(this) {
                if (pendingPolarCallbacks > 0) { request.running = false; true } else false
            }
            if (polarPending) {
                schedule(finalizationRetryRunnable, FINALIZATION_RETRY_DELAY_MILLIS)
                return
            }
            try {
                polarFileStore?.close()
            } catch (error: PolarPersistenceException) {
                val message = "Polar storage failed. Original files are retained, but buffered Polar samples may be missing; close and reopen Woona before recording again."
                synchronized(this) {
                    request.status = RecordingStatus.FAILED
                    terminalIntent = RecordingStatus.FAILED
                    pendingPolarFailure = message
                    captureRequiresRestart = true
                }
                showErrorOnMainThread(message, error)
            }
            polarSessionManager?.setRecordingActive(false, request.recordingId)
            bleSessionController.setCaptureActive(false)
            val drained = try {
                packetCaptureController.finishCapture(CAPTURE_FINISH_TIMEOUT_MILLIS)
            } catch (failure: Exception) {
                when (packetCaptureController.recoverPendingFragments(requireNotNull(currentRecordingDirectory))) {
                    is PacketRecoveryResult.Recovered -> {
                        val message = "Packet storage failed. Available pending fragments were saved separately; originals may be incomplete or overlap. Close and reopen Woona before recording again."
                        synchronized(this) {
                            request.status = RecordingStatus.FAILED
                            terminalIntent = RecordingStatus.FAILED
                            pendingPacketFailure = message
                            captureRequiresRestart = true
                        }
                        showErrorOnMainThread(message)
                        true
                    }
                    PacketRecoveryResult.Busy -> false
                    PacketRecoveryResult.Failed -> {
                        showErrorOnMainThread("Recovery could not be saved. Keep Woona open, check storage and retry.", failure)
                        synchronized(this) { request.running = false }
                        try { onRecoveryRetryNeeded() } catch (error: Exception) { Log.e("WOONA_CAPTURE", "Could not report recovery failure", error) }
                        schedule(finalizationRetryRunnable, RECOVERY_RETRY_DELAY_MILLIS)
                        return
                    }
                    else -> throw failure
                }
            }
            if (!drained) {
                Log.w("WOONA_CAPTURE", "Capture drain is still pending; retrying")
                synchronized(this) { request.running = false }
                schedule(finalizationRetryRunnable, FINALIZATION_RETRY_DELAY_MILLIS)
                return
            }
            // Admission is closed by finishCapture. Inspect its durable-session
            // counter here, not only a UI callback that may still be queued.
            val dropped = packetCaptureController.droppedFragments()
            if (dropped > 0) synchronized(this) {
                request.status = RecordingStatus.FAILED
                terminalIntent = RecordingStatus.FAILED
                pendingOverflowFailure = "Capture queue overflow: $dropped fragment(s) were not recorded."
            }
            persistSyncMetadata(requirePersistence = true)
            val recording = checkNotNull(currentRecording?.takeIf { it.id == request.recordingId })
            recordingFinalizingListener(recording)
            val status = sealFinalizationStatus(request)
            woonaDatabase?.finishRecording(request.recordingId, status)
            val disconnectAfterFinalization = synchronized(this) {
                recordingFinalized = true
                synchronizedCaptureStarted = false
                if (recording.source == RecordingSource.LIVE) teardownPending = true
                recording.source == RecordingSource.LIVE && !disconnectInProgress.get()
            }
            if (recording.source != RecordingSource.LIVE) {
                try { recordingFinalizedListener(recording) }
                catch (error: Exception) { Log.e("WOONA_CAPTURE", "Post-capture scheduling failed; recording is preserved", error) }
            }
            // The terminal transaction has already committed. A UI refresh
            // listener must not turn that success into a false stop failure.
            try { notifyRecordingChanged() }
            catch (error: Exception) { Log.e("WOONA_CAPTURE", "Post-commit recording notification failed", error) }
            completeFinalization(request, null)
            if (disconnectAfterFinalization) requestDisconnect(Executor { it.run() })
        } catch (error: VideoStopPendingException) {
            Log.w("WOONA_CAPTURE", "Camera stop is still pending; retrying")
            synchronized(this) { request.running = false; request.statusSealed = false }
            try { schedule(finalizationRetryRunnable, FINALIZATION_RETRY_DELAY_MILLIS) }
            catch (scheduleError: Exception) { completeFinalization(request, scheduleError) }
        } catch (error: android.database.sqlite.SQLiteDatabaseLockedException) {
            Log.w("WOONA_CAPTURE", "Finalization database is locked; retrying")
            synchronized(this) { request.running = false; request.statusSealed = false }
            try { schedule(finalizationRetryRunnable, FINALIZATION_RETRY_DELAY_MILLIS) }
            catch (scheduleError: Exception) { completeFinalization(request, scheduleError) }
        } catch (error: Exception) {
            Log.e("WOONA_CAPTURE", "Capture finalization failed; recording remains open", error)
            showErrorOnMainThread("Failed to finalize recording; recording remains open", error)
            completeFinalization(request, error)
        }
    }

    // The database has one error-code field. Packet storage takes precedence
    // over Polar, then overflow, then generic failures; retain all known details.
    // Must be called under the controller monitor.
    private fun pendingFailureForFinalization(): PendingCaptureFailure? {
        val packet = pendingPacketFailure
        val polar = pendingPolarFailure
        val overflow = pendingOverflowFailure
        return when {
            packet != null -> PendingCaptureFailure("packet_persistence_failed", listOfNotNull(packet, polar?.let { "Polar also failed: $it" }, overflow).joinToString(" "))
            polar != null -> PendingCaptureFailure("polar_persistence_failed", listOfNotNull(polar, overflow).joinToString(" "))
            overflow != null -> PendingCaptureFailure("queue_overflow", overflow)
            else -> pendingCaptureFailure
        }
    }

    private fun sealFinalizationStatus(request: FinalizationRequest): RecordingStatus {
        while (true) {
            val failure = synchronized(this) { pendingFailureForFinalization() }
            failure?.let { woonaDatabase?.markCaptureError(request.recordingId, it.code, it.message) }
            val status = synchronized(this) {
                val currentFailure = pendingFailureForFinalization()
                if (currentFailure != failure) null else {
                    // An event before this boundary may promote the result.
                    // If an earlier sealed commit failed, reapply its newer intent.
                    terminalIntent?.let { intended ->
                        if (terminalPriority(intended) > terminalPriority(request.status)) request.status = intended
                    }
                    request.statusSealed = true
                    request.status
                }
            }
            if (status != null) return status
        }
    }

    private fun terminalPriority(status: RecordingStatus): Int = when (status) {
        RecordingStatus.FAILED -> 3
        RecordingStatus.INTERRUPTED -> 2
        RecordingStatus.COMPLETED -> 1
        else -> 0
    }

    private fun completeFinalization(request: FinalizationRequest, error: Throwable?) {
        synchronized(this) {
            if (finalizationRequest !== request) return
            finalizationRequest = null
            request.running = false
            recordingFinalizing = false
        }
        if (error == null) request.outcome.complete(Unit) else request.outcome.completeExceptionally(error)
    }

    @Synchronized
    fun startVideo() {
        val recorder = videoRecorder
        val recording = currentRecording
        val metadata = captureSyncMetadata
        if (
            recorder == null ||
            recording == null ||
            recording.source != RecordingSource.LIVE ||
            recordingFinalized ||
            recordingFinalizing || captureStopRequested || disconnectInProgress.get() || teardownPending || closeRequested ||
            metadata?.sensor == null ||
            uiState.videoState !in setOf(VideoCaptureState.READY, VideoCaptureState.FAILED)
        ) {
            return
        }

        val requestedAt = captureClockAnchor("video_requested")
        captureSyncMetadata = metadata.copy(
            video = VideoSyncMetadata(
                requestedAtUtc = requestedAt.absoluteUtc,
                requestedMonotonicNs = requestedAt.monotonicTimeNs,
            ),
        )
        persistSyncMetadata()
        uiStateHolder.videoStarting()
        val videoGeneration = ++videoCallbackGeneration
        val recordingId = recording.id
        val started = recorder.start(
            file = File(requireNotNull(currentRecordingDirectory), "video.mp4"),
            onStarted = { info ->
                runOnUiThread {
                    synchronized(this) {
                        if (videoCallbackGeneration == videoGeneration && currentRecordingId == recordingId && !recordingFinalized) {
                            handleVideoStarted(info)
                        }
                    }
                }
            },
            onError = { message, throwable ->
                Log.e("WOONA_VIDEO", message, throwable)
                runOnUiThread {
                    synchronized(this) {
                        if (videoCallbackGeneration == videoGeneration && currentRecordingId == recordingId && !recordingFinalized) {
                            try { handleVideoFailure("$message${throwable?.let { ": $it" }.orEmpty()}") }
                            catch (error: Exception) { showErrorOnMainThread("Could not persist camera failure; stop will retry", error) }
                        }
                    }
                }
            },
        )
        if (!started) handleVideoFailure()
    }

    private fun stopVideo() {
        synchronized(videoStopLock) {
            val recorder = videoRecorder ?: return
            val stopped = synchronized(this) { pendingVideoStop } ?: run {
                if (!recorder.isActive() && !recorder.hasPendingStop()) {
                    recorder.currentFailure()?.let { (message, error) ->
                        handleVideoFailure("$message${error?.let { ": $it" }.orEmpty()}", requirePersistence = true)
                    }
                    return
                }
                runOnUiThread { uiStateHolder.videoStopping() }
                synchronized(this) { videoCallbackGeneration++ }
                // This can wait for the camera handler. Other stop/error callers
                // must still be able to acquire the controller monitor.
                val result = recorder.stop()
                PendingVideoStop(result, result?.stoppedMonotonicNs ?: monotonicNanosProvider()).also {
                    synchronized(this) { pendingVideoStop = it }
                }
            }
            val result = stopped.result
            synchronized(this) {
                result?.startInfo?.let(::applyVideoStartMetadata)
                val metadata = captureSyncMetadata
                val video = metadata?.video
                if (metadata != null && video != null) {
                    captureSyncMetadata = metadata.copy(
                        video = video.copy(
                            stoppedAtUtc = instantForMonotonic(stopped.stoppedAt).toString(),
                            stoppedMonotonicNs = stopped.stoppedAt,
                            durationNs = result?.durationNs,
                            firstVideoSamplePtsUs = result?.firstVideoSamplePtsUs,
                        ),
                    )
                    persistSyncMetadata(requirePersistence = true)
                }
                // Remember the stop outcome across DB contention, including null.
                // A retry must not interpret an inactive recorder as success.
                if (result == null) handleVideoFailure(requirePersistence = true)
                currentSessionFile("video.mp4")
                    ?.takeIf { it.exists() && it.length() > 0L }
                    ?.let { file ->
                        currentRecordingId?.let { recordingId ->
                            woonaDatabase?.registerArtifact(recordingId, ArtifactType.VIDEO, file)
                        }
                    }
                pendingVideoStop = null
            }
            if (result != null) runOnUiThread { uiStateHolder.videoFinished() }
            notifyRecordingChanged()
        }
    }

    @Synchronized
    fun onCaptureServiceFailure(code: String, message: String, error: RuntimeException,
                                expectedRecordingId: String? = currentRecordingId) {
        if (expectedRecordingId != currentRecordingId || v2PreviewConnection || !isLiveCapture()) return
        val details = "$code: $message: $error"
        if (code == "camera_foreground_unavailable") {
            handleVideoFailure(details)
        } else {
            stageCaptureFailure(code, details)?.let { finalizeRecording(RecordingStatus.FAILED, it) }
            requestDisconnect(Executor { it.run() })
        }
        showErrorOnMainThread(details)
    }

    fun onVideoPermissionDenied() {
        uiStateHolder.videoFailed(appTextResolver.getString(R.string.video_camera_permission_required))
    }

    @Synchronized
    private fun handleVideoStarted(info: AndroidVideoStartInfo) {
        if (recordingFinalized) return
        val offsetNs = applyVideoStartMetadata(info) ?: return
        persistSyncMetadata()
        uiStateHolder.videoStarted(offsetNs / 1_000_000.0)
        notifyRecordingChanged()
    }

    private fun applyVideoStartMetadata(info: AndroidVideoStartInfo): Long? {
        val metadata = captureSyncMetadata ?: return null
        val sensor = metadata.sensor ?: return null
        val video = metadata.video ?: return null
        val offsetNs = monotonicOffsetNs(sensor, info.firstFrameMonotonicNs)
        captureSyncMetadata = metadata.copy(
            video = video.copy(
                mediaRecorderStartedMonotonicNs = info.mediaRecorderStartedMonotonicNs,
                firstFrameAtUtc = instantForMonotonic(info.firstFrameMonotonicNs).toString(),
                firstFrameEpochMillis = sensor.wallClockEpochMillis + offsetNs / 1_000_000.0,
                firstFrameMonotonicNs = info.firstFrameMonotonicNs,
                firstFrameCameraTimestampNs = info.firstFrameCameraTimestampNs,
                firstFrameCallbackMonotonicNs = info.firstFrameCallbackMonotonicNs,
                offsetFromSensorNs = offsetNs,
                cameraTimestampSource = info.cameraTimestampSource,
                synchronizationQuality = info.synchronizationQuality,
                cameraId = info.cameraId,
                lensFacing = info.lensFacing,
                width = info.width,
                height = info.height,
                frameRate = info.frameRate,
                rotationDegrees = info.rotationDegrees,
            ),
        )
        return offsetNs
    }

    @Synchronized
    private fun handleVideoFailure(message: String = appTextResolver.getString(R.string.video_recording_failed), requirePersistence: Boolean = false) {
        if (recordingFinalized) return
        if (synchronizedCaptureStarted) {
            currentRecordingId?.let { woonaDatabase?.markCaptureError(it, "camera_failed", message) }
            runOnUiThread { uiStateHolder.videoDegraded(message) }
        } else {
            runOnUiThread { uiStateHolder.videoFailed(message) }
        }
        persistSyncMetadata(requirePersistence)
        notifyRecordingChanged()
    }

    private fun captureClockAnchor(event: String): SyncClockAnchor {
        val before = monotonicNanosProvider()
        val wallClock = wallClockMillisProvider()
        val after = monotonicNanosProvider()
        return SyncClockAnchor(
            event = event,
            absoluteUtc = Instant.ofEpochMilli(wallClock).toString(),
            wallClockEpochMillis = wallClock,
            monotonicTimeNs = before + (after - before) / 2,
            monotonicClock = "android.elapsedRealtimeNanos",
            samplingUncertaintyNs = (after - before).coerceAtLeast(0L) / 2,
        )
    }

    private fun instantForMonotonic(monotonicNs: Long): Instant {
        val sensor = requireNotNull(captureSyncMetadata?.sensor)
        return absoluteInstantForMonotonic(sensor, monotonicNs)
    }

    private fun persistSyncMetadata(requirePersistence: Boolean = false) {
        val recordingId = currentRecordingId ?: return
        val metadata = captureSyncMetadata ?: return
        try {
            woonaDatabase?.writeSyncMetadata(recordingId, metadata)
        } catch (exception: Exception) {
            Log.e("WOONA_DATABASE", "Failed to write synchronization metadata", exception)
            showErrorOnMainThread("Failed to write synchronization metadata", exception)
            if (requirePersistence) throw exception
        }
    }

    private fun currentSessionFile(name: String): File? {
        return currentRecordingDirectory?.let { File(it, name) }
    }

    private fun archiveMetadata(
        summary: com.example.myapplication.data.RecordingSummary,
        synchronizationJson: String?,
    ) = SessionArchiveMetadata(
        profileId = summary.recording.profileId,
        profileName = summary.profileName,
        recordingId = summary.recording.id,
        source = summary.recording.source.value,
        status = summary.recording.status.value,
        timezone = summary.recording.timezone,
        profileQuestionnaireJson = summary.profileQuestionnaire.toJson(),
        questionnaireJson = summary.recording.questionnaire?.toJson(),
        synchronizationJson = synchronizationJson,
        sessionLabel = summary.recording.sessionLabel,
    )

    private fun syncProcessorSelection() {
        packetCaptureController.updateSelection(
            sensorType = uiStateHolder.uiState.selectedSensorType,
            channel = uiStateHolder.uiState.selectedChannel,
        )
    }

    private fun notifyRecordingChanged() {
        recordingChangedListener()
    }

    private fun captureErrorCode(message: String): String {
        val normalized = message.lowercase()
        return when {
            "notification silence" in normalized || "recovery exhausted" in normalized -> "notification_timeout"
            ("queue" in normalized && "overflow" in normalized) ||
                ("очеред" in normalized && "переполн" in normalized) -> "queue_overflow"
            "persist" in normalized || "flush" in normalized || "storage" in normalized ||
                "сохран" in normalized || "буфер" in normalized -> "storage_error"
            "gatt" in normalized || "ble" in normalized -> "gatt_error"
            else -> "capture_error"
        }
    }

    private fun startLinkMonitor() {
        weakSinceMillis = null
        lastAlarmQuality = null
        linkMonitorRunning = true
        updateConnectionQuality(connectionQualityTracker.connect(monotonicMillis()))
        cancel(linkMonitorRunnable)
        schedule(linkMonitorRunnable, LINK_MONITOR_INTERVAL_MILLIS)
    }

    private fun startPolarRecording() {
        if (uiState.polarStatus.state != PolarConnectionState.READY) return
        try {
            if (polarFileStore?.start() == true && polarSessionManager?.setRecordingActive(true, currentRecordingId) == false) {
                showErrorOnMainThread(appTextResolver.getString(R.string.polar_reconnect_before_recording))
            }
        } catch (error: Exception) {
            reportPolarFailure(error, currentRecordingId)
        }
    }

    private fun stopLinkMonitor() {
        linkMonitorRunning = false
        cancel(linkMonitorRunnable)
    }

    private fun updateConnectionQuality(snapshot: ConnectionQualitySnapshot) {
        uiStateHolder.updateConnectionQuality(snapshot)
        val now = monotonicMillis()
        when (snapshot.quality) {
            ConnectionQuality.WEAK -> {
                val weakSince = weakSinceMillis ?: now.also { weakSinceMillis = it }
                if (now - weakSince >= WEAK_ALARM_DELAY_MILLIS && lastAlarmQuality != ConnectionQuality.WEAK) {
                    lastAlarmQuality = ConnectionQuality.WEAK
                    onConnectionAlarm(ConnectionQuality.WEAK)
                }
            }

            ConnectionQuality.LOST -> {
                weakSinceMillis = null
                if (lastAlarmQuality != ConnectionQuality.LOST) {
                    lastAlarmQuality = ConnectionQuality.LOST
                    onConnectionAlarm(ConnectionQuality.LOST)
                }
            }

            ConnectionQuality.GOOD -> {
                weakSinceMillis = null
                if (lastAlarmQuality != null) onConnectionAlarm(ConnectionQuality.GOOD)
                lastAlarmQuality = null
            }

            else -> weakSinceMillis = null
        }
    }

    private fun monotonicMillis(): Long = monotonicNanosProvider() / 1_000_000L

    companion object {
        const val PERMISSION_REQUIRED_MESSAGE = "Permissions are required!"
        private val FINALIZATION_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "capture-finalization").apply { isDaemon = true }
        }
        private const val CAPTURE_FINISH_TIMEOUT_MILLIS = 2_000L
        private const val FINALIZATION_RETRY_DELAY_MILLIS = 1_000L
        private const val RECOVERY_RETRY_DELAY_MILLIS = 30_000L
        private const val LINK_MONITOR_INTERVAL_MILLIS = 1_000L
        private const val WEAK_ALARM_DELAY_MILLIS = 5_000L
    }
}

private data class SessionExportSnapshot(
    val packetSourcePath: String?,
    val timelineSourcePath: String?,
    val rawSourcePath: String?,
    val logSourcePath: String?,
    val sessionStartMillis: Long?,
    val packetSnapshot: File?,
    val timelineSnapshot: File?,
    val rawSnapshot: File?,
    val logSnapshot: File?,
    val csvSnapshot: File?,
) {
    fun matches(
        packetFile: File?,
        timelineFile: File?,
        rawFile: File?,
        logFile: File?,
        sessionStartMillis: Long?,
    ): Boolean {
        return packetSourcePath == packetFile?.absolutePath &&
            timelineSourcePath == timelineFile?.absolutePath &&
            rawSourcePath == rawFile?.absolutePath &&
            logSourcePath == logFile?.absolutePath &&
            this.sessionStartMillis == sessionStartMillis
    }

    fun hasSameBase(other: SessionExportSnapshot): Boolean {
        return packetSourcePath == other.packetSourcePath &&
            timelineSourcePath == other.timelineSourcePath &&
            rawSourcePath == other.rawSourcePath &&
            logSourcePath == other.logSourcePath &&
            sessionStartMillis == other.sessionStartMillis &&
            packetSnapshot?.absolutePath == other.packetSnapshot?.absolutePath &&
            timelineSnapshot?.absolutePath == other.timelineSnapshot?.absolutePath &&
            rawSnapshot?.absolutePath == other.rawSnapshot?.absolutePath &&
            logSnapshot?.absolutePath == other.logSnapshot?.absolutePath
    }
}

private data class ExportSnapshotBuildPlan(
    val epoch: Long,
    val sessionStartMillis: Long?,
    val packetFile: File?,
    val timelineFile: File?,
    val rawFile: File?,
    val logFile: File?,
    val staleSnapshot: SessionExportSnapshot?,
)

private fun deleteSnapshotFiles(snapshot: SessionExportSnapshot?) {
    listOfNotNull(
        snapshot?.packetSnapshot,
        snapshot?.timelineSnapshot,
        snapshot?.rawSnapshot,
        snapshot?.logSnapshot,
        snapshot?.csvSnapshot,
    )
        .forEach { file ->
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (exception: Exception) {
                Log.w("BLE_SHARE", "Failed to delete snapshot file ${file.absolutePath}", exception)
            }
        }
}
