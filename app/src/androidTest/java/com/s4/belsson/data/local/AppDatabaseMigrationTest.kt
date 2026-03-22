package com.s4.belsson.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val dbName = "belsson-migration-test.db"

    @Test
    fun migrate1To2_preservesLegacyDataAndCreatesDomainTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)

        createVersion1Database(context)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

        val sqliteDb = db.openHelper.writableDatabase

        // Legacy tables and rows remain available after migration.
        assertEquals(1, scalarInt(sqliteDb, "SELECT COUNT(*) FROM patients"))
        assertEquals(1, scalarInt(sqliteDb, "SELECT COUNT(*) FROM medical_reports"))

        // New v2 domain tables exist.
        assertTrue(tableExists(sqliteDb, "users"))
        assertTrue(tableExists(sqliteDb, "user_settings"))
        assertTrue(tableExists(sqliteDb, "cases"))
        assertTrue(tableExists(sqliteDb, "case_files"))
        assertTrue(tableExists(sqliteDb, "billing"))
        assertTrue(tableExists(sqliteDb, "team_members"))
        assertTrue(tableExists(sqliteDb, "case_analysis"))
        assertTrue(tableExists(sqliteDb, "chat_messages"))

        db.close()
        context.deleteDatabase(dbName)
    }

    private fun createVersion1Database(context: Context) {
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `patients` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `remote_patient_id` TEXT,
                `first_name` TEXT NOT NULL,
                `last_name` TEXT NOT NULL,
                `dob` TEXT,
                `gender` TEXT,
                `phone` TEXT,
                `email` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_patients_remote_patient_id` ON `patients` (`remote_patient_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_patients_last_name_first_name` ON `patients` (`last_name`, `first_name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_patients_updated_at` ON `patients` (`updated_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `medical_reports` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `patient_id` INTEGER NOT NULL,
                `session_id` TEXT NOT NULL,
                `scan_region` TEXT NOT NULL,
                `workflow` TEXT NOT NULL,
                `safe_height_mm` REAL NOT NULL,
                `bone_width_mm` REAL NOT NULL,
                `bone_height_mm` REAL NOT NULL,
                `nerve_detected` INTEGER NOT NULL,
                `recommendation` TEXT NOT NULL,
                `pdf_path` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                FOREIGN KEY(`patient_id`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_medical_reports_patient_id` ON `medical_reports` (`patient_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_medical_reports_session_id` ON `medical_reports` (`session_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_medical_reports_created_at` ON `medical_reports` (`created_at`)")

        db.execSQL(
            """
            INSERT INTO patients(id, remote_patient_id, first_name, last_name, dob, gender, phone, email, created_at, updated_at)
            VALUES (1, 'legacy-patient', 'Legacy', 'User', NULL, NULL, NULL, NULL, 1700000000, 1700000000)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO medical_reports(
                id, patient_id, session_id, scan_region, workflow, safe_height_mm,
                bone_width_mm, bone_height_mm, nerve_detected, recommendation, pdf_path, created_at
            )
            VALUES (1, 1, 'legacy-session', 'mandible', 'cbct_implant', 9.5, 7.1, 11.2, 1, 'ok', '/tmp/report.pdf', 1700000000)
            """.trimIndent()
        )

        db.execSQL("PRAGMA user_version = 1")
        db.close()
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, tableName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun scalarInt(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }
}
