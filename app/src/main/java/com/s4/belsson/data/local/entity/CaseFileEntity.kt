package com.s4.belsson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "case_files",
    foreignKeys = [
        ForeignKey(
            entity = CaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["case_local_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["case_local_id"])]
)
data class CaseFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "case_local_id")
    val caseLocalId: Long,
    val filename: String,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "uploaded_at")
    val uploadedAt: String
)
