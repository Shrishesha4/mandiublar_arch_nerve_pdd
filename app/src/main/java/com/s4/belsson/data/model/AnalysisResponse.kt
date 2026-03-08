package com.s4.belsson.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from POST /analyze-jaw
 */
@Serializable
data class AnalysisResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("patient_name") val patientName: String,
    @SerialName("opg_image_base64") val opgImageBase64: String,
    @SerialName("nerve_path") val nervePath: List<NervePathPoint>,
    @SerialName("arch_path") val archPath: List<NervePathPoint> = emptyList(),
    @SerialName("planning_overlay") val planningOverlay: PlanningOverlay = PlanningOverlay(),
    @SerialName("bone_metrics") val boneMetrics: BoneMetrics,
    val metadata: DicomMetadata
)

@Serializable
data class NervePathPoint(
    val x: Int,
    val y: Int
)

@Serializable
data class OverlayLine(
    val start: NervePathPoint,
    val end: NervePathPoint
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
    @SerialName("width_mm") val widthMm: Double,
    @SerialName("height_mm") val heightMm: Double,
    @SerialName("safe_height_mm") val safeHeightMm: Double,
    @SerialName("safety_margin_mm") val safetyMarginMm: Double,
    @SerialName("density_estimate_hu") val densityEstimateHu: Double,
    @SerialName("measurement_location") val measurementLocation: MeasurementLocation,
    @SerialName("safety_status") val safetyStatus: String = "warning",
    @SerialName("safety_reason") val safetyReason: String = ""
)

@Serializable
data class MeasurementLocation(
    val x: Int,
    val y: Int
)

@Serializable
data class DicomMetadata(
    @SerialName("pixel_spacing") val pixelSpacing: List<Double>,
    @SerialName("slice_thickness") val sliceThickness: Double,
    val rows: Int,
    val columns: Int,
    @SerialName("num_slices") val numSlices: Int
)

/**
 * Response from POST /measure
 */
@Serializable
data class MeasureResponse(
    @SerialName("bone_metrics") val boneMetrics: BoneMetrics,
    @SerialName("planning_overlay") val planningOverlay: PlanningOverlay = PlanningOverlay()
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
