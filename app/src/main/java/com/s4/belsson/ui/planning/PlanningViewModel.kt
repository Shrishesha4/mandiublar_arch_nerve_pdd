package com.s4.belsson.ui.planning

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s4.belsson.data.model.AnalysisResponse
import com.s4.belsson.data.model.BoneMetrics
import com.s4.belsson.data.model.PlanningOverlay
import com.s4.belsson.data.repository.ImplantRepository
import com.s4.belsson.util.MeasurementManager
import com.s4.belsson.util.ReportGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * UI state for the planning dashboard.
 */
sealed class PlanningUiState {
    data object Idle : PlanningUiState()
    data object Loading : PlanningUiState()
    data class Success(
        val analysis: AnalysisResponse,
        val opgBitmap: Bitmap?,
        val measurementManager: MeasurementManager
    ) : PlanningUiState()
    data class Error(val message: String) : PlanningUiState()
}

/**
 * ViewModel for the Dental Implant Planning Dashboard.
 * Handles DICOM upload, result parsing, interactive measurements, and report generation.
 */
class PlanningViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ImplantRepository(application)
    private val reportGenerator = ReportGenerator(application)

    private val _uiState = MutableStateFlow<PlanningUiState>(PlanningUiState.Idle)
    val uiState: StateFlow<PlanningUiState> = _uiState.asStateFlow()

    /** Updated when user taps a specific tooth region */
    private val _tapMetrics = MutableStateFlow<BoneMetrics?>(null)
    val tapMetrics: StateFlow<BoneMetrics?> = _tapMetrics.asStateFlow()

    private val _tapOverlay = MutableStateFlow<PlanningOverlay?>(null)
    val tapOverlay: StateFlow<PlanningOverlay?> = _tapOverlay.asStateFlow()

    /** Session ID from the last successful analysis */
    private var currentSessionId: String? = null

    // ─────────────────────────────────────────────
    // Upload & Analyze
    // ─────────────────────────────────────────────

    /**
     * Upload a DICOM file for analysis.
     */
    fun uploadDicom(uri: Uri) {
        _uiState.value = PlanningUiState.Loading
        _tapMetrics.value = null
        _tapOverlay.value = null

        viewModelScope.launch {
            repository.analyzeJaw(uri).collect { result ->
                result.fold(
                    onSuccess = { response ->
                        currentSessionId = response.sessionId

                        val opgBitmap = decodeBase64ToBitmap(response.opgImageBase64)

                        val mm = MeasurementManager(
                            pixelSpacingRow = response.metadata.pixelSpacing.getOrElse(0) { 1.0 },
                            pixelSpacingCol = response.metadata.pixelSpacing.getOrElse(1) { 1.0 },
                            sliceThickness = response.metadata.sliceThickness
                        )

                        _uiState.value = PlanningUiState.Success(
                            analysis = response,
                            opgBitmap = opgBitmap,
                            measurementManager = mm
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = PlanningUiState.Error(
                            error.message ?: "Unknown error"
                        )
                    }
                )
            }
        }
    }

    // ─────────────────────────────────────────────
    // Interactive Measurement (tap on tooth region)
    // ─────────────────────────────────────────────

    /**
     * Measure bone at a tapped coordinate on the jaw view.
     */
    fun measureAtCoordinate(x: Int, y: Int) {
        val sessionId = currentSessionId ?: return

        viewModelScope.launch {
            repository.measureAt(sessionId, x, y).collect { result ->
                result.fold(
                    onSuccess = { response ->
                        _tapMetrics.value = response.boneMetrics
                        _tapOverlay.value = response.planningOverlay
                    },
                    onFailure = { /* silently ignore for now */ }
                )
            }
        }
    }

    // ─────────────────────────────────────────────
    // Report Generation
    // ─────────────────────────────────────────────

    /**
     * Generate a PDF report and return the file path.
     */
    fun generateReport(toothLabel: String = "Tooth 36"): File? {
        val state = _uiState.value
        if (state !is PlanningUiState.Success) return null

        return reportGenerator.generateReport(
            analysis = state.analysis,
            opgBitmap = state.opgBitmap,
            toothLabel = toothLabel
        )
    }

    // ─────────────────────────────────────────────
    // Reset
    // ─────────────────────────────────────────────

    fun reset() {
        _uiState.value = PlanningUiState.Idle
        _tapMetrics.value = null
        _tapOverlay.value = null
        currentSessionId = null
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun decodeBase64ToBitmap(base64: String): Bitmap? {
        return try {
            if (base64.isBlank()) return null
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
