package com.example.myapplication.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant
import java.security.MessageDigest

data class ProfileSyncRecord(
    val dogId: String,
    val dogName: String,
    val expectedRevision: Long,
    val profileVersionId: String,
    val schemaVersion: Int,
    val validationState: String,
    val questionnaireJson: String,
    val contentSha256: String,
    val clientCreatedAtUtc: String,
)

data class RecordingSyncClock(
    val schemaVersion: Int,
    val sessionZeroAtUtc: String,
    val sessionZeroWallClockMs: Long,
    val sessionZeroMonotonicNs: Long,
    val sessionZeroUncertaintyNs: Long,
    val firstSensorPacketMonotonicNs: Long?,
    val lastSensorPacketMonotonicNs: Long?,
    val videoRequestedMonotonicNs: Long?,
    val mediaRecorderStartedMonotonicNs: Long?,
    val videoFirstFrameMonotonicNs: Long?,
    val videoFirstFrameCameraTimestampNs: Long?,
    val videoOffsetFromSensorNs: Long?,
    val cameraTimestampSource: String?,
    val cameraClockQuality: String,
    val sensorClockQuality: String,
    val overallSyncQuality: String,
    val calibrationOffsetNs: Long,
    val estimatedDriftPpm: Double?,
    val firstSensorDeviceTimerMillis: Long? = null,
    val videoFirstFrameCallbackMonotonicNs: Long? = null,
    val videoFirstSamplePtsUs: Long? = null,
    val monotonicClock: String = "android.elapsedRealtimeNanos",
) {
    init { require(monotonicClock in setOf("android.elapsedRealtimeNanos", "ios.CMClock.hostTime")) { "Unsupported monotonic clock domain: $monotonicClock" } }
}

data class ArtifactSyncRecord(
    val id: String,
    val type: String,
    val fileName: String,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedBytes: Long,
    val uploadState: String,
    val clientCreatedAtUtc: String,
    val referenceMetadataJson: String? = null,
)

data class RecordingSyncRecord(
    val id: String,
    val dogId: String,
    val profileVersionId: String,
    val source: String,
    val captureStatus: String,
    val sessionLabel: String,
    val questionnaireSchemaVersion: Int,
    val questionnaireValidationState: String,
    val sessionQuestionnaireJson: String,
    val videoRequested: Boolean,
    val startedAtUtc: String,
    val endedAtUtc: String?,
    val timezone: String,
    val sensorHardwareId: String?,
    val appVersion: String,
    val protocolVersion: String?,
    val captureErrorCode: String?,
    val captureErrorMessage: String?,
    val profile: ProfileSyncRecord,
    val sync: RecordingSyncClock,
    val artifacts: List<ArtifactSyncRecord>,
)

data class ServerSyncCounts(
    val pending: Int,
    val uploading: Int,
    val synced: Int,
    val failed: Int,
    val lastErrorMessage: String? = null,
)

data class RemoteProfileVersion(
    val id: String,
    val schemaVersion: Int,
    val validationState: String,
    val questionnaireJson: String,
    val contentSha256: String,
    val clientCreatedAtUtc: String,
    val supersededAtUtc: String? = null,
)

data class RemoteDog(
    val id: String,
    val numberOrName: String,
    val revision: Long,
    val profile: RemoteProfileVersion,
    val profileVersions: List<RemoteProfileVersion> = listOf(profile),
    val accountId: String? = null,
)

data class RemoteArtifact(
    val id: String,
    val type: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storageStatus: String,
    val referenceMetadataJson: String? = null,
)

data class RemoteRecording(
    val id: String,
    val dogId: String,
    val profile: RemoteProfileVersion,
    val source: String,
    val captureStatus: String,
    val startedAtUtc: String,
    val endedAtUtc: String?,
    val timezone: String,
    val sessionLabel: String,
    val questionnaireSchemaVersion: Int,
    val questionnaireValidationState: String,
    val sessionQuestionnaireJson: String,
    val videoRequested: Boolean,
    val sensorHardwareId: String?,
    val appVersion: String,
    val protocolVersion: String?,
    val captureErrorCode: String?,
    val captureErrorMessage: String?,
    val receiptSha256: String?,
    val verifiedAtUtc: String?,
    val sync: RecordingSyncClock,
    val artifacts: List<RemoteArtifact>,
    val questionnaireRevision: Int = 1,
    val accountId: String? = null,
)

data class RestoreResult(
    val dogs: Int,
    val recordings: Int,
    val artifacts: Int,
    val conflicts: Int,
    val recordingConflicts: Int = 0,
)

