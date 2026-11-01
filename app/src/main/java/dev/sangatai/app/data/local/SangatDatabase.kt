package dev.sangatai.app.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sangatai.app.data.local.crm.CaptureDao
import dev.sangatai.app.data.local.crm.CaptureEntity
import dev.sangatai.app.data.local.crm.ConnectionDao
import dev.sangatai.app.data.local.crm.ConnectionEntity
import dev.sangatai.app.data.local.crm.CrmConverters
import dev.sangatai.app.data.local.crm.InteractionLogDao
import dev.sangatai.app.data.local.crm.InteractionLogEntity
import dev.sangatai.app.data.local.crm.PersonDao
import dev.sangatai.app.data.local.crm.PersonEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Database(
    entities = [
        IdeaEntity::class,
        PersonEntity::class,
        CaptureEntity::class,
        InteractionLogEntity::class,
        ConnectionEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class, CrmConverters::class)
abstract class SangatDatabase : RoomDatabase() {
    abstract fun ideaDao(): IdeaDao
    abstract fun personDao(): PersonDao
    abstract fun captureDao(): CaptureDao
    abstract fun interactionLogDao(): InteractionLogDao
    abstract fun connectionDao(): ConnectionDao

    companion object {
        const val NAME = "sangatai.db"

        /**
         * Deleted rows are overwritten with zeros instead of lingering in free pages, so a delete
         * really removes the text from the file.
         */
        val SECURE_DELETE = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.query("PRAGMA secure_delete = ON").close()
            }
        }

        /**
         * The social graph: people, the rants they came from, interactions and connections.
         * Only adds tables; every existing note stays. The SQL matches Room's generated schema exactly.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `name_key` TEXT NOT NULL, `aliases_json` TEXT NOT NULL, " +
                        "`dynamic_tags_json` TEXT NOT NULL, `suppressed_tags_json` TEXT NOT NULL, `embedding` BLOB, " +
                        "`embedding_model` TEXT, `name_locked` INTEGER NOT NULL, `created_by_user` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_interaction_at` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_people_name_key` ON `people` (`name_key`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_people_last_interaction_at` ON `people` (`last_interaction_at`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `captures` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`raw_text` TEXT NOT NULL, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                        "`last_error` TEXT, `summary` TEXT, `mention_count` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_status` ON `captures` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_created_at` ON `captures` (`created_at`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `interaction_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`person_id` INTEGER NOT NULL, `capture_id` INTEGER, `timestamp` INTEGER NOT NULL, " +
                        "`raw_text` TEXT NOT NULL, `nano_summary` TEXT, `tags_added_json` TEXT NOT NULL, " +
                        "FOREIGN KEY(`person_id`) REFERENCES `people`(`id`) ON UPDATE CASCADE ON DELETE CASCADE , " +
                        "FOREIGN KEY(`capture_id`) REFERENCES `captures`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_interaction_logs_person_id_timestamp` " +
                        "ON `interaction_logs` (`person_id`, `timestamp`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_interaction_logs_capture_id` ON `interaction_logs` (`capture_id`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `connections` (`edge_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`person_a` INTEGER NOT NULL, `person_b` INTEGER NOT NULL, `relation_json` TEXT NOT NULL, " +
                        "FOREIGN KEY(`person_a`) REFERENCES `people`(`id`) ON UPDATE CASCADE ON DELETE CASCADE , " +
                        "FOREIGN KEY(`person_b`) REFERENCES `people`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_connections_person_a_person_b` " +
                        "ON `connections` (`person_a`, `person_b`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_connections_person_b` ON `connections` (`person_b`)")
            }
        }

        /** Adds the category column; existing ideas keep everything and show as Other. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ideas ADD COLUMN category TEXT")
            }
        }

        /** Adds the refine chat log; existing ideas start with no turns. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ideas ADD COLUMN refinements TEXT")
            }
        }

        /**
         * Ideas became Markdown notes: the summary, task list and tags of each split idea are
         * folded into one body (ticked tasks stay ticked as "- [x]"), and their columns dropped.
         * SQLite can't drop columns on older Androids, so the table is rebuilt.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE ideas_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, raw_input TEXT NOT NULL, idea_title TEXT, " +
                        "body TEXT, category TEXT, status TEXT NOT NULL, attempts INTEGER NOT NULL, last_error TEXT, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, refinements TEXT)"
                )
                db.query(
                    "SELECT id, raw_input, idea_title, raw_concept, tech_stack_anchors, immediate_micro_tasks, " +
                        "category, completed_mask, status, attempts, last_error, created_at, updated_at, refinements FROM ideas"
                ).use { row ->
                    while (row.moveToNext()) {
                        val body = legacyBody(
                            concept = row.getString(3).orEmpty(),
                            tags = legacyList(row.getString(4)),
                            tasks = legacyList(row.getString(5)),
                            doneMask = row.getInt(7),
                        )
                        val values = ContentValues().apply {
                            put("id", row.getLong(0))
                            put("raw_input", row.getString(1))
                            put("idea_title", row.getString(2))
                            put("body", body.ifEmpty { null })
                            put("category", row.getString(6))
                            put("status", row.getString(8))
                            put("attempts", row.getInt(9))
                            put("last_error", row.getString(10))
                            put("created_at", row.getLong(11))
                            put("updated_at", row.getLong(12))
                            put("refinements", row.getString(13))
                        }
                        db.insert("ideas_new", SQLiteDatabase.CONFLICT_NONE, values)
                    }
                }
                db.execSQL("DROP TABLE ideas")
                db.execSQL("ALTER TABLE ideas_new RENAME TO ideas")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_created_at ON ideas (created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_status ON ideas (status)")
            }
        }

        /** The JSON string lists version 3 stored tags and tasks in. */
        private fun legacyList(value: String?): List<String> =
            if (value.isNullOrEmpty()) emptyList()
            else runCatching { Json.decodeFromString(ListSerializer(String.serializer()), value) }.getOrDefault(emptyList())

        private fun legacyBody(concept: String, tags: List<String>, tasks: List<String>, doneMask: Int): String =
            buildList {
                if (concept.isNotBlank()) add(concept.trim())
                if (tasks.isNotEmpty()) {
                    add(
                        tasks.mapIndexed { index, task ->
                            val done = index < Int.SIZE_BITS - 1 && (doneMask and (1 shl index)) != 0
                            "- [${if (done) "x" else " "}] $task"
                        }.joinToString("\n")
                    )
                }
                if (tags.isNotEmpty()) add("*${tags.joinToString(" · ")}*")
            }.joinToString("\n\n")
    }
}
