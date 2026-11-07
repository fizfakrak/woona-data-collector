package com.example.myapplication.profile

import com.example.myapplication.localization.recordingCaptureText
import com.example.myapplication.localization.recordingUploadText
import com.example.myapplication.localization.recordingSourceText
import com.example.myapplication.localization.recordingUploadErrorText

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.myapplication.sync.QuestionnaireRevisionConflict
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.myapplication.data.toJson
import com.example.myapplication.data.ACTIVITY_TYPES
import com.example.myapplication.data.DogProfile
import com.example.myapplication.data.DogQuestionnaire
import com.example.myapplication.data.Recording
import com.example.myapplication.data.SURFACES
import com.example.myapplication.data.SessionQuestionnaire
import com.example.myapplication.data.HeartQuestionnaire
import com.example.myapplication.data.HEART_OPTIONS
import com.example.myapplication.data.readyForSync
import com.example.myapplication.data.validate
import com.example.myapplication.feature.device.VideoCaptureState
import com.example.myapplication.feature.device.DeviceListItem
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.questionnaireText
import com.example.myapplication.ble.PolarConnectionState
import com.example.myapplication.ble.PolarStatus
import com.example.myapplication.protocol.ConnectionQuality
import android.content.res.Configuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager

enum class ProfileScreenSection { ALL, ANIMALS, SESSION, VIDEO, RECORDINGS }

