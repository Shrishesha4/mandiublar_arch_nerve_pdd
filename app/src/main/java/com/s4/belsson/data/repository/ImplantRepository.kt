package com.s4.belsson.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.s4.belsson.data.api.ImplantApiService
import com.s4.belsson.data.model.AnalysisResponse
import com.s4.belsson.data.model.MeasureResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Repository that bridges the Android layer (URIs, ContentResolver)
 * with the Ktor API service.
 */
class ImplantRepository(private val context: Context) {

    /**
     * Read a DICOM file from a content URI, upload it, and return the analysis.
     */
    fun analyzeJaw(uri: Uri): Flow<Result<AnalysisResponse>> = flow {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open URI: $uri")

            val bytes = inputStream.use { it.readBytes() }

            // Resolve the real display name (e.g. "patient_cbct.dcm") from the
            // ContentResolver instead of parsing the opaque content:// URI path.
            val fileName = resolveFileName(uri)

            val response = ImplantApiService.analyzeJaw(
                dicomBytes = bytes,
                fileName = fileName
            )

            emit(Result.success(response))
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
            emit(Result.success(response))
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
    private fun resolveFileName(uri: Uri): String {
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
                    if (!name.isNullOrBlank()) return ensureDcmExtension(name)
                }
            }
        }

        // 2. Fallback: last segment of the URI path
        val segment = uri.lastPathSegment
        if (!segment.isNullOrBlank()) return ensureDcmExtension(segment)

        // 3. Last resort
        return "scan.dcm"
    }

    /** Appends ".dcm" if the name has no extension or a non-dcm extension. */
    private fun ensureDcmExtension(name: String): String {
        return if (name.lowercase().endsWith(".dcm")) name else "$name.dcm"
    }
}
