package com.example.myapplication

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Folder
import com.example.myapplication.profile.ProfileScreenSection
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.feature.device.ChartWindowPreset
import com.example.myapplication.feature.device.DeviceScreenEntryPoint
import com.example.myapplication.feature.device.DeviceUiState
import com.example.myapplication.feature.device.V2StorageScreen
import com.example.myapplication.feature.device.V2StorageUiState
import com.example.myapplication.feature.device.ExportPhase
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.appStringResource
import com.example.myapplication.protocol.ConnectionQuality
import com.example.myapplication.sync.ServerSyncStatus
import com.example.myapplication.sync.ServerSyncUiState
import com.example.myapplication.ui.theme.AppThemeMode

private enum class AppDestination(
    val titleRes: Int,
    val navLabelRes: Int,
    val icon: ImageVector,
    val supportsExport: Boolean,
) {
    ANIMALS(R.string.nav_animals, R.string.nav_animals, Icons.Rounded.Pets, false),
    SESSION(R.string.nav_session, R.string.nav_session, Icons.Rounded.ShowChart, true),
    VIDEO(R.string.nav_video, R.string.nav_video, Icons.Rounded.Videocam, false),
    RECORDINGS(R.string.nav_records, R.string.nav_records, Icons.Rounded.Folder, true),
    SETTINGS(
        titleRes = R.string.nav_settings,
        navLabelRes = R.string.nav_settings,
        icon = Icons.Rounded.Settings,
        supportsExport = false,
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAppShell(
    uiState: DeviceUiState,
    selectedLanguage: AppLanguage,
    selectedThemeMode: AppThemeMode,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
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
    onDisconnect: () -> Unit,
    onV2Discovery: () -> Unit = {},
    onV2PreviewConnect: (String) -> Unit = {},
    onV2PreviewScan: () -> Unit = {},
    onV2StreamToggle: (Int) -> Unit = {},
    onV2LiveStart: () -> Unit = {},
    onV2LiveStop: () -> Unit = {},
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
    canShareAllFiles: Boolean,
    canSharePacketFile: Boolean,
    canShareCsvFile: Boolean,
    canShareRawFile: Boolean,
    canShareLogFile: Boolean,
    onShareAllFiles: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareCsvFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
    overviewProfileContent: @Composable (ProfileScreenSection) -> Unit = {},
) {
    var selectedDestinationName by rememberSaveable {
        mutableStateOf(AppDestination.ANIMALS.name)
    }
    var isExportSheetVisible by rememberSaveable { mutableStateOf(false) }
    var showSessionCharts by rememberSaveable { mutableStateOf(false) }
    var showSessionStorage by rememberSaveable { mutableStateOf(false) }
    val selectedDestination = AppDestination.entries.firstOrNull { it.name == selectedDestinationName } ?: AppDestination.ANIMALS
    val canExportAnyFile = (canShareAllFiles || canSharePacketFile || canShareCsvFile || canShareRawFile || canShareLogFile) && uiState.exportPhase == null

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = appStringResource(selectedDestination.titleRes),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        if (selectedDestination.supportsExport) {
                            ServerSyncTopStatus(state = serverSyncState)
                            TextButton(
                                onClick = { isExportSheetVisible = true },
                                enabled = canExportAnyFile,
                            ) {
                                Text(appStringResource(R.string.action_export))
                            }
                        }
                        ConnectionStatusBadge(uiState = uiState)
                    },
                )
            },
            bottomBar = {
                AppBottomNavigation(
                    selectedDestination = selectedDestination,
                    onDestinationSelected = { selectedDestinationName = it.name },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                uiState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(12.dp)) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                when (selectedDestination) {
                    AppDestination.RECORDINGS -> Box(Modifier.fillMaxSize().padding(12.dp)) {
                        overviewProfileContent(ProfileScreenSection.RECORDINGS)
                    }
                    AppDestination.ANIMALS, AppDestination.VIDEO -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        overviewProfileContent(when (selectedDestination) {
                            AppDestination.ANIMALS -> ProfileScreenSection.ANIMALS
                            AppDestination.VIDEO -> ProfileScreenSection.VIDEO
                            else -> ProfileScreenSection.RECORDINGS
                        })
                    }
                    AppDestination.SESSION -> Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showSessionCharts && !showSessionStorage, onClick = { showSessionCharts = false; showSessionStorage = false }, label = { Text(appStringResource(R.string.nav_session)) }, modifier = Modifier.testTag("session.summary"))
                            FilterChip(selected = showSessionCharts && !showSessionStorage, onClick = { showSessionCharts = true; showSessionStorage = false }, label = { Text(appStringResource(R.string.nav_charts)) }, modifier = Modifier.testTag("session.charts"))
                            FilterChip(selected = showSessionStorage, onClick = { showSessionStorage = true; showSessionCharts = false }, label = { Text(if (selectedLanguage == AppLanguage.RUSSIAN) "SD-карта" else "SD card") }, modifier = Modifier.testTag("session.storage"))
                        }
                        Box(modifier = Modifier.weight(1f)) {
                        if (showSessionStorage) {
                            V2StorageScreen(
                                state = V2StorageUiState(
                                    connected = uiState.isConnected,
                                    v2Ready = uiState.v2Ready,
                                    storageSupported = (uiState.v2Capabilities and
                                        ((1L shl 7) or (1L shl 8))) ==
                                        ((1L shl 7) or (1L shl 8)),
                                    busy = uiState.v2DeviceSessionMode != 0,
                                ),
                                language = selectedLanguage,
                                // Commands intentionally unavailable until feature controller,
                                // durable record writer and server ACK path are wired.
                            )
                        } else if (!showSessionCharts) DeviceScreenEntryPoint.Overview(
                            uiState = uiState.copy(errorMessage = null),
                            onStartScan = onStartScan,
                            onConnect = onConnect,
                            onDisconnect = onDisconnect,
                            onV2Discovery = onV2Discovery,
                            onV2PreviewConnect = onV2PreviewConnect,
                            onV2PreviewScan = onV2PreviewScan,
                            onV2StreamToggle = onV2StreamToggle,
                            onV2LiveStart = onV2LiveStart,
                            onV2LiveStop = onV2LiveStop,
                            showReplayAction = showReplayAction,
                            onReplayRequest = onReplayRequest,
                            profileContent = { overviewProfileContent(ProfileScreenSection.SESSION) },
                        ) else DeviceScreenEntryPoint.Charts(
                        uiState = uiState.copy(errorMessage = null),
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
                    )
                        }
                    }

                    AppDestination.SETTINGS -> DeviceScreenEntryPoint.Settings(
                        uiState = uiState.copy(errorMessage = null),
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
                    )
                }
                }
            }
        }

        uiState.exportPhase?.let { exportPhase ->
            ExportProgressOverlay(phase = exportPhase)
        }
    }

    if (isExportSheetVisible) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            ModalBottomSheet(
                onDismissRequest = { isExportSheetVisible = false },
            ) {
                ExportSheet(
                    canShareAllFiles = canShareAllFiles,
                    canSharePacketFile = canSharePacketFile,
                    canShareCsvFile = canShareCsvFile,
                    canShareRawFile = canShareRawFile,
                    canShareLogFile = canShareLogFile,
                    onShareAllFiles = {
                        isExportSheetVisible = false
                        onShareAllFiles()
                    },
                    onSharePacketFile = {
                        isExportSheetVisible = false
                        onSharePacketFile()
                    },
                    onShareCsvFile = {
                        isExportSheetVisible = false
                        onShareCsvFile()
                    },
                    onShareRawFile = {
                        isExportSheetVisible = false
                        onShareRawFile()
                    },
                    onShareLogFile = {
                        isExportSheetVisible = false
                        onShareLogFile()
                    },
                )
            }
        }
    }
}

