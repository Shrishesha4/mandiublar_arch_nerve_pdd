package com.s4.belsson.ui.planning

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s4.belsson.data.local.entity.BillingEntity
import com.s4.belsson.data.local.entity.CaseEntity
import com.s4.belsson.data.local.entity.ChatMessageEntity
import com.s4.belsson.data.local.entity.TeamMemberEntity
import com.s4.belsson.data.local.entity.UserEntity
import com.s4.belsson.data.local.entity.UserSettingsEntity
import com.s4.belsson.data.model.AnalysisResponse
import com.s4.belsson.data.model.BoneMetrics
import com.s4.belsson.data.model.CaseAnalysisResponse
import com.s4.belsson.data.model.CaseCreateRequest
import com.s4.belsson.data.model.NervePathPoint
import com.s4.belsson.data.model.PlanningOverlay
import com.s4.belsson.data.repository.DomainSyncOrchestrator
import com.s4.belsson.data.repository.ImplantRepository
import com.s4.belsson.data.repository.ImplantRepository.UploadWorkflow
import com.s4.belsson.data.repository.LocalMedicalRepository
import com.s4.belsson.data.repository.UnifiedDomainRepository
import com.s4.belsson.util.MeasurementManager
import com.s4.belsson.util.ReportGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    data class Authenticated(val email: String, val userId: Int) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

data class DomainDashboardUiState(
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val profile: UserEntity? = null,
    val settings: UserSettingsEntity? = null,
    val cases: List<CaseEntity> = emptyList(),
    val teamMembers: List<TeamMemberEntity> = emptyList(),
    val billing: BillingEntity? = null,
    val chatMessages: List<ChatMessageEntity> = emptyList(),
)

/**
 * ViewModel for the Dental Implant Planning Dashboard.
 * Handles DICOM upload, result parsing, interactive measurements, and report generation.
 */
class PlanningViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ImplantRepository(application)
    private val localRepository = LocalMedicalRepository(application)
    private val unifiedRepository = UnifiedDomainRepository(application)
    private val reportGenerator = ReportGenerator(application)
    private var domainSyncOrchestrator: DomainSyncOrchestrator? = null
    private var domainObserverJob: Job? = null
    private var currentUserId: Int? = null
    private var currentUserEmail: String = ""

    private val _uiState = MutableStateFlow<PlanningUiState>(PlanningUiState.Idle)
    val uiState: StateFlow<PlanningUiState> = _uiState.asStateFlow()

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _domainState = MutableStateFlow(DomainDashboardUiState())
    val domainState: StateFlow<DomainDashboardUiState> = _domainState.asStateFlow()

    private val _selectedCaseId = MutableStateFlow<Long?>(null)
    val selectedCaseId: StateFlow<Long?> = _selectedCaseId.asStateFlow()

    private val _caseFlowMessage = MutableStateFlow<String?>(null)
    val caseFlowMessage: StateFlow<String?> = _caseFlowMessage.asStateFlow()

    private val _caseFlowResult = MutableStateFlow<CaseAnalysisResponse?>(null)
    val caseFlowResult: StateFlow<CaseAnalysisResponse?> = _caseFlowResult.asStateFlow()

    private val _caseFlowBitmap = MutableStateFlow<Bitmap?>(null)
    val caseFlowBitmap: StateFlow<Bitmap?> = _caseFlowBitmap.asStateFlow()

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
        if (hasSession) {
            val userId = repository.getSavedUserId() ?: stableLocalUserId(email)
            handleAuthenticated(email = email, userId = userId)
        } else {
            _authState.value = AuthUiState.Unauthenticated
        }
    }

    fun login(email: String, password: String) {
        _authState.value = AuthUiState.Loading
        viewModelScope.launch {
            repository.login(email, password).collect { result ->
                result.fold(
                    onSuccess = { auth ->
                        handleAuthenticated(auth.user.email, auth.user.id)
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
                        handleAuthenticated(auth.user.email, auth.user.id)
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
        domainSyncOrchestrator?.stop()
        domainObserverJob?.cancel()
        _domainState.value = DomainDashboardUiState()
        currentUserId = null
        currentUserEmail = ""
        repository.logout()
        reset()
        _authState.value = AuthUiState.Unauthenticated
    }

    fun refreshDomainData() {
        viewModelScope.launch {
            domainSyncOrchestrator?.triggerSync()
        }
    }

    fun selectCase(caseId: Long?) {
        _selectedCaseId.value = caseId
    }

    fun clearCaseFlowMessage() {
        _caseFlowMessage.value = null
    }

    fun updateProfile(
        name: String,
        phone: String?,
        practiceName: String?,
        bio: String?,
        specialty: String?,
    ) {
        val userId = currentUserId ?: return
        val email = currentUserEmail
        if (email.isBlank()) return

        viewModelScope.launch {
            unifiedRepository.optimisticUpdateProfile(
                userId = userId,
                email = email,
                name = name,
                phone = phone,
                practiceName = practiceName,
                bio = bio,
                specialty = specialty,
            ).onFailure { err ->
                _domainState.value = _domainState.value.copy(syncError = err.message)
            }
        }
    }

    fun updateSettings(theme: String, language: String) {
        val userId = currentUserId ?: return
        viewModelScope.launch {
            unifiedRepository.optimisticUpdateSettings(userId, theme, language)
                .onFailure { err ->
                    _domainState.value = _domainState.value.copy(syncError = err.message)
                }
        }
    }

    fun createCase(
        firstName: String,
        lastName: String,
        age: Int,
        toothNumber: String,
        complaint: String,
        caseType: String,
    ) {
        val userId = currentUserId ?: return
        viewModelScope.launch {
            unifiedRepository.optimisticCreateCase(
                userId,
                CaseCreateRequest(
                    fname = firstName,
                    lname = lastName,
                    patientAge = age,
                    toothNumber = toothNumber,
                    complaint = complaint,
                    caseType = caseType,
                )
            ).onFailure { err ->
                _domainState.value = _domainState.value.copy(syncError = err.message)
            }
        }
    }

    fun addTeamMember(name: String, email: String, role: String) {
        val userId = currentUserId ?: return
        viewModelScope.launch {
            unifiedRepository.optimisticAddTeamMember(userId, name, email, role)
                .onFailure { err ->
                    _domainState.value = _domainState.value.copy(syncError = err.message)
                }
        }
    }

    fun removeTeamMember(memberId: Int) {
        val userId = currentUserId ?: return
        viewModelScope.launch {
            unifiedRepository.optimisticRemoveTeamMember(userId, memberId)
                .onFailure { err ->
                    _domainState.value = _domainState.value.copy(syncError = err.message)
                }
        }
    }

    fun sendChatMessage(message: String) {
        val userId = currentUserId ?: return
        if (message.isBlank()) return

        viewModelScope.launch {
            unifiedRepository.sendChatAndPersist(userId, message.trim())
                .onFailure { err ->
                    _domainState.value = _domainState.value.copy(syncError = err.message)
                }
        }
    }

    // ─────────────────────────────────────────────
    // Upload & Analyze
    // ─────────────────────────────────────────────

    /**
     * Upload files only to backend for a selected case. Analysis is triggered separately.
     */
    fun uploadFilesOnly(cbctUri: Uri, panoramicUri: Uri) {
        val localCaseId = _selectedCaseId.value
        val selectedCase = _domainState.value.cases.firstOrNull { it.id == localCaseId }
        val caseIdentifier = selectedCase?.caseId ?: selectedCase?.remoteId?.toString()

        if (caseIdentifier.isNullOrBlank()) {
            _uiState.value = PlanningUiState.Error("Select a patient case before uploading files")
            return
        }

        _uiState.value = PlanningUiState.Loading
        _tapMetrics.value = null
        _tapOverlay.value = null
        _tapSafeZonePath.value = null
        _tapRecommendationLine.value = null
        _tapIanStatusMessage.value = null

        viewModelScope.launch {
            repository.uploadFilesOnly(
                caseId = caseIdentifier,
                cbctUri = cbctUri,
                panoramicUri = panoramicUri,
            ).collect { result ->
                result.fold(
                    onSuccess = {
                        _uiState.value = PlanningUiState.Idle
                        refreshDomainData()
                    },
                    onFailure = { err ->
                        _uiState.value = PlanningUiState.Error(err.message ?: "Upload failed")
                    }
                )
            }
        }
    }

    fun startCaseFlowAnalysis(archUri: Uri, ianUri: Uri) {
        val localCaseId = _selectedCaseId.value
        val selectedCase = _domainState.value.cases.firstOrNull { it.id == localCaseId }
        val caseIdentifier = selectedCase?.caseId ?: selectedCase?.remoteId?.toString()

        if (caseIdentifier.isNullOrBlank()) {
            _uiState.value = PlanningUiState.Error("Select a patient case before starting analysis")
            return
        }

        _caseFlowMessage.value = null
        _caseFlowResult.value = null
        _caseFlowBitmap.value = null
        _uiState.value = PlanningUiState.Loading

        viewModelScope.launch {
            val uploadResult = repository.uploadFilesOnly(
                caseId = caseIdentifier,
                cbctUri = archUri,
                panoramicUri = ianUri,
            ).first()

            uploadResult.fold(
                onSuccess = {
                    val analysisResult = repository.runCaseAnalysis(caseIdentifier).first()
                    analysisResult.fold(
                        onSuccess = { response ->
                            _uiState.value = PlanningUiState.Idle
                            _caseFlowResult.value = response
                            _caseFlowBitmap.value = decodeBase64ToBitmap(response.opgImageBase64.orEmpty())
                            _caseFlowMessage.value = "The results look good, but AI is not 100% accurate. Please consider expert clinical judgment."
                            refreshDomainData()
                        },
                        onFailure = { err ->
                            _uiState.value = PlanningUiState.Error(err.message ?: "Case analysis failed")
                        },
                    )
                },
                onFailure = { err ->
                    _uiState.value = PlanningUiState.Error(err.message ?: "File upload failed")
                },
            )
        }
    }

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

                viewModelScope.launch {
                    localRepository.upsertPatientFromAnalysis(panoramicResponse)
                }
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

        val file = reportGenerator.generateReport(
            analysis = preferred,
            opgBitmap = preferredBitmap,
            toothLabel = toothLabel,
            tapMetrics = _tapMetrics.value,
            tapRecommendationLine = _tapRecommendationLine.value,
            tapIanStatusMessage = _tapIanStatusMessage.value,
            tapSafeZonePath = _tapSafeZonePath.value ?: emptyList()
        )

        viewModelScope.launch {
            val patientId = localRepository.upsertPatientFromAnalysis(preferred)
            localRepository.saveReport(patientId, preferred, file.absolutePath)
        }

        return file
    }

    fun generateCombinedReport(toothLabel: String = "Tooth 36"): File? {
        val state = _uiState.value
        if (state !is PlanningUiState.Success) return null

        val file = reportGenerator.generateCombinedReport(
            reports = listOf(
                ReportGenerator.ReportPayload(
                    analysis = state.cbctAnalysis,
                    opgBitmap = state.cbctBitmap,
                    toothLabel = toothLabel,
                ),
                ReportGenerator.ReportPayload(
                    analysis = state.panoramicAnalysis,
                    opgBitmap = state.panoramicBitmap,
                    toothLabel = toothLabel,
                    tapMetrics = _tapMetrics.value,
                    tapRecommendationLine = _tapRecommendationLine.value,
                    tapIanStatusMessage = _tapIanStatusMessage.value,
                    tapSafeZonePath = _tapSafeZonePath.value ?: emptyList(),
                ),
            )
        )

        viewModelScope.launch {
            val patientId = localRepository.upsertPatientFromAnalysis(state.panoramicAnalysis)
            localRepository.saveReport(patientId, state.panoramicAnalysis, file.absolutePath)
        }

        return file
    }

    // ─────────────────────────────────────────────
    // Reset
    // ─────────────────────────────────────────────

    fun reset() {
        _uiState.value = PlanningUiState.Idle
        _caseFlowMessage.value = null
        _caseFlowResult.value = null
        _caseFlowBitmap.value = null
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

    private fun handleAuthenticated(email: String, userId: Int) {
        currentUserEmail = email
        currentUserId = userId
        _authState.value = AuthUiState.Authenticated(email = email, userId = userId)
        startDomainObservers(userId)
        if (domainSyncOrchestrator == null) {
            domainSyncOrchestrator = DomainSyncOrchestrator(
                scope = viewModelScope,
                repository = unifiedRepository,
                userIdProvider = { currentUserId },
                fallbackEmailProvider = { currentUserEmail },
                onSyncStateChanged = { isSyncing, error ->
                    _domainState.value = _domainState.value.copy(
                        isSyncing = isSyncing,
                        syncError = error,
                    )
                },
            )
        }
        domainSyncOrchestrator?.start(intervalMs = 120_000L)
    }

    private fun startDomainObservers(userId: Int) {
        domainObserverJob?.cancel()
        domainObserverJob = viewModelScope.launch {
            unifiedRepository.observeUser(userId)
                .combine(unifiedRepository.observeSettings(userId)) { profile, settings ->
                    profile to settings
                }
                .combine(unifiedRepository.observeCases(userId)) { profileSettings, cases ->
                    Triple(profileSettings.first, profileSettings.second, cases)
                }
                .combine(unifiedRepository.observeTeam(userId)) { profileSettingsCases, team ->
                    DomainDashboardUiState(
                        isSyncing = _domainState.value.isSyncing,
                        syncError = _domainState.value.syncError,
                        profile = profileSettingsCases.first,
                        settings = profileSettingsCases.second,
                        cases = profileSettingsCases.third,
                        teamMembers = team,
                        billing = _domainState.value.billing,
                        chatMessages = _domainState.value.chatMessages,
                    )
                }
                .combine(unifiedRepository.observeBilling(userId)) { baseState, billing ->
                    baseState.copy(billing = billing)
                }
                .combine(unifiedRepository.observeChat(userId)) { baseState, chat ->
                    baseState.copy(chatMessages = chat)
                }
                .collect { merged ->
                _domainState.value = merged
            }
        }
    }

    private fun stableLocalUserId(email: String): Int {
        if (email.isBlank()) return 1_000_001
        val hash = email.trim().lowercase().hashCode()
        val normalized = if (hash == Int.MIN_VALUE) 1 else abs(hash)
        return 1_000_000 + normalized
    }
}
