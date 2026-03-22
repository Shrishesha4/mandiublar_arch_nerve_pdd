package com.s4.belsson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.s4.belsson.data.local.entity.CaseAnalysisEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CaseAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(analysis: CaseAnalysisEntity)

    @Query("SELECT * FROM case_analysis WHERE case_local_id = :caseLocalId LIMIT 1")
    fun observeByCaseLocalId(caseLocalId: Long): Flow<CaseAnalysisEntity?>
}
