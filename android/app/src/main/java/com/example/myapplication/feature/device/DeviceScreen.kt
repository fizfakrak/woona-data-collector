package com.example.myapplication.feature.device

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.R
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.appStringResource
import com.example.myapplication.protocol.ConnectionQuality
import com.example.myapplication.sync.queueExplanation
import com.example.myapplication.sync.label
import com.example.myapplication.sync.ServerSyncStatus
import com.example.myapplication.sync.mutationBlockExplanation
import com.example.myapplication.sync.ServerSyncUiState
import com.example.myapplication.ui.theme.AppThemeMode
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private const val CHART_FULLSCREEN_DIALOG_TEST_TAG = "chart_fullscreen_dialog"
private const val CHART_FULLSCREEN_CONTROLS_TEST_TAG = "chart_fullscreen_controls"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceOverviewScreen(
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onV2Discovery: () -> Unit = {},
    onV2PreviewConnect: (String) -> Unit = {},
    onV2SdConnect: (String) -> Unit = {},
    onV2PreviewScan: () -> Unit = {},
    onV2StreamToggle: (Int) -> Unit = {},
    onV2LiveStart: () -> Unit = {},
    onV2LiveStop: () -> Unit = {},
    sdMode: Boolean = false,
    sdCapture: V2SdCaptureUiState = V2SdCaptureUiState(),
    onV2SdStart: () -> Unit = {},
    onV2SdStop: () -> Unit = {},
    onV2SdDuration: (Int) -> Unit = {},
    onV2SdVideo: (Boolean) -> Unit = {},
    onV2StorageDownload: (Long) -> Unit = {},
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
    profileContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!uiState.v2DiscoveryRequested) item {
            profileContent()
        }

        if (!uiState.errorMessage.isNullOrBlank()) {
            item {
                ErrorCard(message = uiState.errorMessage)
            }
        }

        if (uiState.connectionQuality in setOf(
                ConnectionQuality.WARNING,
                ConnectionQuality.WEAK,
                ConnectionQuality.LOST,
            )
        ) {
            item {
                ConnectionQualityCard(uiState)
            }
        }

        if (shouldShowSessionSummary(uiState) && !uiState.v2DiscoveryRequested) {
            item {
                SessionSummaryCard(uiState = uiState, compact = false)
            }
        }

        if (!uiState.showCaptureUi && !uiState.isReplayRunning) {
            item {
                DeviceDiscoverySection(
                    uiState = uiState,
                    onStartScan = onStartScan,
                    onConnect = onConnect,
                    onV2PreviewConnect = onV2PreviewConnect,
                    onV2SdConnect = onV2SdConnect,
                    onV2PreviewScan = onV2PreviewScan,
                    showReplayAction = showReplayAction,
                    onReplayRequest = onReplayRequest,
                )
            }
        } else if (!uiState.v2DiscoveryRequested) {
            item { DisconnectCard(onDisconnect = onDisconnect) }
        }

        if (uiState.v2DiscoveryRequested) {
            if (sdMode) item { V2SdOverviewCard(uiState, sdCapture,
                onV2SdStart,onV2SdStop,onV2SdDuration,onV2SdVideo,onV2StreamToggle,
                onV2StorageDownload) }
            else item { V2LivePreviewCard(uiState, onV2LiveStart, onV2LiveStop, onV2StreamToggle) }
            item { DisconnectCard(onDisconnect = onDisconnect, warnAboutSd = true) }
        }

        item {
            EventLogSection(uiState = uiState)
        }
    }
}


