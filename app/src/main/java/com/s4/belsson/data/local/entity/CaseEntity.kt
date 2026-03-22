package com.s4.belsson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cases",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["case_id"], unique = true),
        Index(value = ["created_at"])
    ]
)
data class CaseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "user_id")
    val userId: Int,
    @ColumnInfo(name = "remote_id")
    val remoteId: Int,
    @ColumnInfo(name = "case_id")
    val caseId: String,
    val fname: String,
    val lname: String,
    @ColumnInfo(name = "patient_age")
    val patientAge: Int? = null,
    @ColumnInfo(name = "tooth_number")
    val toothNumber: String? = null,
    val complaint: String? = null,
    @ColumnInfo(name = "case_type")
    val caseType: String? = null,
    val status: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