@Composable
fun ProfilesOverview(
    profiles: List<DogProfile>,
    selectedProfileId: String?,
    recordings: List<Recording>,
    language: AppLanguage,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onEditProfile: (DogProfile) -> Unit,
    onShareRecording: (String) -> Unit,
    onDownloadRecording: (String) -> Unit = {},
    onRemoveLocalRecording: (String) -> Unit = {},
    localRemovalEnabled: Boolean = true,
    videoState: VideoCaptureState,
    videoOffsetMillis: Double?,
    onVideoAction: () -> Unit,
    showVideoPreview: Boolean = true,
    onPreviewSurface: (Surface?) -> Unit = {},
    connectionQuality: ConnectionQuality = ConnectionQuality.IDLE,
    packetsLost: Long = 0L,
    polarStatus: PolarStatus = PolarStatus(),
    polarDevices: List<DeviceListItem> = emptyList(),
    onPolarScan: () -> Unit = {},
    onPolarConnect: (DeviceListItem) -> Unit = {},
    onPolarDisconnect: () -> Unit = {},
    onHeartQuestionnaire: (String) -> Unit = {},
    onAddReference: (String) -> Unit = {},
    onEditQuestionnaire: (Recording) -> Unit = {},
    section: ProfileScreenSection = ProfileScreenSection.ALL,
    canSelectProfile: Boolean = true,
    showingAllRecordings: Boolean = false,
    onShowAllRecordings: () -> Unit = {},
    recordingsTotal: Int = recordings.size,
    historyHasMoreRecordings: Boolean = recordings.size < recordingsTotal,
    recordingsLoading: Boolean = false,
    recordingsError: String? = null,
    onShowMoreRecordings: () -> Unit = {},
    onRetryRecordings: () -> Unit = {},
    onRefreshRecordings: () -> Unit = {},
    historyListState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    serverReports: Map<String, Pair<String?, Boolean>> = emptyMap(),
    onCheckServerRecording: (String) -> Unit = {},
    sdHistory: @Composable () -> Unit = {},
) {
    var pendingLocalRemoval by remember { mutableStateOf<Recording?>(null) }
    pendingLocalRemoval?.let { recording ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingLocalRemoval = null },
            title = { Text(tr(language, "Remove session files from this device?", "Удалить файлы сессии с устройства?")) },
            text = { Text(tr(language,
                "Session ${recording.sessionLabel}: the app will first verify the server copy. Only local files will be removed. Session history stays and files can be downloaded again.",
                "Сессия ${recording.sessionLabel}: приложение сначала проверит серверную копию. Будут удалены только локальные файлы. История останется, файлы можно будет скачать снова.")) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingLocalRemoval = null
                    onRemoveLocalRecording(recording.id)
                }) { Text(tr(language, "Remove from device", "Удалить с устройства")) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingLocalRemoval = null }) { Text(tr(language, "Cancel", "Отмена")) } },
        )
    }
    val selected = profiles.firstOrNull { it.id == selectedProfileId }
    if (section in setOf(ProfileScreenSection.ALL, ProfileScreenSection.SESSION, ProfileScreenSection.VIDEO) && videoState != VideoCaptureState.IDLE) {
        CaptureStatusCard(
            videoState,
            videoOffsetMillis,
            language,
            onVideoAction,
            showVideoPreview && section != ProfileScreenSection.SESSION,
            onPreviewSurface,
            connectionQuality,
            packetsLost,
            polarStatus,
            videoRequested = showVideoPreview,
        )
    }

    if (section in setOf(ProfileScreenSection.ALL, ProfileScreenSection.ANIMALS)) {
    if (!canSelectProfile) Text(tr(language, "Finish the active recording or account operation before changing the animal.", "Завершите текущую запись или операцию с аккаунтом перед сменой животного."))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr(language, "Active dog", "Активная собака"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canSelectProfile) { onSelectProfile(profile.id) },
                ) {
                    RadioButton(
                        selected = profile.id == selectedProfileId,
                        enabled = canSelectProfile,
                        onClick = { onSelectProfile(profile.id) },
                    )
                    Text(profile.numberOrName, modifier = Modifier.padding(top = 12.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreateProfile, enabled = canSelectProfile) {
                    Text(tr(language, "New dog", "Новая собака"))
                }
                OutlinedButton(
                    onClick = { selected?.let(onEditProfile) },
                    enabled = selected != null && canSelectProfile,
                ) {
                    Text(tr(language, "Edit", "Изменить"))
                }
            }
        }
    }

    }

    if (section in setOf(ProfileScreenSection.ALL, ProfileScreenSection.SESSION) && videoState == VideoCaptureState.IDLE) {
        PolarStatusCard(
            status = polarStatus,
            devices = polarDevices,
            language = language,
            onScan = onPolarScan,
            onConnect = onPolarConnect,
            onDisconnect = onPolarDisconnect,
        )
    }

    if (section == ProfileScreenSection.SESSION) {
        Text(tr(language, "Selected animal: ${selected?.numberOrName ?: "none — choose one in Animals"}", "Животное: ${selected?.numberOrName ?: "не выбрано — откройте Животные"}"))
        Text(tr(language, "Storage: phone. Files remain on this device until the server verifies their upload.", "Хранение: телефон. Файлы остаются на устройстве до подтверждения серверной загрузки."), style = MaterialTheme.typography.bodySmall)
    }
    if (section == ProfileScreenSection.VIDEO && videoState == VideoCaptureState.IDLE) {
        Text(tr(language, "No active video. Prepare a session and enable video in its questionnaire. Start and stop control the sensor and camera together.", "Видео не запущено. Подготовьте сессию и включите видео в её анкете. Старт и остановка управляют датчиком и камерой вместе."))
    }
    if (section in setOf(ProfileScreenSection.ALL, ProfileScreenSection.RECORDINGS)) {
        val header: @Composable () -> Unit = {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (showingAllRecordings) tr(language, "Recording history", "История записей") else tr(language, "Last 10 recordings", "Последние 10 записей"),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(tr(language, "Animal: ${selected?.numberOrName ?: "not selected"}. Choose another animal in Animals.", "Животное: ${selected?.numberOrName ?: "не выбрано"}. Выбор другого животного — во вкладке Животные."), style = MaterialTheme.typography.bodySmall)
                if (showingAllRecordings) Text(tr(language, "Shown ${recordings.size} of $recordingsTotal", "Показано ${recordings.size} из $recordingsTotal"), modifier = Modifier.testTag("history.count"))
                if (!showingAllRecordings && recordingsTotal > 10) OutlinedButton(onClick = onShowAllRecordings, enabled = !recordingsLoading) {
                    Text(tr(language, "Open history", "Открыть историю"))
                }
                if (showingAllRecordings) {
                    OutlinedButton(onClick = onRefreshRecordings, enabled = !recordingsLoading) { Text(tr(language, "Refresh history", "Обновить историю")) }
                    Text(tr(language, "The count reflects when history was opened. Refresh history to include added and removed recordings.", "Счётчик относится к моменту открытия. Обновите историю, чтобы учесть добавленные и удалённые записи."), style = MaterialTheme.typography.bodySmall)
                }
                if (recordingsLoading) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(tr(language, "Loading recordings…", "Загружаем записи…"))
                }
                if (recordingsError != null) {
                    Text(tr(language, "History could not be loaded. Existing data is preserved.", "Не удалось загрузить историю. Существующие данные сохранены."), color = MaterialTheme.colorScheme.error)
                    Text(recordingsError, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRetryRecordings, enabled = !recordingsLoading) { Text(tr(language, "Retry", "Повторить")) }
                }
                if (recordings.isEmpty() && !recordingsLoading) Text(tr(language, "No recordings for this dog", "У этой собаки пока нет записей"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val content: @Composable (Recording) -> Unit = { recording ->
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val localArtifacts = recording.artifacts.filter { it.localPresence in setOf("local", "both") }
                val remoteArtifacts = recording.artifacts.filter { it.localPresence == "remote_only" }
                val dataLabels = recordingDataLabels(recording, language)
                if (recording.status.value in setOf("completed", "failed", "interrupted")) {
                    OutlinedButton(onClick = { onEditQuestionnaire(recording) }, enabled = canSelectProfile && recording.serverSyncState != "uploading") { Text(tr(language, "Edit questionnaire", "Изменить анкету")) }
                    if (recording.questionnaire?.sessionKind == "heart" && !recording.questionnaire.readyForSync()) {
                        Button(onClick = { onHeartQuestionnaire(recording.id) }, enabled = canSelectProfile && recording.serverSyncState != "uploading") { Text(tr(language, "Complete heart questionnaire", "Заполнить анкету по сердцебиению")) }
                    }
                    OutlinedButton(onClick = { onAddReference(recording.id) }, enabled = canSelectProfile && recording.serverSyncState != "uploading") { Text(tr(language, "Add ECG / heart rate / intervals", "Добавить ЭКГ / ЧСС / интервалы")) }
                }
                Text(
                    "${recording.sessionLabel} · ${recordingDisplayTime(recording.startedAtUtc)}",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${selected?.numberOrName.orEmpty()} · ${recordingCaptureText(recording.status.value, recording.captureErrorCode, recording.captureErrorMessage, language)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recording.status.value in setOf("failed", "interrupted") || recording.captureErrorCode != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(recordingSourceText(recording.source.value, language), style = MaterialTheme.typography.bodySmall)
                Text(
                    recordingUploadText(recording.serverSyncState, recording.serverSyncError, language),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recording.serverSyncState in setOf("retryable_error", "permanent_error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val bytesTotal = recording.artifacts.sumOf { it.sizeBytes }
                val bytesUploaded = recording.artifacts.sumOf { it.uploadedBytes.coerceIn(0, it.sizeBytes) }
                val verifiedFiles = recording.artifacts.count { it.uploadState == "available" }
                Text(tr(language, "Transferred: $bytesUploaded of $bytesTotal bytes. Server-confirmed files: $verifiedFiles/${recording.artifacts.size}.", "Передано: $bytesUploaded из $bytesTotal байт. Подтверждено сервером файлов: $verifiedFiles/${recording.artifacts.size}."), style = MaterialTheme.typography.bodySmall)
                if (bytesTotal > 0) androidx.compose.material3.LinearProgressIndicator(progress = { (bytesUploaded.toDouble() / bytesTotal).toFloat() }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onCheckServerRecording(recording.id) }, enabled = serverReports[recording.id]?.second != true) {
                    Text(tr(language, "Check server transfer and files", "Проверить доставку и файлы на сервере"))
                }
                if (serverReports[recording.id]?.second == true) androidx.compose.material3.CircularProgressIndicator()
                serverReports[recording.id]?.first?.let { report ->
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) }
                }
                val errorDetails = listOfNotNull(recording.captureErrorCode, recording.captureErrorMessage, recording.serverSyncError).joinToString("\n")
                if (errorDetails.isNotBlank()) {
                    var expanded by remember(recording.id) { mutableStateOf(false) }
                    androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) { Text(tr(language, "Error details", "Подробности ошибки")) }
                    if (expanded) androidx.compose.foundation.text.selection.SelectionContainer { Text(errorDetails, style = MaterialTheme.typography.bodySmall) }
                }

                Text(
                    tr(
                        language,
                        "Data: ${dataLabels.ifEmpty { listOf("not written yet") }.joinToString()}",
                        "Данные: ${dataLabels.ifEmpty { listOf("ещё не записаны") }.joinToString()}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (remoteArtifacts.isNotEmpty()) {
                    Text(
                        tr(
                            language,
                            "On server only: ${remoteArtifacts.size}",
                            "Только на сервере: ${remoteArtifacts.size}",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { onDownloadRecording(recording.id) }) {
                        Text(tr(language, "Download files", "Скачать файлы"))
                    }
                }
                TextButton(
                    onClick = { onShareRecording(recording.id) },
                    enabled = localArtifacts.isNotEmpty(),
                ) {
                    Text(tr(language, "Share session ZIP", "Поделиться ZIP сессии"))
                }
                if (localArtifacts.isNotEmpty()) {
                    androidx.compose.material3.TextButton(
                        onClick = { pendingLocalRemoval = recording },
                        enabled = localRemovalEnabled && recording.serverSyncState == "synced" && recording.status.value in setOf("completed", "failed", "interrupted"),
                    ) { Text(tr(language, "Remove from device", "Удалить с устройства")) }
                    if (recording.serverSyncState != "synced") Text(tr(language,
                        "Local files can be removed after the server confirms the full upload.",
                        "Удаление локальных файлов доступно после подтверждения полной загрузки."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val more: @Composable () -> Unit = {
            val remaining = (recordingsTotal - recordings.size).coerceAtLeast(0)
            if (showingAllRecordings && remaining > 0 && !historyHasMoreRecordings) Text(tr(language, "History changed while you were viewing it. Refresh to recount recordings.", "История изменилась во время просмотра. Обновите её, чтобы пересчитать записи."))
            if (showingAllRecordings && remaining > 0 && historyHasMoreRecordings) OutlinedButton(onClick = onShowMoreRecordings, enabled = !recordingsLoading,
                modifier = Modifier.fillMaxWidth().testTag("history.more")) {
                val amount = minOf(100, remaining)
                Text(tr(language, "Show $amount more", "Показать ещё $amount"))
            }
        }
        if (section == ProfileScreenSection.RECORDINGS) {
            if (recordingsLoading && recordings.isEmpty()) Card(Modifier.fillMaxWidth()) { header() }
            else LazyColumn(Modifier.fillMaxSize().testTag("recordings.history"), state = historyListState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "history.header") { Card(Modifier.fillMaxWidth()) { header() } }
                item(key = "history.sd.local") { sdHistory() }
                items(recordings, key = { it.id }) { recording -> Card(Modifier.fillMaxWidth()) { content(recording) } }
                item(key = "history.more") { more() }
            }
        } else {
            Card(Modifier.fillMaxWidth()) { Column { header(); recordings.forEach { content(it) }; more() } }
        }
    }
}

@Composable
private fun CaptureStatusCard(
    state: VideoCaptureState,
    offsetMillis: Double?,
    language: AppLanguage,
    onAction: () -> Unit,
    showVideoPreview: Boolean,
    onPreviewSurface: (Surface?) -> Unit,
    connectionQuality: ConnectionQuality,
    packetsLost: Long,
    polarStatus: PolarStatus,
    videoRequested: Boolean,
) {
    val status = when (state) {
        VideoCaptureState.WAITING_FOR_SENSOR -> tr(language, "Waiting for the sensor", "Ожидание датчика")
        VideoCaptureState.READY -> if (videoRequested) tr(language, "Sensor and camera are ready", "Датчик и камера готовы") else tr(language, "Sensor is ready; video is disabled in the questionnaire", "Датчик готов; видео отключено в анкете")
        VideoCaptureState.STARTING -> tr(language, "Starting synchronized capture…", "Запуск синхронной записи…")
        VideoCaptureState.RECORDING -> if (videoRequested) tr(language, "Sensor and video are recording", "Датчик и видео записываются") else tr(language, "Sensor is recording; video is disabled", "Датчик записывается; видео отключено")
        VideoCaptureState.DEGRADED -> tr(language, "Sensor is recording; video failed", "Датчик записывается; видео недоступно")
        VideoCaptureState.STOPPING -> tr(language, "Finalizing all files…", "Сохранение всех файлов…")
        VideoCaptureState.FINISHED -> tr(language, "Session saved", "Сессия сохранена")
        VideoCaptureState.FAILED -> tr(language, "Capture failed", "Ошибка записи")
        VideoCaptureState.IDLE -> ""
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                tr(language, "Synchronized session", "Синхронная сессия"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                tr(
                    language,
                    "Collar: ${connectionQuality.name.lowercase()} · lost $packetsLost",
                    "Ошейник: ${collarQualityLabel(connectionQuality, language)} · потеряно $packetsLost",
                ),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                polarStatusLine(polarStatus, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state in setOf(
                    VideoCaptureState.READY,
                    VideoCaptureState.STARTING,
                    VideoCaptureState.RECORDING,
                    VideoCaptureState.DEGRADED,
                    VideoCaptureState.FAILED,
                )
            ) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state in setOf(
                                VideoCaptureState.STARTING,
                                VideoCaptureState.RECORDING,
                                VideoCaptureState.DEGRADED,
                            )
                        ) {
                            tr(language, "Stop session", "Завершить сессию")
                        } else {
                            tr(language, "Start session", "Начать сессию")
                        },
                    )
                }
            }
            if (showVideoPreview && state !in setOf(VideoCaptureState.FINISHED, VideoCaptureState.FAILED, VideoCaptureState.DEGRADED)) {
                val configuration = LocalConfiguration.current
                val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(
                                    object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            onPreviewSurface(holder.surface)
                                        }

                                        override fun surfaceChanged(
                                            holder: SurfaceHolder,
                                            format: Int,
                                            width: Int,
                                            height: Int,
                                        ) {
                                            onPreviewSurface(holder.surface)
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            onPreviewSurface(null)
                                        }
                                    },
                                )
                            }
                        },
                        modifier = Modifier
                            .testTag("video_preview")
                            .then(
                                if (portrait) Modifier.fillMaxWidth()
                                else Modifier.widthIn(max = (configuration.screenHeightDp * 0.4f * 16f / 9f).dp)
                            )
                            .aspectRatio(if (portrait) 9f / 16f else 16f / 9f),
                    )
                }
            }
            offsetMillis?.let {
                Text(
                    tr(
                        language,
                        "First-frame offset: ${"%.3f".format(it)} ms",
                        "Смещение первого кадра: ${"%.3f".format(it)} мс",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PolarStatusCard(
    status: PolarStatus,
    devices: List<DeviceListItem>,
    language: AppLanguage,
    onScan: () -> Unit,
    onConnect: (DeviceListItem) -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Polar H10", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(polarStatusLine(status, language), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.state in setOf(PolarConnectionState.READY, PolarConnectionState.RECORDING)) {
                OutlinedButton(onClick = onDisconnect) {
                    Text(tr(language, "Disconnect Polar", "Отключить Polar"))
                }
            } else {
                Button(onClick = onScan, enabled = status.state != PolarConnectionState.SCANNING) {
                    Text(
                        if (status.state == PolarConnectionState.SCANNING) {
                            tr(language, "Searching…", "Поиск…")
                        } else {
                            tr(language, "Find Polar", "Найти Polar")
                        },
                    )
                }
                devices.forEach { device ->
                    TextButton(onClick = { onConnect(device) }) {
                        Text("${device.name} · ${device.address}")
                    }
                }
            }
        }
    }
}

internal fun recordingDisplayTime(value: String, zoneId: ZoneId = ZoneId.systemDefault()): String =
    runCatching {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(Instant.parse(value).atZone(zoneId))
    }.getOrDefault(value)

internal fun recordingDataLabels(recording: Recording, language: AppLanguage): List<String> = buildList {
    val names = recording.artifacts.map { it.fileName }.toSet()
    val types = recording.artifacts.map { it.type.value }.toSet()
    if ("ecg" in types) add(tr(language, "ECG", "ЭКГ"))
    if ("heart_rate" in types) add(tr(language, "Heart rate", "ЧСС"))
    if ("rr" in types) add(tr(language, "Beat intervals", "Интервалы между ударами"))
    if (names.any { it in setOf("packets.bin", "raw_fragments.binlog", "channel.csv") }) {
        add(tr(language, "collar", "ошейник"))
    }
    if ("video.mp4" in names) add(tr(language, "video", "видео"))
    if (names.any { it.startsWith("polar_") }) add("Polar HR/ECG/ACC")
    if ("diagnostics.log" in names) add(tr(language, "diagnostics", "диагностика"))
}

