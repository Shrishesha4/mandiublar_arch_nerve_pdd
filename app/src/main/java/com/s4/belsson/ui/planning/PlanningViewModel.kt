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
import com.s4.belsson.data.model.NervePathPoint
import com.s4.belsson.data.model.PlanningOverlay
import com.s4.belsson.data.repository.ImplantRepository
import com.s4.belsson.data.repository.ImplantRepository.UploadWorkflow
import com.s4.belsson.util.MeasurementManager
import com.s4.belsson.util.ReportGenerator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * UI state for the planning dashboard.
 */
sealed class PlanningUiState {
    data object Idle : PlanningUiState()
    data object Loading : PlanningUiState()
    data class Success(
        val cbctAnalysis: AnalysisResponse,
        val cbctBitmap: Bitmap?,
        val cbctMeasurementManager: MeasurementManager,
        val panoramicAnalysis: AnalysisResponse,
        val panoramicBitmap: Bitmap?,
        val panoramicMeasurementManager: MeasurementManager,
    ) : PlanningUiState()
    data class Error(val message: String) : PlanningUiState()
}

sealed class AuthUiState {
    data object Loading : AuthUiState()
    data object Unauthenticated : AuthUiState()
    data class Authenticated(val email: String) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
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

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    /** Updated when user taps a specific tooth region */
    private val _tapMetrics = MutableStateFlow<BoneMetrics?>(null)
    val tapMetrics: StateFlow<BoneMetrics?> = _tapMetrics.asStateFlow()

    private val _tapOverlay = MutableStateFlow<PlanningOverlay?>(null)
    val tapOverlay: StateFlow<PlanningOverlay?> = _tapOverlay.asStateFlow()

    private val _tapSafeZonePath = MutableStateFlow<List<NervePathPoint>?>(null)
    val tapSafeZonePath: StateFlow<List<NervePathPoint>?> = _tapSafeZonePath.asStateFlow()

    private val _tapRecommendationLine = MutableStateFlow<String?>(null)
    val tapRecommendationLine: StateFlow<String?> = _tapRecommendationLine.asStateFlow()

    private val _tapIanStatusMessage = MutableStateFlow<String?>(null)
    val tapIanStatusMessage: StateFlow<String?> = _tapIanStatusMessage.asStateFlow()

    /** Session ID from the last successful analysis */
    private var currentSessionId: String? = null

    init {
        val hasSession = repository.hasActiveSession()
        val email = repository.getSavedEmail().orEmpty()
        _authState.value = if (hasSession) {
            AuthUiState.Authenticated(email = email)
        } else {
            AuthUiState.Unauthenticated
        }
    }

    fun login(email: String, password: String) {
        _authState.value = AuthUiState.Loading
        viewModelScope.launch {
            repository.login(email, password).collect { result ->
                result.fold(
                    onSuccess = { auth ->
                        _authState.value = AuthUiState.Authenticated(auth.user.email)
                    },
                    onFailure = { err ->
                        _authState.value = AuthUiState.Error(err.message ?: "Login failed")
                    }
                )
            }
        }
    }

    fun signup(email: String, password: String) {
        _authState.value = AuthUiState.Loading
        viewModelScope.launch {
            repository.signup(email, password).collect { result ->
                result.fold(
                    onSuccess = { auth ->
                        _authState.value = AuthUiState.Authenticated(auth.user.email)
                    },
                    onFailure = { err ->
                        _authState.value = AuthUiState.Error(err.message ?: "Signup failed")
                    }
                )
            }
        }
    }

    fun clearAuthError() {
        if (_authState.value is AuthUiState.Error) {
            _authState.value = AuthUiState.Unauthenticated
        }
    }

    fun logout() {
        repository.logout()
        reset()
        _authState.value = AuthUiState.Unauthenticated
    }

    // ─────────────────────────────────────────────
    // Upload & Analyze
    // ─────────────────────────────────────────────

