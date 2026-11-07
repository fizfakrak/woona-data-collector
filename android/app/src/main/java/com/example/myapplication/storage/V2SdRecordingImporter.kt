package com.example.myapplication.storage

import com.example.myapplication.data.CaptureSyncMetadata
import com.example.myapplication.data.RecordingSource
import com.example.myapplication.data.RecordingStatus
import com.example.myapplication.data.SessionQuestionnaire
import com.example.myapplication.data.validate
import com.example.myapplication.data.SyncClockAnchor
import com.example.myapplication.data.VideoSyncMetadata
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.data.sessionQuestionnaireFromJson
import com.example.myapplication.protocol.WoonaV2Codec
import com.example.myapplication.protocol.WoonaV2StorageCodec
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import org.json.JSONObject

/**
 * Imports a fully downloaded V2 SD session into the *ordinary* recording model.
 *
 * STORAGE is only a device->phone transport. The resulting recording is normal
 * source=live and contains the exact same canonical artifact types as streamed
 * capture: packets.bin, packet_timeline.bin, raw_fragments.binlog,
 * diagnostics.log, sync.json and optional video.mp4.
 *
 * Original CRC-validated V2 .frame files remain untouched in the private spool.
 * The server has no V2-storage-specific ingest rule and never needs to know how
 * the phone obtained the measurements.
 */
