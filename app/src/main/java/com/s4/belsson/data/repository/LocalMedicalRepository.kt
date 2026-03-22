package com.s4.belsson.data.repository

import android.content.Context
import com.s4.belsson.data.local.AppDatabase
import com.s4.belsson.data.local.entity.MedicalReportEntity
import com.s4.belsson.data.local.entity.PatientEntity
import com.s4.belsson.data.model.AnalysisResponse
import kotlinx.coroutines.flow.Flow

class LocalMedicalRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val patientDao = database.patientDao()
    private val reportDao = database.medicalReportDao()

    fun observePatients(): Flow<List<PatientEntity>> = patientDao.observeAll()

    fun observeReports(): Flow<List<MedicalReportEntity>> = reportDao.observeAll()

    fun observeReportsByPatient(patientId: Long): Flow<List<MedicalReportEntity>> =
        reportDao.observeByPatient(patientId)

    suspend fun addPatient(
        firstName: String,
        lastName: String,
        dob: String? = null,
        gender: String? = null,
        phone: String? = null,
        email: String? = null,
    ): Long {
        return patientDao.upsert(
            PatientEntity(
                firstName = firstName,
                lastName = lastName,
                dob = dob,
                gender = gender,
                phone = phone,
                email = email,
            )
        )
    }

    suspend fun upsertPatientFromAnalysis(analysis: AnalysisResponse): Long {
        val names = analysis.patientName.trim().split(" ").filter { it.isNotBlank() }
        val firstName = names.firstOrNull() ?: "Unknown"
        val lastName = names.drop(1).joinToString(" ").ifBlank { "Patient" }

        val remoteId = analysis.sessionId.takeIf { it.isNotBlank() }
        val existing = remoteId?.let { patientDao.getByRemoteId(it) }

        val entity = PatientEntity(
            id = existing?.id ?: 0,
            remotePatientId = remoteId,
            firstName = firstName,
            lastName = lastName,
            updatedAt = System.currentTimeMillis(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )

        val insertedId = patientDao.upsert(entity)
        return if (entity.id != 0L) entity.id else insertedId
    }

    suspend fun saveReport(patientId: Long, analysis: AnalysisResponse, pdfPath: String) {
        reportDao.upsert(
            MedicalReportEntity(
                patientId = patientId,
                sessionId = analysis.sessionId,
                workflow = analysis.workflow,
                scanRegion = analysis.scanRegion,
                safeHeightMm = analysis.boneMetrics.safeHeightMm,
                boneWidthMm = analysis.boneMetrics.widthMm,
                boneHeightMm = analysis.boneMetrics.heightMm,
                nerveDetected = analysis.ianDetected,
                recommendation = analysis.recommendationLine,
                pdfPath = pdfPath
            )
        )
    }
}