    /**
     * Process both CBCT and panoramic uploads in parallel.
     */
    fun uploadBoth(cbctUri: Uri, panoramicUri: Uri) {
        _uiState.value = PlanningUiState.Loading
        _tapMetrics.value = null
        _tapOverlay.value = null
        _tapSafeZonePath.value = null
        _tapRecommendationLine.value = null
        _tapIanStatusMessage.value = null

        val panoramicIsNonDicom = isNonDicomPanoramic(panoramicUri)

        viewModelScope.launch {
            try {
                val cbctDeferred = async {
                    repository.analyzeJaw(cbctUri, UploadWorkflow.CBCT_IMPLANT).first()
                }
                val panoramicDeferred = async {
                    repository.analyzeJaw(
                        panoramicUri,
                        UploadWorkflow.PANORAMIC_MANDIBULAR_CANAL,
                    ).first()
                }

                val cbctResult = cbctDeferred.await()
                val panoramicResult = panoramicDeferred.await()

                val failure =
                    cbctResult.exceptionOrNull() ?: panoramicResult.exceptionOrNull()
                if (failure != null) {
                    _uiState.value =
                        PlanningUiState.Error(failure.message ?: "Unknown error")
                    return@launch
                }

                val cbctResponse = cbctResult.getOrNull() ?: run {
                    _uiState.value =
                        PlanningUiState.Error("CBCT analysis returned no data")
                    return@launch
                }
                val panoramicResponseRaw = panoramicResult.getOrNull() ?: run {
                    _uiState.value =
                        PlanningUiState.Error("Panoramic analysis returned no data")
                    return@launch
                }

                val panoramicResponse = if (panoramicIsNonDicom) {
                    panoramicResponseRaw.copy(patientName = cbctResponse.patientName)
                } else {
                    panoramicResponseRaw
                }

                currentSessionId = panoramicResponse.sessionId

                // CBCT OPG for 3D volumes may produce very small or empty images —
                // decodeBase64ToBitmap already returns null safely.
                val cbctBitmap = decodeBase64ToBitmap(cbctResponse.opgImageBase64)
                val panoramicBitmap =
                    decodeBase64ToBitmap(panoramicResponse.opgImageBase64)

                val cbctMm = MeasurementManager(
                    pixelSpacingRow = sanitizeSpacing(
                        cbctResponse.metadata.pixelSpacing.getOrElse(0) { 1.0 }),
                    pixelSpacingCol = sanitizeSpacing(
                        cbctResponse.metadata.pixelSpacing.getOrElse(1) { 1.0 }),
                    sliceThickness = sanitizeSpacing(cbctResponse.metadata.sliceThickness)
                )
                val panoramicMm = MeasurementManager(
                    pixelSpacingRow = sanitizeSpacing(
                        panoramicResponse.metadata.pixelSpacing.getOrElse(0) { 1.0 }),
                    pixelSpacingCol = sanitizeSpacing(
                        panoramicResponse.metadata.pixelSpacing.getOrElse(1) { 1.0 }),
                    sliceThickness = sanitizeSpacing(panoramicResponse.metadata.sliceThickness)
                )

                _uiState.value = PlanningUiState.Success(
                    cbctAnalysis = cbctResponse,
                    cbctBitmap = cbctBitmap,
                    cbctMeasurementManager = cbctMm,
                    panoramicAnalysis = panoramicResponse,
                    panoramicBitmap = panoramicBitmap,
                    panoramicMeasurementManager = panoramicMm,
                )
            } catch (oom: OutOfMemoryError) {
                _uiState.value = PlanningUiState.Error(
                    "Out of memory — the CBCT dataset is too large for this device."
                )
            } catch (e: Exception) {
                _uiState.value = PlanningUiState.Error(
                    e.message ?: "Unexpected error during analysis"
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
                        _tapSafeZonePath.value = response.safeZonePath
                        _tapRecommendationLine.value = response.recommendationLine
                        _tapIanStatusMessage.value = response.ianStatusMessage
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

        val preferred = state.panoramicAnalysis
        val preferredBitmap = state.panoramicBitmap

        return reportGenerator.generateReport(
            analysis = preferred,
            opgBitmap = preferredBitmap,
            toothLabel = toothLabel,
            tapMetrics = _tapMetrics.value,
            tapRecommendationLine = _tapRecommendationLine.value,
            tapIanStatusMessage = _tapIanStatusMessage.value,
            tapSafeZonePath = _tapSafeZonePath.value ?: emptyList()
        )
    }

    // ─────────────────────────────────────────────
    // Reset
    // ─────────────────────────────────────────────

    fun reset() {
        _uiState.value = PlanningUiState.Idle
        _tapMetrics.value = null
        _tapOverlay.value = null
        _tapSafeZonePath.value = null
        _tapRecommendationLine.value = null
        _tapIanStatusMessage.value = null
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

    private fun sanitizeSpacing(value: Double): Double {
        if (!value.isFinite()) return 1.0
        return abs(value).coerceAtLeast(0.01)
    }

    private fun isNonDicomPanoramic(uri: Uri): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(uri)?.lowercase().orEmpty()
        if (mime.startsWith("image/")) return true
        if (mime.contains("dicom") || mime == "application/dicom") return false

        val path = uri.toString().lowercase()
        if (path.endsWith(".dcm") || path.endsWith(".dicom")) return false
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
    }
}