object V2SdRecordingImporter {
    fun importCompleted(
        root: File,
        deviceIdentity: String,
        sessionSequence: Long,
        database: WoonaDatabase,
    ): String? {
        require(sessionSequence > 0L)
        val store = V2StoredRecordStore(root, deviceIdentity)
        val sessionDir = store.mediaDirectory(sessionSequence)
        val marker = File(sessionDir, "recording-id.txt")
        marker.takeIf(File::isFile)?.readText(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
            val existing = database.recording(id)
            // Old Stage-4.6 imports used a transport-specific server schema and
            // must not be silently treated as canonical. A newly recorded SD
            // session (with questionnaire in its sidecar) receives a new record.
            if (existing != null && existing.questionnaire != null) return id
        }

        val sidecar = File(sessionDir, "time-sync.json")
        if (!sidecar.isFile) return null
        val json = JSONObject(sidecar.readText(Charsets.UTF_8))
        val profileId = json.optString("profileId").trim().takeIf(String::isNotEmpty) ?: return null
        if (database.profile(profileId) == null) return null
        val questionnaireObject = json.optJSONObject("sessionQuestionnaire") ?: return null
        val questionnaire = sessionQuestionnaireFromJson(questionnaireObject.toString())
        require(questionnaire.validate().isValid) { "Stored session questionnaire is incomplete" }

        val recordFiles = sessionDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("record-[0-9]{10}\\.frame")) }
            .sortedBy(File::getName)
        if (recordFiles.isEmpty()) return null

        val startWallMs = json.getLong("startAcknowledgedWallEpochMs").coerceAtLeast(0L)
        val startedAt = Instant.ofEpochMilli(startWallMs)
        val recording = database.beginRecording(
            profileId = profileId,
            source = RecordingSource.LIVE,
            questionnaire = questionnaire,
            now = startedAt,
        )

        try {
            database.markRecording(recording.id)
            val targetDir = database.recordingDirectory(recording.relativeDirectory).also { it.mkdirs() }
            val result = writeCanonicalArtifacts(recordFiles, sessionSequence, targetDir, json)

            val sourceVideo = File(sessionDir, "video.mp4")
            if (sourceVideo.isFile && sourceVideo.length() > 0L) {
                copyAtomically(sourceVideo, File(targetDir, "video.mp4"))
            } else if (questionnaire.videoRequested) {
                database.markCaptureError(
                    recording.id,
                    "camera_failed",
                    json.stringOrNull("videoError")
                        ?: "Video was requested but no completed video file exists",
                )
            }

            database.writeSyncMetadata(
                recording.id,
                syncMetadata(
                    recordingId = recording.id,
                    profileId = profileId,
                    timezone = recording.timezone,
                    json = json,
                    firstTimestampUs = result.firstTimestampUs,
                    lastTimestampUs = result.lastTimestampUs,
                ),
                now = startedAt,
            )
            database.finishRecording(
                recording.id,
                RecordingStatus.COMPLETED,
                finishInstant(json, startWallMs, result.lastWallClockMillis),
            )
            writeMarker(marker, recording.id)
            return recording.id
        } catch (error: Exception) {
            runCatching {
                database.markCaptureError(
                    recording.id,
                    "sd_import_failed",
                    error.message ?: error.javaClass.simpleName,
                )
                database.finishRecording(recording.id, RecordingStatus.FAILED)
            }
            throw error
        }
    }

    private data class CanonicalResult(
        val firstTimestampUs: Long,
        val lastTimestampUs: Long,
        val lastWallClockMillis: Long,
        val packetCount: Long,
    )

    private fun writeCanonicalArtifacts(
        files: List<File>,
        sessionSequence: Long,
        directory: File,
        json: JSONObject,
    ): CanonicalResult {
        val packetTarget = File(directory, "packets.bin")
        val timelineTarget = File(directory, "packet_timeline.bin")
        val rawTarget = File(directory, "raw_fragments.binlog")
        val diagnosticTarget = File(directory, "diagnostics.log")
        val packetTmp = File(directory, "packets.bin.part")
        val timelineTmp = File(directory, "packet_timeline.bin.part")
        val rawTmp = File(directory, "raw_fragments.binlog.part")
        val diagnosticTmp = File(directory, "diagnostics.log.part")
        val mapping = V2SdCanonicalizer.ClockMapping(json)

        var expected = 0L
        var firstTimestampUs: Long? = null
        var lastTimestampUs = -1L
        var lastWallMs = -1L
        try {
            FileOutputStream(packetTmp).use { packetFile ->
                FileOutputStream(timelineTmp).use { timelineFile ->
                    FileOutputStream(rawTmp).use { rawFile ->
                        val packetOut = BufferedOutputStream(packetFile, 128 * 1024)
                        val timelineOut = DataOutputStream(BufferedOutputStream(timelineFile, 128 * 1024))
                        val rawOut = DataOutputStream(BufferedOutputStream(rawFile, 128 * 1024))
                        timelineOut.write(V2SdCanonicalizer.TIMELINE_MAGIC)
                        rawOut.write(V2SdCanonicalizer.RAW_MAGIC)

                        for (file in files) {
                            val frame = requireNotNull(WoonaV2Codec.decodeRaw(file.readBytes())) {
                                "Stored V2 frame CRC invalid: ${file.name}"
                            }
                            val record = requireNotNull(WoonaV2StorageCodec.parseRecord(frame)) {
                                "Stored V2 frame malformed: ${file.name}"
                            }
                            require(record.sessionSequence == sessionSequence && record.recordSequence == expected) {
                                "Stored V2 sequence gap at ${file.name}; expected $expected"
                            }
                            val packet = V2SdCanonicalizer.packet(record)
                            val point = mapping.at(record.timestampUs)
                            packetOut.write(packet)

                            // Same binary format produced by BlePacketTimelineFileStore.
                            timelineOut.writeLong(expected)
                            timelineOut.writeLong(record.recordSequence)
                            timelineOut.writeLong(point.deviceTimerMillis)
                            timelineOut.writeLong(point.wallClockMillis)
                            timelineOut.writeLong(point.phoneMonotonicNs)
                            timelineOut.writeInt(packet.size)

                            // Same binary container produced by BleRawFragmentFileStore.
                            // The normalized complete packet is one lossless fragment.
                            rawOut.writeLong(expected)
                            rawOut.writeLong(point.wallClockMillis)
                            rawOut.writeLong(point.phoneMonotonicNs)
                            rawOut.writeInt(packet.size)
                            rawOut.write(packet)

                            if (firstTimestampUs == null) firstTimestampUs = record.timestampUs
                            val endUs = V2SdCanonicalizer.lastSampleTimestampUs(record)
                            if (endUs > lastTimestampUs) {
                                lastTimestampUs = endUs
                                lastWallMs = mapping.at(endUs).wallClockMillis
                            }
                            expected++
                        }
                        packetOut.flush()
                        timelineOut.flush()
                        rawOut.flush()
                        packetFile.fd.sync()
                        timelineFile.fd.sync()
                        rawFile.fd.sync()
                    }
                }
            }
            require(expected == files.size.toLong() && firstTimestampUs != null && lastTimestampUs >= 0L)

            val firstWall = mapping.at(requireNotNull(firstTimestampUs)).wallClockMillis
            FileOutputStream(diagnosticTmp).use { output ->
                val message = buildString {
                    append(firstWall).append('\t').append(0).append('\t').append("INFO").append('\t')
                    append("Durable device recording imported into canonical live artifacts; ")
                    append("packets=").append(expected).append("; source frames retained locally")
                    append('\n')
                }
                output.write(message.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            moveReplace(packetTmp, packetTarget)
            moveReplace(timelineTmp, timelineTarget)
            moveReplace(rawTmp, rawTarget)
            moveReplace(diagnosticTmp, diagnosticTarget)
            return CanonicalResult(requireNotNull(firstTimestampUs), lastTimestampUs, lastWallMs, expected)
        } finally {
            packetTmp.delete(); timelineTmp.delete(); rawTmp.delete(); diagnosticTmp.delete()
        }
    }

    private fun syncMetadata(
        recordingId: String,
        profileId: String,
        timezone: String,
        json: JSONObject,
        firstTimestampUs: Long,
        lastTimestampUs: Long,
    ): CaptureSyncMetadata {
        val mapping = V2SdCanonicalizer.ClockMapping(json)
        val first = mapping.at(firstTimestampUs)
        val last = mapping.at(lastTimestampUs)
        val startPhoneNs = json.getLong("startAcknowledgedAndroidElapsedNs")
        val startWallMs = json.getLong("startAcknowledgedWallEpochMs")
        val uncertaintyNs = json.optLong("uncertaintyNsAtAnchor", 0L).coerceAtLeast(0L)
        val videoFirstNs = json.longOrNull("videoFirstFrameElapsedNs")
        val video = if (json.optJSONObject("sessionQuestionnaire")?.optBoolean("videoRequested", false) == true) {
            val stopNs = json.longOrNull("videoStopElapsedNs")
            val firstFrameEpochMs = videoFirstNs?.let { first.wallClockMillis + (it - first.phoneMonotonicNs) / 1_000_000.0 }
            VideoSyncMetadata(
                requestedAtUtc = Instant.ofEpochMilli(startWallMs).toString(),
                requestedMonotonicNs = startPhoneNs,
                mediaRecorderStartedMonotonicNs = json.longOrNull("videoRecorderStartedElapsedNs"),
                firstFrameAtUtc = firstFrameEpochMs?.let { Instant.ofEpochMilli(it.toLong()).toString() },
                firstFrameEpochMillis = firstFrameEpochMs,
                firstFrameMonotonicNs = videoFirstNs,
                firstFrameCameraTimestampNs = json.longOrNull("videoFirstCameraTimestampNs"),
                firstFrameCallbackMonotonicNs = json.longOrNull("videoFirstFrameCallbackElapsedNs"),
                firstVideoSamplePtsUs = json.longOrNull("firstVideoSamplePtsUs"),
                offsetFromSensorNs = videoFirstNs?.let { it - first.phoneMonotonicNs },
                cameraTimestampSource = json.stringOrNull("cameraTimestampSource"),
                synchronizationQuality = json.stringOrNull("cameraSynchronizationQuality") ?: "unavailable",
                cameraId = json.stringOrNull("cameraId"),
                width = json.intOrNull("videoWidth"),
                height = json.intOrNull("videoHeight"),
                frameRate = json.intOrNull("videoFrameRate"),
                rotationDegrees = json.intOrNull("rotationDegrees"),
                stoppedAtUtc = stopNs?.let {
                    Instant.ofEpochMilli(startWallMs + (it - startPhoneNs) / 1_000_000L).toString()
                },
                stoppedMonotonicNs = stopNs,
                durationNs = json.longOrNull("videoDurationNs"),
            )
        } else null

        return CaptureSyncMetadata(
            recordingId = recordingId,
            profileId = profileId,
            source = "live",
            timezone = timezone,
            selectedSessionStartUtc = Instant.ofEpochMilli(startWallMs).toString(),
            sensor = SyncClockAnchor(
                event = "v2_time_sync",
                absoluteUtc = Instant.ofEpochMilli(first.wallClockMillis).toString(),
                wallClockEpochMillis = first.wallClockMillis,
                monotonicTimeNs = first.phoneMonotonicNs,
                monotonicClock = "android.elapsedRealtimeNanos",
                samplingUncertaintyNs = uncertaintyNs,
            ),
            firstSensorDeviceTimerMillis = first.deviceTimerMillis,
            lastSensorPacketMonotonicNs = last.phoneMonotonicNs,
            video = video,
        )
    }

    private fun finishInstant(json: JSONObject, startWallMs: Long, lastWallMs: Long): Instant {
        val finishedNs = json.longOrNull("finishedAndroidElapsedNs")
        val startNs = json.longOrNull("startAcknowledgedAndroidElapsedNs")
        val byPhone = if (finishedNs != null && startNs != null) {
            startWallMs + (finishedNs - startNs).coerceAtLeast(0L) / 1_000_000L
        } else lastWallMs
        return Instant.ofEpochMilli(maxOf(byPhone, lastWallMs))
    }

    private fun copyAtomically(source: File, target: File) {
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            source.inputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, 128 * 1024)
                    output.fd.sync()
                }
            }
            moveReplace(tmp, target)
        } finally { tmp.delete() }
    }

    private fun writeMarker(marker: File, recordingId: String) {
        val tmp = File(marker.parentFile, "recording-id.txt.part")
        try {
            FileOutputStream(tmp).use { output ->
                output.write((recordingId + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            moveReplace(tmp, marker)
        } finally { tmp.delete() }
    }

    private fun moveReplace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JSONObject.longOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null
    private fun JSONObject.intOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null
    private fun JSONObject.stringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name).takeIf(String::isNotBlank) else null
}
