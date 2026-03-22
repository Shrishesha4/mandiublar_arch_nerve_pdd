package com.s4.belsson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "case_analysis",
    foreignKeys = [
        ForeignKey(
            entity = CaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["case_local_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["case_local_id"], unique = true)]
)
data class CaseAnalysisEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "case_local_id")
    val caseLocalId: Long,
    @ColumnInfo(name = "arch_curve_json")
    val archCurveJson: String,
    @ColumnInfo(name = "nerve_path_json")
    val nervePathJson: String,
    @ColumnInfo(name = "bone_width_36")
    val boneWidth36: String,
    @ColumnInfo(name = "bone_height")
    val boneHeight: String,
    @ColumnInfo(name = "nerve_distance")
    val nerveDistance: String,
    @ColumnInfo(name = "safe_implant_length")
    val safeImplantLength: String,
    @ColumnInfo(name = "clinical_report")
    val clinicalReport: String? = null,
    @ColumnInfo(name = "patient_explanation")
    val patientExplanation: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String
)