fun WoonaDatabase.pendingProfileVersionIds(): List<String> =
    readableDatabase.query(
        "dog_profile_versions",
        arrayOf("id"),
        "server_sync_state IN ('pending','retryable_error')",
        null,
        null,
        null,
        "client_created_at_utc,id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

fun WoonaDatabase.profileSyncRecord(profileVersionId: String): ProfileSyncRecord? =
    readableDatabase.rawQuery(
        """
        SELECT d.id,d.number_or_name,d.server_revision,v.id,v.schema_version,
               v.validation_state,v.questionnaire_json,v.content_sha256,v.client_created_at_utc
        FROM dog_profile_versions v JOIN dogs d ON d.id=v.dog_id
        WHERE v.id=?
        """.trimIndent(),
        arrayOf(profileVersionId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        ProfileSyncRecord(
            dogId = cursor.getString(0),
            dogName = cursor.getString(1),
            expectedRevision = cursor.getLong(2),
            profileVersionId = cursor.getString(3),
            schemaVersion = cursor.getInt(4).coerceAtLeast(1),
            validationState = cursor.getString(5),
            questionnaireJson = cursor.getString(6),
            contentSha256 = cursor.getString(7),
            clientCreatedAtUtc = cursor.getString(8),
        )
    }

fun WoonaDatabase.markProfileUploading(profileVersionId: String) {
    writableDatabase.execSQL(
        """
        UPDATE dog_profile_versions
        SET server_sync_state='uploading',sync_attempt_count=sync_attempt_count+1,
            last_error_code=NULL,last_error_message=NULL
        WHERE id=?
        """.trimIndent(),
        arrayOf(profileVersionId),
    )
}

fun WoonaDatabase.markProfileSynced(
    profileVersionId: String,
    dogId: String,
    serverRevision: Long,
    now: Instant = Instant.now(),
) {
    writableDatabase.beginTransaction()
    try {
        writableDatabase.update(
            "dog_profile_versions",
            ContentValues().apply {
                put("server_sync_state", "synced")
                put("server_revision", serverRevision)
                put("server_synced_at_utc", now.toString())
                putNull("next_retry_at_utc")
                putNull("last_error_code")
                putNull("last_error_message")
            },
            "id=?",
            arrayOf(profileVersionId),
        )
        writableDatabase.update(
            "dogs",
            ContentValues().apply { put("server_revision", serverRevision) },
            "id=?",
            arrayOf(dogId),
        )
        writableDatabase.setTransactionSuccessful()
    } finally {
        writableDatabase.endTransaction()
    }
}

fun WoonaDatabase.markProfileSyncError(
    profileVersionId: String,
    retryable: Boolean,
    code: String,
    message: String,
) {
    writableDatabase.update(
        "dog_profile_versions",
        ContentValues().apply {
            put("server_sync_state", if (retryable) "retryable_error" else "permanent_error")
            put("last_error_code", code.take(100))
            put("last_error_message", message.take(1000))
        },
        "id=?",
        arrayOf(profileVersionId),
    )
}

fun WoonaDatabase.pendingRecordingIds(): List<String> =
    readableDatabase.rawQuery(
        """
        SELECT r.id FROM recordings r
        JOIN server_sync_state s ON s.recording_id=r.id
        WHERE r.status IN ('completed','failed','interrupted')
          AND s.state IN ('pending','retryable_error')
          AND NOT (r.protocol_version='v2-storage' AND r.questionnaire_validation_state='legacy_incomplete')
          AND (r.remote_copy=0 OR s.server_receipt_sha256 IS NOT NULL)
          AND NOT EXISTS (
              SELECT 1 FROM artifacts a
              WHERE a.recording_id=r.id
                AND a.local_presence IN ('local','both')
                AND a.hash_state<>'verified'
          )
        ORDER BY r.client_created_at_utc,r.id
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (recording(id)?.questionnaire?.readyForSync() != false) add(id)
            }
        }
    }

fun WoonaDatabase.hashPendingArtifacts(): Int {
    val pending = readableDatabase.query(
        "artifacts",
        arrayOf("id", "recording_id", "relative_path"),
        "hash_state='pending'",
        null,
        null,
        null,
        "created_at_utc,id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        }
    }
    pending.forEach { (artifactId, recordingId, relativePath) ->
        val file = runCatching { resolveRelativePath(relativePath) }.getOrNull()
        if (file?.isFile != true) {
            writableDatabase.update(
                "artifacts",
                ContentValues().apply {
                    put("hash_state", "failed")
                    put("local_presence", "missing")
                    put("last_error_code", "local_file_missing")
                },
                "id=?",
                arrayOf(artifactId),
            )
            markRecordingSyncError(
                recordingId,
                retryable = false,
                code = "local_file_missing",
                message = "Artifact $artifactId is missing",
            )
        } else {
            val hash = try {
                sha256(file)
            } catch (error: Exception) {
                markRecordingSyncError(recordingId, true, "artifact_read_error", "Cannot read $relativePath: $error")
                throw error
            }
            writableDatabase.update(
                "artifacts",
                ContentValues().apply {
                    put("size_bytes", file.length())
                    put("sha256", hash)
                    put("hash_state", "verified")
                    put("local_presence", "local")
                },
                "id=?",
                arrayOf(artifactId),
            )
        }
    }
    return pending.size
}

fun WoonaDatabase.recordingSyncRecord(recordingId: String): RecordingSyncRecord? {
    val recording = readableDatabase.rawQuery(
        """
        SELECT r.id,r.dog_id,r.dog_profile_version_id,r.source,r.status,
               r.session_label,r.questionnaire_schema_version,
               r.questionnaire_validation_state,r.questionnaire_json,
               r.video_requested,r.started_at_utc,r.ended_at_utc,r.timezone,
               r.sensor_hardware_id,r.app_version,r.protocol_version,
               r.capture_error_code,r.capture_error_message
        FROM recordings r WHERE r.id=?
        """.trimIndent(),
        arrayOf(recordingId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        listOf(
            cursor.getString(0),
            cursor.getString(1),
            cursor.getString(2),
            cursor.getString(3),
            cursor.getString(4),
            cursor.getString(5),
            cursor.getInt(6),
            cursor.getString(7),
            if (cursor.isNull(8)) null else cursor.getString(8),
            cursor.getInt(9),
            cursor.getString(10),
            if (cursor.isNull(11)) null else cursor.getString(11),
            cursor.getString(12),
            if (cursor.isNull(13)) null else cursor.getString(13),
            cursor.getString(14),
            if (cursor.isNull(15)) null else cursor.getString(15),
            if (cursor.isNull(16)) null else cursor.getString(16),
            if (cursor.isNull(17)) null else cursor.getString(17),
        )
    }
    val profile = profileSyncRecord(recording[2] as String) ?: return null
    val sync = readableDatabase.query(
        "recording_sync",
        SYNC_COLUMNS,
        "recording_id=?",
        arrayOf(recordingId),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        RecordingSyncClock(
            schemaVersion = cursor.getInt(0),
            sessionZeroAtUtc = cursor.getString(1),
            sessionZeroWallClockMs = cursor.getLong(2),
            sessionZeroMonotonicNs = cursor.getLong(3),
            sessionZeroUncertaintyNs = cursor.getLong(4),
            firstSensorPacketMonotonicNs = cursor.nullableLong(5),
            lastSensorPacketMonotonicNs = cursor.nullableLong(6),
            videoRequestedMonotonicNs = cursor.nullableLong(7),
            mediaRecorderStartedMonotonicNs = cursor.nullableLong(8),
            videoFirstFrameMonotonicNs = cursor.nullableLong(9),
            videoFirstFrameCameraTimestampNs = cursor.nullableLong(10),
            videoOffsetFromSensorNs = cursor.nullableLong(11),
            cameraTimestampSource = cursor.nullableString(12),
            cameraClockQuality = cursor.getString(13),
            sensorClockQuality = cursor.getString(14),
            overallSyncQuality = cursor.getString(15),
            calibrationOffsetNs = cursor.getLong(16),
            estimatedDriftPpm = if (cursor.isNull(17)) null else cursor.getDouble(17),
            firstSensorDeviceTimerMillis = cursor.nullableLong(18),
            videoFirstFrameCallbackMonotonicNs = cursor.nullableLong(19),
            videoFirstSamplePtsUs = cursor.nullableLong(20),
            monotonicClock = cursor.getString(21),
        )
    }
    val artifacts = readableDatabase.query(
        "artifacts",
        arrayOf(
            "id",
            "type",
            "file_name",
            "mime_type",
            "relative_path",
            "size_bytes",
            "sha256",
            "uploaded_bytes",
            "upload_state",
            "created_at_utc",
            "reference_metadata_json",
        ),
        "recording_id=? AND local_presence IN ('local','both')",
        arrayOf(recordingId),
        null,
        null,
        "created_at_utc,id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ArtifactSyncRecord(
                        id = cursor.getString(0),
                        type = cursor.getString(1),
                        fileName = cursor.getString(2),
                        mimeType = cursor.getString(3),
                        relativePath = cursor.getString(4),
                        sizeBytes = cursor.getLong(5),
                        sha256 = cursor.getString(6),
                        uploadedBytes = cursor.getLong(7),
                        uploadState = cursor.getString(8),
                        clientCreatedAtUtc = cursor.getString(9),
                        referenceMetadataJson = cursor.nullableString(10),
                    ),
                )
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    return RecordingSyncRecord(
        id = recording[0] as String,
        dogId = recording[1] as String,
        profileVersionId = recording[2] as String,
        source = recording[3] as String,
        captureStatus = recording[4] as String,
        sessionLabel = recording[5] as String,
        questionnaireSchemaVersion = (recording[6] as Int).coerceAtLeast(1),
        questionnaireValidationState = recording[7] as String,
        sessionQuestionnaireJson = recording[8] as String? ?: "{}",
        videoRequested = recording[9] as Int == 1,
        startedAtUtc = recording[10] as String,
        endedAtUtc = recording[11] as String?,
        timezone = recording[12] as String,
        sensorHardwareId = recording[13] as String?,
        appVersion = recording[14] as String,
        protocolVersion = recording[15] as String?,
        captureErrorCode = recording[16] as String?,
        captureErrorMessage = recording[17] as String?,
        profile = profile,
        sync = sync,
        artifacts = artifacts,
    )
}

fun WoonaDatabase.artifactFile(artifact: ArtifactSyncRecord): File =
    resolveRelativePath(artifact.relativePath)

fun WoonaDatabase.acceptServerProfile(dogId: String, profile: RemoteProfileVersion) {
    writableDatabase.update("dog_profile_versions", ContentValues().apply {
        put("questionnaire_json", profile.questionnaireJson)
        put("content_sha256", profile.contentSha256)
        put("validation_state", profile.validationState)
    }, "id=? AND dog_id=?", arrayOf(profile.id, dogId))
}

fun WoonaDatabase.acceptServerRecording(recording: RemoteRecording) {
    acceptServerProfile(recording.dogId, recording.profile)
    writableDatabase.update("recordings", ContentValues().apply {
        put("questionnaire_json", recording.sessionQuestionnaireJson)
        put("questionnaire_revision", recording.questionnaireRevision)
        put("session_label", recording.sessionLabel)
        put("questionnaire_validation_state", recording.questionnaireValidationState)
    }, "id=? AND dog_id=? AND dog_profile_version_id=?",
        arrayOf(recording.id, recording.dogId, recording.profile.id))
}

fun WoonaDatabase.applyServerDeletions(
    entities: List<Pair<String, String>>,
    deleteEntry: (File, Boolean) -> Boolean = { file, directory -> if (directory) file.deleteRecursively() else file.delete() },
): Int {
    // Retain the exact connection for the entire transaction, including its
    // finally block. Re-entering the helper there can open another connection.
    val db = writableDatabase
    val accepted = entities.toMutableSet()
    fun marker(kind: String, id: String): Pair<String,String> = kind to MessageDigest.getInstance("SHA-256")
        .digest(id.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
    fun retired(kind: String, id: String): Boolean = marker(kind,id) in accepted
    val directories = mutableListOf<File>()
    val ids = mutableListOf<String>()
    db.beginTransaction()
    try {
        // Select candidates and persist their cleanup intents atomically.
        db.rawQuery("SELECT kind,id_sha256 FROM deferred_retirements", null).use { cursor ->
            while (cursor.moveToNext()) accepted += cursor.getString(0) to cursor.getString(1)
        }
        db.rawQuery("SELECT id,dog_id,status,relative_directory FROM recordings", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) in setOf("preparing", "recording")) continue
                if (retired("recording", cursor.getString(0)) || retired("dog", cursor.getString(1))) {
                    ids += cursor.getString(0)
                    directories += recordingDirectory(cursor.getString(3))
                }
            }
        }
        // Files are untouched until every retirement row and cleanup intent commits.
        directories.forEach { enqueueRetirementCleanup(db, it, directory = true) }
        ids.forEach { db.delete("recordings", "id=?", arrayOf(it)) }
        val artifacts = mutableListOf<Pair<String,File>>()
        db.rawQuery("SELECT id,relative_path FROM artifacts WHERE recording_id NOT IN (SELECT id FROM recordings WHERE status IN ('preparing','recording'))",null).use { cursor ->
            while (cursor.moveToNext()) if (retired("artifact",cursor.getString(0))) artifacts += cursor.getString(0) to resolveRelativePath(cursor.getString(1))
        }
        artifacts.forEach { (id,file) ->
            enqueueRetirementCleanup(db, file, directory = false)
            db.delete("artifacts","id=?",arrayOf(id))
        }
        val profiles = mutableListOf<String>()
        db.rawQuery("SELECT id,dog_id FROM dog_profile_versions WHERE id NOT IN (SELECT dog_profile_version_id FROM recordings)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val current = db.rawQuery("SELECT 1 FROM dog_profile_versions WHERE id=? AND superseded_at_utc IS NULL",arrayOf(cursor.getString(0))).use { it.moveToFirst() }
                if ((!current && retired("profile", cursor.getString(0))) || retired("dog", cursor.getString(1))) profiles += cursor.getString(0)
            }
        }
        profiles.forEach { db.delete("dog_profile_versions", "id=?", arrayOf(it)) }
        val dogs = mutableListOf<String>()
        db.rawQuery("SELECT id FROM dogs WHERE id NOT IN (SELECT dog_id FROM recordings) AND id NOT IN (SELECT dog_id FROM dog_profile_versions)", null).use { cursor ->
            while (cursor.moveToNext()) if (retired("dog", cursor.getString(0))) dogs += cursor.getString(0)
        }
        dogs.forEach { db.delete("dogs", "id=?", arrayOf(it)) }
        // Keep exact accepted negative knowledge while active capture or profile
        // references defer retirement. A later stop/recovery needs no new approval.
        val deferred = mutableSetOf<Pair<String,String>>()
        listOf("recording" to "recordings", "dog" to "dogs", "profile" to "dog_profile_versions", "artifact" to "artifacts").forEach { (kind, table) ->
            db.rawQuery("SELECT id FROM $table", null).use { cursor ->
                while (cursor.moveToNext()) marker(kind,cursor.getString(0)).takeIf { it in accepted }?.let(deferred::add)
            }
        }
        db.delete("deferred_retirements", null, null)
        deferred.forEach { (kind, hash) -> db.insertOrThrow("deferred_retirements", null, ContentValues().apply {
            put("kind",kind);put("id_sha256",hash);put("received_at_utc",java.time.Instant.now().toString())
        }) }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
    retryRetirementCleanup(deleteEntry)
    return ids.size
}

fun WoonaDatabase.markRecordingUploading(recordingId: String) {
    writableDatabase.execSQL(
        """
        UPDATE server_sync_state
        SET state='uploading',attempt_count=attempt_count+1,updated_at_utc=?,
            last_error_code=NULL,last_error_message=NULL
        WHERE recording_id=?
        """.trimIndent(),
        arrayOf(Instant.now().toString(), recordingId),
    )
}

fun WoonaDatabase.markArtifactProgress(
    artifactId: String,
    uploadedBytes: Long,
    available: Boolean = false,
) {
    writableDatabase.update(
        "artifacts",
        ContentValues().apply {
            put("uploaded_bytes", uploadedBytes)
            put("upload_state", if (available) "available" else "uploading")
            if (available) put("server_verified_at_utc", Instant.now().toString())
        },
        "id=?",
        arrayOf(artifactId),
    )
}

fun WoonaDatabase.markRecordingSynced(
    recordingId: String,
    receiptSha256: String,
    receiptAtUtc: String,
) {
    writableDatabase.update(
        "server_sync_state",
        ContentValues().apply {
            put("state", "synced")
            put("server_receipt_sha256", receiptSha256)
            put("server_receipt_at_utc", receiptAtUtc)
            put("updated_at_utc", Instant.now().toString())
            putNull("last_error_code")
            putNull("last_error_message")
        },
        "recording_id=?",
        arrayOf(recordingId),
    )
    writableDatabase.execSQL("""
        UPDATE server_sync_state SET state='pending' WHERE recording_id=? AND EXISTS (
            SELECT 1 FROM artifacts WHERE recording_id=? AND local_presence IN ('local','both') AND upload_state<>'available'
        )
    """.trimIndent(), arrayOf(recordingId, recordingId))
}

fun WoonaDatabase.markRecordingSyncError(
    recordingId: String,
    retryable: Boolean,
    code: String,
    message: String,
) {
    writableDatabase.update(
        "server_sync_state",
        ContentValues().apply {
            put("state", if (retryable) "retryable_error" else "permanent_error")
            put("last_error_code", code.take(100))
            put("last_error_message", message.take(1000))
            put("updated_at_utc", Instant.now().toString())
        },
        "recording_id=?",
        arrayOf(recordingId),
    )
}

fun WoonaDatabase.resetFailedSync() {
    writableDatabase.execSQL(
        "UPDATE dog_profile_versions SET server_sync_state='pending' WHERE server_sync_state IN ('retryable_error','permanent_error')",
    )
    writableDatabase.execSQL(
        "UPDATE server_sync_state SET state='pending',updated_at_utc=? WHERE state IN ('retryable_error','permanent_error')",
        arrayOf(Instant.now().toString()),
    )
}

fun WoonaDatabase.serverSyncCounts(): ServerSyncCounts =
    readableDatabase.rawQuery(
        """
        WITH sync_items AS (
          SELECT 'recording' AS item_kind,s.recording_id AS item_id,s.state,s.last_error_code,s.last_error_message,s.updated_at_utc
          FROM server_sync_state s
          JOIN recordings r ON r.id=s.recording_id
          WHERE NOT (r.protocol_version='v2-storage' AND r.questionnaire_validation_state='legacy_incomplete')
          UNION ALL
          SELECT 'profile' AS item_kind,id AS item_id,server_sync_state,last_error_code,last_error_message,client_created_at_utc
          FROM dog_profile_versions
        )
        SELECT
          SUM(CASE WHEN state='pending' THEN 1 ELSE 0 END),
          SUM(CASE WHEN state='uploading' THEN 1 ELSE 0 END),
          SUM(CASE WHEN state='synced' THEN 1 ELSE 0 END),
          SUM(CASE WHEN state IN ('retryable_error','permanent_error') THEN 1 ELSE 0 END),
          (SELECT item_kind || ' ' || item_id || ': ' || COALESCE(NULLIF(last_error_message,''),last_error_code)
           FROM sync_items
           WHERE state IN ('retryable_error','permanent_error')
           ORDER BY updated_at_utc DESC LIMIT 1)
        FROM sync_items
        """.trimIndent(),
        null,
    ).use { cursor ->
        cursor.moveToFirst()
        ServerSyncCounts(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3), cursor.getString(4))
    }

fun WoonaDatabase.restoreServerMetadata(
    dogs: List<RemoteDog>,
    recordings: List<RemoteRecording>,
    now: Instant = Instant.now(),
): RestoreResult {
    var restoredDogs = 0
    var restoredRecordings = 0
    var restoredArtifacts = 0
    var conflicts = 0
    var recordingConflicts = 0
    val database = writableDatabase
    val rejectedDogs = mutableSetOf<String>()
    database.beginTransaction()
    try {
        dogs.forEach { dog ->
            val owner = profile(dog.id)?.accountId
            if (owner != null && owner != dog.accountId) {
                conflicts++; rejectedDogs += dog.id; return@forEach
            }
            val localCurrent = database.rawQuery(
                """
                SELECT v.id,v.server_sync_state FROM dog_profile_versions v
                WHERE v.dog_id=? AND v.superseded_at_utc IS NULL
                """.trimIndent(),
                arrayOf(dog.id),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
            }
            val divergent = localCurrent != null &&
                localCurrent.first != dog.profile.id &&
                localCurrent.second != "synced"
            if (divergent) conflicts++
            val insertedDog = database.insertWithOnConflict(
                "dogs",
                null,
                ContentValues().apply {
                    put("id", dog.id)
                    put("account_id", dog.accountId)
                    put("number_or_name", dog.numberOrName)
                    put("server_revision", dog.revision)
                    put("created_at_utc", dog.profile.clientCreatedAtUtc)
                    put("updated_at_utc", dog.profile.clientCreatedAtUtc)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (insertedDog != -1L) restoredDogs++
            database.update("dogs", ContentValues().apply { put("account_id", dog.accountId) }, "id=?", arrayOf(dog.id))
            dog.profileVersions
                .filter { it.id != dog.profile.id }
                .forEach { version ->
                    insertRemoteProfileVersion(
                        database,
                        dog.id,
                        version,
                        supersededAtUtc = version.supersededAtUtc ?: version.clientCreatedAtUtc,
                        serverRevision = null,
                    )
                }
            database.update(
                "dogs",
                ContentValues().apply {
                    if (!divergent) put("number_or_name", dog.numberOrName)
                    put("server_revision", dog.revision)
                    put("updated_at_utc", now.toString())
                },
                "id=?",
                arrayOf(dog.id),
            )
            if (!divergent) {
                database.update(
                    "dog_profile_versions",
                    ContentValues().apply { put("superseded_at_utc", now.toString()) },
                    "dog_id=? AND superseded_at_utc IS NULL AND id<>?",
                    arrayOf(dog.id, dog.profile.id),
                )
            }
            insertRemoteProfileVersion(
                database,
                dog.id,
                dog.profile,
                supersededAtUtc = if (divergent) now.toString() else null,
                serverRevision = dog.revision,
            )
            if (!divergent) {
                database.update(
                    "dog_profile_versions",
                    ContentValues().apply {
                        putNull("superseded_at_utc")
                        put("server_sync_state", "synced")
                        put("server_revision", dog.revision)
                        put("server_synced_at_utc", now.toString())
                    },
                    "id=?",
                    arrayOf(dog.profile.id),
                )
            }
        }

        recordings.forEach { recording ->
            val owner = profile(recording.dogId)?.accountId
            if (recording.dogId in rejectedDogs || (owner != null && owner != recording.accountId)) {
                conflicts++; recordingConflicts++; return@forEach
            }
            val relativeDirectory = database.rawQuery("SELECT relative_directory FROM recordings WHERE id=?", arrayOf(recording.id)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else recordingRelativeDirectory(
                    recording.dogId, recording.id, Instant.parse(recording.startedAtUtc), recording.timezone,
                )
            }
            val mismatched = recording.artifacts.any { artifact ->
                database.rawQuery(
                    "SELECT id,recording_id,sha256,size_bytes,type,file_name FROM artifacts WHERE id=? OR (recording_id=? AND relative_path=?)",
                    arrayOf(artifact.id, recording.id, "$relativeDirectory/${artifact.fileName}"),
                ).use { cursor ->
                    var conflict = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) != recording.id || cursor.getString(2) != artifact.sha256 ||
                            cursor.getLong(3) != artifact.sizeBytes || cursor.getString(4) != artifact.type ||
                            cursor.getString(5) != artifact.fileName ||
                            (cursor.getString(0) != artifact.id && recording.receiptSha256 == null)) conflict = true
                    }
                    conflict
                }
            }
            if (mismatched) { conflicts++; recordingConflicts++; return@forEach }
            insertRemoteProfileVersion(
                database,
                recording.dogId,
                recording.profile,
                supersededAtUtc = recording.startedAtUtc,
                serverRevision = null,
            )
            val inserted = database.insertWithOnConflict(
                "recordings",
                null,
                ContentValues().apply {
                    put("id", recording.id)
                    put("remote_copy", 1)
                    put("dog_id", recording.dogId)
                    put("dog_profile_version_id", recording.profile.id)
                    put("source", recording.source)
                    put("status", recording.captureStatus)
                    put("session_label", recording.sessionLabel)
                    put("questionnaire_schema_version", recording.questionnaireSchemaVersion)
                    put("questionnaire_validation_state", recording.questionnaireValidationState)
                    put("questionnaire_json", recording.sessionQuestionnaireJson)
                    put("questionnaire_revision", recording.questionnaireRevision)
                    put("video_requested", if (recording.videoRequested) 1 else 0)
                    put("started_at_utc", recording.startedAtUtc)
                    if (recording.endedAtUtc == null) putNull("ended_at_utc") else put("ended_at_utc", recording.endedAtUtc)
                    put("timezone", recording.timezone)
                    if (recording.sensorHardwareId == null) putNull("sensor_hardware_id") else put("sensor_hardware_id", recording.sensorHardwareId)
                    put("app_version", recording.appVersion)
                    if (recording.protocolVersion == null) putNull("protocol_version") else put("protocol_version", recording.protocolVersion)
                    if (recording.captureErrorCode == null) putNull("capture_error_code") else put("capture_error_code", recording.captureErrorCode)
                    if (recording.captureErrorMessage == null) putNull("capture_error_message") else put("capture_error_message", recording.captureErrorMessage)
                    put("relative_directory", relativeDirectory)
                    put("client_created_at_utc", recording.startedAtUtc)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (inserted != -1L) {
                restoredRecordings++
                insertRemoteSync(database, recording)
            } else if (recording.receiptSha256 != null) {
                database.update("recordings",ContentValues().apply {
                    put("dog_id",recording.dogId)
                    put("dog_profile_version_id",recording.profile.id)
                    put("questionnaire_json",recording.sessionQuestionnaireJson)
                    put("questionnaire_revision",recording.questionnaireRevision)
                    put("session_label",recording.sessionLabel)
                    put("questionnaire_schema_version",recording.questionnaireSchemaVersion)
                    put("questionnaire_validation_state",recording.questionnaireValidationState)
                },"id=?",arrayOf(recording.id))
                database.delete("recording_sync","recording_id=?",arrayOf(recording.id))
                insertRemoteSync(database,recording)
            }
            database.insertWithOnConflict(
                "server_sync_state",
                null,
                ContentValues().apply {
                    put("recording_id", recording.id)
                    put("state", if (recording.receiptSha256 == null) "pending" else "synced")
                    if (recording.receiptSha256 == null) putNull("server_receipt_sha256") else put("server_receipt_sha256", recording.receiptSha256)
                    if (recording.verifiedAtUtc == null) putNull("server_receipt_at_utc") else put("server_receipt_at_utc", recording.verifiedAtUtc)
                    put("updated_at_utc", now.toString())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (recording.receiptSha256 != null) {
                database.update(
                    "server_sync_state",
                    ContentValues().apply {
                        put("state", "synced")
                        put("server_receipt_sha256", recording.receiptSha256)
                        if (recording.verifiedAtUtc == null) {
                            putNull("server_receipt_at_utc")
                        } else {
                            put("server_receipt_at_utc", recording.verifiedAtUtc)
                        }
                        put("updated_at_utc", now.toString())
                        putNull("last_error_code")
                        putNull("last_error_message")
                    },
                    "recording_id=?",
                    arrayOf(recording.id),
                )
            }
            recording.artifacts.forEach { artifact ->
                val relativePath = "$relativeDirectory/${artifact.fileName}"
                val existingId = database.rawQuery(
                    "SELECT id FROM artifacts WHERE id=? OR (recording_id=? AND relative_path=?) ORDER BY relative_path=? DESC LIMIT 1",
                    arrayOf(artifact.id, recording.id, relativePath, relativePath),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                if (existingId != null) {
                    if (existingId != artifact.id) database.delete("artifacts", "id=? AND recording_id=?", arrayOf(artifact.id, recording.id))
                    database.execSQL(
                        """
                        UPDATE artifacts
                        SET id=?,mime_type=?,reference_metadata_json=?,local_presence=CASE
                                WHEN local_presence IN ('local','both') THEN 'both'
                                ELSE 'remote_only'
                            END,
                            upload_state=?,
                            uploaded_bytes=?,
                            server_verified_at_utc=?
                        WHERE id=? AND recording_id=?
                        """.trimIndent(),
                        arrayOf<Any?>(
                            artifact.id,
                            artifact.mimeType,
                            artifact.referenceMetadataJson,
                            if (artifact.storageStatus == "available") "available" else "uploading",
                            if (artifact.storageStatus == "available") artifact.sizeBytes else 0L,
                            recording.verifiedAtUtc,
                            existingId,
                            recording.id,
                        ),
                    )
                    return@forEach
                }
                val insertedArtifact = database.insertWithOnConflict(
                    "artifacts",
                    null,
                    ContentValues().apply {
                        put("id", artifact.id)
                        put("recording_id", recording.id)
                        put("type", artifact.type)
                        put("reference_metadata_json", artifact.referenceMetadataJson)
                        put("file_name", artifact.fileName)
                        put("mime_type", artifact.mimeType)
                        put("relative_path", relativePath)
                        put("size_bytes", artifact.sizeBytes)
                        put("sha256", artifact.sha256)
                        put("hash_state", "verified")
                        put("local_presence", "remote_only")
                        put("upload_state", if (artifact.storageStatus == "available") "available" else "uploading")
                        put("uploaded_bytes", if (artifact.storageStatus == "available") artifact.sizeBytes else 0)
                        put("created_at_utc", recording.startedAtUtc)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (insertedArtifact != -1L) restoredArtifacts++
            }
        }
        database.setTransactionSuccessful()
    } finally {
        database.endTransaction()
    }
    return RestoreResult(restoredDogs, restoredRecordings, restoredArtifacts, conflicts, recordingConflicts)
}

/** Remove only verified uploaded files; retain metadata for history and re-download. */
fun WoonaDatabase.removeLocalRecordingFiles(recordingId: String, remote: RemoteRecording): Long {
    val local = requireNotNull(recording(recordingId)) { "local_copy_not_synced" }
    require(local.status.value in setOf("completed", "failed", "interrupted") &&
        local.serverSyncState == "synced") { "local_copy_not_synced" }
    require(remote.id == local.id && remote.dogId == local.profileId && remote.captureStatus in setOf("completed", "failed", "interrupted") &&
        !remote.receiptSha256.isNullOrBlank() && !remote.verifiedAtUtc.isNullOrBlank()) { "server_copy_unverified" }
    require(local.artifacts.isNotEmpty() && local.artifacts.all { artifact ->
        remote.artifacts.any { saved -> saved.fileName == artifact.fileName && saved.type == artifact.type.value &&
            saved.sizeBytes == artifact.sizeBytes && saved.sha256 == artifact.sha256 && saved.storageStatus == "available" }
    }) { "server_copy_unverified" }
    val directory = recordingDirectory(local.relativeDirectory).canonicalFile
    require(directory != rootDirectory.canonicalFile && directory.toPath().startsWith(rootDirectory.canonicalFile.toPath())) { "local_copy_unsafe_path" }
    val targets = local.artifacts.map { artifact -> artifact to resolveRelativePath(artifact.relativePath).canonicalFile }
    // Validate every path and every existing file before deleting the first byte.
    targets.forEach { (artifact, file) ->
        require(file.toPath().startsWith(directory.toPath()) && file != directory) { "local_copy_unsafe_path" }
        require(!file.exists() || (file.isFile && file.length() == artifact.sizeBytes && sha256(file) == artifact.sha256)) { "local_copy_changed" }
    }
    val modified = targets.associate { (_, file) -> file.path to file.lastModified() }
    val restored = restoreServerMetadata(emptyList(), listOf(remote))
    require(restored.conflicts == 0 && restored.recordingConflicts == 0) { "server_copy_unverified" }
    var removedBytes = 0L
    try {
        targets.forEach { (artifact, file) ->
            if (file.exists()) {
                require(file.canonicalFile == file && file.length() == artifact.sizeBytes && file.lastModified() == modified[file.path]) { "local_copy_changed" }
                check(file.delete()) { "local_copy_remove_failed: ${artifact.fileName}" }
                removedBytes += artifact.sizeBytes
            }
        }
    } finally {
        // Preserve progress after a partial I/O failure; never claim absent files are local.
        recording(recordingId)?.artifacts.orEmpty().forEach { artifact ->
            val present = resolveRelativePath(artifact.relativePath).isFile
            writableDatabase.update("artifacts", ContentValues().apply {
                put("local_presence", if (present) "both" else "remote_only")
            }, "id=?", arrayOf(artifact.id))
        }
    }
    return removedBytes
}

fun WoonaDatabase.remoteArtifacts(recordingId: String): List<Artifact> =
    recording(recordingId)?.artifacts.orEmpty().filter { it.uploadState == "available" && (it.localPresence == "remote_only" || !resolveRelativePath(it.relativePath).isFile) }

fun WoonaDatabase.artifactDownloadTarget(artifact: Artifact): File =
    resolveRelativePath(artifact.relativePath)

fun WoonaDatabase.markArtifactDownloaded(artifactId: String) {
    writableDatabase.update(
        "artifacts",
        ContentValues().apply { put("local_presence", "both") },
        "id=?",
        arrayOf(artifactId),
    )
}

private fun insertRemoteProfileVersion(
    database: SQLiteDatabase,
    dogId: String,
    profile: RemoteProfileVersion,
    supersededAtUtc: String?,
    serverRevision: Long?,
) {
    database.insertWithOnConflict(
        "dog_profile_versions",
        null,
        ContentValues().apply {
            put("id", profile.id)
            put("dog_id", dogId)
            put("schema_version", profile.schemaVersion)
            put("validation_state", profile.validationState)
            put("questionnaire_json", profile.questionnaireJson)
            put("content_sha256", profile.contentSha256)
            put("client_created_at_utc", profile.clientCreatedAtUtc)
            if (supersededAtUtc == null) putNull("superseded_at_utc") else put("superseded_at_utc", supersededAtUtc)
            put("server_sync_state", "synced")
            if (serverRevision == null) putNull("server_revision") else put("server_revision", serverRevision)
            put("server_synced_at_utc", Instant.now().toString())
        },
        SQLiteDatabase.CONFLICT_IGNORE,
    )
}

private fun insertRemoteSync(database: SQLiteDatabase, recording: RemoteRecording) {
    val sync = recording.sync
    database.insertOrThrow(
        "recording_sync",
        null,
        ContentValues().apply {
            put("recording_id", recording.id)
            put("schema_version", sync.schemaVersion)
            put("monotonic_clock", sync.monotonicClock)
            put("session_zero_at_utc", sync.sessionZeroAtUtc)
            put("session_zero_wall_clock_ms", sync.sessionZeroWallClockMs)
            put("session_zero_monotonic_ns", sync.sessionZeroMonotonicNs)
            put("session_zero_uncertainty_ns", sync.sessionZeroUncertaintyNs)
            if (sync.firstSensorPacketMonotonicNs == null) putNull("first_sensor_packet_monotonic_ns") else put("first_sensor_packet_monotonic_ns", sync.firstSensorPacketMonotonicNs)
            if (sync.firstSensorDeviceTimerMillis == null) putNull("first_sensor_device_timer_ms") else put("first_sensor_device_timer_ms", sync.firstSensorDeviceTimerMillis)
            if (sync.lastSensorPacketMonotonicNs == null) putNull("last_sensor_packet_monotonic_ns") else put("last_sensor_packet_monotonic_ns", sync.lastSensorPacketMonotonicNs)
            if (sync.videoRequestedMonotonicNs == null) putNull("video_requested_monotonic_ns") else put("video_requested_monotonic_ns", sync.videoRequestedMonotonicNs)
            if (sync.mediaRecorderStartedMonotonicNs == null) putNull("media_recorder_started_monotonic_ns") else put("media_recorder_started_monotonic_ns", sync.mediaRecorderStartedMonotonicNs)
            if (sync.videoFirstFrameMonotonicNs == null) putNull("video_first_frame_monotonic_ns") else put("video_first_frame_monotonic_ns", sync.videoFirstFrameMonotonicNs)
            if (sync.videoFirstFrameCameraTimestampNs == null) putNull("video_first_frame_camera_timestamp_ns") else put("video_first_frame_camera_timestamp_ns", sync.videoFirstFrameCameraTimestampNs)
            if (sync.videoFirstFrameCallbackMonotonicNs == null) putNull("video_first_frame_callback_monotonic_ns") else put("video_first_frame_callback_monotonic_ns", sync.videoFirstFrameCallbackMonotonicNs)
            if (sync.videoFirstSamplePtsUs == null) putNull("video_first_sample_pts_us") else put("video_first_sample_pts_us", sync.videoFirstSamplePtsUs)
            if (sync.videoOffsetFromSensorNs == null) putNull("video_offset_from_sensor_ns") else put("video_offset_from_sensor_ns", sync.videoOffsetFromSensorNs)
            if (sync.cameraTimestampSource == null) putNull("camera_timestamp_source") else put("camera_timestamp_source", sync.cameraTimestampSource)
            put("camera_clock_quality", sync.cameraClockQuality)
            put("sensor_clock_quality", sync.sensorClockQuality)
            put("overall_sync_quality", sync.overallSyncQuality)
            put("calibration_offset_ns", sync.calibrationOffsetNs)
            if (sync.estimatedDriftPpm == null) putNull("estimated_drift_ppm") else put("estimated_drift_ppm", sync.estimatedDriftPpm)
            put("updated_at_utc", Instant.now().toString())
        },
    )
}

private fun android.database.Cursor.nullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun android.database.Cursor.nullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)

private val SYNC_COLUMNS = arrayOf(
    "schema_version",
    "session_zero_at_utc",
    "session_zero_wall_clock_ms",
    "session_zero_monotonic_ns",
    "session_zero_uncertainty_ns",
    "first_sensor_packet_monotonic_ns",
    "last_sensor_packet_monotonic_ns",
    "video_requested_monotonic_ns",
    "media_recorder_started_monotonic_ns",
    "video_first_frame_monotonic_ns",
    "video_first_frame_camera_timestamp_ns",
    "video_offset_from_sensor_ns",
    "camera_timestamp_source",
    "camera_clock_quality",
    "sensor_clock_quality",
    "overall_sync_quality",
    "calibration_offset_ns",
    "estimated_drift_ppm",
    "first_sensor_device_timer_ms",
    "video_first_frame_callback_monotonic_ns",
    "video_first_sample_pts_us",
    "monotonic_clock",
)

/** Caller holds serverOperationLock; only orphaned upload states are recovered.
 * Confirmed offsets, receipt fields, hashes and local files remain untouched. */
fun WoonaDatabase.recoverOrphanedUploads(hasUnfinishedWork: (String) -> Boolean): Pair<Int, Int> {
    fun ids(sql: String): List<String> = readableDatabase.rawQuery(sql, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    val profiles = ids("SELECT id FROM dog_profile_versions WHERE server_sync_state='uploading'")
        .filterNot { hasUnfinishedWork("server-profile-$it") }
    val recordings = ids("SELECT recording_id FROM server_sync_state WHERE state='uploading'")
        .filterNot { hasUnfinishedWork("server-sync-$it") }
    if (profiles.isEmpty() && recordings.isEmpty()) return 0 to 0
    var profileCount = 0
    var recordingCount = 0
    writableDatabase.beginTransaction()
    try {
        val values = ContentValues().apply {
            put("last_error_code", "orphaned_upload_recovered")
            put("last_error_message", "orphaned_upload_recovered: Previous uploading state had no unfinished background task. Retry resumes from confirmed progress; local files are preserved.")
        }
        profiles.forEach { id ->
            values.put("server_sync_state", "pending")
            profileCount += writableDatabase.update("dog_profile_versions", values, "id=? AND server_sync_state='uploading'", arrayOf(id))
            values.remove("server_sync_state")
        }
        recordings.forEach { id ->
            values.put("state", "pending")
            values.put("updated_at_utc", Instant.now().toString())
            recordingCount += writableDatabase.update("server_sync_state", values, "recording_id=? AND state='uploading'", arrayOf(id))
        }
        writableDatabase.setTransactionSuccessful()
    } finally { writableDatabase.endTransaction() }
    return profileCount to recordingCount
}

// Policy refresh includes a persisted uploading state whose WorkRequest may have
// been re-enqueued by WorkManager after process death. This does not reset it.
fun WoonaDatabase.networkPolicyProfileVersionIds(): List<String> = readableDatabase.rawQuery(
    "SELECT id FROM dog_profile_versions WHERE server_sync_state IN ('pending','retryable_error','uploading')", null,
).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

fun WoonaDatabase.networkPolicyRecordingIds(): List<String> = readableDatabase.rawQuery(
    "SELECT recording_id FROM server_sync_state WHERE state IN ('pending','retryable_error','uploading')", null,
).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
