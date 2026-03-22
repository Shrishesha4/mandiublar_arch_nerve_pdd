package com.s4.belsson.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val phone: String? = null,
    @SerialName("practice_name")
    val practiceName: String? = null,
    val practice: String? = null
)

@Serializable
data class WebUser(
    val name: String,
    val email: String,
    val phone: String? = null,
    val practice: String? = null
)

@Serializable
data class WebAuthResponse(
    val token: String,
    @SerialName("token_type")
    val tokenType: String = "bearer",
    val user: WebUser
)

@Serializable
data class UserProfile(
    val name: String,
    val email: String,
    val phone: String? = null,
    @SerialName("practice_name")
    val practiceName: String? = null,
    val bio: String? = null,
    val specialty: String? = null
)

@Serializable
data class UserUpdateRequest(
    val name: String,
    val email: String,
    val phone: String? = null,
    @SerialName("practice_name")
    val practiceName: String? = null,
    val bio: String? = null,
    val specialty: String? = null
)

@Serializable
data class TeamMember(
    val id: Int,
    val name: String,
    val email: String,
    val role: String
)

@Serializable
data class TeamMemberCreateRequest(
    val name: String,
    val email: String,
    val role: String
)

@Serializable
data class TeamMutationResponse(
    val message: String,
    val id: Int? = null
)

@Serializable
data class BillingHistoryItem(
    val date: String,
    val amount: String,
    val status: String
)

@Serializable
data class BillingResponse(
    @SerialName("plan_name")
    val planName: String,
    val status: String,
    @SerialName("next_billing_date")
    val nextBillingDate: String? = null,
    @SerialName("card_last4")
    val cardLast4: String? = null,
    @SerialName("billing_history")
    val billingHistory: List<BillingHistoryItem> = emptyList()
)

@Serializable
data class BillingUpdateRequest(
    @SerialName("plan_name")
    val planName: String,
    @SerialName("card_last4")
    val cardLast4: String? = null
)

@Serializable
data class MessageResponse(
    val message: String
)

@Serializable
data class SettingsResponse(
    val theme: String = "system",
    val language: String = "en",
    val notifications: JsonObject? = null
)

@Serializable
data class CaseCreateRequest(
    val fname: String,
    val lname: String,
    @SerialName("patient_age")
    val patientAge: Int,
    @SerialName("tooth_number")
    val toothNumber: String,
    val complaint: String,
    @SerialName("case_type")
    val caseType: String
)

@Serializable
data class CaseResponse(
    val id: Int,
    @SerialName("case_id")
    val caseId: String,
    val fname: String,
    val lname: String,
    @SerialName("patient_age")
    val patientAge: Int? = null,
    @SerialName("tooth_number")
    val toothNumber: String? = null,
    val complaint: String? = null,
    @SerialName("case_type")
    val caseType: String? = null,
    val status: String,
    @SerialName("created_at")
    val createdAt: String
)

@Serializable
data class CaseUploadResponse(
    val message: String,
    val path: String
)

@Serializable
data class CaseAnalysisResponse(
    @SerialName("case_id")
    val caseId: Int,
    @SerialName("arch_curve_data")
    val archCurveData: List<List<Double>>,
    @SerialName("nerve_path_data")
    val nervePathData: List<List<Double>>,
    @SerialName("bone_width_36")
    val boneWidth36: String,
    @SerialName("bone_height")
    val boneHeight: String,
    @SerialName("nerve_distance")
    val nerveDistance: String,
    @SerialName("safe_implant_length")
    val safeImplantLength: String,
    @SerialName("clinical_report")
    val clinicalReport: String? = null,
    @SerialName("patient_explanation")
    val patientExplanation: String? = null,
    @SerialName("created_at")
    val createdAt: String
)

@Serializable
data class ChatRequest(
    val message: String
)

@Serializable
data class ChatResponse(
    val reply: String
)
