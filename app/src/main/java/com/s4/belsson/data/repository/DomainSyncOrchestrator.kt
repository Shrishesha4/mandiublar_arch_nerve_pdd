package com.s4.belsson.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DomainSyncOrchestrator(
    private val scope: CoroutineScope,
    private val repository: UnifiedDomainRepository,
    private val userIdProvider: () -> Int?,
    private val fallbackEmailProvider: () -> String,
    private val onSyncStateChanged: (isSyncing: Boolean, errorMessage: String?) -> Unit,
) {
    private val syncMutex = Mutex()
    private var periodicJob: Job? = null

    fun start(intervalMs: Long = 60_000L) {
        if (periodicJob?.isActive == true) return

        periodicJob = scope.launch {
            triggerSync()
            while (isActive) {
                delay(intervalMs)
                triggerSync()
            }
        }
    }

    suspend fun triggerSync() {
        val userId = userIdProvider() ?: return
        syncMutex.withLock {
            onSyncStateChanged(true, null)
            val result = runCatching {
                repository.syncUserDomain(userId, fallbackEmailProvider())
            }
            onSyncStateChanged(false, result.exceptionOrNull()?.message)
        }
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
    }
}
