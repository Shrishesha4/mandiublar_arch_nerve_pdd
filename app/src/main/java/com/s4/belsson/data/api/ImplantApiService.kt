package com.s4.belsson.data.api

import com.s4.belsson.data.model.AnalysisResponse
import com.s4.belsson.data.model.AuthRequest
import com.s4.belsson.data.model.AuthResponse
import com.s4.belsson.data.model.BillingResponse
import com.s4.belsson.data.model.BillingUpdateRequest
import com.s4.belsson.data.model.CaseAnalysisResponse
import com.s4.belsson.data.model.CaseCreateRequest
import com.s4.belsson.data.model.CaseResponse
import com.s4.belsson.data.model.CaseUploadResponse
import com.s4.belsson.data.model.ChatRequest
import com.s4.belsson.data.model.ChatResponse
import com.s4.belsson.data.model.LoginRequest
import com.s4.belsson.data.model.MessageResponse
import com.s4.belsson.data.model.MeasureRequest
import com.s4.belsson.data.model.MeasureResponse
import com.s4.belsson.data.model.RegisterRequest
import com.s4.belsson.data.model.SettingsResponse
import com.s4.belsson.data.model.TeamMember
import com.s4.belsson.data.model.TeamMemberCreateRequest
import com.s4.belsson.data.model.TeamMutationResponse
import com.s4.belsson.data.model.UserProfile
import com.s4.belsson.data.model.UserUpdateRequest
import com.s4.belsson.data.model.WebAuthResponse
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Ktor-based API service for communicating with the
 * Dental Implant Planning FastAPI backend.
 */
object ImplantApiService {

