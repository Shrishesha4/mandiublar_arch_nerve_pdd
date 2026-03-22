package com.s4.belsson.ui.patients

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s4.belsson.data.local.entity.MedicalReportEntity
import com.s4.belsson.data.local.entity.PatientEntity
import com.s4.belsson.data.repository.LocalMedicalRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PatientRecordsUiState(
    val patients: List<PatientEntity> = emptyList(),
    val reports: List<MedicalReportEntity> = emptyList(),
    val selectedPatientId: Long? = null,
    val isSavingPatient: Boolean = false,
    val message: String? = null,
)

class PatientRecordsViewModel(application: Application) : AndroidViewModel(application) {
    private val localRepository = LocalMedicalRepository(application)

    private val _uiState = MutableStateFlow(PatientRecordsUiState())
    val uiState: StateFlow<PatientRecordsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            localRepository.observePatients().collect { patients ->
                _uiState.update { state ->
                    val selectedId = state.selectedPatientId?.takeIf { id ->
                        patients.any { it.id == id }
                    }
                    state.copy(patients = patients, selectedPatientId = selectedId)
                }
            }
        }

        viewModelScope.launch {
            localRepository.observeReports().collect { reports ->
                _uiState.update { it.copy(reports = reports) }
            }
        }
    }

    fun selectPatient(patientId: Long?) {
        _uiState.update { it.copy(selectedPatientId = patientId) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun addPatient(
        firstName: String,
        lastName: String,
        dob: String?,
        gender: String?,
        phone: String?,
        email: String?,
    ) {
        if (firstName.isBlank() || lastName.isBlank()) {
            _uiState.update { it.copy(message = "First and last name are required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingPatient = true, message = null) }
            runCatching {
                localRepository.addPatient(
                    firstName = firstName.trim(),
                    lastName = lastName.trim(),
                    dob = dob?.trim().takeUnless { it.isNullOrBlank() },
                    gender = gender?.trim().takeUnless { it.isNullOrBlank() },
                    phone = phone?.trim().takeUnless { it.isNullOrBlank() },
                    email = email?.trim().takeUnless { it.isNullOrBlank() },
                )
            }.onSuccess { id ->
                _uiState.update {
                    it.copy(
                        isSavingPatient = false,
                        selectedPatientId = id,
                        message = "Patient added",
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSavingPatient = false,
                        message = err.message ?: "Failed to add patient",
                    )
                }
            }
        }
    }
}
