package com.s4.belsson.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from POST /analyze-jaw
 */
@Serializable
data class AnalysisResponse(
    @SerialName("session_id") val sessionId: String = "",
    val workflow: String = "cbct_implant",
    @SerialName("patient_name") val patientName: String = "Unknown",
    @SerialName("opg_image_base64") val opgImageBase64: String = "",
    @SerialName("nerve_path") val nervePath: List<NervePathPoint> = emptyList(),
    @SerialName("arch_path") val archPath: List<NervePathPoint> = emptyList(),
    @SerialName("planning_overlay") val planningOverlay: PlanningOverlay = PlanningOverlay(),
    @SerialName("bone_metrics") val boneMetrics: BoneMetrics = BoneMetrics(),
    @SerialName("scan_region") val scanRegion: String = "unknown",
    @SerialName("ian_applicable") val ianApplicable: Boolean = false,
    @SerialName("ian_detected") val ianDetected: Boolean = false,
    @SerialName("ian_status_message") val ianStatusMessage: String = "",
    @SerialName("safe_zone_path") val safeZonePath: List<NervePathPoint> = emptyList(),
    @SerialName("recommendation_line") val recommendationLine: String = "",
    val metadata: DicomMetadata = DicomMetadata()
)

@Serializable
data class NervePathPoint(
    val x: Int = 0,
    val y: Int = 0
)

@Serializable
data class OverlayLine(
    val start: NervePathPoint = NervePathPoint(),
    val end: NervePathPoint = NervePathPoint()
)

@Serializable
data class PlanningOverlay(
    @SerialName("outer_contour") val outerContour: List<NervePathPoint> = emptyList(),
    @SerialName("inner_contour") val innerContour: List<NervePathPoint> = emptyList(),
    @SerialName("base_guide") val baseGuide: List<NervePathPoint> = emptyList(),
    @SerialName("width_indicator") val widthIndicator: OverlayLine? = null,
    @SerialName("sector_lines") val sectorLines: List<OverlayLine> = emptyList(),
)

@Serializable
data class BoneMetrics(
    @SerialName("width_mm") val widthMm: Double = 3.0,
    @SerialName("height_mm") val heightMm: Double = 10.0,
    @SerialName("safe_height_mm") val safeHeightMm: Double = 8.0,
    @SerialName("safety_margin_mm") val safetyMarginMm: Double = 2.0,
    @SerialName("density_estimate_hu") val densityEstimateHu: Double = 0.0,
    @SerialName("measurement_location") val measurementLocation: MeasurementLocation = MeasurementLocation(),
    @SerialName("safety_status") val safetyStatus: String = "warning",
    @SerialName("safety_reason") val safetyReason: String = ""
)

@Serializable
data class MeasurementLocation(
    val x: Int = 0,
    val y: Int = 0
)

@Serializable
data class DicomMetadata(
    @SerialName("pixel_spacing") val pixelSpacing: List<Double> = listOf(1.0, 1.0),
    @SerialName("slice_thickness") val sliceThickness: Double = 1.0,
    val rows: Int = 0,
    val columns: Int = 0,
    @SerialName("num_slices") val numSlices: Int = 1,
    @SerialName("dataset_type") val datasetType: String = "unknown",
    val modality: String = "UNKNOWN",
    @SerialName("is_calibrated_hu") val isCalibratedHu: Boolean = false,
    @SerialName("rescale_slope") val rescaleSlope: Double = 1.0,
    @SerialName("rescale_intercept") val rescaleIntercept: Double = 0.0,
    @SerialName("has_rescale") val hasRescale: Boolean = false,
)

/**
 * Response from POST /measure
 */
@Serializable
data class MeasureResponse(
    @SerialName("bone_metrics") val boneMetrics: BoneMetrics = BoneMetrics(),
    @SerialName("planning_overlay") val planningOverlay: PlanningOverlay = PlanningOverlay(),
    @SerialName("scan_region") val scanRegion: String = "unknown",
    @SerialName("ian_applicable") val ianApplicable: Boolean = false,
    @SerialName("ian_detected") val ianDetected: Boolean = false,
    @SerialName("ian_status_message") val ianStatusMessage: String = "",
    @SerialName("safe_zone_path") val safeZonePath: List<NervePathPoint> = emptyList(),
    @SerialName("recommendation_line") val recommendationLine: String = ""
)

/**
 * Request body for POST /measure
 */
@Serializable
data class MeasureRequest(
    @SerialName("session_id") val sessionId: String,
    val x: Int,
    val y: Int
)

private fun Double.safeFinite(default: Double): Double = if (isFinite()) this else default

fun AnalysisResponse.sanitized(): AnalysisResponse {
    val safeSpacing = metadata.pixelSpacing
        .takeIf { it.size >= 2 }
        ?.map { it.safeFinite(1.0).coerceAtLeast(0.01) }
        ?: listOf(1.0, 1.0)

    val safeMetrics = boneMetrics.copy(
        widthMm = boneMetrics.widthMm.safeFinite(3.0).coerceIn(3.0, 15.0),
        heightMm = boneMetrics.heightMm.safeFinite(10.0).coerceIn(10.0, 35.0),
        safeHeightMm = boneMetrics.safeHeightMm.safeFinite(8.0).coerceIn(0.0, 35.0),
        safetyMarginMm = boneMetrics.safetyMarginMm.safeFinite(2.0).coerceIn(0.0, 10.0),
        densityEstimateHu = boneMetrics.densityEstimateHu.safeFinite(0.0)
    )

    return copy(
        boneMetrics = safeMetrics,
        metadata = metadata.copy(
            pixelSpacing = safeSpacing,
            sliceThickness = metadata.sliceThickness.safeFinite(1.0).coerceAtLeast(0.01),
            rows = metadata.rows.coerceAtLeast(0),
            columns = metadata.columns.coerceAtLeast(0),
            numSlices = metadata.numSlices.coerceAtLeast(1)
        )
    )
}

fun MeasureResponse.sanitized(): MeasureResponse {
    val safeMetrics = boneMetrics.copy(
        widthMm = boneMetrics.widthMm.safeFinite(3.0).coerceIn(3.0, 15.0),
        heightMm = boneMetrics.heightMm.safeFinite(10.0).coerceIn(10.0, 35.0),
        safeHeightMm = boneMetrics.safeHeightMm.safeFinite(8.0).coerceIn(0.0, 35.0),
        safetyMarginMm = boneMetrics.safetyMarginMm.safeFinite(2.0).coerceIn(0.0, 10.0),
        densityEstimateHu = boneMetrics.densityEstimateHu.safeFinite(0.0)
    )
    return copy(boneMetrics = safeMetrics)
}

