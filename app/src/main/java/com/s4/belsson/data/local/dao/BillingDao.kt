package com.s4.belsson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.s4.belsson.data.local.entity.BillingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BillingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(billing: BillingEntity)

    @Query("SELECT * FROM billing WHERE user_id = :userId LIMIT 1")
    fun observeByUserId(userId: Int): Flow<BillingEntity?>
}
