package com.example.myapplication.storage

import com.example.myapplication.protocol.WoonaV2TimeSync
import com.example.myapplication.data.SessionQuestionnaire
import com.example.myapplication.data.validate
import com.example.myapplication.data.toJson
import com.example.myapplication.video.AndroidVideoStartInfo
import com.example.myapplication.video.AndroidVideoStopInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * V2 SD + phone-video sidecar, independent of Legacy capture/recording DB.
 * The original record timestamps are never rewritten. All camera times use
 * Android elapsedRealtimeNanos; the mapping is usable ONLY within the same
 * MCU boot epoch. The F405 test epoch is deliberately NOT unique across boots.
 */
class V2SdSessionMedia(
    root: File,
    deviceIdentity: String,
    val sequence: Long,
    val sessionId: Long,
    val streamMask: Long,
    val durationSeconds: Int,
    val clock: WoonaV2TimeSync.Sample,
    val startConfirmedPhoneNs: Long,
    val startConfirmedEpochMs: Long,
    val profileId: String,
    val questionnaire: SessionQuestionnaire,
) {
    private val sessionDirectory = V2StoredRecordStore(root, deviceIdentity).mediaDirectory(sequence)
    val videoFile = File(sessionDirectory, "video.mp4")
    private val sidecar = File(sessionDirectory, "time-sync.json")
    init {
        require(!sidecar.exists() && !videoFile.exists()) {
            "SD/video sidecar already exists; refusing to overwrite a previous session"
        }
    }
    val videoRequested: Boolean = questionnaire.videoRequested
    init { require(questionnaire.validate().isValid) { "V2 SD requires a valid session questionnaire" } }
    private val document = JSONObject()
        .put("schema", "woona.v2.sd-video-sync.v1")
        .put("source", "stm32f405-emulator-test")
        .put("storageSessionSeq", sequence)
        .put("profileId", profileId)
        .put("sessionQuestionnaire", JSONObject(questionnaire.toJson()))
        .put("bootLocalSessionId", sessionId)
        .put("streamMask", streamMask)
        .put("durationSeconds", durationSeconds)
        .put("clockEpochId", java.lang.Long.toUnsignedString(clock.clockEpochId))
        .put("clockEpochUniqueAcrossReboots", false)
        .put("deviceAnchorUs", clock.deviceMidUs)
        .put("androidAnchorElapsedNs", clock.phoneMidNs)
        .put("correctedRttNs", clock.correctedRttNs)
        .put("uncertaintyNsAtAnchor", clock.correctedRttNs / 2)
        .put("startAcknowledgedAndroidElapsedNs", startConfirmedPhoneNs)
        .put("startAcknowledgedWallEpochMs", startConfirmedEpochMs)
        .put("videoRequested", videoRequested)
        .put("videoFileName", "video.mp4")
        .put("state", "started")

    /** V2 stored timestamps map to Android phone clock using this affine mapping. */
    fun androidElapsedNsForStoredTimestamp(timestampUs: Long): Long = clock.toAndroidElapsedNs(timestampUs)

    /** Tells postprocessing when this SD sample occurred relative to video frame zero. */
    fun sdSampleRelativeToVideoFirstFrameMs(timestampUs: Long): Double? {
        val videoNs = document.optLong("videoFirstFrameElapsedNs", -1)
        if (videoNs < 0) return null
        return (androidElapsedNsForStoredTimestamp(timestampUs) - videoNs) / 1_000_000.0
    }

    @Synchronized fun writeStarted() = writeAtomic()

    @Synchronized fun videoStarted(info: AndroidVideoStartInfo) {
        document.put("videoFirstFrameElapsedNs", info.firstFrameMonotonicNs)
            .put("videoRecorderStartedElapsedNs", info.mediaRecorderStartedMonotonicNs)
            .put("videoFirstCameraTimestampNs", info.firstFrameCameraTimestampNs)
            .put("videoFirstFrameCallbackElapsedNs", info.firstFrameCallbackMonotonicNs)
            .put("cameraTimestampSource", info.cameraTimestampSource)
            .put("cameraSynchronizationQuality", info.synchronizationQuality)
            .put("cameraId", info.cameraId)
            .put("videoFrameRate", info.frameRate)
            .put("videoWidth", info.width)
            .put("videoHeight", info.height)
            .put("rotationDegrees", info.rotationDegrees)
        writeAtomic()
    }

    @Synchronized fun videoStopped(info: AndroidVideoStopInfo?) {
        info?.startInfo?.let { videoStarted(it) }
        document.put("videoStopElapsedNs", info?.stoppedMonotonicNs ?: JSONObject.NULL)
            .put("videoDurationNs", info?.durationNs ?: JSONObject.NULL)
            .put("firstVideoSamplePtsUs", info?.firstVideoSamplePtsUs ?: JSONObject.NULL)
            .put("videoFileBytes", if (videoFile.exists()) videoFile.length() else 0)
        writeAtomic()
    }

    @Synchronized fun videoError(message: String) {
        document.put("videoError", message)
        writeAtomic()
    }

    @Synchronized fun finish(state: String, message: String? = null) {
        document.put("state", state)
            .put("finishedAndroidElapsedNs", android.os.SystemClock.elapsedRealtimeNanos())
        if (message != null) document.put("message", message)
        writeAtomic()
    }

    @Synchronized private fun writeAtomic() = writeDocument(sidecar, document)

    companion object {
        /** Called after the original V2 frame was fsync'd by V2StoredRecordStore. */
        fun firstStoredBlockOffsetMs(root: File, identity: String,
                                     sessionSequence: Long, timestampUs: Long): Double? {
            val file = File(V2StoredRecordStore(root, identity).mediaDirectory(sessionSequence),
                "time-sync.json")
            if (!file.isFile) return null // Older/foreign SD session: no clock anchor.
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val existing = json.optLong("firstStoredRecordTimestampUs", -1L)
            if (existing >= 0L && existing <= timestampUs) {
                val offset = json.optDouble("firstStoredMinusVideoFirstMs", Double.NaN)
                return offset.takeIf { !it.isNaN() }
            }
            val deviceMidUs = json.getLong("deviceAnchorUs")
            val phoneMidNs = json.getLong("androidAnchorElapsedNs")
            val phoneNs = Math.addExact(phoneMidNs,
                Math.multiplyExact(Math.subtractExact(timestampUs, deviceMidUs), 1000L))
            json.put("firstStoredRecordTimestampUs", timestampUs)
                .put("firstStoredRecordAndroidElapsedNs", phoneNs)
            val videoNs = json.optLong("videoFirstFrameElapsedNs", -1L)
            val offset = if (videoNs >= 0L) (phoneNs - videoNs) / 1_000_000.0 else null
            if (offset != null) json.put("firstStoredMinusVideoFirstMs", offset)
            writeDocument(file, json)
            return offset
        }

        private fun writeDocument(file: File, json: JSONObject) {
            Files.createDirectories(file.parentFile.toPath())
            val tmp = File.createTempFile(".sync-", ".tmp", file.parentFile)
            try {
                FileOutputStream(tmp).use { output ->
                    output.write(json.toString(2).toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { tmp.delete() }
        }
    }
}
