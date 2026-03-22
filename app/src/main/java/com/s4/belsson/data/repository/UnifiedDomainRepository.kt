package com.s4.belsson.data.repository

import android.content.Context
import com.s4.belsson.data.api.ImplantApiService
import com.s4.belsson.data.local.AppDatabase
import com.s4.belsson.data.local.entity.BillingEntity
import com.s4.belsson.data.local.entity.CaseAnalysisEntity
import com.s4.belsson.data.local.entity.CaseEntity
import com.s4.belsson.data.local.entity.CaseFileEntity
import com.s4.belsson.data.local.entity.ChatMessageEntity
import com.s4.belsson.data.local.entity.TeamMemberEntity
import com.s4.belsson.data.local.entity.UserEntity
import com.s4.belsson.data.local.entity.UserSettingsEntity
import com.s4.belsson.data.model.BillingResponse
import com.s4.belsson.data.model.CaseCreateRequest
import com.s4.belsson.data.model.CaseAnalysisResponse
import com.s4.belsson.data.model.CaseResponse
import com.s4.belsson.data.model.SettingsResponse
import com.s4.belsson.data.model.TeamMember
import com.s4.belsson.data.model.TeamMemberCreateRequest
import com.s4.belsson.data.model.UserUpdateRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

class UnifiedDomainRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val userDao = database.userDao()
    private val settingsDao = database.userSettingsDao()
    private val caseDao = database.caseDao()
    private val fileDao = database.caseFileDao()
    private val billingDao = database.billingDao()
    private val teamDao = database.teamMemberDao()
    private val analysisDao = database.caseAnalysisDao()
    private val chatDao = database.chatMessageDao()

    private val serializer = Json { ignoreUnknownKeys = true }

    fun observeUser(userId: Int): Flow<UserEntity?> = userDao.observeById(userId)

    fun observeSettings(userId: Int): Flow<UserSettingsEntity?> = settingsDao.observeByUserId(userId)

    fun observeCases(userId: Int): Flow<List<CaseEntity>> = caseDao.observeByUserId(userId)

    fun observeBilling(userId: Int): Flow<BillingEntity?> = billingDao.observeByUserId(userId)

    fun observeTeam(userId: Int): Flow<List<TeamMemberEntity>> = teamDao.observeByOwnerId(userId)

    fun observeChat(userId: Int): Flow<List<ChatMessageEntity>> = chatDao.observeByUserId(userId)

    suspend fun syncUserDomain(userId: Int, fallbackEmail: String) {
        val profile = ImplantApiService.getUserProfile()
        userDao.upsert(
            UserEntity(
                id = userId,
                name = profile.name,
                email = profile.email.ifBlank { fallbackEmail },
                phone = profile.phone,
                practiceName = profile.practiceName,
                bio = profile.bio,
                specialty = profile.specialty,
            )
        )

        val settings = ImplantApiService.getSettings()
        settingsDao.upsert(settings.toEntity(userId))

        val teamMembers = ImplantApiService.getTeamMembers()
        teamDao.clearByOwnerId(userId)
        if (teamMembers.isNotEmpty()) {
            teamDao.upsertAll(teamMembers.map { it.toEntity(userId) })
        }

        val billing = ImplantApiService.getBilling()
        billingDao.upsert(billing.toEntity(userId, serializer))

        val remoteCases = ImplantApiService.listCases()
        caseDao.clearByUserId(userId)
        if (remoteCases.isNotEmpty()) {
            caseDao.upsertAll(remoteCases.map { it.toEntity(userId) })
        }
    }

    suspend fun optimisticUpdateProfile(
        userId: Int,
        email: String,
        name: String,
        phone: String?,
        practiceName: String?,
        bio: String?,
        specialty: String?,
    ): Result<Unit> {
        val previous = userDao.getById(userId)
        val optimistic = UserEntity(
            id = userId,
            name = name,
            email = email,
            phone = phone,
            practiceName = practiceName,
            bio = bio,
            specialty = specialty,
        )
        userDao.upsert(optimistic)

        return runCatching {
            ImplantApiService.updateUserProfile(
                UserUpdateRequest(
                    name = name,
                    email = email,
                    phone = phone,
                    practiceName = practiceName,
                    bio = bio,
                    specialty = specialty,
                )
            )
            val refreshed = ImplantApiService.getUserProfile()
            userDao.upsert(
                UserEntity(
                    id = userId,
                    name = refreshed.name,
                    email = refreshed.email,
                    phone = refreshed.phone,
                    practiceName = refreshed.practiceName,
                    bio = refreshed.bio,
                    specialty = refreshed.specialty,
                )
            )
        }.onFailure {
            if (previous != null) {
                userDao.upsert(previous)
            }
        }
    }

    suspend fun optimisticUpdateSettings(userId: Int, theme: String, language: String): Result<Unit> {
        val previous = settingsDao.getByUserId(userId)
        val optimistic = UserSettingsEntity(
            userId = userId,
            theme = theme,
            language = language,
            notificationsJson = previous?.notificationsJson ?: "{}",
        )
        settingsDao.upsert(optimistic)

        return runCatching {
            ImplantApiService.updateSettings(
                SettingsResponse(
                    theme = theme,
                    language = language,
                    notifications = null,
                )
            )
            val refreshed = ImplantApiService.getSettings()
            settingsDao.upsert(refreshed.toEntity(userId))
        }.onFailure {
            if (previous != null) {
                settingsDao.upsert(previous)
            }
        }
    }

    suspend fun optimisticCreateCase(userId: Int, request: CaseCreateRequest): Result<Unit> {
        val tempRemoteId = temporaryId()
        val tempCase = CaseEntity(
            userId = userId,
            remoteId = tempRemoteId,
            caseId = "TMP-${abs(tempRemoteId)}",
            fname = request.fname,
            lname = request.lname,
            patientAge = request.patientAge,
            toothNumber = request.toothNumber,
            complaint = request.complaint,
            caseType = request.caseType,
            status = "Pending Analysis",
            createdAt = "Pending Sync",
        )
        caseDao.upsert(tempCase)

        return runCatching {
            val created = ImplantApiService.createCase(request)
            caseDao.deleteByRemoteId(tempRemoteId)
            caseDao.upsert(created.toEntity(userId))
            Unit
        }.onFailure {
            caseDao.deleteByRemoteId(tempRemoteId)
        }
    }

    suspend fun optimisticAddTeamMember(
        userId: Int,
        name: String,
        email: String,
        role: String,
    ): Result<Unit> {
        val tempRemoteId = temporaryId()
        teamDao.upsert(
            TeamMemberEntity(
                remoteId = tempRemoteId,
                ownerId = userId,
                name = name,
                email = email,
                role = role,
            )
        )

        return runCatching {
            val created = ImplantApiService.addTeamMember(
                TeamMemberCreateRequest(name = name, email = email, role = role)
            )
            val remoteId = created.id ?: throw IllegalStateException("Server did not return team member id")
            teamDao.deleteByRemoteId(tempRemoteId)
            teamDao.upsert(
                TeamMemberEntity(
                    remoteId = remoteId,
                    ownerId = userId,
                    name = name,
                    email = email,
                    role = role,
                )
            )
        }.onFailure {
            teamDao.deleteByRemoteId(tempRemoteId)
        }
    }

    suspend fun optimisticRemoveTeamMember(userId: Int, memberId: Int): Result<Unit> {
        teamDao.deleteByRemoteId(memberId)
        return runCatching {
            ImplantApiService.removeTeamMember(memberId)
            Unit
        }.onFailure {
            syncUserDomain(userId, "")
        }
    }

    suspend fun sendChatAndPersist(userId: Int, message: String): Result<String> {
        chatDao.insert(ChatMessageEntity(userId = userId, role = "user", message = message))
        return runCatching {
            val reply = ImplantApiService.chat(message).reply
            chatDao.insert(ChatMessageEntity(userId = userId, role = "assistant", message = reply))
            reply
        }.onFailure { err ->
            chatDao.insert(
                ChatMessageEntity(
                    userId = userId,
                    role = "assistant",
                    message = "Sync failed: ${err.message ?: "Unknown error"}",
                )
            )
        }
    }

    suspend fun cacheCaseUpload(caseRemoteId: Int, filename: String, path: String, uploadedAt: String) {
        val caseEntity = caseDao.findByRemoteId(caseRemoteId) ?: return
        fileDao.upsertAll(
            listOf(
                CaseFileEntity(
                    caseLocalId = caseEntity.id,
                    filename = filename,
                    filePath = path,
                    uploadedAt = uploadedAt,
                )
            )
        )
    }

    suspend fun saveCaseAnalysis(caseRemoteId: Int, analysis: CaseAnalysisResponse) {
        val caseEntity = caseDao.findByRemoteId(caseRemoteId) ?: return
        analysisDao.upsert(
            CaseAnalysisEntity(
                caseLocalId = caseEntity.id,
                archCurveJson = serializer.encodeToString(analysis.archCurveData),
                nervePathJson = serializer.encodeToString(analysis.nervePathData),
                boneWidth36 = analysis.boneWidth36,
                boneHeight = analysis.boneHeight,
                nerveDistance = analysis.nerveDistance,
                safeImplantLength = analysis.safeImplantLength,
                clinicalReport = analysis.clinicalReport,
                patientExplanation = analysis.patientExplanation,
                createdAt = analysis.createdAt,
            )
        )
    }

    suspend fun addChatMessage(userId: Int, role: String, message: String) {
        chatDao.insert(ChatMessageEntity(userId = userId, role = role, message = message))
    }

    private fun SettingsResponse.toEntity(userId: Int): UserSettingsEntity {
        return UserSettingsEntity(
            userId = userId,
            theme = theme,
            language = language,
            notificationsJson = notifications?.toString() ?: "{}",
        )
    }

    private fun TeamMember.toEntity(userId: Int): TeamMemberEntity {
        return TeamMemberEntity(
            remoteId = id,
            ownerId = userId,
            name = name,
            email = email,
            role = role,
        )
    }

    private fun BillingResponse.toEntity(userId: Int, json: Json): BillingEntity {
        return BillingEntity(
            userId = userId,
            planName = planName,
            status = status,
            nextBillingDate = nextBillingDate,
            cardLast4 = cardLast4,
            billingHistoryJson = json.encodeToString(billingHistory),
        )
    }

    private fun CaseResponse.toEntity(userId: Int): CaseEntity {
        return CaseEntity(
            userId = userId,
            remoteId = id,
            caseId = caseId,
            fname = fname,
            lname = lname,
            patientAge = patientAge,
            toothNumber = toothNumber,
            complaint = complaint,
            caseType = caseType,
            status = status,
            createdAt = createdAt,
        )
    }

    private fun temporaryId(): Int {
        val now = System.currentTimeMillis().toInt()
        return if (now == Int.MIN_VALUE) -1 else -abs(now)
    }
}
