package com.s4.belsson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "billing",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BillingEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: Int,
    @ColumnInfo(name = "plan_name")
    val planName: String,
    val status: String,
    @ColumnInfo(name = "next_billing_date")
    val nextBillingDate: String? = null,
    @ColumnInfo(name = "card_last4")
    val cardLast4: String? = null,
    @ColumnInfo(name = "billing_history_json")
    val billingHistoryJson: String = "[]",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
