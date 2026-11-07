package com.example.myapplication.feature.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.protocol.WoonaV2StorageCodec
import com.example.myapplication.protocol.WoonaV2TimeSync

/** This UI renders state only. No BLE, file or server work is performed in Compose. */
data class V2StorageUiState(
    val connected: Boolean = false,
    val v2Ready: Boolean = false,
    val storageSupported: Boolean = false,
    val busy: Boolean = false,
    val sessions: List<WoonaV2StorageCodec.Session> = emptyList(),
    val localSessions: List<com.example.myapplication.storage.V2StoredRecordStore.LocalSession> = emptyList(),
    val downloadingSession: Long? = null,
    val savedRecords: Long = 0,
    val totalRecords: Long = 0,
    val completed: Boolean = false,
    val error: String? = null,
)

data class V2SdCaptureUiState(
    val phase: String = "idle", // syncing / starting / recording / stopping / completed / failed
    val busy: Boolean = false,
    val running: Boolean = false,
    val sessionSequence: Long? = null,
    val durationSeconds: Int = 120,
    val elapsedSeconds: Long = 0,
    val videoEnabled: Boolean = true,
    val videoStatus: String = "",
    val error: String? = null,
)

/** Null actions mean that the transport path is not wired yet; UI disables the action. */
data class V2StorageActions(
    val refresh: (() -> Unit)? = null,
    val startOnDevice: (() -> Unit)? = null,
    val stopOnDevice: (() -> Unit)? = null,
    val durationChanged: ((Int) -> Unit)? = null,
    val videoChanged: ((Boolean) -> Unit)? = null,
    val download: ((Long) -> Unit)? = null,
    val stopTransfer: (() -> Unit)? = null,
)