@Composable
private fun V2SdOverviewCard(ui: DeviceUiState, sd: V2SdCaptureUiState,
    start: () -> Unit, stop: () -> Unit, duration: (Int) -> Unit,
    video: (Boolean) -> Unit, toggle: (Int) -> Unit,
    download: (Long) -> Unit) {
    Card(colors = appCardColors()) { Column(Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Запись на SD-карту", style = MaterialTheme.typography.titleMedium)
        val supported = ui.v2Capabilities and (1L shl 1) != 0L
        Text(if (supported) "После начала измерения сохраняются на устройстве; видеозапись — на телефоне."
            else "SD отсутствует или не поддерживается прошивкой")
        Text("Режим: ${sd.phase}")
        var durationMinutesText by rememberSaveable { mutableStateOf((sd.durationSeconds / 60).toString()) }
        val durationMinutesValue = durationMinutesText.toIntOrNull()
        val durationInputValid = durationMinutesValue != null && durationMinutesValue in 1..180
        if (!sd.running && !sd.busy) {
            Text("Длительность записи")
            androidx.compose.material3.OutlinedTextField(
                value = durationMinutesText,
                onValueChange = { entered ->
                    val digits = entered.filter { it.isDigit() }.take(3)
                    durationMinutesText = digits
                    digits.toIntOrNull()?.takeIf { it in 1..180 }?.let { minutes ->
                        duration(minutes * 60)
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("v2.sd.duration.minutes"),
                label = { Text("Время, минут") },
                supportingText = {
                    Text(if (durationInputValid) "От 1 до 180 минут · максимум 3 часа"
                        else "Введите целое число от 1 до 180")
                },
                isError = !durationInputValid,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
            Text("Быстрый выбор", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 60).forEach { minutes ->
                    FilterChip(
                        selected = durationInputValid && durationMinutesValue == minutes,
                        onClick = {
                            durationMinutesText = minutes.toString()
                            duration(minutes * 60)
                        },
                        label = { Text("$minutes мин") },
                    )
                }
            }
            Text("Видео и описание сессии выбираются в общей анкете после нажатия «Начать SD».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val labels = listOf(R.string.v2_stream_accel,R.string.v2_stream_gyro,
                R.string.v2_stream_contact,R.string.v2_stream_ambient,R.string.v2_stream_piezo,
                R.string.v2_stream_temp1,R.string.v2_stream_temp2)
            labels.forEachIndexed { index, label ->
                if (ui.v2StreamMask and (1L shl index) != 0L) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = ui.v2SelectedStreamMask and (1L shl index) != 0L,
                            onCheckedChange = { toggle(index+1) })
                        Text(appStringResource(label))
                    }
                }
            }
        }
        if (sd.running) {
            val elapsed = sd.elapsedSeconds
            val total = sd.durationSeconds.toLong()
            fun displayDuration(seconds: Long): String {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60
                return if (hours > 0) {
                    "$hours:${minutes.toString().padStart(2,'0')}:${secs.toString().padStart(2,'0')}"
                } else {
                    "$minutes:${secs.toString().padStart(2,'0')}"
                }
            }
            LinearProgressIndicator(progress = { (elapsed.toFloat()/total).coerceIn(0f,1f) },
                modifier = Modifier.fillMaxWidth())
            Text("Запись: ${displayDuration(elapsed)} / ${displayDuration(total)}")
            Text("Таймер — по времени телефона с момента START_ACK, не таймер датчика.",
                style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = start, enabled = supported && ui.v2Ready && !sd.busy && !sd.running &&
                durationInputValid && ui.v2SelectedStreamMask != 0L && ui.v2DeviceSessionMode == 0) {
                Text("Начать SD")
            }
            OutlinedButton(onClick = stop, enabled = sd.running && !sd.busy) {
                Text("Остановить")
            }
        }
        sd.sessionSequence?.let { sequence ->
            Text("SD #$sequence")
            if (!sd.running && !sd.busy) {
                Button(onClick = { download(sequence) }) { Text("Обновить SD и скачать") }
                Text("Каталог датчика будет обновлён автоматически; загрузка продолжится с первого отсутствующего блока.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (sd.videoStatus.isNotBlank()) Text(sd.videoStatus)
        sd.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Каталог и возобновление загрузки также доступны во вкладке SD-карта.",
            style = MaterialTheme.typography.bodySmall)
    } }
}

/** Explicit V2 diagnostic flow: never confuse preview with a persisted recording. */
@Composable
private fun V2LivePreviewCard(
    uiState: DeviceUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStreamToggle: (Int) -> Unit,
) {
    Card(colors = appCardColors()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(appStringResource(R.string.v2_preview_title), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("v2.preview.title"))
            Text(
                appStringResource(R.string.v2_preview_intro),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(appStringResource(R.string.v2_preview_foreground), style = MaterialTheme.typography.bodySmall)
            val stageLabel = when (uiState.v2PreviewStage) {
                V2PreviewStage.NONE, V2PreviewStage.CONNECTING -> R.string.v2_preview_connecting
                V2PreviewStage.CHECKING -> R.string.v2_preview_checking
                V2PreviewStage.READY -> R.string.v2_preview_ready
                V2PreviewStage.STARTING -> R.string.v2_preview_starting
                V2PreviewStage.RUNNING -> R.string.v2_preview_running
                V2PreviewStage.STOPPING -> R.string.v2_preview_stopping
                V2PreviewStage.STOPPED -> R.string.v2_preview_stopped
                V2PreviewStage.FAILED -> R.string.v2_connection_failed
            }
            Text(appStringResource(stageLabel), modifier = Modifier.testTag("v2.preview.status"))
            uiState.v2DiscoveryError?.let { reason ->
                SelectionContainer { Text(reason, color = MaterialTheme.colorScheme.error) }
            }
            Text(appStringResource(R.string.v2_sd_disconnect_warning), style = MaterialTheme.typography.bodySmall)
            if (uiState.v2StreamMask != 0L) {
                Text(appStringResource(R.string.v2_preview_streams), style = MaterialTheme.typography.labelMedium)
                val streams = listOf(R.string.v2_stream_accel, R.string.v2_stream_gyro,
                    R.string.v2_stream_contact, R.string.v2_stream_ambient, R.string.v2_stream_piezo,
                    R.string.v2_stream_temp1, R.string.v2_stream_temp2)
                streams.forEachIndexed { index, label ->
                    val id = index + 1
                    if (uiState.v2StreamMask and (1L shl index) != 0L) Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = uiState.v2SelectedStreamMask and (1L shl index) != 0L,
                            onCheckedChange = { onStreamToggle(id) },
                            enabled = uiState.v2PreviewStage in setOf(V2PreviewStage.READY, V2PreviewStage.STOPPED),
                            modifier = Modifier.testTag("v2.preview.stream.$id"),
                        )
                        Text(appStringResource(label))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart,
                    enabled = uiState.v2Ready && uiState.v2SelectedStreamMask != 0L &&
                        uiState.v2PreviewStage in setOf(V2PreviewStage.READY, V2PreviewStage.STOPPED),
                    modifier = Modifier.testTag("v2.preview.start")) { Text(appStringResource(R.string.v2_preview_start)) }
                OutlinedButton(onClick = onStop, enabled = uiState.v2PreviewStage == V2PreviewStage.RUNNING,
                    modifier = Modifier.testTag("v2.preview.stop")) { Text(appStringResource(R.string.v2_preview_stop)) }
            }
            Text(appStringResource(R.string.v2_preview_storage_unavailable), style = MaterialTheme.typography.bodySmall)
            if (uiState.v2RejectedFrames > 0) Text(appStringResource(R.string.v2_rejected_frames_explanation), style = MaterialTheme.typography.bodySmall)
                Text(
                    appStringResource(R.string.v2_preview_metrics, uiState.v2PreviewBlocks,
                        uiState.v2SequenceGaps, uiState.v2ChartSkippedBlocks, uiState.v2RejectedFrames),
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }
}

@Composable
fun DeviceChartsScreen(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    onChartPanGesture: (Float) -> Unit,
    onChartZoomGesture: (Float, Float) -> Unit,
    onChartFullscreenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isChartFullscreenVisible = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isChartFullscreenVisible.value) {
        onChartFullscreenChange(isChartFullscreenVisible.value)
    }
    DisposableEffect(Unit) {
        onDispose {
            onChartFullscreenChange(false)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            ErrorCard(message = uiState.errorMessage)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            ChartTab(
                uiState = uiState,
                onSensorSelect = onSensorSelect,
                onChannelSelect = onChannelSelect,
                onChartWindowSelect = onChartWindowSelect,
                onFollowLiveChange = onFollowLiveChange,
                onChartPanLeft = onChartPanLeft,
                onChartPanRight = onChartPanRight,
                onChartZoomIn = onChartZoomIn,
                onChartZoomOut = onChartZoomOut,
                onChartZoomReset = onChartZoomReset,
                onChartPanGesture = onChartPanGesture,
                onChartZoomGesture = onChartZoomGesture,
                onChartFullscreenRequest = { isChartFullscreenVisible.value = true },
            )
        }
    }

    if (isChartFullscreenVisible.value) {
        ChartFullscreenDialog(
            uiState = uiState,
            onSensorSelect = onSensorSelect,
            onChannelSelect = onChannelSelect,
            onChartWindowSelect = onChartWindowSelect,
            onFollowLiveChange = onFollowLiveChange,
            onChartPanLeft = onChartPanLeft,
            onChartPanRight = onChartPanRight,
            onChartZoomIn = onChartZoomIn,
            onChartZoomOut = onChartZoomOut,
            onChartZoomReset = onChartZoomReset,
            onChartPanGesture = onChartPanGesture,
            onChartZoomGesture = onChartZoomGesture,
            onDismiss = { isChartFullscreenVisible.value = false },
        )
    }
}

@Composable
fun DeviceSettingsScreen(
    uiState: DeviceUiState,
    selectedLanguage: AppLanguage,
    selectedThemeMode: AppThemeMode,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    onThemeModeSelect: (AppThemeMode) -> Unit,
    serverSyncState: ServerSyncUiState = ServerSyncUiState(),
    onServerConfigure: () -> Unit = {},
    onServerWifiOnlyChange: (Boolean) -> Unit = {},
    onServerRetryPending: () -> Unit = {},
    onServerRestore: () -> Unit = {},
    onServerCheck: () -> Unit = {},
    onServerStorageCheck: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            item { ErrorCard(message = uiState.errorMessage) }
        }
        item {
            BleTransportProfileCard(
                selectedProfile = uiState.transportProfile,
                onTransportProfileSelect = onTransportProfileSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            ThemeSettingsCard(
                selectedThemeMode = selectedThemeMode,
                onThemeModeSelect = onThemeModeSelect,
            )
        }

        item {
            LanguageSettingsCard(
                selectedLanguage = selectedLanguage,
                onLanguageSelect = onLanguageSelect,
            )
        }

        item {
            com.example.myapplication.storage.CompleteArchiveCard(
                language = selectedLanguage,
                captureActive = uiState.showCaptureUi || uiState.isReplayPreparing || uiState.isReplayRunning ||
                    uiState.videoState == VideoCaptureState.STOPPING,
            )
        }

        item {
            com.example.myapplication.storage.RetirementCleanupCard(
                language = selectedLanguage,
                state = serverSyncState,
                captureActive = uiState.showCaptureUi || uiState.isReplayPreparing || uiState.isReplayRunning || uiState.videoState == VideoCaptureState.STOPPING,
            )
        }

        item {
            ServerSyncSettingsCard(
                state = serverSyncState,
                language = selectedLanguage,
                onConfigure = onServerConfigure,
                onWifiOnlyChange = onServerWifiOnlyChange,
                onRetryPending = onServerRetryPending,
                onRestore = onServerRestore,
                onCheck = onServerCheck,
                onStorageCheck = onServerStorageCheck,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onTabSelect: (DeviceCaptureTab) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    onChartPanGesture: (Float) -> Unit,
    onChartZoomGesture: (Float, Float) -> Unit,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    onDisconnect: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            ErrorCard(message = uiState.errorMessage)
        }

        if (!uiState.showCaptureUi) {
            ScanSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                uiState = uiState,
                onStartScan = onStartScan,
                onConnect = onConnect,
                onTransportProfileSelect = onTransportProfileSelect,
                showReplayAction = showReplayAction,
                onReplayRequest = onReplayRequest,
            )
        } else {
            CaptureTabs(
                selectedTab = uiState.selectedTab,
                onTabSelect = onTabSelect,
            )

            Spacer(Modifier.height(8.dp))

            when (uiState.selectedTab) {
                DeviceCaptureTab.OVERVIEW -> OverviewTab(
                    uiState = uiState,
                    onTransportProfileSelect = onTransportProfileSelect,
                    onDisconnect = onDisconnect,
                    onSharePacketFile = onSharePacketFile,
                    onShareRawFile = onShareRawFile,
                    onShareLogFile = onShareLogFile,
                )

                DeviceCaptureTab.CHART -> ChartTab(
                    uiState = uiState,
                    onSensorSelect = onSensorSelect,
                    onChannelSelect = onChannelSelect,
                    onChartWindowSelect = onChartWindowSelect,
                    onFollowLiveChange = onFollowLiveChange,
                    onChartPanLeft = onChartPanLeft,
                    onChartPanRight = onChartPanRight,
                    onChartZoomIn = onChartZoomIn,
                    onChartZoomOut = onChartZoomOut,
                    onChartZoomReset = onChartZoomReset,
                    onChartPanGesture = onChartPanGesture,
                    onChartZoomGesture = onChartZoomGesture,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        SelectionContainer {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun ConnectionQualityCard(uiState: DeviceUiState) {
    val severe = uiState.connectionQuality in setOf(ConnectionQuality.WEAK, ConnectionQuality.LOST)
    val title = when (uiState.connectionQuality) {
        ConnectionQuality.LOST -> appStringResource(R.string.connection_lost)
        ConnectionQuality.WEAK -> appStringResource(R.string.connection_weak)
        else -> appStringResource(R.string.connection_warning)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (severe) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                appStringResource(
                    R.string.connection_quality_detail,
                    uiState.recentLossPercent,
                    uiState.recentRejectedPercent,
                    uiState.packetSilenceMillis / 1_000.0,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (uiState.connectionQuality == ConnectionQuality.LOST) {
                Text(
                    appStringResource(R.string.connection_lost_data_safe),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DeviceDiscoverySection(
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onV2PreviewConnect: (String) -> Unit,
    onV2SdConnect: (String) -> Unit,
    onV2PreviewScan: () -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    var showConnectionDialog by rememberSaveable { mutableStateOf(false) }
    var connectionMode by rememberSaveable { mutableStateOf("legacy") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Режим сессии", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = connectionMode == "legacy", onClick = { connectionMode = "legacy" },
                    label = { Text("Legacy") }, modifier = Modifier.testTag("session.mode.legacy"))
                FilterChip(selected = connectionMode == "preview", onClick = { connectionMode = "preview" },
                    label = { Text(appStringResource(R.string.v2_preview_title)) }, modifier = Modifier.testTag("session.mode.v2"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = connectionMode == "sd", onClick = { connectionMode = "sd" },
                    label = { Text("SD-запись") }, modifier = Modifier.testTag("session.mode.sd"))
            }
            Text(when (connectionMode) {
                "preview" -> "V2 без сохранения сессии: просмотр потоков и диагностика."
                "sd" -> "Запись на датчик; после загрузки телефон создаёт обычную запись приложения."
                else -> "Legacy: потоковая запись непосредственно на телефон."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    OutlinedButton(onClick = { showConnectionDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Text(appStringResource(R.string.new_connection))
    }
    if (showConnectionDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showConnectionDialog = false }) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Surface {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Режим: " + when (connectionMode) {
                            "preview" -> "V2 предпросмотр"
                            "sd" -> "SD-запись"
                            else -> "Legacy"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (connectionMode != "legacy") {
                            Text(appStringResource(R.string.v2_preview_intro), style = MaterialTheme.typography.bodySmall)
                            Text(appStringResource(R.string.v2_sd_disconnect_warning), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                DeviceDiscoveryDialogContent(
                    uiState, if (connectionMode != "legacy") onV2PreviewScan else onStartScan,
                    onConnect = { address ->
                        showConnectionDialog = false
                        when (connectionMode) {
                            "sd" -> onV2SdConnect(address)
                            "preview" -> onV2PreviewConnect(address)
                            else -> onConnect(address)
                        }
                    },
                    showReplayAction = showReplayAction,
                    onReplayRequest = { showConnectionDialog = false; onReplayRequest() },
                )
                TextButton(onClick = { showConnectionDialog = false }) { Text(appStringResource(android.R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun DeviceDiscoveryDialogContent(
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    val scanButtonLabel = if (uiState.isScanning) {
        appStringResource(R.string.action_scanning)
    } else {
        appStringResource(R.string.action_start_scan)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(appStringResource(R.string.ble_devices_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = appStringResource(R.string.ble_devices_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStartScan,
                enabled = !uiState.isScanning && !uiState.isReplayPreparing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(scanButtonLabel)
            }
            if (showReplayAction) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onReplayRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isReplayPreparing) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(appStringResource(R.string.action_cancel_replay_preparation))
                    } else {
                        Text(appStringResource(R.string.action_replay_bin_debug))
                    }
                }
            }
            if (uiState.isReplayPreparing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            if (uiState.foundDevices.isEmpty()) {
                Text(
                    text = if (uiState.isScanning) {
                        appStringResource(R.string.ble_devices_scanning_empty)
                    } else {
                        appStringResource(R.string.ble_devices_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingTextColor(),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.foundDevices.forEach { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConnect(device.address) },
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = supportingTextColor(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisconnectCard(onDisconnect: () -> Unit, warnAboutSd: Boolean = false) {
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    if (confirmDisconnect) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text(appStringResource(R.string.action_disconnect)) },
        text = { Text(appStringResource(R.string.v2_sd_disconnect_confirm)) },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) {
            Text(appStringResource(R.string.action_disconnect))
        } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(appStringResource(android.R.string.cancel)) } },
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(appStringResource(R.string.connection_title), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { if (warnAboutSd) confirmDisconnect = true else onDisconnect() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(appStringResource(R.string.action_disconnect))
            }
        }
    }
}

@Composable
private fun EventLogSection(uiState: DeviceUiState) {
    if (uiState.diagnosticEvents.isNotEmpty()) {
        DiagnosticEventsCard(uiState = uiState)
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(appStringResource(R.string.latest_events_title), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = appStringResource(R.string.latest_events_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
        }
    }
}

@Composable
private fun ThemeSettingsCard(
    selectedThemeMode: AppThemeMode,
    onThemeModeSelect: (AppThemeMode) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(appStringResource(R.string.settings_theme_title), style = MaterialTheme.typography.labelMedium)
            Text(
                text = appStringResource(R.string.settings_theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppThemeMode.entries.forEachIndexed { index, themeMode ->
                    SegmentedButton(
                        selected = selectedThemeMode == themeMode,
                        onClick = { onThemeModeSelect(themeMode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = AppThemeMode.entries.size,
                        ),
                        label = {
                            Text(appStringResource(themeMode.labelRes))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun appCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

@Composable
private fun supportingTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun LanguageSettingsCard(
    selectedLanguage: AppLanguage,
    onLanguageSelect: (AppLanguage) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(appStringResource(R.string.settings_language_title), style = MaterialTheme.typography.labelMedium)
            Text(
                text = appStringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppLanguage.entries.forEach { language ->
                    LanguageOptionRow(
                        language = language,
                        selected = language == selectedLanguage,
                        onSelect = { onLanguageSelect(language) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageOptionRow(
    language: AppLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Text(
            text = languageDisplayName(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ServerSyncSettingsCard(
    state: ServerSyncUiState,
    language: AppLanguage,
    onConfigure: () -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onRetryPending: () -> Unit,
    onRestore: () -> Unit,
    onCheck: () -> Unit,
    onStorageCheck: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("server.sync.settings"),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (language == AppLanguage.RUSSIAN) "Серверное хранилище" else "Server storage",
                style = MaterialTheme.typography.labelMedium,
            )
            if (!state.mutationsAllowed) Text(state.mutationBlockExplanation(language), style = MaterialTheme.typography.bodySmall)
            Text(if (language == AppLanguage.RUSSIAN) "Очередь всех локальных аккаунтов. Каждая анкета и запись отправляется в свой аккаунт." else "Queue for all local accounts. Each profile and recording uploads to its own account.", style = MaterialTheme.typography.bodySmall)
            Text(state.queueExplanation(language), style = MaterialTheme.typography.bodySmall)
            state.operationMessage?.let { message -> androidx.compose.foundation.text.selection.SelectionContainer { Text(message, style = MaterialTheme.typography.bodySmall) } }
            Text((if (language == AppLanguage.RUSSIAN) "Аккаунт: " else "Account: ") + (state.accountId ?: (if (language == AppLanguage.RUSSIAN) "без аккаунта" else "unassigned")))
            Text(state.connectionCheck.label(language), style = MaterialTheme.typography.bodySmall)
            state.checkedAtUtc?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.connectionError?.let { androidx.compose.foundation.text.selection.SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            OutlinedButton(onClick = onCheck, enabled = state.isConfigured && state.connectionCheck != com.example.myapplication.sync.ConnectionCheck.CHECKING) {
                Text(if (language == AppLanguage.RUSSIAN) "Проверить соединение" else "Check connection")
            }
            com.example.myapplication.sync.ServerStorageSection(state.storage, state.isConfigured, language, onStorageCheck)
            Text(if (language == AppLanguage.RUSSIAN) "Фоновые задачи: ${state.workersRunning} выполняются, ${state.workersQueued} ожидают. Счётчики ниже включают анкеты и сессии." else "Background jobs: ${state.workersRunning} running, ${state.workersQueued} queued. Counts below include profiles and sessions.", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.isConfigured) state.baseUrl else {
                        if (language == AppLanguage.RUSSIAN) "Не настроено" else "Not configured"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onConfigure,
                    enabled = state.connectionEditorAllowed,
                ) {
                    Text(
                        if (state.isConfigured) {
                            if (language == AppLanguage.RUSSIAN) "Переподключить" else "Reconnect"
                        } else {
                            if (language == AppLanguage.RUSSIAN) "Подключить" else "Connect"
                        },
                    )
                }
            }
            ServerSyncSwitchRow(
                title = if (language == AppLanguage.RUSSIAN) "Только по Wi‑Fi" else "Wi-Fi only",
                checked = state.wifiOnly,
                enabled = state.isConfigured && state.networkPolicyAllowed,
                onCheckedChange = onWifiOnlyChange,
            )
            Text(
                text = if (language == AppLanguage.RUSSIAN) {
                    "Ожидают: ${state.pending} · выгружаются: ${state.uploading} · синхронизированы: ${state.synced}"
                } else {
                    "Pending: ${state.pending} · uploading: ${state.uploading} · synced: ${state.synced}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            if (state.failed > 0) {
                Text(
                    text = if (language == AppLanguage.RUSSIAN) {
                        "Ошибки: ${state.failed}"
                    } else {
                        "Failed: ${state.failed}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                state.lastErrorMessage?.let { message ->
                    ErrorCard(com.example.myapplication.sync.serverSyncErrorText(message, language))
                }
            }
            if (state.status == ServerSyncStatus.UPLOADING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(
                onClick = onRetryPending,
                enabled = state.isConfigured && state.mutationsAllowed && state.pending + state.failed > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (language == AppLanguage.RUSSIAN) "Повторить выгрузку" else "Retry uploads")
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = state.isConfigured && state.mutationsAllowed && !state.restoring,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.restoring) {
                        if (language == AppLanguage.RUSSIAN) "Восстановление…" else "Restoring…"
                    } else {
                        if (language == AppLanguage.RUSSIAN) "Восстановить с сервера" else "Restore from server"
                    },
                )
            }
        }
    }
}

@Composable
private fun ServerSyncSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else supportingTextColor(),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun shouldShowSessionSummary(uiState: DeviceUiState): Boolean {
    return uiState.showCaptureUi ||
        uiState.isReplayRunning ||
        uiState.packetsReceived > 0L ||
        uiState.packetsLost > 0L ||
        uiState.packetsRejected > 0L ||
        uiState.fragmentsReceived > 0L
}

@Composable
private fun ScanSection(
    modifier: Modifier,
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    val scanButtonLabel = if (uiState.isScanning) {
        appStringResource(R.string.action_scanning)
    } else {
        appStringResource(R.string.action_start_scan)
    }
    Column(modifier = modifier) {
        Text(appStringResource(R.string.ble_devices_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        BleTransportProfileCard(
            selectedProfile = uiState.transportProfile,
            onTransportProfileSelect = onTransportProfileSelect,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onStartScan,
            enabled = !uiState.isScanning && !uiState.isReplayPreparing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(scanButtonLabel)
        }
        if (showReplayAction) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onReplayRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isReplayPreparing) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(appStringResource(R.string.action_cancel_replay_preparation))
                } else {
                    Text(appStringResource(R.string.action_replay_bin_debug))
                }
            }
        }
        if (uiState.isReplayPreparing) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
            items(uiState.foundDevices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onConnect(device.address) },
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = supportingTextColor(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureTabs(
    selectedTab: DeviceCaptureTab,
    onTabSelect: (DeviceCaptureTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = DeviceCaptureTab.entries.indexOf(selectedTab),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DeviceCaptureTab.entries.forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onTabSelect(tab) },
                text = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun OverviewTab(
    uiState: DeviceUiState,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    onDisconnect: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SessionSummaryCard(uiState = uiState, compact = false)
        }

        item {
            BleTransportProfileCard(
                selectedProfile = uiState.transportProfile,
                onTransportProfileSelect = onTransportProfileSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (uiState.diagnosticEvents.isNotEmpty()) {
            item {
                DiagnosticEventsCard(uiState = uiState)
            }
        }

        item {
            ActionButtons(
                onDisconnect = onDisconnect,
                onSharePacketFile = onSharePacketFile,
                onShareRawFile = onShareRawFile,
                onShareLogFile = onShareLogFile,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BleTransportProfileCard(
    selectedProfile: BleTransportProfile,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(appStringResource(R.string.ble_transport_profile_title), style = MaterialTheme.typography.labelMedium)
            Text(
                text = appStringResource(R.string.ble_transport_profile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            // A one-line marquee hid the start of a long localized label, and an
            // equal-weight row broke words mid-syllable on a narrow phone. Each
            // option keeps its natural width and wraps at word boundaries instead.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BleTransportProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = selectedProfile == profile,
                        onClick = { onTransportProfileSelect(profile) },
                        label = {
                            Text(
                                text = appStringResource(profile.titleRes),
                                maxLines = 2,
                                softWrap = true,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
            Text(
                text = appStringResource(selectedProfile.shortDescriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                selectedProfile.detailedDescriptionResIds.forEach { lineResId ->
                    Text(
                        text = "• ${appStringResource(lineResId)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = supportingTextColor(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartTab(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    onChartPanGesture: (Float) -> Unit,
    onChartZoomGesture: (Float, Float) -> Unit,
    onChartFullscreenRequest: () -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactHeight = maxHeight < 600.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (compactHeight) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChartHeader(
                uiState = uiState,
                onChartFullscreenRequest = onChartFullscreenRequest,
            )
            SensorSelector(
                uiState = uiState,
                onSensorSelect = onSensorSelect,
                onChannelSelect = onChannelSelect,
            )
            ChartControls(
                chart = uiState.chart,
                onChartWindowSelect = onChartWindowSelect,
                onFollowLiveChange = onFollowLiveChange,
                onChartPanLeft = onChartPanLeft,
                onChartPanRight = onChartPanRight,
                onChartZoomIn = onChartZoomIn,
                onChartZoomOut = onChartZoomOut,
                onChartZoomReset = onChartZoomReset,
            )
            ChartViewport(
                chart = uiState.chart,
                onPanGesture = onChartPanGesture,
                onZoomGesture = onChartZoomGesture,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (compactHeight) {
                            Modifier.height(280.dp)
                        } else {
                            Modifier.weight(1f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun ChartHeader(
    uiState: DeviceUiState,
    onChartFullscreenRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChartStatusLine(
            uiState = uiState,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onChartFullscreenRequest,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Fullscreen,
                contentDescription = appStringResource(R.string.chart_enter_fullscreen),
            )
        }
    }
}

@Composable
private fun ChartFullscreenDialog(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    onChartPanGesture: (Float) -> Unit,
    onChartZoomGesture: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            DialogImmersiveModeEffect()
            ChartFullscreenContent(
                uiState = uiState,
                onSensorSelect = onSensorSelect,
                onChannelSelect = onChannelSelect,
                onChartWindowSelect = onChartWindowSelect,
                onFollowLiveChange = onFollowLiveChange,
                onChartPanLeft = onChartPanLeft,
                onChartPanRight = onChartPanRight,
                onChartZoomIn = onChartZoomIn,
                onChartZoomOut = onChartZoomOut,
                onChartZoomReset = onChartZoomReset,
                onChartPanGesture = onChartPanGesture,
                onChartZoomGesture = onChartZoomGesture,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun DialogImmersiveModeEffect() {
    val view = LocalView.current

    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        dialogWindow?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        dialogWindow?.attributes = dialogWindow?.attributes?.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val insetsController = dialogWindow?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
        }

        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun ChartFullscreenContent(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    onChartPanGesture: (Float) -> Unit,
    onChartZoomGesture: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag(CHART_FULLSCREEN_DIALOG_TEST_TAG)
    ) {
        val useLandscapeLayout = maxWidth > maxHeight
        val contentMaxWidth = maxWidth

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChartFullscreenToolbar(
                uiState = uiState,
                modifier = Modifier.padding(end = 48.dp),
            )
            if (useLandscapeLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(contentMaxWidth.coerceAtMost(320.dp))
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ChartFullscreenControls(
                            uiState = uiState,
                            onSensorSelect = onSensorSelect,
                            onChannelSelect = onChannelSelect,
                            onChartWindowSelect = onChartWindowSelect,
                            onFollowLiveChange = onFollowLiveChange,
                            onChartPanLeft = onChartPanLeft,
                            onChartPanRight = onChartPanRight,
                            onChartZoomIn = onChartZoomIn,
                            onChartZoomOut = onChartZoomOut,
                            onChartZoomReset = onChartZoomReset,
                        )
                    }
                    ChartViewport(
                        chart = uiState.chart,
                        onPanGesture = onChartPanGesture,
                        onZoomGesture = onChartZoomGesture,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                ChartFullscreenControls(
                    uiState = uiState,
                    onSensorSelect = onSensorSelect,
                    onChannelSelect = onChannelSelect,
                    onChartWindowSelect = onChartWindowSelect,
                    onFollowLiveChange = onFollowLiveChange,
                    onChartPanLeft = onChartPanLeft,
                    onChartPanRight = onChartPanRight,
                    onChartZoomIn = onChartZoomIn,
                    onChartZoomOut = onChartZoomOut,
                    onChartZoomReset = onChartZoomReset,
                )
                ChartViewport(
                    chart = uiState.chart,
                    onPanGesture = onChartPanGesture,
                    onZoomGesture = onChartZoomGesture,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
        ChartFullscreenCloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 1.dp, end = 1.dp),
        )
    }
}

@Composable
private fun ChartFullscreenToolbar(
    uiState: DeviceUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = appStringResource(R.string.chart_fullscreen_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ChartStatusLine(uiState = uiState)
        }
    }
}

@Composable
private fun ChartFullscreenCloseButton(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(44.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = appStringResource(R.string.chart_exit_fullscreen),
            )
        }
    }
}

@Composable
private fun ChartFullscreenControls(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CHART_FULLSCREEN_CONTROLS_TEST_TAG),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val sensorItems = if (uiState.v2DiscoveryRequested) {
                    listOf("AXL" to 2, "GIR" to 3, "MIC" to 4,
                        "AMB" to 5, "PZ" to 6, "T1" to 7, "T2" to 8).filter { (_, type) ->
                        uiState.v2StreamMask and (1L shl (type - 2)) != 0L
                    }
                } else listOf("T" to 1, "AXL" to 2, "GIR" to 3, "MIC" to 4)
                sensorItems.forEach { (label, sensorType) ->
                    FullscreenControlButton(
                        text = label,
                        selected = uiState.selectedSensorType == sensorType,
                        onClick = { onSensorSelect(sensorType) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val maxChannels = if (uiState.selectedSensorType == 2 || uiState.selectedSensorType == 3) 3 else 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                for (channel in 1..maxChannels) {
                    FullscreenControlButton(
                        text = appStringResource(R.string.chart_channel, channel),
                        selected = uiState.selectedChannel == channel,
                        onClick = { onChannelSelect(channel) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ChartWindowPreset.entries.forEach { preset ->
                    FullscreenControlButton(
                        text = preset.label,
                        selected = uiState.chart.windowPreset == preset,
                        onClick = { onChartWindowSelect(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FullscreenControlButton(
                    text = if (uiState.chart.isFollowingLive) {
                        appStringResource(R.string.chart_follow_live)
                    } else {
                        appStringResource(R.string.chart_follow_history)
                    },
                    selected = uiState.chart.isFollowingLive,
                    onClick = { onFollowLiveChange(!uiState.chart.isFollowingLive) },
                    modifier = Modifier.weight(1.2f),
                )
                FullscreenControlButton(
                    text = "←",
                    enabled = uiState.chart.canPanLeft,
                    onClick = onChartPanLeft,
                    modifier = Modifier.width(34.dp),
                )
                FullscreenControlButton(
                    text = "→",
                    enabled = uiState.chart.canPanRight,
                    onClick = onChartPanRight,
                    modifier = Modifier.width(34.dp),
                )
                FullscreenControlButton(
                    text = "−",
                    enabled = uiState.chart.canZoomOut,
                    onClick = onChartZoomOut,
                    modifier = Modifier.width(34.dp),
                )
                FullscreenControlButton(
                    text = "+",
                    enabled = uiState.chart.canZoomIn,
                    onClick = onChartZoomIn,
                    modifier = Modifier.width(34.dp),
                )
                FullscreenControlButton(
                    text = appStringResource(R.string.chart_y_reset),
                    onClick = onChartZoomReset,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FullscreenControlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        !enabled -> supportingTextColor()
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.height(32.dp),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SessionSummaryCard(
    uiState: DeviceUiState,
    compact: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isReplayRunning) {
                Text(
                    appStringResource(R.string.debug_replay),
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                SummaryMetric(
                    label = appStringResource(R.string.summary_packets_received),
                    value = "${uiState.packetsReceived}",
                    color = MaterialTheme.colorScheme.primary,
                )
                SummaryMetric(
                    label = appStringResource(R.string.summary_gap),
                    value = "${uiState.packetsLost}",
                    color = if (uiState.packetsLost > 0) MaterialTheme.colorScheme.error else supportingTextColor(),
                )
                SummaryMetric(
                    label = appStringResource(R.string.summary_rejected),
                    value = "${uiState.packetsRejected}",
                    color = if (uiState.packetsRejected > 0) MaterialTheme.colorScheme.error else supportingTextColor(),
                )
            }

            if (compact) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = appStringResource(
                        R.string.summary_fragments_compact,
                        uiState.fragmentsReceived,
                        uiState.rawBytesReceived,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = supportingTextColor(),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = appStringResource(
                        R.string.summary_fragments_full,
                        uiState.fragmentsReceived,
                        uiState.rawBytesReceived,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingTextColor(),
                )
                Spacer(Modifier.height(8.dp))
                StatusLine(
                    label = appStringResource(R.string.summary_last_issue),
                    value = uiState.lastPacketIssue ?: appStringResource(R.string.summary_none),
                    valueColor = if (uiState.lastPacketIssue.isNullOrBlank()) supportingTextColor() else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                StatusLine(
                    label = appStringResource(R.string.summary_timer_regressions),
                    value = "${uiState.timerRegressionRejects}",
                    valueColor = if (uiState.timerRegressionRejects > 0) MaterialTheme.colorScheme.error else supportingTextColor(),
                )
                Spacer(Modifier.height(4.dp))
                StatusLine(
                    label = appStringResource(R.string.summary_rejection_breakdown),
                    value = uiState.rejectionBreakdown ?: appStringResource(R.string.summary_none),
                    valueColor = if (uiState.rejectionBreakdown.isNullOrBlank()) {
                        supportingTextColor()
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = color,
        )
    }
}

@Composable
private fun DiagnosticEventsCard(uiState: DeviceUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(appStringResource(R.string.latest_events_title), style = MaterialTheme.typography.labelMedium)
            Text(
                appStringResource(R.string.latest_events_order),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            Spacer(Modifier.height(8.dp))
            val logState = rememberLazyListState()
            LaunchedEffect(uiState.diagnosticEvents.firstOrNull()?.id) {
                if (uiState.diagnosticEvents.isNotEmpty()) {
                    logState.animateScrollToItem(0)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                state = logState,
            ) {
                items(
                    items = uiState.diagnosticEvents,
                    key = { it.id },
                ) { event ->
                    Text(
                        text = event.message,
                        color = diagnosticColor(event.type),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorSelector(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(appStringResource(R.string.chart_sensor_selection), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().then(
                    if (uiState.v2DiscoveryRequested) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
                ),
                horizontalArrangement = if (uiState.v2DiscoveryRequested) Arrangement.spacedBy(4.dp) else Arrangement.SpaceEvenly,
            ) {
                val sensors = if (uiState.v2DiscoveryRequested) {
                    listOf("AXL" to 2, "GIR" to 3, "MIC" to 4,
                        "AMB" to 5, "PZ" to 6, "T1" to 7, "T2" to 8).filter { (_, type) ->
                        uiState.v2StreamMask and (1L shl (type - 2)) != 0L
                    }
                } else listOf("T" to 1, "AXL" to 2, "GIR" to 3, "MIC" to 4)
                sensors.forEach { (name, type) ->
                    FilterChip(
                        selected = uiState.selectedSensorType == type,
                        onClick = { onSensorSelect(type) },
                        label = { Text(name) },
                    )
                }
            }

            val maxChannels = if (uiState.selectedSensorType == 2 || uiState.selectedSensorType == 3) 3 else 1
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (channel in 1..maxChannels) {
                    FilterChip(
                        selected = uiState.selectedChannel == channel,
                        onClick = { onChannelSelect(channel) },
                        label = { Text(appStringResource(R.string.chart_channel, channel)) },
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartControls(
    chart: ChartUiState,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChartWindowPreset.entries.forEach { preset ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = chart.windowPreset == preset,
                        onClick = { onChartWindowSelect(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = chart.isFollowingLive,
                    onClick = { onFollowLiveChange(!chart.isFollowingLive) },
                    label = {
                        Text(
                            if (chart.isFollowingLive) {
                                appStringResource(R.string.chart_follow_live)
                            } else {
                                appStringResource(R.string.chart_follow_history)
                            },
                        )
                    },
                )
                IconButton(
                    onClick = onChartPanLeft,
                    enabled = chart.canPanLeft,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text("←", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartPanRight,
                    enabled = chart.canPanRight,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text("→", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartZoomOut,
                    enabled = chart.canZoomOut,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text("−", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartZoomIn,
                    enabled = chart.canZoomIn,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text("+", textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = onChartZoomReset,
                ) {
                    Text(appStringResource(R.string.chart_y_reset))
                }
            }
        }
    }
}

@Composable
private fun ChartStatusLine(
    uiState: DeviceUiState,
    modifier: Modifier = Modifier,
) {
    val connectionLabel = when {
        uiState.isReplayPreparing -> appStringResource(R.string.status_replay_preparing)
        uiState.isReplayRunning -> appStringResource(R.string.status_replay)
        uiState.isConnected -> appStringResource(R.string.status_connected)
        uiState.isScanning -> appStringResource(R.string.status_scanning)
        else -> appStringResource(R.string.status_idle)
    }
    Text(
        text = appStringResource(
            R.string.chart_status_line,
            connectionLabel,
            uiState.packetsReceived,
            uiState.packetsLost,
            uiState.packetsRejected,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = supportingTextColor(),
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EmptyChartState(
    chart: ChartUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(appStringResource(R.string.chart_empty_state), color = supportingTextColor())
        Text(
            appStringResource(R.string.chart_empty_state_window, chart.windowPreset.label),
            style = MaterialTheme.typography.bodySmall,
            color = supportingTextColor(),
        )
    }
}

@Composable
private fun ActionButtons(
    onDisconnect: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
            ) {
                Text(appStringResource(R.string.action_disconnect))
            }
            Button(
                onClick = onSharePacketFile,
                modifier = Modifier.weight(1f),
            ) {
                Text(appStringResource(R.string.action_send_file))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onShareRawFile,
                modifier = Modifier.weight(1f),
            ) {
                Text(appStringResource(R.string.action_send_raw))
            }
            OutlinedButton(
                onClick = onShareLogFile,
                modifier = Modifier.weight(1f),
            ) {
                Text(appStringResource(R.string.action_send_log))
            }
        }
    }
}

@Composable
private fun diagnosticColor(type: PacketDiagnosticType): Color {
    return when (type) {
        PacketDiagnosticType.INFO -> MaterialTheme.colorScheme.primary
        PacketDiagnosticType.ACCEPTED -> MaterialTheme.colorScheme.tertiary
        PacketDiagnosticType.GAP -> MaterialTheme.colorScheme.secondary
        PacketDiagnosticType.REJECTED -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = supportingTextColor(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
        )
    }
}

@Composable
private fun ChartViewport(
    chart: ChartUiState,
    onPanGesture: (Float) -> Unit,
    onZoomGesture: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (chart.points.isNotEmpty()) {
            DeviceChart(
                chart = chart,
                onPanGesture = onPanGesture,
                onZoomGesture = onZoomGesture,
            )
        } else {
            EmptyChartState(chart = chart)
        }
    }
}

@Composable
private fun DeviceChart(
    chart: ChartUiState,
    onPanGesture: (Float) -> Unit,
    onZoomGesture: (Float, Float) -> Unit,
) {
    val viewportStart = chart.viewportStartMillis ?: return
    val viewportEnd = chart.viewportEndMillis ?: return
    val sessionStart = chart.sessionStartMillis ?: viewportStart
    val colorScheme = MaterialTheme.colorScheme
    val xTicks = buildXAxisTicks(viewportStart, viewportEnd, sessionStart)
    val yTicks = remember(chart.yAxisCenter, chart.yAxisAbsRange) {
        buildYAxisTicks(chart.yAxisCenter, chart.yAxisAbsRange)
    }
    val yAxisWidth = 56.dp
    val xAxisHeight = 26.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .padding(top = 2.dp),
    ) {
        Text(
            text = buildChartSummary(chart, sessionStart, viewportStart, viewportEnd),
            style = MaterialTheme.typography.labelSmall,
            color = supportingTextColor(),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .width(yAxisWidth)
                    .fillMaxSize()
                    .padding(top = 8.dp, end = 6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(bottom = xAxisHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    yTicks.forEach { tick ->
                        Text(
                            text = tick.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = supportingTextColor(),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(chart.viewportStartMillis, chart.viewportEndMillis, chart.yAxisAbsRange) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (zoom != 1f) {
                                    val anchorFractionY = if (size.height > 0f) {
                                        (centroid.y / size.height).coerceIn(0f, 1f)
                                    } else {
                                        0.5f
                                    }
                                    onZoomGesture(zoom, anchorFractionY)
                                }
                                if (pan.x != 0f && size.width > 0f) {
                                    onPanGesture(-(pan.x / size.width))
                                }
                            }
                        }
                        .padding(top = 8.dp, end = 4.dp),
                ) {
                    val width = size.width
                    val height = size.height
                    val minPoint = chart.yAxisCenter - chart.yAxisAbsRange
                    val maxPoint = chart.yAxisCenter + chart.yAxisAbsRange
                    val range = (maxPoint - minPoint).coerceAtLeast(1f)
                    val timeRange = (viewportEnd - viewportStart).coerceAtLeast(1L).toFloat()

                    yTicks.forEach { tick ->
                        val y = height - ((tick.value - minPoint) / range * height)
                        drawLine(
                            color = if (tick.value == 0f) {
                                colorScheme.outline
                            } else {
                                colorScheme.outlineVariant
                            },
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = if (tick.value == 0f) 2f else 1f,
                        )
                    }

                    xTicks.forEach { tick ->
                        val x = ((tick.timeMillis - viewportStart) / timeRange) * width
                        drawLine(
                            color = colorScheme.outlineVariant,
                            start = Offset(x, 0f),
                            end = Offset(x, height),
                            strokeWidth = 1f,
                        )
                    }

                    val path = Path().apply {
                        chart.points.forEachIndexed { index, point ->
                            val x = ((point.timeMillis - viewportStart) / timeRange) * width
                            val clampedValue = point.value.coerceIn(minPoint, maxPoint)
                            val y = height - ((clampedValue - minPoint) / range * height)
                            if (index == 0 || point.startsNewSegment) {
                                moveTo(x, y)
                            } else {
                                lineTo(x, y)
                            }
                        }
                    }

                    drawPath(
                        path = path,
                        color = colorScheme.primary,
                        style = Stroke(width = 3f),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(xAxisHeight),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    xTicks.forEach { tick ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = when (tick) {
                                xTicks.first() -> Alignment.CenterStart
                                xTicks.last() -> Alignment.CenterEnd
                                else -> Alignment.Center
                            },
                        ) {
                            Text(
                                text = tick.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = supportingTextColor(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildYAxisTicks(
    axisCenter: Float,
    axisAbsRange: Float,
): List<AxisTick> {
    val halfRange = axisAbsRange / 2f
    return listOf(
        AxisTick(label = formatAxisValue(axisCenter + axisAbsRange), value = axisCenter + axisAbsRange),
        AxisTick(label = formatAxisValue(axisCenter + halfRange), value = axisCenter + halfRange),
        AxisTick(label = formatAxisValue(axisCenter), value = axisCenter),
        AxisTick(label = formatAxisValue(axisCenter - halfRange), value = axisCenter - halfRange),
        AxisTick(label = formatAxisValue(axisCenter - axisAbsRange), value = axisCenter - axisAbsRange),
    )
}

private fun formatAxisValue(value: Float): String {
    return value.toInt().toString()
}

@Composable
private fun buildChartSummary(
    chart: ChartUiState,
    sessionStart: Long,
    viewportStart: Long,
    viewportEnd: Long,
): String {
    val modeLabel = if (chart.isFollowingLive) {
        appStringResource(R.string.chart_follow_live)
    } else {
        appStringResource(R.string.chart_follow_history)
    }
    val yZoom = (32_768f / chart.yAxisAbsRange).toInt().coerceAtLeast(1)
    return appStringResource(
        R.string.chart_summary,
        modeLabel,
        chart.windowPreset.label,
        yZoom,
        formatRelativeMillis(viewportStart - sessionStart),
        formatRelativeMillis(viewportEnd - sessionStart),
    )
}

@Composable
private fun formatRelativeMillis(durationMillis: Long): String {
    val parts = relativeTimeParts(durationMillis)
    return if (parts.showMinutes) {
        appStringResource(R.string.relative_time_minutes_seconds, parts.minutes, parts.seconds)
    } else {
        appStringResource(R.string.relative_time_seconds, parts.seconds)
    }
}

@Composable
private fun buildXAxisTicks(
    viewportStart: Long,
    viewportEnd: Long,
    sessionStart: Long,
): List<TimeAxisTick> {
    return buildXAxisTickModels(viewportStart, viewportEnd, sessionStart).map { tick ->
        TimeAxisTick(
            timeMillis = tick.timeMillis,
            label = formatRelativeMillis(tick.relativeMillis),
        )
    }
}

internal fun relativeTimeParts(durationMillis: Long): RelativeTimeParts {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return RelativeTimeParts(
        minutes = minutes,
        seconds = seconds,
        showMinutes = minutes > 0L,
    )
}

internal fun buildXAxisTickModels(
    viewportStart: Long,
    viewportEnd: Long,
    sessionStart: Long,
): List<TimeAxisTickModel> {
    val stepCount = 4
    val duration = (viewportEnd - viewportStart).coerceAtLeast(1L)
    return (0..stepCount).map { index ->
        val timeMillis = viewportStart + (duration * index / stepCount)
        TimeAxisTickModel(
            timeMillis = timeMillis,
            relativeMillis = timeMillis - sessionStart,
        )
    }
}

@Composable
private fun languageDisplayName(language: AppLanguage): String {
    return appStringResource(language.displayNameRes)
}

object DeviceScreenEntryPoint {
    @Composable
    fun Overview(
        uiState: DeviceUiState,
        onStartScan: () -> Unit,
        onConnect: (String) -> Unit,
        onDisconnect: () -> Unit,
        onV2Discovery: () -> Unit = {},
        onV2PreviewConnect: (String) -> Unit = {},
        onV2SdConnect: (String) -> Unit = {},
        onV2PreviewScan: () -> Unit = {},
        onV2StreamToggle: (Int) -> Unit = {},
        onV2LiveStart: () -> Unit = {},
        onV2LiveStop: () -> Unit = {},
        sdMode: Boolean = false,
        sdCapture: V2SdCaptureUiState = V2SdCaptureUiState(),
        onV2SdStart: () -> Unit = {},
        onV2SdStop: () -> Unit = {},
        onV2SdDuration: (Int) -> Unit = {},
        onV2SdVideo: (Boolean) -> Unit = {},
        onV2StorageDownload: (Long) -> Unit = {},
        showReplayAction: Boolean,
        onReplayRequest: () -> Unit,
        profileContent: @Composable () -> Unit = {},
        modifier: Modifier = Modifier,
    ) {
        DeviceOverviewScreen(
            uiState = uiState,
            onStartScan = onStartScan,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onV2Discovery = onV2Discovery,
            onV2PreviewConnect = onV2PreviewConnect,
            onV2SdConnect = onV2SdConnect,
            onV2PreviewScan = onV2PreviewScan,
            onV2StreamToggle = onV2StreamToggle,
            onV2LiveStart = onV2LiveStart,
            onV2LiveStop = onV2LiveStop,
            sdMode = sdMode,
            sdCapture = sdCapture,
            onV2SdStart = onV2SdStart,
            onV2SdStop = onV2SdStop,
            onV2SdDuration = onV2SdDuration,
            onV2SdVideo = onV2SdVideo,
            onV2StorageDownload = onV2StorageDownload,
            showReplayAction = showReplayAction,
            onReplayRequest = onReplayRequest,
            profileContent = profileContent,
            modifier = modifier,
        )
    }

    @Composable
    fun Charts(
        uiState: DeviceUiState,
        onSensorSelect: (Int) -> Unit,
        onChannelSelect: (Int) -> Unit,
        onChartWindowSelect: (ChartWindowPreset) -> Unit,
        onFollowLiveChange: (Boolean) -> Unit,
        onChartPanLeft: () -> Unit,
        onChartPanRight: () -> Unit,
        onChartZoomIn: () -> Unit,
        onChartZoomOut: () -> Unit,
        onChartZoomReset: () -> Unit,
        onChartPanGesture: (Float) -> Unit,
        onChartZoomGesture: (Float, Float) -> Unit,
        onChartFullscreenChange: (Boolean) -> Unit = {},
        modifier: Modifier = Modifier,
    ) {
        DeviceChartsScreen(
            uiState = uiState,
            onSensorSelect = onSensorSelect,
            onChannelSelect = onChannelSelect,
            onChartWindowSelect = onChartWindowSelect,
            onFollowLiveChange = onFollowLiveChange,
            onChartPanLeft = onChartPanLeft,
            onChartPanRight = onChartPanRight,
            onChartZoomIn = onChartZoomIn,
            onChartZoomOut = onChartZoomOut,
            onChartZoomReset = onChartZoomReset,
            onChartPanGesture = onChartPanGesture,
            onChartZoomGesture = onChartZoomGesture,
            onChartFullscreenChange = onChartFullscreenChange,
            modifier = modifier,
        )
    }

    @Composable
    fun Settings(
        uiState: DeviceUiState,
        selectedLanguage: AppLanguage,
        selectedThemeMode: AppThemeMode,
        onTransportProfileSelect: (BleTransportProfile) -> Unit,
        onLanguageSelect: (AppLanguage) -> Unit,
        onThemeModeSelect: (AppThemeMode) -> Unit,
        serverSyncState: ServerSyncUiState = ServerSyncUiState(),
        onServerConfigure: () -> Unit = {},
        onServerWifiOnlyChange: (Boolean) -> Unit = {},
        onServerRetryPending: () -> Unit = {},
        onServerRestore: () -> Unit = {},
    onServerCheck: () -> Unit = {},
    onServerStorageCheck: () -> Unit = {},
        modifier: Modifier = Modifier,
    ) {
        DeviceSettingsScreen(
            uiState = uiState,
            selectedLanguage = selectedLanguage,
            selectedThemeMode = selectedThemeMode,
            serverSyncState = serverSyncState,
            onTransportProfileSelect = onTransportProfileSelect,
            onLanguageSelect = onLanguageSelect,
            onThemeModeSelect = onThemeModeSelect,
            onServerConfigure = onServerConfigure,
            onServerWifiOnlyChange = onServerWifiOnlyChange,
            onServerRetryPending = onServerRetryPending,
            onServerRestore = onServerRestore,
            onServerCheck = onServerCheck,
            onServerStorageCheck = onServerStorageCheck,
            modifier = modifier,
        )
    }
}

private data class AxisTick(
    val label: String,
    val value: Float,
)

internal data class RelativeTimeParts(
    val minutes: Long,
    val seconds: Long,
    val showMinutes: Boolean,
)

internal data class TimeAxisTickModel(
    val timeMillis: Long,
    val relativeMillis: Long,
)

private data class TimeAxisTick(
    val timeMillis: Long,
    val label: String,
)