@Composable
private fun ServerSyncTopStatus(state: ServerSyncUiState) {
    if (state.status == ServerSyncStatus.NOT_CONFIGURED) return

    val colorScheme = MaterialTheme.colorScheme
    val icon = when (state.status) {
        ServerSyncStatus.READY -> Icons.Rounded.CloudDone
        ServerSyncStatus.NEEDS_ATTENTION -> Icons.Rounded.CloudOff
        else -> Icons.Rounded.CloudUpload
    }
    val contentColor = when (state.status) {
        ServerSyncStatus.NEEDS_ATTENTION -> colorScheme.error
        ServerSyncStatus.READY -> colorScheme.primary
        else -> colorScheme.tertiary
    }
    val label = appStringResource(serverSyncStatusLabelRes(state.status))

    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.status == ServerSyncStatus.UPLOADING) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = colorScheme.surfaceVariant,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier
                    .padding(7.dp)
                    .size(18.dp),
            )
        }
    }
}

@Composable
private fun ExportProgressOverlay(phase: ExportPhase) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                modifier = Modifier.fillMaxSize(),
            ) {}

            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = appStringResource(R.string.export_progress_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = appStringResource(phase.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun serverSyncStatusLabelRes(status: ServerSyncStatus): Int {
    return when (status) {
        ServerSyncStatus.NOT_CONFIGURED -> R.string.server_sync_not_configured
        ServerSyncStatus.READY -> R.string.server_sync_ready
        ServerSyncStatus.WAITING -> R.string.server_sync_waiting
        ServerSyncStatus.UPLOADING -> R.string.server_sync_uploading
        ServerSyncStatus.NEEDS_ATTENTION -> R.string.server_sync_needs_attention
    }
}

@Composable
private fun ConnectionStatusBadge(uiState: DeviceUiState) {
    val colorScheme = MaterialTheme.colorScheme
    val (label, containerColor, contentColor) = when {
        uiState.isReplayPreparing -> Triple(
            appStringResource(R.string.status_replay_preparing),
            colorScheme.secondaryContainer,
            colorScheme.onSecondaryContainer,
        )

        uiState.isReplayRunning -> Triple(
            appStringResource(R.string.status_replay),
            colorScheme.tertiaryContainer,
            colorScheme.onTertiaryContainer,
        )

        uiState.connectionQuality == ConnectionQuality.LOST -> Triple(
            appStringResource(R.string.connection_lost),
            colorScheme.errorContainer,
            colorScheme.onErrorContainer,
        )

        uiState.connectionQuality == ConnectionQuality.WEAK -> Triple(
            appStringResource(R.string.connection_weak),
            colorScheme.errorContainer,
            colorScheme.onErrorContainer,
        )

        uiState.connectionQuality == ConnectionQuality.WARNING -> Triple(
            appStringResource(R.string.connection_warning),
            colorScheme.tertiaryContainer,
            colorScheme.onTertiaryContainer,
        )

        uiState.connectionQuality == ConnectionQuality.GOOD -> Triple(
            appStringResource(R.string.connection_good),
            colorScheme.primaryContainer,
            colorScheme.onPrimaryContainer,
        )

        uiState.connectionQuality == ConnectionQuality.WAITING -> Triple(
            appStringResource(R.string.connection_waiting),
            colorScheme.secondaryContainer,
            colorScheme.onSecondaryContainer,
        )

        uiState.isConnected -> Triple(
            appStringResource(R.string.status_connected),
            colorScheme.primaryContainer,
            colorScheme.onPrimaryContainer,
        )

        uiState.isScanning -> Triple(
            appStringResource(R.string.status_scanning),
            colorScheme.secondaryContainer,
            colorScheme.onSecondaryContainer,
        )

        else -> Triple(
            appStringResource(R.string.status_idle),
            colorScheme.surfaceVariant,
            colorScheme.onSurfaceVariant,
        )
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AppBottomNavigation(
    selectedDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppDestination.entries.forEach { destination ->
                ShellNavItem(
                    destination = destination,
                    selected = selectedDestination == destination,
                    onClick = { onDestinationSelected(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ShellNavItem(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (selected) colorScheme.secondaryContainer else Color.Transparent
    val titleColor = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.Tab,
                )
                .testTag("tab." + destination.name.lowercase())
                .padding(horizontal = 2.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = appStringResource(destination.navLabelRes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = titleColor,
                )
            }
        }
    }
}

@Composable
private fun ExportSheet(
    canShareAllFiles: Boolean,
    canSharePacketFile: Boolean,
    canShareCsvFile: Boolean,
    canShareRawFile: Boolean,
    canShareLogFile: Boolean,
    onShareAllFiles: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareCsvFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = appStringResource(R.string.export_session_files),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = appStringResource(R.string.export_choose_artifact),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_all_files),
            description = appStringResource(R.string.export_all_files_desc),
            enabled = canShareAllFiles,
            onClick = onShareAllFiles,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_compiled_binary),
            description = appStringResource(R.string.export_compiled_binary_desc),
            enabled = canSharePacketFile,
            onClick = onSharePacketFile,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_channel_csv),
            description = appStringResource(R.string.export_channel_csv_desc),
            enabled = canShareCsvFile,
            onClick = onShareCsvFile,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_raw_data_stream),
            description = appStringResource(R.string.export_raw_data_stream_desc),
            enabled = canShareRawFile,
            onClick = onShareRawFile,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_session_log),
            description = appStringResource(R.string.export_session_log_desc),
            enabled = canShareLogFile,
            onClick = onShareLogFile,
        )
    }
}