private fun collarQualityLabel(quality: ConnectionQuality, language: AppLanguage): String = when (quality) {
    ConnectionQuality.GOOD -> tr(language, "good", "хорошо")
    ConnectionQuality.WARNING -> tr(language, "unstable", "нестабильно")
    ConnectionQuality.WEAK -> tr(language, "loss", "потери")
    ConnectionQuality.LOST -> tr(language, "disconnected", "разрыв")
    ConnectionQuality.WAITING -> tr(language, "waiting for data", "ожидание данных")
    ConnectionQuality.IDLE -> tr(language, "idle", "не подключён")
}

private fun polarStatusLine(status: PolarStatus, language: AppLanguage): String {
    val state = when (status.state) {
        PolarConnectionState.IDLE -> tr(language, "not connected", "не подключён")
        PolarConnectionState.SCANNING -> tr(language, "searching", "поиск")
        PolarConnectionState.CONNECTING -> tr(language, "connecting", "подключение")
        PolarConnectionState.READY -> tr(language, "ready", "готов")
        PolarConnectionState.RECORDING -> tr(language, "recording", "пишет данные")
        PolarConnectionState.LOST -> tr(language, "disconnected", "разрыв")
        PolarConnectionState.FAILED -> tr(language, "error", "ошибка")
    }
    val metrics = listOfNotNull(
        status.batteryPercent?.let { tr(language, "battery $it%", "заряд $it%") },
        status.heartRateBpm?.let { tr(language, "HR $it bpm", "пульс $it уд/мин") },
    ) + if (status.state == PolarConnectionState.RECORDING) {
        listOf("ECG ${status.ecgFrames}", "ACC ${status.accFrames}")
    } else {
        emptyList()
    }
    return listOf("Polar: $state", *metrics.toTypedArray()).joinToString(" · ")
}

