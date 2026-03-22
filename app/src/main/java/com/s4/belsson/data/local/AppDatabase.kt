package com.s4.belsson.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.s4.belsson.data.local.dao.BillingDao
import com.s4.belsson.data.local.dao.CaseAnalysisDao
import com.s4.belsson.data.local.dao.CaseDao
import com.s4.belsson.data.local.dao.CaseFileDao
import com.s4.belsson.data.local.dao.ChatMessageDao
import com.s4.belsson.data.local.dao.MedicalReportDao
import com.s4.belsson.data.local.dao.PatientDao
import com.s4.belsson.data.local.dao.TeamMemberDao
import com.s4.belsson.data.local.dao.UserDao
import com.s4.belsson.data.local.dao.UserSettingsDao
import com.s4.belsson.data.local.entity.BillingEntity
import com.s4.belsson.data.local.entity.CaseAnalysisEntity
import com.s4.belsson.data.local.entity.CaseEntity
import com.s4.belsson.data.local.entity.CaseFileEntity
import com.s4.belsson.data.local.entity.ChatMessageEntity
import com.s4.belsson.data.local.entity.MedicalReportEntity
import com.s4.belsson.data.local.entity.PatientEntity
import com.s4.belsson.data.local.entity.TeamMemberEntity
import com.s4.belsson.data.local.entity.UserEntity
import com.s4.belsson.data.local.entity.UserSettingsEntity

@Database(
    entities = [
        PatientEntity::class,
        MedicalReportEntity::class,
        UserEntity::class,
        UserSettingsEntity::class,
        CaseEntity::class,
        CaseFileEntity::class,
        BillingEntity::class,
        TeamMemberEntity::class,
        CaseAnalysisEntity::class,
        ChatMessageEntity::class,
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun medicalReportDao(): MedicalReportDao
    abstract fun userDao(): UserDao
    abstract fun userSettingsDao(): UserSettingsDao
    abstract fun caseDao(): CaseDao
    abstract fun caseFileDao(): CaseFileDao
    abstract fun billingDao(): BillingDao
    abstract fun teamMemberDao(): TeamMemberDao
    abstract fun caseAnalysisDao(): CaseAnalysisDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "belsson_local.db"
                ).addMigrations(MIGRATION_1_2).build().also {
                    INSTANCE = it
                }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `users` (
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `phone` TEXT,
                        `practice_name` TEXT,
                        `bio` TEXT,
                        `specialty` TEXT,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_users_email` ON `users` (`email`)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_settings` (
                        `user_id` INTEGER NOT NULL,
                        `theme` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `notifications_json` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`user_id`),
                        FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cases` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `user_id` INTEGER NOT NULL,
                        `remote_id` INTEGER NOT NULL,
                        `case_id` TEXT NOT NULL,
                        `fname` TEXT NOT NULL,
                        `lname` TEXT NOT NULL,
                        `patient_age` INTEGER,
                        `tooth_number` TEXT,
                        `complaint` TEXT,
                        `case_type` TEXT,
                        `status` TEXT NOT NULL,
                        `created_at` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_cases_user_id` ON `cases` (`user_id`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cases_case_id` ON `cases` (`case_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_cases_created_at` ON `cases` (`created_at`)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `case_files` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `case_local_id` INTEGER NOT NULL,
                        `filename` TEXT NOT NULL,
                        `file_path` TEXT NOT NULL,
                        `uploaded_at` TEXT NOT NULL,
                        FOREIGN KEY(`case_local_id`) REFERENCES `cases`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_case_files_case_local_id` ON `case_files` (`case_local_id`)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `billing` (
                        `user_id` INTEGER NOT NULL,
                        `plan_name` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `next_billing_date` TEXT,
                        `card_last4` TEXT,
                        `billing_history_json` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`user_id`),
                        FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `team_members` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `remote_id` INTEGER NOT NULL,
                        `owner_id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`owner_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_team_members_owner_id` ON `team_members` (`owner_id`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_team_members_remote_id` ON `team_members` (`remote_id`)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `case_analysis` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `case_local_id` INTEGER NOT NULL,
                        `arch_curve_json` TEXT NOT NULL,
                        `nerve_path_json` TEXT NOT NULL,
                        `bone_width_36` TEXT NOT NULL,
                        `bone_height` TEXT NOT NULL,
                        `nerve_distance` TEXT NOT NULL,
                        `safe_implant_length` TEXT NOT NULL,
                        `clinical_report` TEXT,
                        `patient_explanation` TEXT,
                        `created_at` TEXT NOT NULL,
                        FOREIGN KEY(`case_local_id`) REFERENCES `cases`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_case_analysis_case_local_id` ON `case_analysis` (`case_local_id`)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `user_id` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `sent_at` INTEGER NOT NULL,
                        FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_user_id` ON `chat_messages` (`user_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sent_at` ON `chat_messages` (`sent_at`)")
            }
        }
    }
}
