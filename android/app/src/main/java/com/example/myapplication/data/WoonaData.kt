package com.example.myapplication.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class DogQuestionnaire(
    val schemaVersion: Int = 1,
    val numberOrName: String = "",
    val shelterOrPlace: String = "",
    val breedStatus: String = "",
    val breedName: String? = null,
    val resembles: String? = null,
    val size: String = "",
    val ageStatus: String = "",
    val ageYears: Int? = null,
    val ageMonths: Int? = null,
    val ageSource: String = "",
    val sex: String = "",
    val sterilizationStatus: String = "",
    val weightStatus: String = "",
    val weightKg: Double? = null,
    val bodyConditionStatus: String = "",
    val bodyConditionScore: Int? = null,
    val muscleMass: String = "",
    val neckCircumferenceStatus: String = "",
    val neckCircumferenceCm: Double? = null,
    val coatLength: String = "",
    val undercoat: String = "",
    val shavedAreasStatus: String = "",
    val shavedAreasDetails: String? = null,
    val observedSigns: List<String> = emptyList(),
    val diagnosesStatus: String = "",
    val diagnosesDetails: String? = null,
    val housing: String = "",
    val housingDetails: String? = null,
    val walksStatus: String = "",
    val walksDescription: String? = null,
    val cohabitants: String = "",
    val shelterPermission: String = "",
    val notesStatus: String = "",
    val notes: String? = null,
    val animalId: String? = null,
    val species: String? = null,
    val savedAtLocal: String? = null,
    val diseaseCategory: String? = null,
    val diseaseCategoryDetails: String? = null,
    val chronicLameness: String? = null,
    val medications: String? = null,
    val history: String? = null,
    val specialistName: String? = null,
)

data class SessionQuestionnaire(
    val schemaVersion: Int = 1,
    val sessionLabel: String = "",
    val operatorName: String = "",
    val activityGroup: String = "",
    val activityType: String = "",
    val activityDetails: String? = null,
    val location: String = "",
    val surface: String = "",
    val surfaceDetails: String? = null,
    val airTemperatureStatus: String = "",
    val airTemperatureC: Double? = null,
    val sensorPosition: String = "",
    val sensorPositionDetails: String? = null,
    val collarTightness: String = "",
    val preMeasurementState: String = "",
    val preMeasurementStateDetails: String? = null,
    val pulseStatus: String = "",
    val pulseBpm: Int? = null,
    val respirationStatus: String = "",
    val respirationPerMinute: Int? = null,
    val bodyTemperatureStatus: String = "",
    val bodyTemperatureC: Double? = null,
    val measurementAtUtc: String? = null,
    val videoRequested: Boolean = true,
    val animalId: String? = null,
    val savedAtLocal: String? = null,
    val sessionDate: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val durationMinutes: Double? = null,
    val plannedActivities: List<String> = emptyList(),
    val surfaces: List<String> = emptyList(),
    val lastMedicationAt: String? = null,
    val notes: String? = null,
    val specialistName: String? = null,
    val sessionKind: String? = null,
    val heartQuestionnaire: HeartQuestionnaire? = null,
)

