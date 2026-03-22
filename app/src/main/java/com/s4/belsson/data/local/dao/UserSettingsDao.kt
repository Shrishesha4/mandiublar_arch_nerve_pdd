package com.s4.belsson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.s4.belsson.data.local.entity.UserSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: UserSettingsEntity)

    @Query("SELECT * FROM user_settings WHERE user_id = :userId LIMIT 1")
    suspend fun getByUserId(userId: Int): UserSettingsEntity?

    @Query("SELECT * FROM user_settings WHERE user_id = :userId LIMIT 1")
    fun observeByUserId(userId: Int): Flow<UserSettingsEntity?>
}
