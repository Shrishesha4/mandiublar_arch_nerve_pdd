package com.s4.belsson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.s4.belsson.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE user_id = :userId ORDER BY sent_at ASC")
    fun observeByUserId(userId: Int): Flow<List<ChatMessageEntity>>

    @Query("DELETE FROM chat_messages WHERE user_id = :userId")
    suspend fun clearByUserId(userId: Int)
}