    // For emulator use "10.0.2.2"; for physical device use your machine's LAN IP
    private const val BASE_URL = "http://10.0.2.2:8000"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        coerceInputValues = true
        explicitNulls = false
        allowSpecialFloatingPointValues = true
    }

    @Volatile
    private var authToken: String? = null

    val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            // IMPORTANT: Do NOT use LogLevel.BODY — it buffers the entire
            // request/response body as a String, which causes OOM on large
            // CBCT ZIP uploads (12+ MB of binary data).
            level = LogLevel.HEADERS
            logger = Logger.ANDROID
        }
        engine {
            connectTimeout = 90_000
            socketTimeout = 300_000
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 90_000
            socketTimeoutMillis = 300_000
        }
    }

    /**
     * Upload a DICOM file for full jaw analysis.
     *
     * @param dicomBytes Raw bytes of the .dcm file
     * @param fileName   Original filename
     * @param toothX     Optional tooth X coordinate
     * @param toothY     Optional tooth Y coordinate
     */
    suspend fun analyzeJaw(
        dicomBytes: ByteArray,
        fileName: String,
        contentType: String = "application/dicom",
        toothX: Int? = null,
        toothY: Int? = null
    ): AnalysisResponse {
        val response = client.submitFormWithBinaryData(
            url = buildString {
                append("$BASE_URL/analyze-jaw")
                val params = mutableListOf<String>()
                toothX?.let { params.add("tooth_x=$it") }
                toothY?.let { params.add("tooth_y=$it") }
                if (params.isNotEmpty()) {
                    append("?${params.joinToString("&")}")
                }
            },
            formData = formData {
                append("file", dicomBytes, Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            }
        ) {
            applyAuthHeader()
        }

        if (!response.status.isSuccess()) {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("")
            throw Exception("Server error: ${response.status} ${detail.take(300)}")
        }

        return response.body()
    }

    suspend fun analyzePanoramic(
        dicomBytes: ByteArray,
        fileName: String,
        contentType: String = "application/dicom",
        toothX: Int? = null,
        toothY: Int? = null
    ): AnalysisResponse {
        val response = client.submitFormWithBinaryData(
            url = buildString {
                append("$BASE_URL/analyze-panoramic")
                val params = mutableListOf<String>()
                toothX?.let { params.add("tooth_x=$it") }
                toothY?.let { params.add("tooth_y=$it") }
                if (params.isNotEmpty()) {
                    append("?${params.joinToString("&")}")
                }
            },
            formData = formData {
                append("file", dicomBytes, Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            }
        ) {
            applyAuthHeader()
        }

        if (!response.status.isSuccess()) {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("")
            throw Exception("Server error: ${response.status} ${detail.take(300)}")
        }

        return response.body()
    }

    suspend fun signup(email: String, password: String): AuthResponse {
        val response = client.post("$BASE_URL/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(email = email, password = password))
        }
        if (!response.status.isSuccess()) {
            throw Exception("Auth error: ${response.status}")
        }
        return response.body()
    }

    suspend fun login(email: String, password: String): AuthResponse {
        val response = client.post("$BASE_URL/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(email = email, password = password))
        }
        if (!response.status.isSuccess()) {
            throw Exception("Auth error: ${response.status}")
        }
        return response.body()
    }

    suspend fun webLogin(email: String, password: String): WebAuthResponse {
        val response = client.post("$BASE_URL/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = password))
        }
        if (!response.status.isSuccess()) {
            throw Exception("Auth error: ${response.status}")
        }
        return response.body()
    }

    suspend fun webRegister(request: RegisterRequest): WebAuthResponse {
        val response = client.post("$BASE_URL/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            throw Exception("Register error: ${response.status}")
        }
        return response.body()
    }

    suspend fun getUserProfile(): UserProfile {
        val response = client.get("$BASE_URL/user") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Profile error: ${response.status}")
        }
        return response.body()
    }

    suspend fun updateUserProfile(request: UserUpdateRequest): MessageResponse {
        val response = client.put("$BASE_URL/user") {
            contentType(ContentType.Application.Json)
            setBody(request)
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Profile update error: ${response.status}")
        }
        return response.body()
    }

    suspend fun getSettings(): SettingsResponse {
        val response = client.get("$BASE_URL/settings") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Settings error: ${response.status}")
        }
        return response.body()
    }

    suspend fun updateSettings(settings: SettingsResponse): MessageResponse {
        val response = client.put("$BASE_URL/settings") {
            contentType(ContentType.Application.Json)
            setBody(settings)
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Settings update error: ${response.status}")
        }
        return response.body()
    }

    suspend fun getTeamMembers(): List<TeamMember> {
        val response = client.get("$BASE_URL/team") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Team error: ${response.status}")
        }
        return response.body()
    }

    suspend fun addTeamMember(request: TeamMemberCreateRequest): TeamMutationResponse {
        val response = client.post("$BASE_URL/team") {
            contentType(ContentType.Application.Json)
            setBody(request)
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Add team member error: ${response.status}")
        }
        return response.body()
    }

    suspend fun removeTeamMember(memberId: Int): MessageResponse {
        val response = client.delete("$BASE_URL/team/$memberId") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Remove team member error: ${response.status}")
        }
        return response.body()
    }

    suspend fun getBilling(): BillingResponse {
        val response = client.get("$BASE_URL/billing") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Billing error: ${response.status}")
        }
        return response.body()
    }

    suspend fun updateBilling(request: BillingUpdateRequest): MessageResponse {
        val response = client.post("$BASE_URL/billing") {
            contentType(ContentType.Application.Json)
            setBody(request)
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Billing update error: ${response.status}")
        }
        return response.body()
    }

    suspend fun listCases(): List<CaseResponse> {
        val response = client.get("$BASE_URL/cases") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Cases error: ${response.status}")
        }
        return response.body()
    }

    suspend fun createCase(request: CaseCreateRequest): CaseResponse {
        val response = client.post("$BASE_URL/cases") {
            contentType(ContentType.Application.Json)
            setBody(request)
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Create case error: ${response.status}")
        }
        return response.body()
    }

    suspend fun getCase(caseId: String): CaseResponse {
        val response = client.get("$BASE_URL/cases/$caseId") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Case detail error: ${response.status}")
        }
        return response.body()
    }

    suspend fun uploadCaseFile(caseId: String, fileBytes: ByteArray, fileName: String): CaseUploadResponse {
        val response = client.submitFormWithBinaryData(
            url = "$BASE_URL/cases/$caseId/upload",
            formData = formData {
                append("file", fileBytes, Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            }
        ) {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Upload case file error: ${response.status}")
        }
        return response.body()
    }

    suspend fun updateCaseStatus(caseId: String, status: String): MessageResponse {
        val response = client.put("$BASE_URL/cases/$caseId/status") {
            parameter("status", status)
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Update case status error: ${response.status}")
        }
        return response.body()
    }

    suspend fun runCaseAnalysis(caseId: String): CaseAnalysisResponse {
        val response = client.post("$BASE_URL/analysis/run/$caseId") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Run analysis error: ${response.status}")
        }
        return response.body()
    }

    suspend fun getCaseAnalysis(caseId: String): CaseAnalysisResponse {
        val response = client.get("$BASE_URL/analysis/result/$caseId") {
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Get analysis result error: ${response.status}")
        }
        return response.body()
    }

    suspend fun chat(message: String): ChatResponse {
        val response = client.post("$BASE_URL/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = message))
            applyAuthHeader()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Chat error: ${response.status}")
        }
        return response.body()
    }

    fun setAuthToken(token: String?) {
        authToken = token
    }

    /**
     * Measure bone metrics at a specific coordinate on a cached session.
     */
    suspend fun measure(sessionId: String, x: Int, y: Int): MeasureResponse {
        val response = client.post("$BASE_URL/measure") {
            contentType(ContentType.Application.Json)
            setBody(MeasureRequest(sessionId = sessionId, x = x, y = y))
            applyAuthHeader()
        }

        if (!response.status.isSuccess()) {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("")
            throw Exception("Server error: ${response.status} ${detail.take(300)}")
        }

        return response.body()
    }

    private fun HttpRequestBuilder.applyAuthHeader() {
        authToken?.takeIf { it.isNotBlank() }?.let { token ->
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