@Composable
fun V2StorageScreen(
    state: V2StorageUiState,
    language: AppLanguage,
    actions: V2StorageActions = V2StorageActions(),
    timeSync: WoonaV2TimeSync.Diagnostics = WoonaV2TimeSync.Diagnostics(),
    timeSyncSupported: Boolean = false,
    onTimeSync: (() -> Unit)? = null,
    sdCapture: V2SdCaptureUiState = V2SdCaptureUiState(),
) {
    val ru = language == AppLanguage.RUSSIAN
    fun tr(ruText: String, enText: String) = if (ru) ruText else enText
    val available = state.connected && state.v2Ready && state.storageSupported && !state.busy && !sdCapture.busy && !sdCapture.running
    LazyColumn(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Синхронизация времени (тест F405)", "Time sync (F405 test)"),
                         style = MaterialTheme.typography.titleMedium)
                    Text(tr("Проверка 7 обменов с контроллером по BLE V2.",
                        "Seven BLE V2 clock exchanges with the controller."),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onTimeSync?.invoke() },
                        enabled = state.connected && state.v2Ready && timeSyncSupported &&
                            !timeSync.running && !state.busy && !sdCapture.busy && !sdCapture.running && onTimeSync != null) {
                        Text(tr("Проверить часы", "Check clocks"))
                    }
                    if (timeSync.running) Text("${timeSync.collected} / ${timeSync.total}")
                    if (!timeSyncSupported) Text(tr("Прошивка не объявляет TIME_SYNC (bit 11)",
                        "Firmware does not advertise TIME_SYNC (bit 11)"))
                    timeSync.bestRttMs?.let { rtt ->
                        Text(String.format(java.util.Locale.US,
                            "RTT %.2f ms · ±%.2f ms", rtt, timeSync.uncertaintyMs ?: 0.0))
                    }
                    timeSync.offsetMs?.let { offset ->
                        Text(String.format(java.util.Locale.US, "Android − STM32: %.2f ms", offset))
                    }
                    if (!timeSync.running && timeSync.collected == timeSync.total &&
                        timeSync.bestRttMs != null)
                        Text(tr("Синхронизация завершена", "Sync completed"))
                    timeSync.clockEpochId?.let { epoch ->
                        Text("Clock epoch: 0x" + epoch.toULong().toString(16),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    timeSync.error?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    Text(tr("Проверка транспорта, не калибровка часов или видео.",
                        "Transport check, not yet video alignment."),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Запись на SD и загрузка", "SD recording and transfer"),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(tr("Датчик записывает измерения локально. После окончания сессии " +
                        "данные передаются по BLE V2 и сохраняются на телефоне перед отправкой серверу.",
                        "The sensor records locally. When a session ends, BLE V2 transfers the records " +
                        "to the phone for durable storage and subsequent server upload."),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(when {
                        !state.connected -> tr("Нет подключения к датчику", "Sensor not connected")
                        !state.v2Ready -> tr("Нужно подключение V2", "V2 connection required")
                        !state.storageSupported -> tr("SD недоступна или не объявлена прошивкой", "SD unavailable or not advertised by firmware")
                        state.busy -> tr("Датчик занят — каталог временно недоступен", "Sensor busy — catalog temporarily unavailable")
                        else -> tr("V2 STORAGE готов", "V2 STORAGE ready")
                    }, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { actions.refresh?.invoke() },
                            enabled = available && actions.refresh != null) {
                            Text(tr("Обновить SD", "Refresh SD"))
                        }
                        Text(tr("Запуск записи: Сессия → Основное → Новое подключение → SD-запись",
                            "Start capture: Session → Overview → New connection → SD recording"),
                            style=MaterialTheme.typography.bodySmall)
                    }
                    if (sdCapture.running) Text(tr("Время записи: ", "Recording time: ") +
                        "${sdCapture.elapsedSeconds} / ${sdCapture.durationSeconds} s")
                    if (sdCapture.videoStatus.isNotBlank()) Text(sdCapture.videoStatus)
                    sdCapture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            Text(tr("Сохранено на этом телефоне", "Saved on this phone"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (state.localSessions.isEmpty()) item {
            Text(tr("Пока нет загруженных записей. Записи на SD и в памяти телефона независимы.",
                "No downloaded sessions yet. Device SD and phone storage are independent."))
        }
        items(state.localSessions, key = { "local-"+it.sequence }) { local ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("#${local.sequence} · ${local.records} " + tr("блоков", "records"))
                Text(tr("Видео: ", "Video: ") + (if (local.hasVideo) "✓" else "—"))
                Text("TIME_SYNC: " + (if (local.hasTimeSync) "✓" else "—"))
                Text(tr("Файлы сохранены в памяти приложения, а не в общей папке загрузок.",
                    "Files are in app-private storage, not Downloads."),
                    style=MaterialTheme.typography.bodySmall)
            } }
        }
        item {
            Text(tr("Сессии на датчике", "Sessions on device"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (state.sessions.isEmpty()) {
            item {
                Text(tr("Список пуст. Подключи свободный датчик V2 и обнови каталог.",
                    "No sessions shown. Connect an idle V2 sensor and refresh the catalog."),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.sessions, key = { it.sequence }) { session ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Сессия #", "Session #") + session.sequence,
                        style = MaterialTheme.typography.titleMedium)
                    val stateLabel = when(session.state) {
                        1 -> tr("Ожидает подтверждения сервера", "Pending server confirmation")
                        2 -> tr("Подтверждена сервером", "Server confirmed")
                        else -> tr("Прервана — возможна частичная запись", "Interrupted — partial recording possible")
                    }
                    Text(stateLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(tr("Блоков: ", "Records: ") + session.recordCount +
                        tr(" · потоков: 0x", " · streams: 0x") + session.streamMask.toString(16),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { actions.download?.invoke(session.sequence) },
                        enabled = available && actions.download != null && state.downloadingSession == null) {
                        Text(tr("Скачать / продолжить", "Download / resume"))
                    }
                }
            }
        }
        if (state.downloadingSession != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Передача #", "Transfer #") + state.downloadingSession,
                            style = MaterialTheme.typography.titleMedium)
                        if (state.totalRecords > 0) {
                            LinearProgressIndicator(
                                progress = { (state.savedRecords.toFloat() / state.totalRecords).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("${state.savedRecords} / ${state.totalRecords}")
                        OutlinedButton(onClick = { actions.stopTransfer?.invoke() },
                            enabled = actions.stopTransfer != null) {
                            Text(tr("Остановить передачу", "Stop transfer"))
                        }
                    }
                }
            }
        }
        if (state.completed) item { Text(tr("Передача завершена; данные сохранены на телефоне",
            "Transfer complete; data saved on the phone")) }
        state.error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text(tr("Передача завершена ≠ подтверждена сервером. Запись на SD не удаляется " +
                "и не подтверждается автоматически после BLE-загрузки.",
                "BLE transfer complete ≠ server confirmed. Device data is not deleted or " +
                "acknowledged merely because it reached the phone."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