data class QuestionnaireValidation(
    val errors: Map<String, String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

fun DogQuestionnaire.validate(): QuestionnaireValidation {
    if (schemaVersion == 2) return validateSheet()
    val errors = linkedMapOf<String, String>()
    fun required(key: String, value: String) {
        if (value.trim().isEmpty()) errors[key] = "Required"
    }
    fun textWhen(key: String, enabled: Boolean, value: String?, max: Int) {
        if (enabled && (value.isNullOrBlank() || value.trim().length > max)) errors[key] = "Required"
        if (!enabled && value != null) errors[key] = "Must be empty"
    }

    if (schemaVersion != 1) errors["schemaVersion"] = "Unsupported schema"
    required("numberOrName", numberOrName)
    if (numberOrName.trim().length > 100) errors["numberOrName"] = "Maximum 100 characters"
    required("shelterOrPlace", shelterOrPlace)
    if (shelterOrPlace.trim().length > 200) errors["shelterOrPlace"] = "Maximum 200 characters"
    if (breedStatus !in setOf("purebred", "mixed", "unknown")) errors["breedStatus"] = "Required"
    textWhen("breedName", breedStatus in setOf("purebred", "mixed"), breedName, 100)
    textWhen("resembles", breedStatus == "mixed", resembles, 200)
    if (size !in setOf("small", "medium", "large", "giant", "unknown")) errors["size"] = "Required"
    if (ageStatus !in setOf("known", "estimated", "unknown")) errors["ageStatus"] = "Required"
    if (ageStatus in setOf("known", "estimated")) {
        if (ageYears == null && ageMonths == null) errors["ageYears"] = "Enter years or months"
        if (ageSource !in setOf("documents", "shelter_report", "dental_estimate", "operator_estimate")) {
            errors["ageSource"] = "Required"
        }
    } else if (ageStatus == "unknown" && (ageYears != null || ageMonths != null || ageSource != "unknown")) {
        errors["ageStatus"] = "Unknown age cannot contain an estimate"
    }
    if (ageYears != null && ageYears !in 0..40) errors["ageYears"] = "Use 0–40"
    if (ageMonths != null && ageMonths !in 0..11) errors["ageMonths"] = "Use 0–11"
    if (sex !in setOf("male", "female", "unknown")) errors["sex"] = "Required"
    if (sterilizationStatus !in setOf("yes", "no", "unknown")) errors["sterilizationStatus"] = "Required"
    if (weightStatus !in setOf("measured", "estimated", "unknown")) errors["weightStatus"] = "Required"
    if (weightStatus in setOf("measured", "estimated")) {
        if (weightKg == null || weightKg <= 0 || weightKg > 150) errors["weightKg"] = "Use >0 and ≤150 kg"
    } else if (weightStatus == "unknown" && weightKg != null) {
        errors["weightKg"] = "Must be empty"
    }
    if (bodyConditionStatus !in setOf("assessed", "unable")) errors["bodyConditionStatus"] = "Required"
    if (bodyConditionStatus == "assessed" && bodyConditionScore !in 1..9) errors["bodyConditionScore"] = "Use 1–9"
    if (bodyConditionStatus == "unable" && bodyConditionScore != null) errors["bodyConditionScore"] = "Must be empty"
    if (muscleMass !in setOf("normal", "mild_loss", "moderate_loss", "severe_loss", "unable")) errors["muscleMass"] = "Required"
    if (neckCircumferenceStatus !in setOf("measured", "not_measured")) errors["neckCircumferenceStatus"] = "Required"
    if (neckCircumferenceStatus == "measured" &&
        (neckCircumferenceCm == null || neckCircumferenceCm <= 0 || neckCircumferenceCm > 150)
    ) errors["neckCircumferenceCm"] = "Use >0 and ≤150 cm"
    if (neckCircumferenceStatus == "not_measured" && neckCircumferenceCm != null) errors["neckCircumferenceCm"] = "Must be empty"
    if (coatLength !in setOf("short", "medium", "long", "unknown")) errors["coatLength"] = "Required"
    if (undercoat !in setOf("none", "moderate", "dense", "unknown")) errors["undercoat"] = "Required"
    if (shavedAreasStatus !in setOf("none", "present", "unknown")) errors["shavedAreasStatus"] = "Required"
    textWhen("shavedAreasDetails", shavedAreasStatus == "present", shavedAreasDetails, 500)
    if (observedSigns.isEmpty()) errors["observedSigns"] = "Choose at least one"
    if (observedSigns.size != observedSigns.distinct().size || observedSigns.any { it !in OBSERVED_SIGNS }) {
        errors["observedSigns"] = "Invalid or duplicate sign"
    }
    if (observedSigns.any { it in setOf("none", "unknown") } && observedSigns.size > 1) {
        errors["observedSigns"] = "None/unknown cannot be combined"
    }
    if (diagnosesStatus !in setOf("yes", "no", "unknown")) errors["diagnosesStatus"] = "Required"
    textWhen("diagnosesDetails", diagnosesStatus == "yes", diagnosesDetails, 2000)
    if (housing !in setOf("enclosure", "room", "home", "free_range", "other", "unknown")) errors["housing"] = "Required"
    textWhen("housingDetails", housing == "other", housingDetails, 500)
    if (walksStatus !in setOf("known", "none", "unknown")) errors["walksStatus"] = "Required"
    textWhen("walksDescription", walksStatus == "known", walksDescription, 500)
    if (cohabitants !in setOf("alone", "other_animals", "people_only", "unknown")) errors["cohabitants"] = "Required"
    if (shelterPermission !in setOf("yes", "no", "not_required", "unknown")) errors["shelterPermission"] = "Required"
    if (notesStatus !in setOf("none", "provided")) errors["notesStatus"] = "Required"
    textWhen("notes", notesStatus == "provided", notes, 2000)
    return QuestionnaireValidation(errors)
}

fun SessionQuestionnaire.validate(): QuestionnaireValidation {
    if (schemaVersion == 2) return validateSheet()
    val errors = linkedMapOf<String, String>()
    fun required(key: String, value: String, max: Int) {
        if (value.trim().isEmpty()) errors[key] = "Required"
        else if (value.trim().length > max) errors[key] = "Maximum $max characters"
    }
    fun detail(key: String, enabled: Boolean, value: String?, max: Int) {
        if (enabled && (value.isNullOrBlank() || value.trim().length > max)) errors[key] = "Required"
        if (!enabled && value != null) errors[key] = "Must be empty"
    }
    if (schemaVersion != 1) errors["schemaVersion"] = "Unsupported schema"
    required("sessionLabel", sessionLabel, 100)
    required("operatorName", operatorName, 150)
    val activities = ACTIVITY_TYPES[activityGroup]
    if (activities == null) errors["activityGroup"] = "Required"
    else if (activityType !in activities) errors["activityType"] = "Choose an activity"
    detail("activityDetails", activityType in setOf("mixed", "other"), activityDetails, 1000)
    val surfaces = SURFACES[location]
    if (surfaces == null) errors["location"] = "Required"
    else if (surface !in surfaces) errors["surface"] = "Choose a surface"
    detail("surfaceDetails", surface == "other", surfaceDetails, 300)
    if (airTemperatureStatus !in MEASUREMENT_STATUSES) errors["airTemperatureStatus"] = "Required"
    if (airTemperatureStatus == "measured" && (airTemperatureC == null || airTemperatureC !in -60.0..70.0)) {
        errors["airTemperatureC"] = "Use −60…70 °C"
    }
    if (airTemperatureStatus == "not_measured" && airTemperatureC != null) errors["airTemperatureC"] = "Must be empty"
    if (sensorPosition !in setOf("dorsal_neck", "left_neck", "right_neck", "chest", "back", "other")) {
        errors["sensorPosition"] = "Required"
    }
    detail("sensorPositionDetails", sensorPosition == "other", sensorPositionDetails, 300)
    if (collarTightness !in setOf("loose", "snug", "tight")) errors["collarTightness"] = "Required"
    if (preMeasurementState !in setOf("rest", "walk", "run", "play", "stress", "other", "unknown")) {
        errors["preMeasurementState"] = "Required"
    }
    detail("preMeasurementStateDetails", preMeasurementState == "other", preMeasurementStateDetails, 500)
    validateMeasurement(errors, "pulse", pulseStatus, pulseBpm?.toDouble(), 20.0..300.0)
    validateMeasurement(errors, "respiration", respirationStatus, respirationPerMinute?.toDouble(), 1.0..200.0)
    validateMeasurement(errors, "bodyTemperature", bodyTemperatureStatus, bodyTemperatureC, 30.0..45.0)
    val anyMeasured = listOf(pulseStatus, respirationStatus, bodyTemperatureStatus).any { it == "measured" }
    if (anyMeasured && (measurementAtUtc == null || runCatching { Instant.parse(measurementAtUtc) }.isFailure)) {
        errors["measurementAtUtc"] = "Enter a valid measurement time"
    }
    if (!anyMeasured && measurementAtUtc != null) errors["measurementAtUtc"] = "Must be empty"
    return QuestionnaireValidation(errors)
}

private fun validateMeasurement(
    errors: MutableMap<String, String>,
    key: String,
    status: String,
    value: Double?,
    range: ClosedFloatingPointRange<Double>,
) {
    if (status !in MEASUREMENT_STATUSES) errors["${key}Status"] = "Required"
    if (status == "measured" && (value == null || value !in range)) errors[key] = "Out of range"
    if (status == "not_measured" && value != null) errors[key] = "Must be empty"
}

val ACTIVITY_TYPES: Map<String, Set<String>> = mapOf(
    "locomotion" to setOf("walk", "trot", "gallop", "run", "stairs_up", "stairs_down", "jump", "mixed"),
    "stationary" to setOf("stand", "sit", "lie", "rest", "sleep"),
    "daily_living" to setOf("play", "eat", "drink", "scratch", "groom", "other"),
    "other" to setOf("other"),
)

val SURFACES: Map<String, Set<String>> = mapOf(
    "indoors" to setOf("tile", "concrete", "wood", "laminate", "carpet", "bed", "kennel_mat", "other"),
    "outdoors" to setOf("asphalt", "concrete", "grass", "soil", "gravel", "snow", "other"),
)

private val MEASUREMENT_STATUSES = setOf("measured", "not_measured")
private val OBSERVED_SIGNS = setOf(
    "none",
    "unknown",
    "labored_breathing",
    "fainting",
    "seizures",
    "cannot_urinate",
    "limb_weakness",
    "jaundice",
    "vomiting_or_no_appetite",
    "stool_changes",
    "cough",
    "pain_or_lameness",
    "thirst_changes",
    "distress",
)

enum class RecordingSource(val value: String) {
    LIVE("live"),
    REPLAY("replay"),
}

enum class RecordingStatus(val value: String) {
    PREPARING("preparing"),
    RECORDING("recording"),
    COMPLETED("completed"),
    FAILED("failed"),
    INTERRUPTED("interrupted"),
}

enum class ArtifactType(val value: String) {
    PACKET("packet"),
    PACKET_TIMELINE("packet_timeline"),
    RAW("raw"),
    DIAGNOSTIC("diagnostic"),
    CSV("csv"),
    VIDEO("video"),
    SYNC("sync"),
    IMPORTED_SOURCE("imported_source"),
    ECG("ecg"),
    HEART_RATE("heart_rate"),
    RR("rr"),
}

data class SyncClockAnchor(
    val event: String,
    val absoluteUtc: String,
    val wallClockEpochMillis: Long,
    val monotonicTimeNs: Long,
    val monotonicClock: String,
    val samplingUncertaintyNs: Long,
)

data class VideoSyncMetadata(
    val fileName: String = "video.mp4",
    val requestedAtUtc: String,
    val requestedMonotonicNs: Long,
    val mediaRecorderStartedMonotonicNs: Long? = null,
    val firstFrameAtUtc: String? = null,
    val firstFrameEpochMillis: Double? = null,
    val firstFrameMonotonicNs: Long? = null,
    val firstFrameCameraTimestampNs: Long? = null,
    val firstFrameCallbackMonotonicNs: Long? = null,
    val firstVideoSamplePtsUs: Long? = null,
    val offsetFromSensorNs: Long? = null,
    val cameraTimestampSource: String? = null,
    val synchronizationQuality: String? = null,
    val cameraId: String? = null,
    val lensFacing: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Int? = null,
    val rotationDegrees: Int? = null,
    val codec: String = "h264",
    val container: String = "mp4",
    val stoppedAtUtc: String? = null,
    val stoppedMonotonicNs: Long? = null,
    val durationNs: Long? = null,
)

data class CaptureSyncMetadata(
    val schemaVersion: Int = 2,
    val recordingId: String,
    val profileId: String,
    val source: String,
    val timezone: String,
    val selectedSessionStartUtc: String,
    val sensor: SyncClockAnchor? = null,
    val firstSensorDeviceTimerMillis: Long? = null,
    val lastSensorPacketMonotonicNs: Long? = null,
    val video: VideoSyncMetadata? = null,
)

fun monotonicOffsetNs(anchor: SyncClockAnchor, monotonicTimeNs: Long): Long =
    monotonicTimeNs - anchor.monotonicTimeNs

fun absoluteInstantForMonotonic(anchor: SyncClockAnchor, monotonicTimeNs: Long): Instant =
    Instant.ofEpochMilli(anchor.wallClockEpochMillis)
        .plusNanos(monotonicOffsetNs(anchor, monotonicTimeNs))

data class DogProfile(
    val id: String,
    val profileVersionId: String,
    val numberOrName: String,
    val questionnaire: DogQuestionnaire,
    val validationState: String,
    val createdAtUtc: String,
    val updatedAtUtc: String,
    val accountId: String? = null,
)

data class Artifact(
    val id: String,
    val recordingId: String,
    val type: ArtifactType,
    val fileName: String,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val localPresence: String,
    val uploadState: String,
    val uploadedBytes: Long,
    val createdAtUtc: String,
    val referenceMetadataJson: String? = null,
)

data class Recording(
    val id: String,
    val profileId: String,
    val profileVersionId: String,
    val source: RecordingSource,
    val status: RecordingStatus,
    val sessionLabel: String,
    val videoRequested: Boolean,
    val startedAtUtc: String,
    val endedAtUtc: String?,
    val timezone: String,
    val questionnaire: SessionQuestionnaire?,
    val relativeDirectory: String,
    val artifacts: List<Artifact> = emptyList(),
    val serverSyncState: String = "pending",
    val questionnaireRevision: Int = 1,
    val captureErrorCode: String? = null,
    val captureErrorMessage: String? = null,
    val serverSyncError: String? = null,
    val remoteOrigin: Boolean = false,
    val serverReceiptSha256: String? = null,
) {
    val requiresServerQuestionnaireEdit: Boolean
        get() = remoteOrigin || serverReceiptSha256 != null || serverSyncState in setOf("synced", "remote")
}

data class RecordingHistoryCursor(val sortTimeMillis: Long, val id: String, val snapshotRowId: Long? = null)

data class RecordingHistoryPage(
    val recordings: List<Recording>,
    val totalCount: Int,
    val nextCursor: RecordingHistoryCursor?,
)

data class RecordingSummary(
    val recording: Recording,
    val profileName: String,
    val profileQuestionnaire: DogQuestionnaire,
)

fun DogQuestionnaire.toJson(): String = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("numberOrName", numberOrName)
    put("shelterOrPlace", shelterOrPlace)
    put("breedStatus", breedStatus)
    putNullable("breedName", breedName)
    putNullable("resembles", resembles)
    put("size", size)
    put("ageStatus", ageStatus)
    putNullable("ageYears", ageYears)
    putNullable("ageMonths", ageMonths)
    put("ageSource", ageSource)
    put("sex", sex)
    put("sterilizationStatus", sterilizationStatus)
    put("weightStatus", weightStatus)
    putNullable("weightKg", weightKg)
    put("bodyConditionStatus", bodyConditionStatus)
    putNullable("bodyConditionScore", bodyConditionScore)
    put("muscleMass", muscleMass)
    put("neckCircumferenceStatus", neckCircumferenceStatus)
    putNullable("neckCircumferenceCm", neckCircumferenceCm)
    put("coatLength", coatLength)
    put("undercoat", undercoat)
    put("shavedAreasStatus", shavedAreasStatus)
    putNullable("shavedAreasDetails", shavedAreasDetails)
    put("observedSigns", JSONArray(observedSigns))
    put("diagnosesStatus", diagnosesStatus)
    putNullable("diagnosesDetails", diagnosesDetails)
    put("housing", housing)
    putNullable("housingDetails", housingDetails)
    put("walksStatus", walksStatus)
    putNullable("walksDescription", walksDescription)
    put("cohabitants", cohabitants)
    put("shelterPermission", shelterPermission)
    put("notesStatus", notesStatus)
    putNullable("notes", notes)
    if (schemaVersion == 2) {
        putNullable("animalId", animalId)
        putNullable("species", species)
        putNullable("savedAtLocal", savedAtLocal)
        putNullable("diseaseCategory", diseaseCategory)
        putNullable("diseaseCategoryDetails", diseaseCategoryDetails)
        putNullable("chronicLameness", chronicLameness)
        putNullable("medications", medications)
        putNullable("history", history)
        putNullable("specialistName", specialistName)
    }
}.toString()

