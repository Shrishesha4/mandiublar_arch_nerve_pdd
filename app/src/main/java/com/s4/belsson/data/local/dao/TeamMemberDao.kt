package com.s4.belsson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.s4.belsson.data.local.entity.TeamMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TeamMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: TeamMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<TeamMemberEntity>)

    @Query("SELECT * FROM team_members WHERE owner_id = :ownerId ORDER BY name ASC")
    fun observeByOwnerId(ownerId: Int): Flow<List<TeamMemberEntity>>

    @Query("DELETE FROM team_members WHERE owner_id = :ownerId")
    suspend fun clearByOwnerId(ownerId: Int)

    @Query("DELETE FROM team_members WHERE remote_id = :remoteId")
    suspend fun deleteByRemoteId(remoteId: Int)
}