@Composable
private fun LegacyDogQuestionnaireDialog(
    initial: DogQuestionnaire?,
    required: Boolean,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onSave: (DogQuestionnaire) -> Unit,
) {
    val values = rememberSaveable(initial, saver = questionnaireValuesSaver) {
        mutableStateMapOf<String, String>().apply { putDog(initial) }
    }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val progress = dogProgress(values)
    QuestionnaireDialog(
        title = tr(language, "Dog profile", "Карточка собаки"),
        subtitle = tr(language, "Every question requires an explicit answer", "На каждый вопрос нужен явный ответ"),
        language = language,
        progress = progress,
        errorCount = errors.size,
        firstErrorKey = errors.keys.firstOrNull(),
        canDismiss = !required,
        onDismiss = onDismiss,
        onSave = {
            val result = values.toDogQuestionnaire()
            errors = result.validate().errors
            if (errors.isEmpty()) onSave(result)
        },
    ) {
        SectionTitle(tr(language, "Who", "Кто"))
        Field(values, "numberOrName", tr(language, "Number or name *", "Номер или кличка *"), error = errors["numberOrName"])
        Field(values, "shelterOrPlace", tr(language, "Shelter or place *", "Приют или место *"), error = errors["shelterOrPlace"])
        Choice(
            values,
            "breedStatus",
            tr(language, "Breed status *", "Статус породы *"),
            options(
                language,
                "purebred" to ("Purebred" to "Породистая"),
                "mixed" to ("Mixed" to "Метис"),
                "unknown" to ("Unknown" to "Неизвестно"),
            ),
            errors["breedStatus"],
        ) {
            values.remove("breedName")
            values.remove("resembles")
        }
        if (values["breedStatus"] in setOf("purebred", "mixed")) {
            Field(values, "breedName", tr(language, "Breed name *", "Название породы *"), error = errors["breedName"])
        }
        if (values["breedStatus"] == "mixed") {
            Field(values, "resembles", tr(language, "Resembles *", "На кого похожа *"), error = errors["resembles"])
        }
        Choice(
            values,
            "size",
            tr(language, "Size *", "Размер *"),
            options(
                language,
                "small" to ("Small" to "Маленькая"),
                "medium" to ("Medium" to "Средняя"),
                "large" to ("Large" to "Крупная"),
                "giant" to ("Giant" to "Гигантская"),
                "unknown" to ("Unknown" to "Неизвестно"),
            ),
            errors["size"],
        )
        Choice(
            values,
            "ageStatus",
            tr(language, "Age status *", "Статус возраста *"),
            options(
                language,
                "known" to ("Known" to "Известен"),
                "estimated" to ("Estimated" to "Оценён"),
                "unknown" to ("Unknown" to "Неизвестен"),
            ),
            errors["ageStatus"],
        ) {
            if (it == "unknown") {
                values.remove("ageYears")
                values.remove("ageMonths")
                values["ageSource"] = "unknown"
            } else if (values["ageSource"] == "unknown") {
                values.remove("ageSource")
            }
        }
        if (values["ageStatus"] in setOf("known", "estimated")) {
            Field(values, "ageYears", tr(language, "Full years", "Полных лет"), KeyboardType.Number, errors["ageYears"])
            Field(values, "ageMonths", tr(language, "Additional months", "Дополнительных месяцев"), KeyboardType.Number, errors["ageMonths"])
            Choice(
                values,
                "ageSource",
                tr(language, "Age source *", "Источник возраста *"),
                options(
                    language,
                    "documents" to ("Documents" to "Документы"),
                    "shelter_report" to ("Shelter report" to "Со слов приюта"),
                    "dental_estimate" to ("Dental estimate" to "Оценка по зубам"),
                    "operator_estimate" to ("Operator estimate" to "Оценка оператора"),
                ),
                errors["ageSource"],
            )
        }
        Choice(values, "sex", tr(language, "Sex *", "Пол *"), options(
            language,
            "male" to ("Male" to "Самец"),
            "female" to ("Female" to "Самка"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["sex"])
        Choice(values, "sterilizationStatus", tr(language, "Sterilized *", "Стерилизация *"), yesNoUnknown(language), errors["sterilizationStatus"])

        SectionTitle(tr(language, "Body", "Тело"))
        Choice(values, "weightStatus", tr(language, "Weight status *", "Статус веса *"), options(
            language,
            "measured" to ("Measured" to "Измерен"),
            "estimated" to ("Estimated" to "Оценён"),
            "unknown" to ("Unknown" to "Неизвестен"),
        ), errors["weightStatus"]) {
            if (it == "unknown") values.remove("weightKg")
        }
        if (values["weightStatus"] in setOf("measured", "estimated")) {
            Field(values, "weightKg", tr(language, "Weight, kg *", "Вес, кг *"), KeyboardType.Decimal, errors["weightKg"])
        }
        Choice(values, "bodyConditionStatus", tr(language, "BCS status *", "Статус BCS *"), options(
            language,
            "assessed" to ("Assessed" to "Оценён"),
            "unable" to ("Unable" to "Невозможно оценить"),
        ), errors["bodyConditionStatus"]) {
            if (it == "unable") values.remove("bodyConditionScore")
        }
        if (values["bodyConditionStatus"] == "assessed") {
            Choice(values, "bodyConditionScore", "BCS 1–9 *", (1..9).map { it.toString() to it.toString() }, errors["bodyConditionScore"])
        }
        Choice(values, "muscleMass", tr(language, "Muscle mass *", "Мышечная масса *"), options(
            language,
            "normal" to ("Normal" to "Норма"),
            "mild_loss" to ("Mild loss" to "Лёгкая потеря"),
            "moderate_loss" to ("Moderate loss" to "Умеренная потеря"),
            "severe_loss" to ("Severe loss" to "Выраженная потеря"),
            "unable" to ("Unable" to "Невозможно оценить"),
        ), errors["muscleMass"])
        Choice(values, "neckCircumferenceStatus", tr(language, "Neck measurement *", "Обхват шеи *"), measuredNotMeasured(language), errors["neckCircumferenceStatus"]) {
            if (it == "not_measured") values.remove("neckCircumferenceCm")
        }
        if (values["neckCircumferenceStatus"] == "measured") {
            Field(values, "neckCircumferenceCm", tr(language, "Neck, cm *", "Обхват, см *"), KeyboardType.Decimal, errors["neckCircumferenceCm"])
        }
        Choice(values, "coatLength", tr(language, "Coat length *", "Длина шерсти *"), options(
            language,
            "short" to ("Short" to "Короткая"),
            "medium" to ("Medium" to "Средняя"),
            "long" to ("Long" to "Длинная"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["coatLength"])
        Choice(values, "undercoat", tr(language, "Undercoat *", "Подшёрсток *"), options(
            language,
            "none" to ("None" to "Нет"),
            "moderate" to ("Moderate" to "Умеренный"),
            "dense" to ("Dense" to "Плотный"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["undercoat"])
        Choice(values, "shavedAreasStatus", tr(language, "Shaved areas *", "Выстриженные участки *"), options(
            language,
            "none" to ("None" to "Нет"),
            "present" to ("Present" to "Есть"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["shavedAreasStatus"]) {
            if (it != "present") values.remove("shavedAreasDetails")
        }
        if (values["shavedAreasStatus"] == "present") {
            Field(values, "shavedAreasDetails", tr(language, "Describe shaved areas *", "Опишите участки *"), error = errors["shavedAreasDetails"], singleLine = false)
        }

        SectionTitle(tr(language, "Condition", "Состояние"))
        MultiChoice(
            values,
            "observedSigns",
            tr(language, "Observed signs *", "Наблюдаемые признаки *"),
            options(
                language,
                "none" to ("None" to "Нет"),
                "unknown" to ("Unknown" to "Неизвестно"),
                "labored_breathing" to ("Labored breathing" to "Затруднённое дыхание"),
                "fainting" to ("Fainting" to "Обморок"),
                "seizures" to ("Seizures" to "Судороги"),
                "cannot_urinate" to ("Cannot urinate" to "Не может помочиться"),
                "limb_weakness" to ("Limb weakness" to "Слабость конечностей"),
                "jaundice" to ("Jaundice" to "Желтушность"),
                "vomiting_or_no_appetite" to ("Vomiting/no appetite" to "Рвота/нет аппетита"),
                "stool_changes" to ("Stool changes" to "Изменения стула"),
                "cough" to ("Cough" to "Кашель"),
                "pain_or_lameness" to ("Pain/lameness" to "Боль/хромота"),
                "thirst_changes" to ("Thirst changes" to "Изменение жажды"),
                "distress" to ("Distress" to "Беспокойство"),
            ),
            errors["observedSigns"],
            exclusive = setOf("none", "unknown"),
        )
        Choice(values, "diagnosesStatus", tr(language, "Veterinary diagnoses *", "Диагнозы врача *"), yesNoUnknown(language), errors["diagnosesStatus"]) {
            if (it != "yes") values.remove("diagnosesDetails")
        }
        if (values["diagnosesStatus"] == "yes") {
            Field(values, "diagnosesDetails", tr(language, "Diagnoses and medication *", "Диагнозы и препараты *"), error = errors["diagnosesDetails"], singleLine = false)
        }

        SectionTitle(tr(language, "Housing", "Содержание"))
        Choice(values, "housing", tr(language, "Housing *", "Где живёт *"), options(
            language,
            "enclosure" to ("Enclosure" to "Вольер"),
            "room" to ("Room" to "Комната"),
            "home" to ("Home" to "Дом"),
            "free_range" to ("Free range" to "Свободный выгул"),
            "other" to ("Other" to "Другое"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["housing"]) {
            if (it != "other") values.remove("housingDetails")
        }
        if (values["housing"] == "other") Field(values, "housingDetails", tr(language, "Housing details *", "Описание жилья *"), error = errors["housingDetails"])
        Choice(values, "walksStatus", tr(language, "Walks *", "Прогулки *"), options(
            language,
            "known" to ("Known" to "Известно"),
            "none" to ("None" to "Нет"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["walksStatus"]) {
            if (it != "known") values.remove("walksDescription")
        }
        if (values["walksStatus"] == "known") Field(values, "walksDescription", tr(language, "Walk details *", "Описание прогулок *"), error = errors["walksDescription"])
        Choice(values, "cohabitants", tr(language, "Lives with *", "С кем живёт *"), options(
            language,
            "alone" to ("Alone" to "Один"),
            "other_animals" to ("Other animals" to "С животными"),
            "people_only" to ("People only" to "Только с людьми"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["cohabitants"])
        Choice(values, "shelterPermission", tr(language, "Written permission *", "Письменное разрешение *"), options(
            language,
            "yes" to ("Yes" to "Да"),
            "no" to ("No" to "Нет"),
            "not_required" to ("Not required" to "Не требуется"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["shelterPermission"])
        Choice(values, "notesStatus", tr(language, "Notes *", "Примечания *"), options(
            language,
            "none" to ("None" to "Нет"),
            "provided" to ("Provided" to "Есть"),
        ), errors["notesStatus"]) {
            if (it == "none") values.remove("notes")
        }
        if (values["notesStatus"] == "provided") Field(values, "notes", tr(language, "Notes *", "Примечания *"), error = errors["notes"], singleLine = false)
    }
}

@Composable
private fun LegacySessionQuestionnaireDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onSave: (SessionQuestionnaire) -> Unit,
) {
    val values = rememberSaveable(saver = questionnaireValuesSaver) {
        mutableStateMapOf<String, String>()
    }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val progress = sessionProgress(values)
    QuestionnaireDialog(
        title = tr(language, "Recording session", "Карточка сессии"),
        subtitle = tr(language, "Start time and UUID are recorded automatically", "Время старта и UUID запишутся автоматически"),
        language = language,
        progress = progress,
        errorCount = errors.size,
        firstErrorKey = errors.keys.firstOrNull(),
        canDismiss = true,
        onDismiss = onDismiss,
        onSave = {
            val result = values.toSessionQuestionnaire()
            errors = result.validate().errors.toMutableMap().apply {
                if (values["videoRequested"] !in setOf("yes", "no")) {
                    put("videoRequested", tr(language, "Required", "Обязательное поле"))
                }
            }
            if (errors.isEmpty()) onSave(result)
        },
    ) {
        SectionTitle(tr(language, "Protocol", "Протокол"))
        Field(values, "sessionLabel", tr(language, "Session label *", "Название сессии *"), error = errors["sessionLabel"])
        Field(values, "operatorName", tr(language, "Operator *", "Оператор *"), error = errors["operatorName"])
        Choice(values, "activityGroup", tr(language, "Activity group *", "Группа активности *"), options(
            language,
            "locomotion" to ("Locomotion" to "Движение"),
            "stationary" to ("Stationary" to "Неподвижно"),
            "daily_living" to ("Daily living" to "Бытовая активность"),
            "other" to ("Other protocol" to "Другой протокол"),
        ), errors["activityGroup"]) { next ->
            if (values["activityType"] !in ACTIVITY_TYPES[next].orEmpty()) {
                values.remove("activityType")
                values.remove("activityDetails")
            }
            if (next == "other") values["activityType"] = "other"
        }
        ACTIVITY_TYPES[values["activityGroup"]]?.let { allowed ->
            Choice(
                values,
                "activityType",
                tr(language, "Activity *", "Активность *"),
                activityOptions(language).filter { it.first in allowed },
                errors["activityType"],
            ) {
                if (it !in setOf("mixed", "other")) values.remove("activityDetails")
            }
        }
        if (values["activityType"] in setOf("mixed", "other")) {
            Field(values, "activityDetails", tr(language, "Protocol details *", "Описание протокола *"), error = errors["activityDetails"], singleLine = false)
        }

        SectionTitle(tr(language, "Conditions", "Условия"))
        Choice(values, "location", tr(language, "Location *", "Место *"), options(
            language,
            "indoors" to ("Indoors" to "В помещении"),
            "outdoors" to ("Outdoors" to "На улице"),
        ), errors["location"]) { next ->
            if (values["surface"] !in SURFACES[next].orEmpty()) {
                values.remove("surface")
                values.remove("surfaceDetails")
            }
        }
        SURFACES[values["location"]]?.let { allowed ->
            Choice(
                values,
                "surface",
                tr(language, "Surface *", "Поверхность *"),
                surfaceOptions(language).filter { it.first in allowed },
                errors["surface"],
            ) {
                if (it != "other") values.remove("surfaceDetails")
            }
        }
        if (values["surface"] == "other") Field(values, "surfaceDetails", tr(language, "Surface details *", "Описание поверхности *"), error = errors["surfaceDetails"])
        Measurement(
            values,
            "airTemperature",
            tr(language, "Air temperature", "Температура воздуха"),
            "°C",
            language,
            errors,
            KeyboardType.Decimal,
        )
        Choice(values, "sensorPosition", tr(language, "Sensor position *", "Положение датчика *"), options(
            language,
            "dorsal_neck" to ("Dorsal neck" to "Сверху на шее"),
            "left_neck" to ("Left neck" to "Слева на шее"),
            "right_neck" to ("Right neck" to "Справа на шее"),
            "chest" to ("Chest" to "Грудь"),
            "back" to ("Back" to "Спина"),
            "other" to ("Other" to "Другое"),
        ), errors["sensorPosition"]) {
            if (it != "other") values.remove("sensorPositionDetails")
        }
        if (values["sensorPosition"] == "other") Field(values, "sensorPositionDetails", tr(language, "Position details *", "Описание позиции *"), error = errors["sensorPositionDetails"])
        Choice(values, "collarTightness", tr(language, "Collar tightness *", "Затяжка ошейника *"), options(
            language,
            "loose" to ("Loose" to "Свободно"),
            "snug" to ("Snug" to "Плотно"),
            "tight" to ("Tight" to "Туго"),
        ), errors["collarTightness"])

        SectionTitle(tr(language, "Manual measurements", "Ручные измерения"))
        Choice(values, "preMeasurementState", tr(language, "State before measurement *", "Состояние до измерения *"), options(
            language,
            "rest" to ("Rest" to "Покой"),
            "walk" to ("Walk" to "Ходьба"),
            "run" to ("Run" to "Бег"),
            "play" to ("Play" to "Игра"),
            "stress" to ("Stress" to "Стресс"),
            "other" to ("Other" to "Другое"),
            "unknown" to ("Unknown" to "Неизвестно"),
        ), errors["preMeasurementState"]) {
            if (it != "other") values.remove("preMeasurementStateDetails")
        }
        if (values["preMeasurementState"] == "other") Field(values, "preMeasurementStateDetails", tr(language, "State details *", "Описание состояния *"), error = errors["preMeasurementStateDetails"])
        Measurement(values, "pulse", tr(language, "Pulse", "Пульс"), "bpm", language, errors, KeyboardType.Number)
        Measurement(values, "respiration", tr(language, "Respiration", "Дыхание"), "/min", language, errors, KeyboardType.Number)
        Measurement(values, "bodyTemperature", tr(language, "Body temperature", "Температура тела"), "°C", language, errors, KeyboardType.Decimal)
        if (listOf("pulseStatus", "respirationStatus", "bodyTemperatureStatus").any { values[it] == "measured" }) {
            if (values["measurementAtUtc"].isNullOrBlank()) values["measurementAtUtc"] = Instant.now().toString()
            Field(values, "measurementAtUtc", tr(language, "Measurement time UTC *", "Время измерений UTC *"), error = errors["measurementAtUtc"])
        } else {
            values.remove("measurementAtUtc")
        }
        Choice(values, "videoRequested", tr(language, "Record video *", "Записывать видео *"), yesNo(language), errors["videoRequested"])
    }
}

@Composable
private fun QuestionnaireDialog(
    title: String,
    subtitle: String,
    language: AppLanguage,
    progress: Pair<Int, Int>,
    errorCount: Int,
    firstErrorKey: String?,
    canDismiss: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend () -> Unit,
    externalError: String? = null,
    onReviewedRevision: (Int) -> Unit = {},
    onUseServerAnswers: ((SessionQuestionnaire) -> Unit)? = null,
    onRestoreDraft: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var operationError by rememberSaveable { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<QuestionnaireRevisionConflict?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { if (canDismiss && !isSaving) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss && !isSaving,
            dismissOnClickOutside = false,
            // Use native window bounds; Compose 1.7's custom fullscreen measurement can exceed them.
            usePlatformDefaultWidth = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val view = LocalView.current
            val focusManager = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            DisposableEffect(view) {
                (view.parent as? DialogWindowProvider)?.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
                onDispose {}
            }
            val scrollState = rememberScrollState()
            // On short windows (including IME-resized dialogs) reserve enough
            // space for the form instead of allowing the header to consume it.
            val compactHeader = maxHeight < 240.dp
            LaunchedEffect(conflict, operationError, externalError) {
                if (conflict != null || operationError != null || externalError != null) scrollState.scrollTo(0)
            }
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!compactHeader) {
                        Text(questionnaireText(title, language), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(questionnaireText(subtitle, language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            tr(
                                language,
                                "Answered: ${progress.first} of ${progress.second}",
                                "Заполнено: ${progress.first} из ${progress.second}",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (errorCount > 0) {
                            Text(
                                tr(
                                    language,
                                    "$errorCount fields need attention",
                                    "Проверьте поля: $errorCount",
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canDismiss) {
                            TextButton(onClick = onDismiss, enabled = !isSaving, modifier = Modifier.weight(1f)) {
                                Text(tr(language, "Cancel", "Отмена"))
                            }
                        }
                        Button(onClick = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            operationError = null
                            isSaving = true
                            scope.launch {
                                try { onSave() }
                                catch (error: QuestionnaireRevisionConflict) { conflict = error }
                                catch (error: Exception) {
                                    operationError = tr(language,
                                        "Save has not been confirmed. Your draft remains open; review the cause and retry.",
                                        "Сохранение не подтверждено. Черновик остаётся открытым; проверьте причину и повторите.") + "\n" + error.toString()
                                } finally { isSaving = false }
                            }
                        }, enabled = !isSaving && conflict == null, modifier = Modifier.weight(1f)) {
                            Text(tr(language, "Validate and save", "Проверить и сохранить"))
                        }
                    }
                    if (isSaving) Text(tr(language, "Saving and checking the result…", "Сохраняем и проверяем результат…"), modifier = Modifier.testTag("questionnaire.saving"))
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).testTag("questionnaire.scroll")) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            conflict?.let { latest ->
                                Text(tr(language, "HTTP 409: server revision ${latest.revision}. Your draft remains in this window. Review the server answers before choosing a version. Original capture files are preserved.",
                                    "HTTP 409: на сервере версия ${latest.revision}. Ваш черновик остаётся в этом окне. Проверьте ответы сервера перед выбором версии. Исходные файлы записи сохраняются."), color = MaterialTheme.colorScheme.error)
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(latest.questionnaire.toJson(), modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()).testTag("questionnaire.serverAnswers"))
                                }
                                TextButton(onClick = { onReviewedRevision(latest.revision); conflict = null
                                    operationError = tr(language, "Your edits are retained. Press Save to replace the reviewed server answers.", "Ваши правки оставлены. Нажмите Сохранить, чтобы заменить проверенные ответы сервера.")
                                }) { Text(tr(language, "Keep my edits for revision ${latest.revision}", "Оставить мои правки для версии ${latest.revision}")) }
                                onUseServerAnswers?.let { use -> TextButton(onClick = { use(latest.questionnaire); onReviewedRevision(latest.revision); conflict = null; operationError = null }) {
                                    Text(tr(language, "Load server answers and retain my draft", "Загрузить ответы сервера и сохранить мой черновик"))
                                } }
                            }
                            onRestoreDraft?.let { restore -> TextButton(onClick = restore, enabled = !isSaving) { Text(tr(language, "Restore my edits", "Вернуть мои правки")) } }
                            (operationError ?: externalError)?.let { error ->
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(error, color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.testTag("questionnaire.saveError"))
                                }
                            }
                        }
                        CompositionLocalProvider(LocalFirstErrorKey provides firstErrorKey, LocalQuestionnaireLanguage provides language) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) { content() }
                        }
                    }
                    if (isSaving) Box(Modifier.fillMaxSize().clearAndSetSemantics { }.clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}))
                }
            }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        questionnaireLabel(text),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Field(
    values: MutableMap<String, String>,
    key: String,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
    singleLine: Boolean = true,
    bringIntoViewKey: String = key,
) {
    val errorModifier = firstErrorModifier(bringIntoViewKey)
    OutlinedTextField(
        value = values[key].orEmpty(),
        onValueChange = { values[key] = it },
        label = { Text(questionnaireLabel(label)) },
        supportingText = error?.let { { Text(questionnaireLabel(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        isError = error != null,
        modifier = Modifier
            .fillMaxWidth()
            .then(errorModifier),
    )
}

@Composable
private fun Choice(
    values: MutableMap<String, String>,
    key: String,
    label: String,
    options: List<Pair<String, String>>,
    error: String? = null,
    onChange: (String) -> Unit = {},
) {
    val errorModifier = firstErrorModifier(key)
    Column(modifier = errorModifier) {
        Text(questionnaireLabel(label), fontWeight = FontWeight.Medium)
        options.forEach { (value, title) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        values[key] = value
                        onChange(value)
                    },
            ) {
                RadioButton(
                    selected = values[key] == value,
                    onClick = {
                        values[key] = value
                        onChange(value)
                    },
                )
                Text(questionnaireLabel(title), modifier = Modifier.padding(top = 12.dp))
            }
        }
        error?.let { Text(questionnaireLabel(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun MultiChoice(
    values: MutableMap<String, String>,
    key: String,
    label: String,
    options: List<Pair<String, String>>,
    error: String?,
    exclusive: Set<String>,
) {
    val errorModifier = firstErrorModifier(key)
    Column(modifier = errorModifier) {
        Text(questionnaireLabel(label), fontWeight = FontWeight.Medium)
        options.forEach { (value, title) ->
            val selected = value in values.list(key)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { values.toggle(key, value, exclusive) },
            ) {
                Checkbox(checked = selected, onCheckedChange = { values.toggle(key, value, exclusive) })
                Text(questionnaireLabel(title), modifier = Modifier.padding(top = 12.dp))
            }
        }
        error?.let { Text(questionnaireLabel(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Measurement(
    values: MutableMap<String, String>,
    key: String,
    label: String,
    unit: String,
    language: AppLanguage,
    errors: Map<String, String>,
    keyboardType: KeyboardType,
) {
    Choice(
        values,
        "${key}Status",
        "$label *",
        measuredNotMeasured(language),
        errors["${key}Status"],
    ) {
        if (it == "not_measured") values.remove(if (key == "airTemperature") "airTemperatureC" else if (key == "bodyTemperature") "bodyTemperatureC" else if (key == "respiration") "respirationPerMinute" else "pulseBpm")
    }
    if (values["${key}Status"] == "measured") {
        val valueKey = when (key) {
            "airTemperature" -> "airTemperatureC"
            "bodyTemperature" -> "bodyTemperatureC"
            "respiration" -> "respirationPerMinute"
            else -> "pulseBpm"
        }
        Field(
            values,
            valueKey,
            "$label, $unit *",
            keyboardType,
            errors[valueKey] ?: errors[key],
            bringIntoViewKey = if (errors[valueKey] != null) valueKey else key,
        )
    }
}

private val LocalFirstErrorKey = staticCompositionLocalOf<String?> { null }
private val LocalQuestionnaireLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

@Composable
private fun questionnaireLabel(text: String): String = questionnaireText(text, LocalQuestionnaireLanguage.current)

private val questionnaireValuesSaver = mapSaver<SnapshotStateMap<String, String>>(
    save = { values -> values.mapValues { it.value } },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.forEach { (key, value) ->
                if (value is String) put(key, value)
            }
        }
    },
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun firstErrorModifier(key: String): Modifier {
    val firstErrorKey = LocalFirstErrorKey.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(firstErrorKey) {
        if (firstErrorKey == key) requester.bringIntoView()
    }
    return Modifier.bringIntoViewRequester(requester)
}

private fun dogProgress(values: Map<String, String>): Pair<Int, Int> {
    val keys = mutableListOf(
        "numberOrName",
        "shelterOrPlace",
        "breedStatus",
        "size",
        "ageStatus",
        "sex",
        "sterilizationStatus",
        "weightStatus",
        "bodyConditionStatus",
        "muscleMass",
        "neckCircumferenceStatus",
        "coatLength",
        "undercoat",
        "shavedAreasStatus",
        "observedSigns",
        "diagnosesStatus",
        "housing",
        "walksStatus",
        "cohabitants",
        "shelterPermission",
        "notesStatus",
    )
    when (values["breedStatus"]) {
        "purebred" -> keys += "breedName"
        "mixed" -> keys += listOf("breedName", "resembles")
    }
    if (values["ageStatus"] in setOf("known", "estimated")) {
        keys += listOf("age", "ageSource")
    }
    if (values["weightStatus"] in setOf("measured", "estimated")) keys += "weightKg"
    if (values["bodyConditionStatus"] == "assessed") keys += "bodyConditionScore"
    if (values["neckCircumferenceStatus"] == "measured") keys += "neckCircumferenceCm"
    if (values["shavedAreasStatus"] == "present") keys += "shavedAreasDetails"
    if (values["diagnosesStatus"] == "yes") keys += "diagnosesDetails"
    if (values["housing"] == "other") keys += "housingDetails"
    if (values["walksStatus"] == "known") keys += "walksDescription"
    if (values["notesStatus"] == "provided") keys += "notes"
    return keys.count { key ->
        if (key == "age") {
            !values["ageYears"].isNullOrBlank() || !values["ageMonths"].isNullOrBlank()
        } else {
            !values[key].isNullOrBlank()
        }
    } to keys.size
}

private fun sessionProgress(values: Map<String, String>): Pair<Int, Int> {
    val keys = mutableListOf(
        "sessionLabel",
        "operatorName",
        "activityGroup",
        "location",
        "airTemperatureStatus",
        "sensorPosition",
        "collarTightness",
        "preMeasurementState",
        "pulseStatus",
        "respirationStatus",
        "bodyTemperatureStatus",
        "videoRequested",
    )
    if (ACTIVITY_TYPES[values["activityGroup"]] != null) keys += "activityType"
    if (values["activityType"] in setOf("mixed", "other")) keys += "activityDetails"
    if (SURFACES[values["location"]] != null) keys += "surface"
    if (values["surface"] == "other") keys += "surfaceDetails"
    if (values["airTemperatureStatus"] == "measured") keys += "airTemperatureC"
    if (values["sensorPosition"] == "other") keys += "sensorPositionDetails"
    if (values["preMeasurementState"] == "other") keys += "preMeasurementStateDetails"
    if (values["pulseStatus"] == "measured") keys += "pulseBpm"
    if (values["respirationStatus"] == "measured") keys += "respirationPerMinute"
    if (values["bodyTemperatureStatus"] == "measured") keys += "bodyTemperatureC"
    if (listOf("pulseStatus", "respirationStatus", "bodyTemperatureStatus").any {
            values[it] == "measured"
        }
    ) {
        keys += "measurementAtUtc"
    }
    return keys.count { !values[it].isNullOrBlank() } to keys.size
}

private fun MutableMap<String, String>.putDog(value: DogQuestionnaire?) {
    if (value == null) return
    put("numberOrName", value.numberOrName)
    put("shelterOrPlace", value.shelterOrPlace)
    put("breedStatus", value.breedStatus)
    putIfNotNull("breedName", value.breedName)
    putIfNotNull("resembles", value.resembles)
    put("size", value.size)
    put("ageStatus", value.ageStatus)
    putIfNotNull("ageYears", value.ageYears)
    putIfNotNull("ageMonths", value.ageMonths)
    put("ageSource", value.ageSource)
    put("sex", value.sex)
    put("sterilizationStatus", value.sterilizationStatus)
    put("weightStatus", value.weightStatus)
    putIfNotNull("weightKg", value.weightKg)
    put("bodyConditionStatus", value.bodyConditionStatus)
    putIfNotNull("bodyConditionScore", value.bodyConditionScore)
    put("muscleMass", value.muscleMass)
    put("neckCircumferenceStatus", value.neckCircumferenceStatus)
    putIfNotNull("neckCircumferenceCm", value.neckCircumferenceCm)
    put("coatLength", value.coatLength)
    put("undercoat", value.undercoat)
    put("shavedAreasStatus", value.shavedAreasStatus)
    putIfNotNull("shavedAreasDetails", value.shavedAreasDetails)
    put("observedSigns", value.observedSigns.joinToString(LIST_SEPARATOR))
    put("diagnosesStatus", value.diagnosesStatus)
    putIfNotNull("diagnosesDetails", value.diagnosesDetails)
    put("housing", value.housing)
    putIfNotNull("housingDetails", value.housingDetails)
    put("walksStatus", value.walksStatus)
    putIfNotNull("walksDescription", value.walksDescription)
    put("cohabitants", value.cohabitants)
    put("shelterPermission", value.shelterPermission)
    put("notesStatus", value.notesStatus)
    putIfNotNull("notes", value.notes)
}

private fun MutableMap<String, String>.toDogQuestionnaire() = DogQuestionnaire(
    numberOrName = text("numberOrName").orEmpty(),
    shelterOrPlace = text("shelterOrPlace").orEmpty(),
    breedStatus = text("breedStatus").orEmpty(),
    breedName = text("breedName"),
    resembles = text("resembles"),
    size = text("size").orEmpty(),
    ageStatus = text("ageStatus").orEmpty(),
    ageYears = integer("ageYears"),
    ageMonths = integer("ageMonths"),
    ageSource = text("ageSource").orEmpty(),
    sex = text("sex").orEmpty(),
    sterilizationStatus = text("sterilizationStatus").orEmpty(),
    weightStatus = text("weightStatus").orEmpty(),
    weightKg = decimal("weightKg"),
    bodyConditionStatus = text("bodyConditionStatus").orEmpty(),
    bodyConditionScore = integer("bodyConditionScore"),
    muscleMass = text("muscleMass").orEmpty(),
    neckCircumferenceStatus = text("neckCircumferenceStatus").orEmpty(),
    neckCircumferenceCm = decimal("neckCircumferenceCm"),
    coatLength = text("coatLength").orEmpty(),
    undercoat = text("undercoat").orEmpty(),
    shavedAreasStatus = text("shavedAreasStatus").orEmpty(),
    shavedAreasDetails = text("shavedAreasDetails"),
    observedSigns = list("observedSigns"),
    diagnosesStatus = text("diagnosesStatus").orEmpty(),
    diagnosesDetails = text("diagnosesDetails"),
    housing = text("housing").orEmpty(),
    housingDetails = text("housingDetails"),
    walksStatus = text("walksStatus").orEmpty(),
    walksDescription = text("walksDescription"),
    cohabitants = text("cohabitants").orEmpty(),
    shelterPermission = text("shelterPermission").orEmpty(),
    notesStatus = text("notesStatus").orEmpty(),
    notes = text("notes"),
)

private fun MutableMap<String, String>.toSessionQuestionnaire() = SessionQuestionnaire(
    sessionLabel = text("sessionLabel").orEmpty(),
    operatorName = text("operatorName").orEmpty(),
    activityGroup = text("activityGroup").orEmpty(),
    activityType = text("activityType").orEmpty(),
    activityDetails = text("activityDetails"),
    location = text("location").orEmpty(),
    surface = text("surface").orEmpty(),
    surfaceDetails = text("surfaceDetails"),
    airTemperatureStatus = text("airTemperatureStatus").orEmpty(),
    airTemperatureC = decimal("airTemperatureC"),
    sensorPosition = text("sensorPosition").orEmpty(),
    sensorPositionDetails = text("sensorPositionDetails"),
    collarTightness = text("collarTightness").orEmpty(),
    preMeasurementState = text("preMeasurementState").orEmpty(),
    preMeasurementStateDetails = text("preMeasurementStateDetails"),
    pulseStatus = text("pulseStatus").orEmpty(),
    pulseBpm = integer("pulseBpm"),
    respirationStatus = text("respirationStatus").orEmpty(),
    respirationPerMinute = integer("respirationPerMinute"),
    bodyTemperatureStatus = text("bodyTemperatureStatus").orEmpty(),
    bodyTemperatureC = decimal("bodyTemperatureC"),
    measurementAtUtc = text("measurementAtUtc"),
    videoRequested = text("videoRequested") != "no",
)

private fun MutableMap<String, String>.putIfNotNull(key: String, value: Any?) {
    if (value != null) put(key, value.toString())
}

private fun MutableMap<String, String>.text(key: String): String? = get(key)?.trim()?.takeIf(String::isNotEmpty)

private fun MutableMap<String, String>.integer(key: String): Int? = text(key)?.toIntOrNull()

private fun MutableMap<String, String>.decimal(key: String): Double? = text(key)?.replace(',', '.')?.toDoubleOrNull()

private fun MutableMap<String, String>.list(key: String): List<String> =
    get(key).orEmpty().split(LIST_SEPARATOR).filter(String::isNotEmpty)

private fun MutableMap<String, String>.toggle(key: String, value: String, exclusive: Set<String>) {
    val next = list(key).toMutableSet()
    if (!next.add(value)) {
        next.remove(value)
    } else if (value in exclusive) {
        next.retainAll(setOf(value))
    } else {
        next.removeAll(exclusive)
    }
    put(key, next.joinToString(LIST_SEPARATOR))
}

private fun options(
    language: AppLanguage,
    vararg values: Pair<String, Pair<String, String>>,
): List<Pair<String, String>> = values.map { (value, labels) ->
    value to tr(language, labels.first, labels.second)
}

private fun yesNo(language: AppLanguage) = options(
    language,
    "yes" to ("Yes" to "Да"),
    "no" to ("No" to "Нет"),
)

private fun yesNoUnknown(language: AppLanguage) = yesNo(language) +
    ("unknown" to tr(language, "Unknown", "Неизвестно"))

private fun measuredNotMeasured(language: AppLanguage) = options(
    language,
    "measured" to ("Measured" to "Измерено"),
    "not_measured" to ("Not measured" to "Не измерено"),
)

private fun activityOptions(language: AppLanguage) = options(
    language,
    "walk" to ("Walk" to "Шаг"),
    "trot" to ("Trot" to "Рысь"),
    "gallop" to ("Gallop" to "Галоп"),
    "run" to ("Run" to "Бег"),
    "stairs_up" to ("Stairs up" to "Лестница вверх"),
    "stairs_down" to ("Stairs down" to "Лестница вниз"),
    "jump" to ("Jump" to "Прыжок"),
    "mixed" to ("Mixed" to "Смешанный протокол"),
    "stand" to ("Stand" to "Стоит"),
    "sit" to ("Sit" to "Сидит"),
    "lie" to ("Lie" to "Лежит"),
    "rest" to ("Rest" to "Покой"),
    "sleep" to ("Sleep" to "Сон"),
    "play" to ("Play" to "Игра"),
    "eat" to ("Eat" to "Еда"),
    "drink" to ("Drink" to "Питьё"),
    "scratch" to ("Scratch" to "Чешется"),
    "groom" to ("Groom" to "Уход"),
    "other" to ("Other" to "Другое"),
)

private fun surfaceOptions(language: AppLanguage) = options(
    language,
    "tile" to ("Tile" to "Плитка"),
    "concrete" to ("Concrete" to "Бетон"),
    "wood" to ("Wood" to "Дерево"),
    "laminate" to ("Laminate" to "Ламинат"),
    "carpet" to ("Carpet" to "Ковёр"),
    "bed" to ("Bed" to "Лежанка"),
    "kennel_mat" to ("Kennel mat" to "Коврик"),
    "asphalt" to ("Asphalt" to "Асфальт"),
    "grass" to ("Grass" to "Трава"),
    "soil" to ("Soil" to "Грунт"),
    "gravel" to ("Gravel" to "Гравий"),
    "snow" to ("Snow" to "Снег"),
    "other" to ("Other" to "Другое"),
)

private fun tr(language: AppLanguage, english: String, russian: String): String =
    if (language == AppLanguage.RUSSIAN) russian else english

private const val LIST_SEPARATOR = "\u001F"

@Composable
fun DogQuestionnaireDialog(
    initial: DogQuestionnaire?, required: Boolean, language: AppLanguage,
    onDismiss: () -> Unit, onSave: (DogQuestionnaire) -> Unit,
) {
    val values = rememberSaveable(initial, saver = questionnaireValuesSaver) {
        mutableStateMapOf<String, String>().apply {
            putDog(initial)
            put("species", initial?.species ?: "собака")
            initial?.let {
                putIfNotNull("animalId", it.animalId)
                putIfNotNull("diseaseCategory", it.diseaseCategory)
                putIfNotNull("diseaseCategoryDetails", it.diseaseCategoryDetails)
                putIfNotNull("chronicLameness", it.chronicLameness)
                putIfNotNull("medications", it.medications)
                putIfNotNull("history", it.history)
                putIfNotNull("specialistName", it.specialistName)
            }
        }
    }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    QuestionnaireDialog(
        title = tr(language, "Dog questionnaire", "Анкета собаки"),
        subtitle = tr(language, "ID and name are required. Leave unknown answers blank.", "ID и кличка обязательны. Неизвестные ответы оставьте пустыми."),
        language = language, progress = listOf("animalId", "numberOrName").count { !values[it].isNullOrBlank() } to 2,
        errorCount = errors.size, firstErrorKey = errors.keys.firstOrNull(), canDismiss = !required,
        onDismiss = onDismiss, onSave = {
            val result = values.toDogQuestionnaire().copy(
                schemaVersion = 2, animalId = values.text("animalId"), species = values.text("species"),
                savedAtLocal = java.time.LocalDateTime.now().toString(),
                diseaseCategory = values.text("diseaseCategory"), diseaseCategoryDetails = values.text("diseaseCategoryDetails"),
                chronicLameness = values.text("chronicLameness"), medications = values.text("medications"),
                history = values.text("history"), specialistName = values.text("specialistName"),
                ageYears = values.sheetInt("ageYears"), ageMonths = values.sheetInt("ageMonths"),
                weightKg = values.sheetDouble("weightKg"), bodyConditionScore = values.sheetInt("bodyConditionScore"),
                neckCircumferenceCm = values.sheetDouble("neckCircumferenceCm"),
            )
            errors = result.validate().errors
            if (errors.isEmpty()) onSave(result)
        },
    ) {
        Field(values, "animalId", "Номер/ID животного *", error = errors["animalId"])
        Field(values, "numberOrName", "Кличка *", error = errors["numberOrName"])
        Field(values, "species", "Вид")
        Field(values, "shelterOrPlace", "Приют/лагерь/место сбора")
        Field(values, "breedName", "Порода")
        Field(values, "resembles", "Если метис — похожая порода/тип")
        SheetChoice(values, "size", "Размер", "мелкий", "средний", "крупный", "гигантский")
        Field(values, "ageYears", "Возраст, лет", KeyboardType.Number, errors["ageYears"])
        Field(values, "ageMonths", "Возраст, месяцев", KeyboardType.Number, errors["ageMonths"])
        SheetChoice(values, "ageSource", "Источник данных о возрасте", "документы", "со слов владельца/приюта", "оценка по зубам", "неизвестно")
        SheetChoice(values, "sex", "Пол", "самец", "самка", "неизвестно")
        SheetChoice(values, "sterilizationStatus", "Стерилизован/кастрирован", "да", "нет", "неизвестно")
        Field(values, "weightKg", "Вес, кг", KeyboardType.Decimal, errors["weightKg"])
        SheetChoice(values, "weightStatus", "Как получен вес", "взвешен", "со слов владельца/приюта", "оценён")
        Field(values, "bodyConditionScore", "BCS (1-9)", KeyboardType.Number, errors["bodyConditionScore"])
        SheetChoice(values, "muscleMass", "Оценка мышечной массы", "норма", "лёгкая потеря", "умеренная потеря", "выраженная потеря", "невозможно оценить")
        Field(values, "neckCircumferenceCm", "Обхват шеи, см", KeyboardType.Decimal, errors["neckCircumferenceCm"])
        SheetChoice(values, "coatLength", "Длина шерсти", "короткая", "средняя", "длинная")
        SheetChoice(values, "undercoat", "Подшёрсток", "отсутствует", "умеренный", "плотный")
        SheetChoice(values, "shavedAreasStatus", "Выстриженные/выбритые участки", "да", "нет")
        Field(values, "shavedAreasDetails", "Где и почему (выстрижено)")
        SheetChoice(values, "diagnosesStatus", "Диагнозы поставлены ветеринаром", "есть", "нет", "неизвестно")
        Field(values, "diagnosesDetails", "Диагнозы (описание)", singleLine = false)
        Field(values, "diseaseCategory", "Категория подтверждённого заболевания")
        Field(values, "diseaseCategoryDetails", "Категория — уточнение (другое)")
        SheetChoice(values, "chronicLameness", "Хроническая хромота/проблемы с суставами", "да", "нет", "неизвестно")
        Field(values, "medications", "Регулярные препараты и дозировка", singleLine = false)
        Field(values, "history", "Анамнез/важные комментарии", singleLine = false)
        SheetChoice(values, "housing", "Где живёт", "квартира/дом", "вольер", "помещение приюта", "свободный выгул", "другое")
        Field(values, "housingDetails", "Где живёт — уточнение (другое)")
        Field(values, "walksDescription", "Прогулки (частота и продолжительность)")
        SheetChoice(values, "cohabitants", "Проживание с другими животными", "одна", "с другими собаками", "с другими животными")
        SheetChoice(values, "shelterPermission", "Разрешение на съёмку", "да", "нет", "не требуется")
        Field(values, "notes", "Примечания", singleLine = false)
        Field(values, "specialistName", "ФИО специалиста, проводящего запись")
        Text(questionnaireText("Дата и время сохранения заполняются автоматически.", language))
    }
}

@Composable
fun SessionQuestionnaireDialog(language: AppLanguage, onDismiss: () -> Unit, onSave: suspend (SessionQuestionnaire) -> Unit, initial: SessionQuestionnaire? = null,
    onSaveAtRevision: (suspend (SessionQuestionnaire, Int?) -> Unit)? = null,
    externalError: String? = null,
    videoAllowed: Boolean = true) {
    val values = rememberSaveable(saver = questionnaireValuesSaver) { mutableStateMapOf<String, String>().apply {
        put("sessionDate", java.time.LocalDate.now().toString())
        put("videoRequested", if (videoAllowed) "yes" else "no")
        put("sessionKind", "activity")
        initial?.let { value ->
            val json = org.json.JSONObject(value.toJson())
            json.keys().forEach { key ->
                if (!json.isNull(key)) {
                    val item = json.get(key)
                    put(key, if (item is org.json.JSONArray) (0 until item.length()).joinToString(LIST_SEPARATOR) { item.getString(it) } else item.toString())
                }
            }
            put("videoRequested", if (value.videoRequested) "yes" else "no")
        }
    } }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reviewedRevision by rememberSaveable { mutableStateOf<Int?>(null) }
    var retainedDraft by rememberSaveable { mutableStateOf<String?>(null) }
    QuestionnaireDialog(
        title = tr(language, "Session questionnaire", "Анкета сессии"),
        subtitle = questionnaireText("Собака связывается с выбранной карточкой. Фактические время и длительность записываются автоматически.", language),
        language = language, progress = listOf("sessionLabel", if (values["sessionKind"] == "heart") "sessionKind" else "plannedActivities").count { !values[it].isNullOrBlank() } to 2,
        errorCount = errors.size, firstErrorKey = errors.keys.firstOrNull(), canDismiss = true, onDismiss = onDismiss,
        onSave = {
            val result = values.toSessionQuestionnaire().copy(
                schemaVersion = 2, savedAtLocal = java.time.LocalDateTime.now().toString(),
                sessionDate = values.text("sessionDate"), startTime = values.text("startTime"), endTime = values.text("endTime"),
                durationMinutes = values.sheetDouble("durationMinutes"), plannedActivities = values.list("plannedActivities"),
                surfaces = values.list("surfaces"), lastMedicationAt = values.text("lastMedicationAt"),
                notes = values.text("notes"), specialistName = values.text("specialistName"), airTemperatureC = values.sheetDouble("airTemperatureC"),
                sessionKind = values["sessionKind"] ?: "activity",
            )
            errors = result.validate().errors
            if (errors.isEmpty()) {
                val base = if (initial == null) result else result.copy(animalId = initial.animalId, videoRequested = initial.videoRequested, sessionKind = initial.sessionKind, heartQuestionnaire = values["reviewedHeart"]?.let { com.example.myapplication.data.sessionQuestionnaireFromJson(it).heartQuestionnaire } ?: initial.heartQuestionnaire)
                val candidate = if (videoAllowed) base else base.copy(videoRequested = false)
                if (onSaveAtRevision != null) onSaveAtRevision(candidate, reviewedRevision) else onSave(candidate)
            }
        },
        externalError = externalError,
        onReviewedRevision = { reviewedRevision = it },
        onUseServerAnswers = { latest ->
            retainedDraft = org.json.JSONArray(retainedDraft ?: "[]").put(org.json.JSONObject(values.toMap())).toString()
            values.loadSessionAnswers(latest); values["reviewedHeart"] = latest.toJson()
        },
        onRestoreDraft = if (retainedDraft == null) null else ({
            val stack = org.json.JSONArray(requireNotNull(retainedDraft)); val saved = stack.getJSONObject(stack.length() - 1); values.clear()
            saved.keys().forEach { values[it] = saved.getString(it) }
            retainedDraft = if (stack.length() <= 1) null else org.json.JSONArray((0 until stack.length() - 1).map { stack.getJSONObject(it) }).toString()
        }),
    ) {
        if (initial == null) Choice(values, "sessionKind", "Вид сессии", listOf("activity" to "Активность", "heart" to "Сердцебиение"), onChange = { if (it == "heart") values["videoRequested"] = "no" })
        Field(values, "sessionLabel", "Номер сессии *", error = errors["sessionLabel"])
        if (values["sessionKind"] == "heart") {
            Text(questionnaireText("Анкета по сердцебиению заполняется после записи. Контрольные файлы можно добавить позже.", language))
            if (initial == null && videoAllowed) Choice(values, "videoRequested", "Записывать видео (необязательно)", yesNo(language))
        } else {
        Field(values, "sessionDate", "Дата сессии (ГГГГ-ММ-ДД)", error = errors["sessionDate"])
        Field(values, "startTime", "Время начала записи (ЧЧ:ММ, для импорта)", error = errors["startTime"])
        Field(values, "endTime", "Время окончания (ЧЧ:ММ, для импорта)", error = errors["endTime"])
        Field(values, "durationMinutes", "Фактическая продолжительность, мин (для импорта)", KeyboardType.Decimal, errors["durationMinutes"])
        Field(values, "operatorName", "Кто проводил запись")
        MultiChoice(values, "plannedActivities", "Что планировалось записывать *", listOf("Аллюр/движение", "Активность", "Покой", "Другое").map { it to it }, errors["plannedActivities"], emptySet())
        Field(values, "activityDetails", "Формат записи — уточнение (другое)")
        SheetChoice(values, "location", "Где проходила сессия", "В помещении", "На улице")
        MultiChoice(values, "surfaces", "Поверхность", listOf("Асфальт", "Бетон", "Плитка", "Грунт", "Трава", "Гравий", "Дерево", "Ламинат", "Ковёр", "Снег", "Другое").map { it to it }, errors["surfaces"], emptySet())
        Field(values, "surfaceDetails", "Поверхность — уточнение (другое)")
        Field(values, "airTemperatureC", "Температура воздуха, °C", KeyboardType.Decimal, errors["airTemperatureC"])
        SheetChoice(values, "sensorPosition", "Положение блока", "Снизу на горле", "Сбоку слева", "Сбоку справа", "Сверху на шее", "Другое")
        Field(values, "sensorPositionDetails", "Положение блока — уточнение (другое)")
        SheetChoice(values, "collarTightness", "Насколько затянут ошейник", "Свободно", "Плотно", "Туго")
        SheetChoice(values, "preMeasurementState", "Состояние животного перед записью", "Спало", "Спокойно лежало не менее 10 минут", "Спокойно бодрствовало", "Гуляло", "Бегало/играло", "Другое")
        Field(values, "preMeasurementStateDetails", "Состояние перед записью — уточнение (другое)")
        Field(values, "lastMedicationAt", "Когда последний раз получал препарат (ЧСС/дыхание/активность)")
        Field(values, "notes", "Примечания", singleLine = false)
        Field(values, "specialistName", "ФИО специалиста, проводящего запись")
        if (initial == null && videoAllowed) Choice(values, "videoRequested", "Записывать видео", yesNo(language))
        }
    }
}

@Composable
fun HeartQuestionnaireDialog(language: AppLanguage, initial: HeartQuestionnaire? = null, onDismiss: () -> Unit, onSave: suspend (HeartQuestionnaire) -> Unit,
    onSaveAtRevision: (suspend (HeartQuestionnaire, Int?) -> Unit)? = null) {
    val values = rememberSaveable(saver = questionnaireValuesSaver) { mutableStateMapOf<String, String>().apply {
        initial?.answers?.let { putAll(it) }
        initial?.let { put("acuteHeartRateFactors", it.acuteHeartRateFactors.joinToString(LIST_SEPARATOR)) }
        initial?.knownHeartConditionDetails?.let { put("knownHeartConditionDetails", it) }
        initial?.heartRelevantMedicationDetails?.let { put("heartRelevantMedicationDetails", it) }
        initial?.referenceArtifact?.let { put("referenceArtifact", it) }
        initial?.referenceBpm?.let { put("referenceBpm", it.toString()) }
        initial?.referenceMeasuredAtUtc?.let { put("referenceMeasuredAtUtc", it) }
    } }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reviewedRevision by rememberSaveable { mutableStateOf<Int?>(null) }
    var retainedDraft by rememberSaveable { mutableStateOf<String?>(null) }
    QuestionnaireDialog("Анкета по активности и сердцебиению",
        "Опишите состояние собаки и условия записи. Анкета не ставит диагноз и не изменяет карточку собаки. Заполните все обязательные вопросы; если ответ неизвестен, выберите «Неизвестно».",
        language, HEART_OPTIONS.keys.count { !values[it].isNullOrBlank() } to 6, errors.size, errors.keys.firstOrNull(), true, onDismiss, {
            val result = HeartQuestionnaire(
                answers = values.toMap(), acuteHeartRateFactors = values.list("acuteHeartRateFactors"),
                knownHeartConditionDetails = values.text("knownHeartConditionDetails"),
                heartRelevantMedicationDetails = values.text("heartRelevantMedicationDetails"),
                referenceArtifact = values.text("referenceArtifact"), referenceBpm = values.sheetDouble("referenceBpm"),
                referenceMeasuredAtUtc = values.text("referenceMeasuredAtUtc"),
            )
            errors = result.validate().errors
            if (errors.isEmpty()) { if (onSaveAtRevision != null) onSaveAtRevision(result, reviewedRevision) else onSave(result) }
        }, onReviewedRevision = { reviewedRevision = it }, onUseServerAnswers = { latest ->
            retainedDraft = org.json.JSONArray(retainedDraft ?: "[]").put(org.json.JSONObject(values.toMap())).toString()
            values.clear(); latest.heartQuestionnaire?.let { heart ->
                values.putAll(heart.answers); values["acuteHeartRateFactors"] = heart.acuteHeartRateFactors.joinToString(LIST_SEPARATOR)
                heart.knownHeartConditionDetails?.let { values["knownHeartConditionDetails"] = it }
                heart.heartRelevantMedicationDetails?.let { values["heartRelevantMedicationDetails"] = it }
                heart.referenceArtifact?.let { values["referenceArtifact"] = it }
                heart.referenceBpm?.let { values["referenceBpm"] = it.toString() }
                heart.referenceMeasuredAtUtc?.let { values["referenceMeasuredAtUtc"] = it }
            }
        }, onRestoreDraft = if (retainedDraft == null) null else ({
            val stack = org.json.JSONArray(requireNotNull(retainedDraft)); val saved = stack.getJSONObject(stack.length() - 1); values.clear()
            saved.keys().forEach { values[it] = saved.getString(it) }
            retainedDraft = if (stack.length() <= 1) null else org.json.JSONArray((0 until stack.length() - 1).map { stack.getJSONObject(it) }).toString()
        })) {
        Text(questionnaireText("1. Сведения о сердце и препаратах", language), fontWeight = FontWeight.SemiBold)
        Choice(values, "knownHeartCondition", "1.1. Диагностировал ли ветеринар заболевание сердца или нарушение ритма? *", HEART_OPTIONS.getValue("knownHeartCondition"), errors["knownHeartCondition"])
        if (values["knownHeartCondition"] == "yes") Field(values, "knownHeartConditionDetails", "Какой диагноз? (необязательно)")
        Choice(values, "heartRelevantMedication", "1.2. Принимала ли собака препараты, которые могут повлиять на частоту сердечных сокращений (ЧСС)? *", HEART_OPTIONS.getValue("heartRelevantMedication"), errors["heartRelevantMedication"])
        if (values["heartRelevantMedication"] == "yes") Field(values, "heartRelevantMedicationDetails", "Название препарата и время последнего приёма (необязательно)")
        Text(questionnaireText("Если оператор не знает, не нужно оценивать препарат самостоятельно — выберите «Неизвестно».", language))
        Text(questionnaireText("2. Условия записи", language), fontWeight = FontWeight.SemiBold)
        Choice(values, "preRecordingState", "2.1. Что собака делала непосредственно перед началом записи? *", HEART_OPTIONS.getValue("preRecordingState"), errors["preRecordingState"])
        Choice(values, "actualActivity", "2.2. Что собака фактически делала во время записи? *", HEART_OPTIONS.getValue("actualActivity"), errors["actualActivity"])
        MultiChoice(values, "acuteHeartRateFactors", "2.3. Были ли во время записи факторы, способные изменить ЧСС? *", HEART_OPTIONS.getValue("acuteHeartRateFactors"), errors["acuteHeartRateFactors"], setOf("none", "unknown"))
        Text(questionnaireText("«Нет» и «Неизвестно» нельзя сочетать с другими вариантами.", language))
        Text(questionnaireText("3. Контрольное измерение", language), fontWeight = FontWeight.SemiBold)
        Choice(values, "referenceMethod", "3.1. Есть ли независимое измерение сердцебиения за тот же период? *", HEART_OPTIONS.getValue("referenceMethod"), errors["referenceMethod"])
        when (values["referenceMethod"]) {
            "ecg", "polar_rr" -> Field(values, "referenceArtifact", "Файл или идентификатор контрольной записи и её временная привязка *", error = errors["referenceArtifact"])
            "bpm_only" -> {
                Field(values, "referenceBpm", "Измеренная ЧСС, уд/мин *", KeyboardType.Decimal, errors["referenceBpm"])
                Field(values, "referenceMeasuredAtUtc", "Время измерения (UTC, ГГГГ-ММ-ДДTЧЧ:ММ:ССZ) *", error = errors["referenceMeasuredAtUtc"])
            }
        }
        Text(questionnaireText("Файл ЭКГ или интервалы с временными метками нужны для проверки отдельных ударов. Одиночное значение ЧСС подходит только для сравнения среднего темпа. По ответам анкеты нельзя делать заключение об аритмии.", language))
    }
}

@Composable
private fun SheetChoice(values: MutableMap<String, String>, key: String, title: String, vararg choices: String) {
    val current = values[key].orEmpty()
    val options = (listOf("") + choices.toList() + listOf(current).filter { it.isNotBlank() && it !in choices }).distinct()
    Choice(values, key, title, options.map { it to it.ifBlank { "Не указано" } })
}

private fun MutableMap<String, String>.sheetInt(key: String): Int? = text(key)?.let { it.toIntOrNull() ?: Int.MIN_VALUE }
private fun MutableMap<String, String>.sheetDouble(key: String): Double? = text(key)?.let { it.replace(',', '.').toDoubleOrNull() ?: Double.NaN }

@Composable
fun ReferenceAttachmentDialog(language: AppLanguage, fileName: String, onDismiss: () -> Unit,
    onSave: suspend (com.example.myapplication.data.ArtifactType, com.example.myapplication.data.ReferenceMetadata) -> Unit) {
    val values = rememberSaveable(saver = questionnaireValuesSaver) { mutableStateMapOf<String, String>() }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    QuestionnaireDialog(questionnaireText("Добавить контрольную запись", language), fileName, language, 0 to 4, errors.size, errors.keys.firstOrNull(), true, onDismiss, {
        val metadata = com.example.myapplication.data.ReferenceMetadata(values["source"].orEmpty(), values["startedAtUtc"].orEmpty(),
            values["endedAtUtc"].orEmpty(), values.sheetDouble("offsetFromRecordingMs"), values.text("notes"))
        errors = metadata.validate().errors + if (values["type"] == null) mapOf("type" to "Выберите тип данных") else emptyMap()
        if (errors.isEmpty()) onSave(com.example.myapplication.data.ArtifactType.entries.first { it.value == values["type"] }, metadata)
    }) {
        Choice(values, "type", "Тип данных *", listOf("ecg" to "ЭКГ", "heart_rate" to "ЧСС", "rr" to "Интервалы между ударами (Polar и др.)"), errors["type"])
        Field(values, "source", "Устройство / источник *", error = errors["source"])
        Field(values, "startedAtUtc", "Начало контрольной записи UTC * (ГГГГ-ММ-ДДTЧЧ:ММ:ССZ)", error = errors["startedAtUtc"])
        Field(values, "endedAtUtc", "Окончание контрольной записи UTC *", error = errors["endedAtUtc"])
        Field(values, "offsetFromRecordingMs", "Поправка к UTC таргета, мс (добавляется к его меткам, если известна)", KeyboardType.Decimal, errors["offsetFromRecordingMs"])
        Field(values, "notes", "Временная привязка / примечания", error = errors["notes"], singleLine = false)
        Text(questionnaireText("Оригинальный файл сохраняется без преобразований. ЧСС без интервалов подходит только для сравнения среднего темпа.", language))
    }
}

private fun MutableMap<String, String>.loadSessionAnswers(value: SessionQuestionnaire) {
    clear()
    val json = org.json.JSONObject(value.toJson())
    json.keys().forEach { key ->
        if (!json.isNull(key)) {
            val item = json.get(key)
            put(key, if (item is org.json.JSONArray) (0 until item.length()).joinToString(LIST_SEPARATOR) { item.getString(it) } else item.toString())
        }
    }
    put("videoRequested", if (value.videoRequested) "yes" else "no")
}