fun dogQuestionnaireFromJson(json: String): DogQuestionnaire {
    val value = JSONObject(json)
    if (!value.has("schemaVersion")) return legacyDogQuestionnaire(value)
    return DogQuestionnaire(
        schemaVersion = value.getInt("schemaVersion"),
        numberOrName = value.getString("numberOrName"),
        shelterOrPlace = value.getString("shelterOrPlace"),
        breedStatus = value.getString("breedStatus"),
        breedName = value.nullableString("breedName"),
        resembles = value.nullableString("resembles"),
        size = value.getString("size"),
        ageStatus = value.getString("ageStatus"),
        ageYears = value.nullableInt("ageYears"),
        ageMonths = value.nullableInt("ageMonths"),
        ageSource = value.getString("ageSource"),
        sex = value.getString("sex"),
        sterilizationStatus = value.getString("sterilizationStatus"),
        weightStatus = value.getString("weightStatus"),
        weightKg = value.nullableDouble("weightKg"),
        bodyConditionStatus = value.getString("bodyConditionStatus"),
        bodyConditionScore = value.nullableInt("bodyConditionScore"),
        muscleMass = value.getString("muscleMass"),
        neckCircumferenceStatus = value.getString("neckCircumferenceStatus"),
        neckCircumferenceCm = value.nullableDouble("neckCircumferenceCm"),
        coatLength = value.getString("coatLength"),
        undercoat = value.getString("undercoat"),
        shavedAreasStatus = value.getString("shavedAreasStatus"),
        shavedAreasDetails = value.nullableString("shavedAreasDetails"),
        observedSigns = value.optJSONArray("observedSigns").toStringList(),
        diagnosesStatus = value.getString("diagnosesStatus"),
        diagnosesDetails = value.nullableString("diagnosesDetails"),
        housing = value.getString("housing"),
        housingDetails = value.nullableString("housingDetails"),
        walksStatus = value.getString("walksStatus"),
        walksDescription = value.nullableString("walksDescription"),
        cohabitants = value.getString("cohabitants"),
        shelterPermission = value.getString("shelterPermission"),
        notesStatus = value.getString("notesStatus"),
        notes = value.nullableString("notes"),
        animalId = value.nullableString("animalId"),
        species = value.nullableString("species"),
        savedAtLocal = value.nullableString("savedAtLocal"),
        diseaseCategory = value.nullableString("diseaseCategory"),
        diseaseCategoryDetails = value.nullableString("diseaseCategoryDetails"),
        chronicLameness = value.nullableString("chronicLameness"),
        medications = value.nullableString("medications"),
        history = value.nullableString("history"),
        specialistName = value.nullableString("specialistName"),
    )
}

fun SessionQuestionnaire.toJson(): String = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("sessionLabel", sessionLabel)
    put("operatorName", operatorName)
    put("activityGroup", activityGroup)
    put("activityType", activityType)
    putNullable("activityDetails", activityDetails)
    put("location", location)
    put("surface", surface)
    putNullable("surfaceDetails", surfaceDetails)
    put("airTemperatureStatus", airTemperatureStatus)
    putNullable("airTemperatureC", airTemperatureC)
    put("sensorPosition", sensorPosition)
    putNullable("sensorPositionDetails", sensorPositionDetails)
    put("collarTightness", collarTightness)
    put("preMeasurementState", preMeasurementState)
    putNullable("preMeasurementStateDetails", preMeasurementStateDetails)
    put("pulseStatus", pulseStatus)
    putNullable("pulseBpm", pulseBpm)
    put("respirationStatus", respirationStatus)
    putNullable("respirationPerMinute", respirationPerMinute)
    put("bodyTemperatureStatus", bodyTemperatureStatus)
    putNullable("bodyTemperatureC", bodyTemperatureC)
    putNullable("measurementAtUtc", measurementAtUtc)
    put("videoRequested", videoRequested)
    if (schemaVersion == 2) {
        sessionKind?.let { put("sessionKind", it) }
        heartQuestionnaire?.let { put("heartQuestionnaire", it.toJson()) }
        putNullable("animalId", animalId)
        putNullable("savedAtLocal", savedAtLocal)
        putNullable("sessionDate", sessionDate)
        putNullable("startTime", startTime)
        putNullable("endTime", endTime)
        putNullable("durationMinutes", durationMinutes)
        put("plannedActivities", JSONArray(plannedActivities))
        put("surfaces", JSONArray(surfaces))
        putNullable("lastMedicationAt", lastMedicationAt)
        putNullable("notes", notes)
        putNullable("specialistName", specialistName)
    }
}.toString()

fun CaptureSyncMetadata.toJson(
    dogQuestionnaire: DogQuestionnaire,
    sessionQuestionnaire: SessionQuestionnaire?,
): String = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("recordingId", recordingId)
    put("profileId", profileId)
    put("source", source)
    put("timezone", timezone)
    put("selectedSessionStartUtc", selectedSessionStartUtc)
    putNullable("firstSensorDeviceTimerMillis", firstSensorDeviceTimerMillis)
    putNullable("lastSensorPacketMonotonicNs", lastSensorPacketMonotonicNs)
    put("dogQuestionnaire", JSONObject(dogQuestionnaire.toJson()))
    put(
        "sessionQuestionnaire",
        sessionQuestionnaire?.let { JSONObject(it.toJson()) } ?: JSONObject.NULL,
    )
    put(
        "sensor",
        sensor?.let {
            JSONObject().apply {
                put("event", it.event)
                put("absoluteUtc", it.absoluteUtc)
                put("wallClockEpochMillis", it.wallClockEpochMillis)
                put("monotonicTimeNs", it.monotonicTimeNs)
                put("monotonicClock", it.monotonicClock)
                put("samplingUncertaintyNs", it.samplingUncertaintyNs)
            }
        } ?: JSONObject.NULL,
    )
    put(
        "video",
        video?.let {
            JSONObject().apply {
                put("fileName", it.fileName)
                put("requestedAtUtc", it.requestedAtUtc)
                put("requestedMonotonicNs", it.requestedMonotonicNs)
                putNullable("mediaRecorderStartedMonotonicNs", it.mediaRecorderStartedMonotonicNs)
                putNullable("firstFrameAtUtc", it.firstFrameAtUtc)
                putNullable("firstFrameEpochMillis", it.firstFrameEpochMillis)
                putNullable("firstFrameMonotonicNs", it.firstFrameMonotonicNs)
                putNullable("firstFrameCameraTimestampNs", it.firstFrameCameraTimestampNs)
                putNullable("firstFrameCallbackMonotonicNs", it.firstFrameCallbackMonotonicNs)
                putNullable("firstVideoSamplePtsUs", it.firstVideoSamplePtsUs)
                putNullable("offsetFromSensorNs", it.offsetFromSensorNs)
                putNullable("cameraTimestampSource", it.cameraTimestampSource)
                putNullable("synchronizationQuality", it.synchronizationQuality)
                putNullable("cameraId", it.cameraId)
                putNullable("lensFacing", it.lensFacing)
                putNullable("width", it.width)
                putNullable("height", it.height)
                putNullable("frameRate", it.frameRate)
                putNullable("rotationDegrees", it.rotationDegrees)
                put("codec", it.codec)
                put("container", it.container)
                putNullable("stoppedAtUtc", it.stoppedAtUtc)
                putNullable("stoppedMonotonicNs", it.stoppedMonotonicNs)
                putNullable("durationNs", it.durationNs)
            }
        } ?: JSONObject.NULL,
    )
}.toString()

fun sessionQuestionnaireFromJson(json: String): SessionQuestionnaire {
    val value = JSONObject(json)
    if (!value.has("schemaVersion")) return legacySessionQuestionnaire(value)
    return SessionQuestionnaire(
        schemaVersion = value.getInt("schemaVersion"),
        sessionLabel = value.getString("sessionLabel"),
        operatorName = value.getString("operatorName"),
        activityGroup = value.getString("activityGroup"),
        activityType = value.getString("activityType"),
        activityDetails = value.nullableString("activityDetails"),
        location = value.getString("location"),
        surface = value.getString("surface"),
        surfaceDetails = value.nullableString("surfaceDetails"),
        airTemperatureStatus = value.getString("airTemperatureStatus"),
        airTemperatureC = value.nullableDouble("airTemperatureC"),
        sensorPosition = value.getString("sensorPosition"),
        sensorPositionDetails = value.nullableString("sensorPositionDetails"),
        collarTightness = value.getString("collarTightness"),
        preMeasurementState = value.getString("preMeasurementState"),
        preMeasurementStateDetails = value.nullableString("preMeasurementStateDetails"),
        pulseStatus = value.getString("pulseStatus"),
        pulseBpm = value.nullableInt("pulseBpm"),
        respirationStatus = value.getString("respirationStatus"),
        respirationPerMinute = value.nullableInt("respirationPerMinute"),
        bodyTemperatureStatus = value.getString("bodyTemperatureStatus"),
        bodyTemperatureC = value.nullableDouble("bodyTemperatureC"),
        measurementAtUtc = value.nullableString("measurementAtUtc"),
        videoRequested = value.getBoolean("videoRequested"),
        animalId = value.nullableString("animalId"),
        savedAtLocal = value.nullableString("savedAtLocal"),
        sessionDate = value.nullableString("sessionDate"),
        startTime = value.nullableString("startTime"),
        endTime = value.nullableString("endTime"),
        durationMinutes = value.nullableDouble("durationMinutes"),
        plannedActivities = value.optJSONArray("plannedActivities").toStringList(),
        surfaces = value.optJSONArray("surfaces").toStringList(),
        lastMedicationAt = value.nullableString("lastMedicationAt"),
        notes = value.nullableString("notes"),
        specialistName = value.nullableString("specialistName"),
        sessionKind = value.nullableString("sessionKind"),
        heartQuestionnaire = value.optJSONObject("heartQuestionnaire")?.let(HeartQuestionnaire::fromJson),
    )
}

fun sessionStartInstant(
    questionnaire: SessionQuestionnaire?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
): Instant {
    return now
}

fun recordingRelativeDirectory(
    profileId: String,
    recordingId: String,
    startedAt: Instant,
    timezone: String,
): String {
    val day = startedAt.atZone(ZoneId.of(timezone)).toLocalDate()
    return "recordings/$profileId/$day/$recordingId"
}

