package com.s4.belsson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.s4.belsson.data.local.entity.CaseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(caseEntity: CaseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cases: List<CaseEntity>)

    @Query("SELECT * FROM cases WHERE user_id = :userId ORDER BY created_at DESC")
    fun observeByUserId(userId: Int): Flow<List<CaseEntity>>

    @Query("SELECT * FROM cases WHERE remote_id = :remoteId LIMIT 1")
    suspend fun findByRemoteId(remoteId: Int): CaseEntity?

    @Query("DELETE FROM cases WHERE remote_id = :remoteId")
    suspend fun deleteByRemoteId(remoteId: Int)

    @Query("DELETE FROM cases WHERE user_id = :userId")
    suspend fun clearByUserId(userId: Int)
}
