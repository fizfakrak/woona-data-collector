package com.example.myapplication.feature.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.storage.V2LocalSessionIndex
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Old/test V2 SD spools that have not yet been promoted into normal recordings. */
@Composable
fun V2SdLocalHistory(
    root: File,
    availableOnDevice: Set<Long> = emptySet(),
    downloadingSession: Long? = null,
    onDownload: (Long) -> Unit = {},
) {
    var entries by remember(root.absolutePath) {
        mutableStateOf<List<V2LocalSessionIndex.Entry>>(emptyList())
    }
    var loading by remember(root.absolutePath) { mutableStateOf(true) }
    LaunchedEffect(root.absolutePath, downloadingSession) {
        loading = true
        entries = withContext(Dispatchers.IO) { V2LocalSessionIndex.scan(root) }
        loading = false
    }

    // New Stage-4.6 sessions are promoted into the ordinary recording DB after
    // download. Keep only old/unpromoted spools here to avoid duplicate cards.
    val pending = entries.filter { it.recordingId == null }
    if (!loading && pending.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Локальные V2 SD-записи",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Здесь остаются старые или ещё не добавленные в общую историю SD-сессии. " +
                        "Новые полностью загруженные сессии автоматически переходят в обычные записи.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) Text("Поиск сохранённых сессий…")
            }
        }

        if (!loading) pending.take(25).forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "SD #${entry.sequence}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (entry.recordCount == 0) {
                        Text(
                            "Данные датчиков не скачаны",
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text("${entry.recordCount} блоков · ${formatBytes(entry.sensorBytes)}")
                    }
                    Text(
                        "Видео: ${if (entry.hasVideo) "сохранено" else "нет"} · " +
                            "синхронизация: ${if (entry.hasTimeSync) "есть" else "нет"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.totalBytes > entry.sensorBytes) {
                        Text(
                            "Всего локально: ${formatBytes(entry.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry.sequence in availableOnDevice) {
                        Row(Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { onDownload(entry.sequence) },
                                enabled = downloadingSession == null,
                            ) {
                                Text(if (entry.recordCount == 0) "Скачать с датчика" else "Продолжить загрузку")
                            }
                        }
                    } else if (entry.recordCount == 0) {
                        Text(
                            "Эта сессия сейчас не найдена в каталоге подключённого датчика.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (!loading && pending.size > 25) {
            Text("Показаны первые 25 из ${pending.size}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes Б"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return String.format(java.util.Locale.US, "%.1f КБ", kib)
    return String.format(java.util.Locale.US, "%.1f МБ", kib / 1024.0)
}