class WoonaDatabase(
    context: Context,
    val rootDirectory: File = File(context.filesDir, "Woona"),
) : SQLiteOpenHelper(
    context,
    File(rootDirectory, "woona.sqlite").absolutePath,
    null,
    DATABASE_VERSION,
) {
    init {
        rootDirectory.mkdirs()
        writableDatabase
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 8) createRetirementCleanupTable(db)
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE artifacts RENAME TO artifacts_v1")
            db.execSQL("DROP INDEX IF EXISTS artifacts_recording")
            createLegacyArtifactsTable(db)
            db.execSQL(
                """
                INSERT INTO artifacts(id,recording_id,type,relative_path,size_bytes,created_at_utc)
                SELECT id,recording_id,type,relative_path,size_bytes,created_at_utc FROM artifacts_v1
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE artifacts_v1")
            db.execSQL("CREATE INDEX artifacts_recording ON artifacts(recording_id)")
        }
        if (oldVersion < 3) migrateV2ToV3(db)
        if (oldVersion < 4) repairLegacySyncRows(db)
        if (oldVersion < 5) repairCanonicalProfileHashes(db)
        if (oldVersion in 3..6) {
            fun hasColumn(table: String, name: String): Boolean = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(1) == name) found = true
                found
            }
            if (!hasColumn("dogs", "account_id")) db.execSQL("ALTER TABLE dogs ADD COLUMN account_id TEXT")
            if (!hasColumn("recordings", "remote_copy")) db.execSQL("ALTER TABLE recordings ADD COLUMN remote_copy INTEGER NOT NULL DEFAULT 0")
            if (!hasColumn("recordings", "questionnaire_revision")) db.execSQL("ALTER TABLE recordings ADD COLUMN questionnaire_revision INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE artifacts RENAME TO artifacts_v5")
            db.execSQL("DROP INDEX IF EXISTS artifacts_recording")
            createArtifactsTable(db)
            val columns = "id,recording_id,type,file_name,mime_type,relative_path,size_bytes,sha256,hash_state,local_presence,upload_state,uploaded_bytes,server_relative_path,server_verified_at_utc,last_error_code,last_error_message,created_at_utc"
            db.execSQL("INSERT INTO artifacts($columns) SELECT $columns FROM artifacts_v5")
            db.execSQL("DROP TABLE artifacts_v5")
            db.execSQL("CREATE INDEX artifacts_recording ON artifacts(recording_id)")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) db.execSQL("CREATE INDEX IF NOT EXISTS recordings_history_time ON recordings(dog_id,$HISTORY_TIME_KEY DESC,id DESC)")
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE dogs (
                id TEXT PRIMARY KEY,
                account_id TEXT,
                number_or_name TEXT NOT NULL CHECK(length(trim(number_or_name)) > 0),
                server_revision INTEGER NOT NULL DEFAULT 0,
                created_at_utc TEXT NOT NULL,
                updated_at_utc TEXT NOT NULL,
                archived_at_utc TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE dog_profile_versions (
                id TEXT PRIMARY KEY,
                dog_id TEXT NOT NULL REFERENCES dogs(id) ON DELETE RESTRICT,
                schema_version INTEGER NOT NULL,
                validation_state TEXT NOT NULL CHECK(validation_state IN ('complete','legacy_incomplete')),
                questionnaire_json TEXT NOT NULL,
                content_sha256 TEXT NOT NULL,
                client_created_at_utc TEXT NOT NULL,
                superseded_at_utc TEXT,
                server_sync_state TEXT NOT NULL DEFAULT 'pending'
                    CHECK(server_sync_state IN ('pending','uploading','synced','retryable_error','permanent_error')),
                server_revision INTEGER,
                sync_attempt_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at_utc TEXT,
                last_error_code TEXT,
                last_error_message TEXT,
                server_synced_at_utc TEXT,
                UNIQUE(dog_id, content_sha256)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX dog_profile_one_current ON dog_profile_versions(dog_id) WHERE superseded_at_utc IS NULL",
        )
        db.execSQL(
            "CREATE INDEX dog_profile_sync ON dog_profile_versions(server_sync_state, client_created_at_utc) WHERE server_sync_state <> 'synced'",
        )
        db.execSQL(
            """
            CREATE TABLE recordings (
                id TEXT PRIMARY KEY,
                questionnaire_revision INTEGER NOT NULL DEFAULT 1,
                remote_copy INTEGER NOT NULL DEFAULT 0,
                dog_id TEXT NOT NULL REFERENCES dogs(id) ON DELETE RESTRICT,
                dog_profile_version_id TEXT NOT NULL REFERENCES dog_profile_versions(id) ON DELETE RESTRICT,
                source TEXT NOT NULL CHECK(source IN ('live','replay')),
                status TEXT NOT NULL CHECK(status IN ('preparing','recording','completed','failed','interrupted')),
                session_label TEXT NOT NULL,
                questionnaire_schema_version INTEGER NOT NULL,
                questionnaire_validation_state TEXT NOT NULL CHECK(questionnaire_validation_state IN ('complete','legacy_incomplete')),
                questionnaire_json TEXT,
                video_requested INTEGER NOT NULL CHECK(video_requested IN (0,1)),
                started_at_utc TEXT,
                ended_at_utc TEXT,
                timezone TEXT NOT NULL,
                sensor_hardware_id TEXT,
                app_version TEXT NOT NULL,
                protocol_version TEXT,
                capture_error_code TEXT,
                capture_error_message TEXT,
                relative_directory TEXT NOT NULL UNIQUE,
                client_created_at_utc TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX recordings_profile_started ON recordings(dog_id, started_at_utc DESC)")
        createSyncTable(db)
        createArtifactsTable(db)
        createServerSyncTable(db)
        createRetirementCleanupTable(db)
        db.execSQL("CREATE INDEX artifacts_recording ON artifacts(recording_id)")
        db.execSQL("CREATE INDEX artifacts_upload ON artifacts(upload_state, recording_id) WHERE upload_state <> 'available'")
        db.execSQL("PRAGMA user_version=$DATABASE_VERSION")
    }

    private fun migrateV2ToV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE dog_profiles RENAME TO dog_profiles_legacy")
        db.execSQL("ALTER TABLE recordings RENAME TO recordings_legacy")
        db.execSQL("ALTER TABLE artifacts RENAME TO artifacts_legacy")
        db.execSQL("DROP INDEX IF EXISTS recordings_profile_started")
        db.execSQL("DROP INDEX IF EXISTS artifacts_recording")
        createSchema(db)

        val profileVersions = mutableMapOf<String, String>()
        db.query(
            "dog_profiles_legacy",
            arrayOf("id", "number_or_name", "questionnaire_json", "created_at_utc", "updated_at_utc"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dogId = cursor.getString(0)
                val questionnaireJson = cursor.getString(2)
                val versionId = UUID.randomUUID().toString()
                profileVersions[dogId] = versionId
                db.insertOrThrow(
                    "dogs",
                    null,
                    ContentValues().apply {
                        put("id", dogId)
                        put("number_or_name", cursor.getString(1))
                        put("created_at_utc", cursor.getString(3))
                        put("updated_at_utc", cursor.getString(4))
                    },
                )
                db.insertOrThrow(
                    "dog_profile_versions",
                    null,
                    ContentValues().apply {
                        put("id", versionId)
                        put("dog_id", dogId)
                        put("schema_version", 1)
                        put("validation_state", "legacy_incomplete")
                        put("questionnaire_json", questionnaireJson)
                        put("content_sha256", canonicalJsonSha256(questionnaireJson))
                        put("client_created_at_utc", cursor.getString(4))
                    },
                )
            }
        }

        db.query(
            "recordings_legacy",
            arrayOf(
                "id",
                "profile_id",
                "source",
                "status",
                "started_at_utc",
                "ended_at_utc",
                "timezone",
                "questionnaire_json",
                "relative_directory",
            ),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val questionnaireJson = if (cursor.isNull(7)) null else cursor.getString(7)
                val parsed = questionnaireJson?.let(::sessionQuestionnaireFromJson)
                db.insertOrThrow(
                    "recordings",
                    null,
                    ContentValues().apply {
                        put("id", cursor.getString(0))
                        put("dog_id", cursor.getString(1))
                        put("dog_profile_version_id", requireNotNull(profileVersions[cursor.getString(1)]))
                        put("source", cursor.getString(2))
                        put("status", cursor.getString(3))
                        put("session_label", parsed?.sessionLabel ?: "Legacy recording")
                        put("questionnaire_schema_version", (parsed?.schemaVersion ?: 1).coerceAtLeast(1))
                        put("questionnaire_validation_state", "legacy_incomplete")
                        if (questionnaireJson == null) putNull("questionnaire_json") else put("questionnaire_json", questionnaireJson)
                        put("video_requested", if (parsed?.videoRequested == true) 1 else 0)
                        put("started_at_utc", cursor.getString(4))
                        if (cursor.isNull(5)) putNull("ended_at_utc") else put("ended_at_utc", cursor.getString(5))
                        put("timezone", cursor.getString(6))
                        put("app_version", "legacy")
                        put("relative_directory", cursor.getString(8))
                        put("client_created_at_utc", cursor.getString(4))
                    },
                )
                db.insertOrThrow(
                    "recording_sync",
                    null,
                    ContentValues().apply {
                        val started = cursor.getString(4)
                        put("recording_id", cursor.getString(0))
                        put("schema_version", 1)
                        put("monotonic_clock", "android.elapsedRealtimeNanos")
                        put("session_zero_at_utc", started)
                        put("session_zero_wall_clock_ms", Instant.parse(started).toEpochMilli())
                        put("session_zero_monotonic_ns", 0)
                        put("session_zero_uncertainty_ns", 0)
                        put("camera_clock_quality", "unavailable")
                        put("sensor_clock_quality", "unavailable")
                        put("overall_sync_quality", "unavailable")
                        put("updated_at_utc", Instant.now().toString())
                    },
                )
                db.insertOrThrow(
                    "server_sync_state",
                    null,
                    ContentValues().apply {
                        put("recording_id", cursor.getString(0))
                        put("updated_at_utc", Instant.now().toString())
                    },
                )
            }
        }

        db.query(
            "artifacts_legacy",
            arrayOf("id", "recording_id", "type", "relative_path", "size_bytes", "created_at_utc"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(3)
                val file = runCatching { resolveRelativePath(relativePath) }.getOrNull()
                val type = if (cursor.getString(2) == "metadata") "sync" else cursor.getString(2)
                db.insertOrThrow(
                    "artifacts",
                    null,
                    ContentValues().apply {
                        put("id", cursor.getString(0))
                        put("recording_id", cursor.getString(1))
                        put("type", type)
                        put("file_name", relativePath.substringAfterLast('/'))
                        put("mime_type", mimeTypeFor(relativePath))
                        put("relative_path", relativePath)
                        put("size_bytes", cursor.getLong(4))
                        put("sha256", ZERO_SHA256)
                        put("hash_state", if (file?.isFile == true) "pending" else "failed")
                        put("local_presence", if (file?.isFile == true) "local" else "missing")
                        put("created_at_utc", cursor.getString(5))
                    },
                )
            }
        }
        db.execSQL("DROP TABLE artifacts_legacy")
        db.execSQL("DROP TABLE recordings_legacy")
        db.execSQL("DROP TABLE dog_profiles_legacy")
    }

    private fun repairLegacySyncRows(db: SQLiteDatabase) {
        db.execSQL("UPDATE dog_profile_versions SET schema_version=1 WHERE schema_version < 1")
        db.execSQL("UPDATE recordings SET questionnaire_schema_version=1 WHERE questionnaire_schema_version < 1")
        db.rawQuery(
            """
            SELECT r.id,r.started_at_utc FROM recordings r
            LEFT JOIN recording_sync s ON s.recording_id=r.id
            WHERE s.recording_id IS NULL
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val started = cursor.getString(1)
                db.insertOrThrow(
                    "recording_sync",
                    null,
                    ContentValues().apply {
                        put("recording_id", cursor.getString(0))
                        put("schema_version", 1)
                        put("monotonic_clock", "android.elapsedRealtimeNanos")
                        put("session_zero_at_utc", started)
                        put("session_zero_wall_clock_ms", Instant.parse(started).toEpochMilli())
                        put("session_zero_monotonic_ns", 0)
                        put("session_zero_uncertainty_ns", 0)
                        put("camera_clock_quality", "unavailable")
                        put("sensor_clock_quality", "unavailable")
                        put("overall_sync_quality", "unavailable")
                        put("updated_at_utc", Instant.now().toString())
                    },
                )
            }
        }
    }

    private fun repairCanonicalProfileHashes(db: SQLiteDatabase) {
        db.query(
            "dog_profile_versions",
            arrayOf("id", "questionnaire_json"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                db.update(
                    "dog_profile_versions",
                    ContentValues().apply {
                        put("content_sha256", canonicalJsonSha256(cursor.getString(1)))
                        put("server_sync_state", "pending")
                        putNull("last_error_code")
                        putNull("last_error_message")
                    },
                    "id=?",
                    arrayOf(cursor.getString(0)),
                )
            }
        }
        db.execSQL(
            """
            UPDATE server_sync_state
            SET state='pending',last_error_code=NULL,last_error_message=NULL,
                updated_at_utc=?
            WHERE state<>'synced'
            """.trimIndent(),
            arrayOf(Instant.now().toString()),
        )
    }

    fun interruptUnfinished(now: Instant = Instant.now()): Int {
        val ids = readableDatabase.query(
            "recordings",
            arrayOf("id"),
            "status IN (?, ?)",
            arrayOf(RecordingStatus.PREPARING.value, RecordingStatus.RECORDING.value),
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        ids.forEach { id ->
            val recording = requireNotNull(recording(id))
            val files = artifactFilesIn(recordingDirectory(recording.relativeDirectory))
            writableDatabase.beginTransaction()
            try {
                writableDatabase.execSQL("""UPDATE recordings SET status='interrupted',
                    capture_error_code=COALESCE(capture_error_code,'process_interrupted'),
                    capture_error_message=COALESCE(capture_error_message,?) WHERE id=?""",
                    arrayOf("Приложение завершилось во время записи. Существующие файлы сохранены; фактическое время остановки неизвестно / The app exited during capture. Existing files are preserved; the actual stop time is unknown", id))
                // A launch timestamp cannot establish when the killed process stopped.
                // Preserve any known machine end and questionnaire answers unchanged.
                files.forEach { (type, file) -> insertArtifact(id, type, file, now) }
                val hasSync = writableDatabase.rawQuery("SELECT 1 FROM recording_sync WHERE recording_id=?", arrayOf(id)).use { it.moveToFirst() }
                if (!hasSync) writableDatabase.execSQL("""UPDATE server_sync_state SET state='permanent_error',
                    last_error_code='recovery_metadata_missing',last_error_message=?,updated_at_utc=? WHERE recording_id=?""",
                    arrayOf("Файлы прерванной записи сохранены, но метаданные синхронизации отсутствуют. Экспортируйте все данные для разбора; время и синхронизация не восстановлены / Interrupted files are preserved, but synchronization metadata is missing. Export all data for review; timing could not be recovered", now.toString(), id))
                writableDatabase.setTransactionSuccessful()
            } finally { writableDatabase.endTransaction() }
        }
        return ids.size
    }

    fun saveProfile(
        questionnaire: DogQuestionnaire,
        profileId: String? = null,
        now: Instant = Instant.now(),
        accountId: String? = null,
    ): DogProfile {
        require(questionnaire.validate().isValid) {
            "Dog questionnaire is incomplete: ${questionnaire.validate().errors.keys.joinToString()}"
        }
        val name = questionnaire.numberOrName.trim()
        val id = profileId ?: UUID.randomUUID().toString()
        val existing = profile(id)
        val createdAt = existing?.createdAtUtc ?: now.toString()
        val normalized = questionnaire.copy(numberOrName = name)
        val questionnaireJson = normalized.toJson()
        val contentSha256 = canonicalJsonSha256(questionnaireJson)
        if (existing != null && existing.questionnaire.copy(savedAtLocal = null) == normalized.copy(savedAtLocal = null)) {
            return existing
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertWithOnConflict(
                "dogs",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("account_id", accountId?.let(::normalizeAccountId))
                    put("number_or_name", name)
                    put("created_at_utc", createdAt)
                    put("updated_at_utc", now.toString())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            writableDatabase.update(
                "dogs",
                ContentValues().apply {
                    put("number_or_name", name)
                    put("updated_at_utc", now.toString())
                },
                "id=?",
                arrayOf(id),
            )
            writableDatabase.update(
                "dog_profile_versions",
                ContentValues().apply { put("superseded_at_utc", now.toString()) },
                "dog_id=? AND superseded_at_utc IS NULL",
                arrayOf(id),
            )
            writableDatabase.insertOrThrow(
                "dog_profile_versions",
                null,
                ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("dog_id", id)
                    put("schema_version", normalized.schemaVersion)
                    put("validation_state", "complete")
                    put("questionnaire_json", questionnaireJson)
                    put("content_sha256", contentSha256)
                    put("client_created_at_utc", now.toString())
                },
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return requireNotNull(profile(id))
    }

    fun profiles(): List<DogProfile> {
        return readableDatabase.rawQuery(
            """
            SELECT d.id,v.id,d.number_or_name,v.questionnaire_json,v.validation_state,
                   d.created_at_utc,d.updated_at_utc,d.account_id
            FROM dogs d
            JOIN dog_profile_versions v ON v.dog_id=d.id AND v.superseded_at_utc IS NULL
            WHERE d.archived_at_utc IS NULL
            ORDER BY d.updated_at_utc DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toProfile())
            }
        }
    }

    fun profile(id: String): DogProfile? {
        return readableDatabase.rawQuery(
            """
            SELECT d.id,v.id,d.number_or_name,v.questionnaire_json,v.validation_state,
                   d.created_at_utc,d.updated_at_utc,d.account_id
            FROM dogs d
            JOIN dog_profile_versions v ON v.dog_id=d.id AND v.superseded_at_utc IS NULL
            WHERE d.id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toProfile() else null }
    }

    fun linkDogAccount(dogId: String, accountId: String) {
        val account = normalizeAccountId(accountId)
        val existing = requireNotNull(profile(dogId)).accountId
        require(existing == null || existing == account) { "Собака уже принадлежит другому аккаунту" }
        writableDatabase.update("dogs", ContentValues().apply { put("account_id", account) }, "id=?", arrayOf(dogId))
    }

    fun beginRecording(
        profileId: String,
        source: RecordingSource,
        questionnaire: SessionQuestionnaire?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Recording {
        val selectedProfile = requireNotNull(profile(profileId)) { "Unknown dog profile" }
        val linkedQuestionnaire = if (questionnaire?.schemaVersion == 2) questionnaire.copy(
            animalId = selectedProfile.questionnaire.animalId,
            sessionDate = if (source == RecordingSource.LIVE) now.atZone(zoneId).toLocalDate().toString() else questionnaire.sessionDate,
            startTime = if (source == RecordingSource.LIVE) now.atZone(zoneId).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) else questionnaire.startTime,
        ) else questionnaire
        if (questionnaire != null) {
            require(questionnaire.validate().isValid) {
                "Session questionnaire is incomplete: ${questionnaire.validate().errors.keys.joinToString()}"
            }
        }
        val id = UUID.randomUUID().toString()
        val startedAt = sessionStartInstant(questionnaire, zoneId, now)
        val timezone = zoneId.id
        val relativeDirectory = recordingRelativeDirectory(profileId, id, startedAt, timezone)
        recordingDirectory(relativeDirectory).mkdirs()
        val values = ContentValues().apply {
            put("id", id)
            put("dog_id", profileId)
            put("dog_profile_version_id", requireNotNull(profile(profileId)).profileVersionId)
            put("source", source.value)
            put("status", RecordingStatus.PREPARING.value)
            put("session_label", questionnaire?.sessionLabel ?: "Imported replay")
            put("questionnaire_schema_version", questionnaire?.schemaVersion ?: 0)
            put("questionnaire_validation_state", if (questionnaire == null) "legacy_incomplete" else "complete")
            put("video_requested", if (questionnaire?.videoRequested == true) 1 else 0)
            put("started_at_utc", startedAt.toString())
            putNull("ended_at_utc")
            put("timezone", timezone)
            put("app_version", com.example.myapplication.BuildConfig.VERSION_NAME)
            if (linkedQuestionnaire == null) putNull("questionnaire_json") else put("questionnaire_json", linkedQuestionnaire.toJson())
            put("relative_directory", relativeDirectory)
            put("client_created_at_utc", now.toString())
        }
        writableDatabase.beginTransaction()
        try {
            check(writableDatabase.insertOrThrow("recordings", null, values) != -1L)
            writableDatabase.insertOrThrow(
                "server_sync_state",
                null,
                ContentValues().apply {
                    put("recording_id", id)
                    put("updated_at_utc", now.toString())
                },
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return requireNotNull(recording(id))
    }

    fun markRecording(recordingId: String) {
        val values = ContentValues().apply {
            put("status", RecordingStatus.RECORDING.value)
        }
        writableDatabase.update(
            "recordings",
            values,
            "id=? AND status=?",
            arrayOf(recordingId, RecordingStatus.PREPARING.value),
        )
    }

    fun markCaptureError(recordingId: String, code: String, message: String) {
        writableDatabase.update(
            "recordings",
            ContentValues().apply {
                put("capture_error_code", code.take(100))
                put("capture_error_message", message.take(1000))
            },
            "id=?",
            arrayOf(recordingId),
        )
    }

    fun finishRecording(
        recordingId: String,
        status: RecordingStatus,
        now: Instant = Instant.now(),
    ) {
        require(status in TERMINAL_STATUSES)
        val recording = requireNotNull(recording(recordingId))
        val files = artifactFilesIn(recordingDirectory(recording.relativeDirectory))
        writableDatabase.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("status", status.value)
                put("ended_at_utc", now.toString())
                if (recording.questionnaire?.schemaVersion == 2 && recording.source == RecordingSource.LIVE) {
                    put("questionnaire_json", recording.questionnaire.copy(
                        endTime = now.atZone(ZoneId.of(recording.timezone)).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                        durationMinutes = java.time.Duration.between(Instant.parse(recording.startedAtUtc), now).toMillis() / 60000.0,
                    ).toJson())
                }
            }
            writableDatabase.update("recordings", values, "id=?", arrayOf(recordingId))
            files.forEach { (type, file) -> insertArtifact(recordingId, type, file, now) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun registerCsv(recordingId: String, file: File, now: Instant = Instant.now()) {
        registerArtifact(recordingId, ArtifactType.CSV, file, now)
    }

    fun saveHeartQuestionnaire(recordingId: String, heart: HeartQuestionnaire) {
        require(heart.validate().isValid)
        val recording = requireNotNull(recording(recordingId))
        require(recording.status in setOf(RecordingStatus.COMPLETED, RecordingStatus.FAILED, RecordingStatus.INTERRUPTED) && recording.questionnaire?.sessionKind == "heart")
        saveSessionQuestionnaire(recordingId, recording.questionnaire.copy(heartQuestionnaire = heart))
    }

    fun saveSessionQuestionnaire(recordingId: String, questionnaire: SessionQuestionnaire) {
        val recording = requireNotNull(recording(recordingId))
        val original = requireNotNull(recording.questionnaire)
        require(!recording.requiresServerQuestionnaireEdit) { "Для изменения восстановленной серверной анкеты подключитесь к серверу. Черновик сохранён в редакторе / Connect to the server to edit a restored server questionnaire. Your draft remains in the editor" }
        require(recording.status in setOf(RecordingStatus.COMPLETED, RecordingStatus.FAILED, RecordingStatus.INTERRUPTED) && recording.serverSyncState != "synced" && recording.serverSyncState != "uploading")
        require(questionnaire.validate().isValid)
        require(original.animalId == questionnaire.animalId && original.videoRequested == questionnaire.videoRequested && original.sessionKind == questionnaire.sessionKind)
        val sync = File(recordingDirectory(recording.relativeDirectory), "sync.json")
        if (sync.isFile) {
            val temporary = File(sync.parentFile, "sync.json.tmp")
            temporary.writeText(JSONObject(sync.readText()).put("sessionQuestionnaire", JSONObject(questionnaire.toJson())).toString())
            Files.move(temporary.toPath(), sync.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("recordings", ContentValues().apply { put("questionnaire_json", questionnaire.toJson()); put("session_label", questionnaire.sessionLabel) }, "id=?", arrayOf(recordingId))
            if (sync.isFile) insertArtifact(recordingId, ArtifactType.SYNC, sync, Instant.now())
            writableDatabase.update("server_sync_state", ContentValues().apply {
                put("state", "pending"); putNull("last_error_code"); putNull("last_error_message")
            }, "recording_id=?", arrayOf(recordingId))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun attachReference(recordingId: String, type: ArtifactType, name: String, input: java.io.InputStream, metadata: ReferenceMetadata) {
        require(type in setOf(ArtifactType.ECG, ArtifactType.HEART_RATE, ArtifactType.RR) && metadata.validate().isValid)
        val recording = requireNotNull(recording(recordingId))
        require(recording.status in setOf(RecordingStatus.COMPLETED, RecordingStatus.FAILED, RecordingStatus.INTERRUPTED))
        val cleanName = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").ifBlank { "data.bin" }
        val safeName = String(cleanName.toByteArray(Charsets.UTF_8).takeLast(180).toByteArray(), Charsets.UTF_8)
        val target = File(recordingDirectory(recording.relativeDirectory), "reference-${UUID.randomUUID()}-$safeName")
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            input.use { source -> temporary.outputStream().use { source.copyTo(it) } }
            require(temporary.length() > 0) { "Контрольный файл пуст" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            writableDatabase.beginTransaction()
            try {
                insertArtifact(recordingId, type, target, Instant.now())
                writableDatabase.update("artifacts", ContentValues().apply { put("reference_metadata_json", metadata.toJson()); put("mime_type", "application/octet-stream") },
                    "recording_id=? AND file_name=?", arrayOf(recordingId, target.name))
                writableDatabase.update("server_sync_state", ContentValues().apply { put("state", "pending") }, "recording_id=?", arrayOf(recordingId))
                writableDatabase.setTransactionSuccessful()
            } finally { writableDatabase.endTransaction() }
        } catch (error: Exception) { target.delete(); throw error }
        finally { temporary.delete() }
    }

    fun registerArtifact(
        recordingId: String,
        type: ArtifactType,
        file: File,
        now: Instant = Instant.now(),
    ) {
        writableDatabase.beginTransaction()
        try {
            insertArtifact(recordingId, type, file, now)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun writeSyncMetadata(
        recordingId: String,
        metadata: CaptureSyncMetadata,
        now: Instant = Instant.now(),
    ): File {
        val recording = requireNotNull(recording(recordingId))
        require(metadata.recordingId == recordingId && metadata.profileId == recording.profileId)
        val profile = requireNotNull(profile(recording.profileId))
        val directory = recordingDirectory(recording.relativeDirectory)
        val target = File(directory, "sync.json")
        val temporary = File(directory, "sync.json.tmp")
        temporary.writeText(
            metadata.toJson(profile.questionnaire, recording.questionnaire),
            Charsets.UTF_8,
        )
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        writableDatabase.insertWithOnConflict(
            "recording_sync",
            null,
            ContentValues().apply {
                put("recording_id", recordingId)
                put("schema_version", metadata.schemaVersion)
                put("monotonic_clock", "android.elapsedRealtimeNanos")
                put("session_zero_at_utc", metadata.sensor?.absoluteUtc ?: metadata.selectedSessionStartUtc)
                put("session_zero_wall_clock_ms", metadata.sensor?.wallClockEpochMillis ?: Instant.parse(metadata.selectedSessionStartUtc).toEpochMilli())
                put("session_zero_monotonic_ns", metadata.sensor?.monotonicTimeNs ?: 0L)
                put("session_zero_uncertainty_ns", metadata.sensor?.samplingUncertaintyNs ?: 0L)
                metadata.sensor?.monotonicTimeNs?.let { put("first_sensor_packet_monotonic_ns", it) }
                metadata.firstSensorDeviceTimerMillis?.let { put("first_sensor_device_timer_ms", it) }
                metadata.lastSensorPacketMonotonicNs?.let { put("last_sensor_packet_monotonic_ns", it) }
                metadata.video?.requestedMonotonicNs?.let { put("video_requested_monotonic_ns", it) }
                metadata.video?.mediaRecorderStartedMonotonicNs?.let { put("media_recorder_started_monotonic_ns", it) }
                metadata.video?.firstFrameMonotonicNs?.let { put("video_first_frame_monotonic_ns", it) }
                metadata.video?.firstFrameCameraTimestampNs?.let { put("video_first_frame_camera_timestamp_ns", it) }
                metadata.video?.firstFrameCallbackMonotonicNs?.let { put("video_first_frame_callback_monotonic_ns", it) }
                metadata.video?.firstVideoSamplePtsUs?.let { put("video_first_sample_pts_us", it) }
                metadata.video?.offsetFromSensorNs?.let { put("video_offset_from_sensor_ns", it) }
                metadata.video?.cameraTimestampSource?.let { put("camera_timestamp_source", it) }
                put(
                    "camera_clock_quality",
                    when {
                        metadata.video?.cameraTimestampSource == "realtime" -> "hardware_monotonic"
                        metadata.video?.firstFrameMonotonicNs != null -> "callback_estimate"
                        else -> "unavailable"
                    },
                )
                put("sensor_clock_quality", when {
                    metadata.sensor == null -> "unavailable"
                    metadata.sensor.event == "v2_time_sync" -> "calibrated"
                    else -> "first_packet_arrival"
                })
                put("overall_sync_quality", metadata.video?.synchronizationQuality ?: "unavailable")
                put("updated_at_utc", now.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        registerArtifact(recordingId, ArtifactType.SYNC, target, now)
        return target
    }

    fun recentRecordings(profileId: String, limit: Int = 10): List<Recording> =
        recordingRows(profileId, limit.coerceAtLeast(0), null).map { it.recording }

    /** Bounded keyset pages: new captures cannot shift offsets and duplicate old rows. */
    fun recordingHistoryPage(profileId: String, limit: Int = 100, after: RecordingHistoryCursor? = null, knownTotalCount: Int? = null): RecordingHistoryPage {
        require(limit in 1..100) { "History page size must be 1–100" }
        require(knownTotalCount == null || knownTotalCount >= 0)
        // New records (including backdated imports) belong to the next refresh.
        val firstSnapshot = if (after?.snapshotRowId == null) readableDatabase.rawQuery(
            "SELECT COUNT(*),COALESCE(MAX(rowid),0) FROM recordings WHERE dog_id=?", arrayOf(profileId),
        ).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) } else null
        val watermark = after?.snapshotRowId ?: requireNotNull(firstSnapshot).second
        val total = knownTotalCount ?: firstSnapshot?.first ?: readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM recordings WHERE dog_id=? AND rowid<=?", arrayOf(profileId,watermark.toString()),
        ).use { it.moveToFirst(); it.getInt(0) }
        val rows = recordingRows(profileId, limit + 1, after, watermark)
        val visible = rows.take(limit)
        return RecordingHistoryPage(visible.map { it.recording }, maxOf(total, visible.size), if (rows.size > limit) {
            visible.last().let { RecordingHistoryCursor(it.sortTimeMillis, it.recording.id, watermark) }
        } else null)
    }

    private data class HistoryRow(val recording: Recording, val sortTimeMillis: Long)

    private fun recordingRows(profileId: String, limit: Int, after: RecordingHistoryCursor?, snapshotRowId: Long? = null): List<HistoryRow> {
        if (limit == 0) return emptyList()
        val timeKey = HISTORY_TIME_KEY.replace("started_at_utc", "r.started_at_utc")
        val projection = RECORDING_COLUMNS.dropLast(1).joinToString(",") { if (it == "started_at_utc") "COALESCE(r.started_at_utc,'')" else "r.$it" } +
            ",s.server_receipt_sha256,COALESCE(s.state,'pending')," +
            "CASE WHEN s.state IN ('retryable_error','permanent_error') THEN COALESCE(NULLIF(s.last_error_message,''),s.last_error_code) END,$timeKey"
        val cursorClause = if (after == null) "" else " AND ($timeKey,r.id)<(CAST(? AS INTEGER),?)"
        val watermarkClause = if (snapshotRowId == null) "" else " AND r.rowid<=?"
        val args = buildList {
            add(profileId); snapshotRowId?.let { add(it.toString()) }
            after?.let { add(it.sortTimeMillis.toString()); add(it.id) }; add(limit.toString())
        }.toTypedArray()
        val rows = readableDatabase.rawQuery(
            "SELECT $projection FROM recordings r LEFT JOIN server_sync_state s ON s.recording_id=r.id " +
                "WHERE r.dog_id=?$watermarkClause$cursorClause ORDER BY $timeKey DESC,r.id DESC LIMIT ?", args,
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(HistoryRow(cursor.toRecording().copy(
                serverSyncState = cursor.getString(17),
                serverSyncError = if (cursor.isNull(18)) null else cursor.getString(18),
            ), cursor.getLong(19)))
        } }
        val grouped = rows.map { it.recording.id }.chunked(500).flatMap { ids ->
            readableDatabase.query("artifacts", ARTIFACT_COLUMNS,
                "recording_id IN (${ids.joinToString(",") { "?" }})", ids.toTypedArray(), null, null,
                "recording_id,created_at_utc,relative_path").use { cursor -> buildList {
                while (cursor.moveToNext()) add(cursor.toArtifact())
            } }
        }.groupBy { it.recordingId }
        return rows.map { it.copy(recording = it.recording.copy(artifacts = grouped[it.recording.id].orEmpty())) }
    }

    fun recording(id: String): Recording? {
        return readableDatabase.query(
            "recordings",
            RECORDING_COLUMNS,
            "id=?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val recording = cursor.toRecording()
                recording.copy(
                    artifacts = artifacts(recording.id),
                    serverSyncState = recordingServerSyncState(recording.id),
                            serverSyncError = recordingServerSyncError(recording.id),
                )
            } else {
                null
            }
        }
    }

    fun recordingSummary(id: String): RecordingSummary? {
        val recording = recording(id) ?: return null
        val profile = profile(recording.profileId) ?: return null
        return RecordingSummary(recording, profile.numberOrName, profile.questionnaire)
    }

    fun artifactFiles(recordingId: String): List<File> {
        return recording(recordingId)?.artifacts.orEmpty().mapNotNull { artifact ->
            resolveRelativePath(artifact.relativePath).takeIf(File::exists)
        }
    }

    fun recordingDirectory(relativeDirectory: String): File = resolveRelativePath(relativeDirectory)

    fun resolveRelativePath(relativePath: String): File {
        val file = File(rootDirectory, relativePath).canonicalFile
        require(file.toPath().startsWith(rootDirectory.canonicalFile.toPath())) { "Path escapes Woona directory" }
        return file
    }

    private fun artifacts(recordingId: String): List<Artifact> = readableDatabase.query(
        "artifacts", ARTIFACT_COLUMNS, "recording_id=?", arrayOf(recordingId), null, null,
        "created_at_utc,relative_path",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toArtifact()) } }

    private fun android.database.Cursor.toArtifact(): Artifact = Artifact(
        id = getString(0), recordingId = getString(1),
        type = ArtifactType.entries.first { it.value == getString(2) },
        fileName = getString(3), mimeType = getString(4), relativePath = getString(5),
        sizeBytes = getLong(6), sha256 = getString(7),
        localPresence = if (getString(9) == "available") {
            if (runCatching { resolveRelativePath(getString(5)).isFile }.getOrDefault(false)) "both" else "remote_only"
        } else getString(8),
        uploadState = getString(9), uploadedBytes = getLong(10), createdAtUtc = getString(11),
        referenceMetadataJson = if (isNull(12)) null else getString(12),
    )

    private fun recordingServerSyncState(recordingId: String): String =
        readableDatabase.query(
            "server_sync_state",
            arrayOf("state"),
            "recording_id=?",
            arrayOf(recordingId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "pending" }

    private fun recordingServerSyncError(recordingId: String): String? =
        readableDatabase.rawQuery(
            "SELECT COALESCE(NULLIF(last_error_message,''),last_error_code) FROM server_sync_state " +
                "WHERE recording_id=? AND state IN ('retryable_error','permanent_error')",
            arrayOf(recordingId),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }

    private fun insertArtifact(
        recordingId: String,
        type: ArtifactType,
        file: File,
        now: Instant,
    ) {
        if (!file.exists()) return
        val relativePath = rootDirectory.canonicalFile.toPath()
            .relativize(file.canonicalFile.toPath())
            .toString()
            .replace(File.separatorChar, '/')
        val values = ContentValues().apply {
            put("id", existingArtifactId(recordingId, relativePath) ?: UUID.randomUUID().toString())
            put("recording_id", recordingId)
            put("type", type.value)
            put("file_name", file.name)
            put("mime_type", mimeTypeFor(file.name))
            put("relative_path", relativePath)
            put("size_bytes", file.length())
            put("sha256", sha256(file))
            put("hash_state", "verified")
            put("local_presence", "local")
            put("upload_state", "pending")
            put("uploaded_bytes", 0)
            put("created_at_utc", now.toString())
        }
        writableDatabase.insertWithOnConflict(
            "artifacts",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun existingArtifactId(recordingId: String, relativePath: String): String? =
        readableDatabase.query(
            "artifacts",
            arrayOf("id"),
            "recording_id=? AND relative_path=?",
            arrayOf(recordingId, relativePath),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun artifactFilesIn(directory: File): List<Pair<ArtifactType, File>> {
        val known = listOf(
            ArtifactType.PACKET to File(directory, "packets.bin"),
            ArtifactType.PACKET_TIMELINE to File(directory, "packet_timeline.bin"),
            ArtifactType.RAW to File(directory, "raw_fragments.binlog"),
            ArtifactType.DIAGNOSTIC to File(directory, "diagnostics.log"),
            ArtifactType.CSV to File(directory, "channel.csv"),
            ArtifactType.CSV to File(directory, "polar_hr.csv"),
            ArtifactType.CSV to File(directory, "polar_ecg.csv"),
            ArtifactType.CSV to File(directory, "polar_acc.csv"),
            ArtifactType.VIDEO to File(directory, "video.mp4"),
            ArtifactType.SYNC to File(directory, "sync.json"),
        ).filter { (_, file) -> file.exists() }
        // Recovery files are additional original bytes, not replacements for raw
        // or packet artifacts. Discover them after process interruption as well.
        val entries = if (directory.exists()) {
            directory.listFiles() ?: throw java.io.IOException("Cannot list recording directory for recovery artifacts")
        } else emptyArray()
        val recovery = entries.filter { it.name.matches(Regex("packet-recovery-[0-9a-fA-F-]{36}\\.bin")) }
            .sortedBy { it.name }.map { file ->
                require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == directory.canonicalFile) {
                    "Invalid packet recovery artifact path"
                }
                ArtifactType.IMPORTED_SOURCE to file
            }
        return known + recovery
    }

    private fun createArtifactsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE artifacts (
                id TEXT PRIMARY KEY,
                recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
                type TEXT NOT NULL CHECK(type IN ('packet','packet_timeline','raw','diagnostic','csv','video','sync','imported_source','ecg','heart_rate','rr')),
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                relative_path TEXT,
                size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
                sha256 TEXT NOT NULL,
                hash_state TEXT NOT NULL DEFAULT 'pending' CHECK(hash_state IN ('pending','verified','failed')),
                local_presence TEXT NOT NULL DEFAULT 'local' CHECK(local_presence IN ('local','remote_only','both','missing')),
                upload_state TEXT NOT NULL DEFAULT 'pending'
                    CHECK(upload_state IN ('pending','uploading','available','retryable_error','permanent_error')),
                uploaded_bytes INTEGER NOT NULL DEFAULT 0,
                server_relative_path TEXT,
                server_verified_at_utc TEXT,
                last_error_code TEXT,
                last_error_message TEXT,
                created_at_utc TEXT NOT NULL,
                reference_metadata_json TEXT,
                UNIQUE(recording_id, relative_path)
            )
            """.trimIndent(),
        )
    }

    private fun createLegacyArtifactsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE artifacts (
                id TEXT PRIMARY KEY,
                recording_id TEXT NOT NULL REFERENCES recordings(id),
                type TEXT NOT NULL CHECK(type IN ('packet','raw','diagnostic','csv','video','metadata')),
                relative_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
                created_at_utc TEXT NOT NULL,
                UNIQUE(recording_id, relative_path)
            )
            """.trimIndent(),
        )
    }

    private fun createSyncTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE recording_sync (
                recording_id TEXT PRIMARY KEY REFERENCES recordings(id) ON DELETE CASCADE,
                schema_version INTEGER NOT NULL,
                monotonic_clock TEXT NOT NULL,
                session_zero_at_utc TEXT NOT NULL,
                session_zero_wall_clock_ms INTEGER NOT NULL,
                session_zero_monotonic_ns INTEGER NOT NULL,
                session_zero_uncertainty_ns INTEGER NOT NULL,
                first_sensor_packet_monotonic_ns INTEGER,
                first_sensor_device_timer_ms INTEGER,
                last_sensor_packet_monotonic_ns INTEGER,
                video_requested_monotonic_ns INTEGER,
                media_recorder_started_monotonic_ns INTEGER,
                video_first_frame_monotonic_ns INTEGER,
                video_first_frame_camera_timestamp_ns INTEGER,
                video_first_frame_callback_monotonic_ns INTEGER,
                video_first_sample_pts_us INTEGER,
                video_offset_from_sensor_ns INTEGER,
                camera_timestamp_source TEXT,
                camera_clock_quality TEXT,
                sensor_clock_quality TEXT NOT NULL,
                overall_sync_quality TEXT NOT NULL,
                calibration_offset_ns INTEGER NOT NULL DEFAULT 0,
                estimated_drift_ppm REAL,
                updated_at_utc TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createServerSyncTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE server_sync_state (
                recording_id TEXT PRIMARY KEY REFERENCES recordings(id) ON DELETE CASCADE,
                state TEXT NOT NULL DEFAULT 'pending'
                    CHECK(state IN ('pending','uploading','synced','retryable_error','permanent_error')),
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at_utc TEXT,
                current_artifact_id TEXT,
                last_error_code TEXT,
                last_error_message TEXT,
                manifest_sha256 TEXT,
                server_receipt_sha256 TEXT,
                server_receipt_at_utc TEXT,
                updated_at_utc TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun android.database.Cursor.toProfile(): DogProfile = DogProfile(
        id = getString(0),
        profileVersionId = getString(1),
        numberOrName = getString(2),
        questionnaire = dogQuestionnaireFromJson(getString(3)),
        validationState = getString(4),
        createdAtUtc = getString(5),
        updatedAtUtc = getString(6),
        accountId = if (isNull(7)) null else getString(7),
    )

    private fun android.database.Cursor.toRecording(): Recording = Recording(
        id = getString(0),
        profileId = getString(1),
        profileVersionId = getString(2),
        source = RecordingSource.entries.first { it.value == getString(3) },
        status = RecordingStatus.entries.first { it.value == getString(4) },
        sessionLabel = getString(5),
        videoRequested = getInt(6) == 1,
        startedAtUtc = getString(7),
        endedAtUtc = if (isNull(8)) null else getString(8),
        timezone = getString(9),
        questionnaire = if (isNull(10)) null else sessionQuestionnaireFromJson(getString(10)),
        relativeDirectory = getString(11),
        questionnaireRevision = getInt(12),
        captureErrorCode = if (isNull(13)) null else getString(13),
        captureErrorMessage = if (isNull(14)) null else getString(14),
        remoteOrigin = getInt(15) != 0,
        serverReceiptSha256 = if (isNull(16)) null else getString(16),
    )

    companion object {
        private const val DATABASE_VERSION = 8
        // Preserve historical text verbatim; normalize only the indexed UI ordering key.
        // SQLite dates have millisecond resolution; UUID deterministically breaks equal instants.
        private const val HISTORY_TIME_KEY = "COALESCE(CAST(ROUND((CASE WHEN started_at_utc GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]*' THEN julianday(started_at_utc) END-2440587.5)*86400000.0) AS INTEGER),-9223372036854775807)"
        private val RECORDING_COLUMNS = arrayOf(
            "id",
            "dog_id",
            "dog_profile_version_id",
            "source",
            "status",
            "session_label",
            "video_requested",
            "started_at_utc",
            "ended_at_utc",
            "timezone",
            "questionnaire_json",
            "relative_directory",
            "questionnaire_revision",
            "capture_error_code",
            "capture_error_message",
            "remote_copy",
            "(SELECT server_receipt_sha256 FROM server_sync_state WHERE recording_id=recordings.id)",
        )
        private val ARTIFACT_COLUMNS = arrayOf(
            "id",
            "recording_id",
            "type",
            "file_name",
            "mime_type",
            "relative_path",
            "size_bytes",
            "sha256",
            "local_presence",
            "upload_state",
            "uploaded_bytes",
            "created_at_utc",
            "reference_metadata_json",
        )
        private val TERMINAL_STATUSES = setOf(
            RecordingStatus.COMPLETED,
            RecordingStatus.FAILED,
            RecordingStatus.INTERRUPTED,
        )
        private const val ZERO_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else getInt(key)

private fun JSONObject.nullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else getDouble(key)

private fun JSONObject.nullableBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else getBoolean(key)

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { index -> getString(index) }
}

internal fun sha256(file: File): String =
    file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun canonicalJsonSha256(json: String): String =
    sha256(canonicalJson(JSONObject(json)).toByteArray(Charsets.UTF_8))

private fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
        separator = ",",
        prefix = "{",
        postfix = "}",
    ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}" }
    is JSONArray -> (0 until value.length()).joinToString(
        separator = ",",
        prefix = "[",
        postfix = "]",
    ) { index -> canonicalJson(value.get(index)) }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> JSONObject.quote(value.toString())
}

private fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "mp4" -> "video/mp4"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "log" -> "text/plain"
    else -> "application/octet-stream"
}

private fun legacyDogQuestionnaire(value: JSONObject): DogQuestionnaire {
    val breed = value.nullableString("breed")
    val resembles = value.nullableString("resembles")
    val weightSource = value.nullableString("weightSource")
    val bodyUnable = value.nullableBoolean("bodyConditionUnableToAssess") == true
    val shaved = value.nullableString("shavedAreas")
    val walks = value.nullableString("walks")
    val notes = value.nullableString("notes")
    return DogQuestionnaire(
        numberOrName = value.optString("numberOrName"),
        shelterOrPlace = value.nullableString("shelterOrPlace").orEmpty(),
        breedStatus = when {
            breed == null -> "unknown"
            resembles != null -> "mixed"
            else -> "purebred"
        },
        breedName = breed,
        resembles = resembles,
        size = value.nullableString("size").orEmpty(),
        ageStatus = if (value.nullableInt("ageYears") != null || value.nullableInt("ageMonths") != null) "estimated" else "unknown",
        ageYears = value.nullableInt("ageYears"),
        ageMonths = value.nullableInt("ageMonths"),
        ageSource = when (value.nullableString("ageSource")) {
            "shelter" -> "shelter_report"
            "dental" -> "dental_estimate"
            "documents" -> "documents"
            null -> "unknown"
            else -> "operator_estimate"
        },
        sex = value.nullableString("sex").orEmpty(),
        sterilizationStatus = when (value.nullableBoolean("sterilized")) {
            true -> "yes"
            false -> "no"
            null -> "unknown"
        },
        weightStatus = when (weightSource) {
            "weighed" -> "measured"
            "estimated" -> "estimated"
            else -> "unknown"
        },
        weightKg = value.nullableDouble("weightKg"),
        bodyConditionStatus = if (bodyUnable) "unable" else if (value.nullableInt("bodyConditionScore") != null) "assessed" else "",
        bodyConditionScore = if (bodyUnable) null else value.nullableInt("bodyConditionScore"),
        muscleMass = value.nullableString("muscleMass").orEmpty(),
        neckCircumferenceStatus = if (value.nullableDouble("neckCircumferenceCm") == null) "not_measured" else "measured",
        neckCircumferenceCm = value.nullableDouble("neckCircumferenceCm"),
        coatLength = value.nullableString("coatLength").orEmpty(),
        undercoat = value.nullableString("undercoat").orEmpty(),
        shavedAreasStatus = if (shaved.isNullOrBlank()) "unknown" else "present",
        shavedAreasDetails = shaved,
        observedSigns = value.optJSONArray("observedSigns").toStringList(),
        diagnosesStatus = when (value.nullableBoolean("diagnosesPresent")) {
            true -> "yes"
            false -> "no"
            null -> "unknown"
        },
        diagnosesDetails = value.nullableString("diagnosesDetails"),
        housing = value.nullableString("housing").orEmpty(),
        walksStatus = if (walks.isNullOrBlank()) "unknown" else "known",
        walksDescription = walks,
        cohabitants = value.nullableString("cohabitants").orEmpty(),
        shelterPermission = when (value.nullableBoolean("shelterPermission")) {
            true -> "yes"
            false -> "no"
            null -> "unknown"
        },
        notesStatus = if (notes.isNullOrBlank()) "none" else "provided",
        notes = notes,
    )
}

private fun legacySessionQuestionnaire(value: JSONObject): SessionQuestionnaire {
    val contents = value.optJSONArray("recordingContents").toStringList()
    val group = when {
        "gait" in contents -> "locomotion"
        "rest" in contents -> "stationary"
        "activity" in contents -> "daily_living"
        "other" in contents -> "other"
        else -> ""
    }
    val type = when (group) {
        "locomotion" -> "mixed"
        "stationary" -> "rest"
        "daily_living" -> "other"
        "other" -> "other"
        else -> ""
    }
    val airTemperature = value.nullableDouble("airTemperatureC")
    val pulse = value.nullableInt("pulseBpm")
    val respiration = value.nullableInt("respirationPerMinute")
    val bodyTemperature = value.nullableDouble("bodyTemperatureC")
    return SessionQuestionnaire(
        sessionLabel = value.optString("sessionNumber").ifBlank { "Legacy session" },
        operatorName = value.nullableString("operator").orEmpty(),
        activityGroup = group,
        activityType = type,
        activityDetails = if (type in setOf("mixed", "other")) "Imported legacy selection: ${contents.joinToString()}" else null,
        location = value.nullableString("location").orEmpty(),
        surface = value.nullableString("surface").orEmpty(),
        airTemperatureStatus = if (airTemperature == null) "not_measured" else "measured",
        airTemperatureC = airTemperature,
        sensorPosition = value.nullableString("sensorPosition").orEmpty(),
        collarTightness = value.nullableString("collarTightness").orEmpty(),
        preMeasurementState = value.nullableString("preMeasurementState").orEmpty(),
        pulseStatus = if (pulse == null) "not_measured" else "measured",
        pulseBpm = pulse,
        respirationStatus = if (respiration == null) "not_measured" else "measured",
        respirationPerMinute = respiration,
        bodyTemperatureStatus = if (bodyTemperature == null) "not_measured" else "measured",
        bodyTemperatureC = bodyTemperature,
        measurementAtUtc = null,
        videoRequested = true,
    )
}
