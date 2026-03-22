package com.s4.belsson.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.s4.belsson.data.api.ImplantApiService
import com.s4.belsson.data.model.AnalysisResponse
import com.s4.belsson.data.model.AuthResponse
import com.s4.belsson.data.model.MeasureResponse
import com.s4.belsson.data.model.sanitized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Repository that bridges the Android layer (URIs, ContentResolver)
 * with the Ktor API service.
 */
class ImplantRepository(private val context: Context) {

    private val sessionStore = AuthSessionStore(context)

    /** Maximum file size (512 MB) to prevent OOM when reading into memory. */
    private val maxFileSizeBytes = 512L * 1024 * 1024

    init {
        ImplantApiService.setAuthToken(sessionStore.getToken())
    }

    enum class UploadWorkflow {
        CBCT_IMPLANT,
        PANORAMIC_MANDIBULAR_CANAL,
    }

    fun signup(email: String, password: String): Flow<Result<AuthResponse>> = flow {
        try {
            val response = ImplantApiService.signup(email.trim(), password)
            sessionStore.saveSession(response.token, response.user.email, response.user.id)
            ImplantApiService.setAuthToken(response.token)
            emit(Result.success(response))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    fun login(email: String, password: String): Flow<Result<AuthResponse>> = flow {
        try {
            val response = ImplantApiService.login(email.trim(), password)
            sessionStore.saveSession(response.token, response.user.email, response.user.id)
            ImplantApiService.setAuthToken(response.token)
            emit(Result.success(response))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    fun logout() {
        sessionStore.clearSession()
        ImplantApiService.setAuthToken(null)
    }

    fun getSavedEmail(): String? = sessionStore.getEmail()

    fun getSavedUserId(): Int? = sessionStore.getUserId()

    fun hasActiveSession(): Boolean = !sessionStore.getToken().isNullOrBlank()

    /**
     * Read a DICOM file from a content URI, upload it, and return the analysis.
     */
    fun analyzeJaw(
        uri: Uri,
        workflow: UploadWorkflow = UploadWorkflow.CBCT_IMPLANT,
    ): Flow<Result<AnalysisResponse>> = flow {
        try {
            // Check file size before reading to avoid OOM on very large CBCT ZIPs
            val fileSize = getFileSize(uri)
            if (fileSize > maxFileSizeBytes) {
                throw IllegalArgumentException(
                    "File too large (${fileSize / (1024 * 1024)} MB). Maximum supported size is ${maxFileSizeBytes / (1024 * 1024)} MB."
                )
            }

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open URI: $uri")

            val bytes = try {
                inputStream.use { it.readBytes() }
            } catch (oom: OutOfMemoryError) {
                throw IllegalArgumentException(
                    "File is too large to process on this device. Try a smaller CBCT dataset."
                )
            }

            if (bytes.isEmpty()) {
                throw IllegalArgumentException("Selected file is empty.")
            }

            // Resolve the real display name (e.g. "patient_cbct.dcm") from the
            // ContentResolver instead of parsing the opaque content:// URI path.
            val fileName = resolveFileName(uri, workflow)
            val contentType = resolveContentType(uri, workflow)

            val response = when (workflow) {
                UploadWorkflow.CBCT_IMPLANT -> ImplantApiService.analyzeJaw(
                    dicomBytes = bytes,
                    fileName = fileName,
                    contentType = contentType,
                )
                UploadWorkflow.PANORAMIC_MANDIBULAR_CANAL -> ImplantApiService.analyzePanoramic(
                    dicomBytes = bytes,
                    fileName = fileName,
                    contentType = contentType,
                )
            }

            emit(Result.success(response.sanitized()))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Measure bone at a specific (x, y) for a previously uploaded session.
     */
    fun measureAt(sessionId: String, x: Int, y: Int): Flow<Result<MeasureResponse>> = flow {
        try {
            val response = ImplantApiService.measure(sessionId, x, y)
            emit(Result.success(response.sanitized()))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Query the ContentResolver for DISPLAY_NAME. Falls back to the last
     * path segment and finally to "scan.dcm" so the backend always receives
     * a filename that ends in ".dcm".
     */
    private fun resolveFileName(uri: Uri, workflow: UploadWorkflow): String {
        // 1. Try ContentResolver (works for content:// URIs from Downloads, Files etc.)
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        return if (workflow == UploadWorkflow.CBCT_IMPLANT) {
                            ensureCbctInputName(name)
                        } else {
                            name
                        }
                    }
                }
            }
        }

        // 2. Fallback: last segment of the URI path
        val segment = uri.lastPathSegment
        if (!segment.isNullOrBlank()) {
            return if (workflow == UploadWorkflow.CBCT_IMPLANT) {
                ensureCbctInputName(segment)
            } else {
                segment
            }
        }

        // 3. Last resort
        return if (workflow == UploadWorkflow.CBCT_IMPLANT) "scan.dcm" else "scan"
    }

    /** Keep .zip/.dcm for CBCT study inputs, else fallback to .dcm for legacy support. */
    private fun ensureCbctInputName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".dcm") -> name
            lower.endsWith(".zip") -> name
            else -> "$name.dcm"
        }
    }

    private fun resolveContentType(uri: Uri, workflow: UploadWorkflow): String {
        val detected = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        if (workflow == UploadWorkflow.PANORAMIC_MANDIBULAR_CANAL) {
            if (detected.startsWith("image/")) return detected
            val lowerName = resolveFileName(uri, workflow).lowercase()
            if (lowerName.endsWith(".png")) return "image/png"
            if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return "image/jpeg"
            return if (detected.isNotBlank()) detected else "application/dicom"
        }

        val cbctName = resolveFileName(uri, workflow).lowercase()
        if (cbctName.endsWith(".zip")) return "application/zip"
        if (detected.contains("zip")) return "application/zip"
        return "application/dicom"
    }

    /**
     * Query the file size via ContentResolver so we can reject excessively
     * large uploads before attempting to read them into a ByteArray.
     */
    private fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                } else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L  // If we can't determine the size, allow the read to proceed
        }
    }
}