@Composable
private fun ExportActionButton(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(8.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

object AppShellEntryPoint {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Render(
        uiState: DeviceUiState,
        selectedLanguage: AppLanguage,
        selectedThemeMode: AppThemeMode,
        onStartScan: () -> Unit,
        onConnect: (String) -> Unit,
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
        onDisconnect: () -> Unit,
        onV2Discovery: () -> Unit = {},
        onV2PreviewConnect: (String) -> Unit = {},
        onV2PreviewScan: () -> Unit = {},
        onV2StreamToggle: (Int) -> Unit = {},
        onV2LiveStart: () -> Unit = {},
        onV2LiveStop: () -> Unit = {},
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
        canShareAllFiles: Boolean,
        canSharePacketFile: Boolean,
        canShareCsvFile: Boolean,
        canShareRawFile: Boolean,
        canShareLogFile: Boolean,
        onShareAllFiles: () -> Unit,
        onSharePacketFile: () -> Unit,
        onShareCsvFile: () -> Unit,
        onShareRawFile: () -> Unit,
        onShareLogFile: () -> Unit,
        showReplayAction: Boolean,
        onReplayRequest: () -> Unit,
        overviewProfileContent: @Composable (ProfileScreenSection) -> Unit = {},
    ) {
        DeviceAppShell(
            uiState = uiState,
            selectedLanguage = selectedLanguage,
            selectedThemeMode = selectedThemeMode,
            serverSyncState = serverSyncState,
            onStartScan = onStartScan,
            onConnect = onConnect,
            onTransportProfileSelect = onTransportProfileSelect,
            onLanguageSelect = onLanguageSelect,
            onThemeModeSelect = onThemeModeSelect,
            onServerConfigure = onServerConfigure,
            onServerWifiOnlyChange = onServerWifiOnlyChange,
            onServerRetryPending = onServerRetryPending,
            onServerRestore = onServerRestore,
            onServerCheck = onServerCheck,
            onServerStorageCheck = onServerStorageCheck,
            onDisconnect = onDisconnect,
            onV2Discovery = onV2Discovery,
            onV2PreviewConnect = onV2PreviewConnect,
            onV2PreviewScan = onV2PreviewScan,
            onV2StreamToggle = onV2StreamToggle,
            onV2LiveStart = onV2LiveStart,
            onV2LiveStop = onV2LiveStop,
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
            canShareAllFiles = canShareAllFiles,
            canSharePacketFile = canSharePacketFile,
            canShareCsvFile = canShareCsvFile,
            canShareRawFile = canShareRawFile,
            canShareLogFile = canShareLogFile,
            onShareAllFiles = onShareAllFiles,
            onSharePacketFile = onSharePacketFile,
            onShareCsvFile = onShareCsvFile,
            onShareRawFile = onShareRawFile,
            onShareLogFile = onShareLogFile,
            showReplayAction = showReplayAction,
            onReplayRequest = onReplayRequest,
            overviewProfileContent = overviewProfileContent,
        )
    }
}
