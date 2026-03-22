package com.s4.belsson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.s4.belsson.data.local.entity.CaseFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CaseFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<CaseFileEntity>)

    @Query("SELECT * FROM case_files WHERE case_local_id = :caseLocalId ORDER BY uploaded_at DESC")
    fun observeByCaseLocalId(caseLocalId: Long): Flow<List<CaseFileEntity>>

    @Query("DELETE FROM case_files WHERE case_local_id = :caseLocalId")
    suspend fun clearByCaseLocalId(caseLocalId: Long)
}
