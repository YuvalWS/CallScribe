package app.callscribe.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY callTimeSec DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE mediaId = :mediaId LIMIT 1")
    suspend fun byMediaId(mediaId: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE contentHash = :hash LIMIT 1")
    suspend fun byHash(hash: String): RecordingEntity?

    @Query("SELECT * FROM recordings")
    suspend fun all(): List<RecordingEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(r: RecordingEntity): Long

    @Update
    suspend fun update(r: RecordingEntity)

    @Query("UPDATE recordings SET status = :status, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: Status, error: String? = null, now: Long = System.currentTimeMillis())

    @Query("UPDATE recordings SET keepForever = :keep WHERE id = :id")
    suspend fun setKeepForever(id: Long, keep: Boolean)

    /** Sets the relationship tag ([tag] = [Relationship.name] or null). [manual] = picked by the user. */
    @Query("UPDATE recordings SET relationship = :tag, relationshipManual = :manual, updatedAt = :now WHERE id = :id")
    suspend fun setRelationship(id: Long, tag: String?, manual: Boolean, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM recordings WHERE fileMissing = 1")
    suspend fun deleteMissing(): Int
}

@Dao
interface PolicyDao {
    @Query("SELECT * FROM policies ORDER BY groupKind IS NULL, contactGroup IS NULL, priority, tag IS NULL, tag, displayName")
    fun observeAll(): Flow<List<PolicyEntity>>

    @Query("SELECT * FROM policies")
    suspend fun all(): List<PolicyEntity>

    @Insert
    suspend fun insert(p: PolicyEntity): Long

    @Update
    suspend fun update(p: PolicyEntity)

    @Query("DELETE FROM policies WHERE id = :id")
    suspend fun delete(id: Long)

    /** Creates any missing group policy with its defaults. */
    @androidx.room.Transaction
    suspend fun ensureGroupDefaults() {
        val have = all().mapNotNull { it.callerType }.toSet()
        CallerType.entries.filter { it !in have }.forEach { insert(PolicyEntity.defaultFor(it)) }
    }
}

/** Migration SQL kept as plain strings so unit tests can run it on SQLite without Android. */
object PolicyMigrations {
    /** v4 → v5 (was 2→3 on the policies branch before the merge renumbering). Add-only. */
    val SQL_4_5 = listOf(
        "ALTER TABLE policies ADD COLUMN tag TEXT",
        "ALTER TABLE policies ADD COLUMN cloudRetentionDays INTEGER NOT NULL DEFAULT -2",
    )

    /** Keep-more merge of a retention value: forever wins, else the longer period. */
    private fun keepMore(col: String) =
        "CASE WHEN $col < 0 OR (SELECT $col FROM policies WHERE groupKind = 'FRAUD' LIMIT 1) < 0 THEN -1 " +
            "ELSE MAX($col, (SELECT $col FROM policies WHERE groupKind = 'FRAUD' LIMIT 1)) END"

    /** v5 → v6: contact-group target + priority; spam and fraud merge (keeping the more protective value), "other labels" goes. */
    val SQL_5_6 = listOf(
        "ALTER TABLE policies ADD COLUMN contactGroup TEXT",
        "ALTER TABLE policies ADD COLUMN priority INTEGER NOT NULL DEFAULT 0",
        // Spam + fraud become one caller type, keeping the more protective value of each field.
        "UPDATE policies SET " +
            "transcribe = MAX(transcribe, (SELECT transcribe FROM policies WHERE groupKind = 'FRAUD' LIMIT 1)), " +
            "summarize = MAX(summarize, (SELECT summarize FROM policies WHERE groupKind = 'FRAUD' LIMIT 1)), " +
            "audioRetentionDays = ${keepMore("audioRetentionDays")}, " +
            "textRetentionDays = ${keepMore("textRetentionDays")} " +
            "WHERE groupKind = 'SPAM' AND EXISTS (SELECT 1 FROM policies WHERE groupKind = 'FRAUD')",
        "UPDATE policies SET groupKind = 'SPAM_FRAUD' WHERE groupKind = 'SPAM'",
        "UPDATE policies SET groupKind = 'SPAM_FRAUD' WHERE groupKind = 'FRAUD' " +
            "AND NOT EXISTS (SELECT 1 FROM policies WHERE groupKind = 'SPAM_FRAUD')",
        // Samsung's other labels no longer have a caller type: those calls follow "Unknown numbers".
        "DELETE FROM policies WHERE groupKind IN ('FRAUD', 'OTHER_LABEL')",
    )

    /** v6 → v7: a policy's retention can start from a date ("only calls from now on"). 0 = all calls, as before. */
    val SQL_6_7 = listOf("ALTER TABLE policies ADD COLUMN appliesFromSec INTEGER NOT NULL DEFAULT 0")
}

@Database(entities = [RecordingEntity::class, PolicyEntity::class], version = 7, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun policies(): PolicyDao

    companion object {
        /** v1 → v2: contact include/exclude rules become contact policies; retention timestamps on recordings. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN audioDeletedAt INTEGER")
                db.execSQL("ALTER TABLE recordings ADD COLUMN textDeletedAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `policies` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`groupKind` TEXT, `displayName` TEXT NOT NULL, `numbers` TEXT NOT NULL, " +
                        "`transcribe` INTEGER NOT NULL, `summarize` INTEGER NOT NULL, " +
                        "`audioRetentionDays` INTEGER NOT NULL, `textRetentionDays` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO policies (groupKind, displayName, numbers, transcribe, summarize, audioRetentionDays, textRetentionDays) " +
                        "SELECT NULL, displayName, numbers, mode = 'INCLUDE', mode = 'INCLUDE', -1, -1 FROM contact_rules"
                )
                db.execSQL("DROP TABLE contact_rules")
            }
        }

        /** v2 → v3: per-call "keep forever" flag. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN keepForever INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 → v4: AI relationship tag + manual-override flag. */
        val MIGRATION_3_4_SQL = listOf(
            "ALTER TABLE recordings ADD COLUMN relationship TEXT",
            "ALTER TABLE recordings ADD COLUMN relationshipManual INTEGER NOT NULL DEFAULT 0",
        )
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_3_4_SQL.forEach(db::execSQL)
        }

        /** v4 → v5: policies gain a tag target and cloud retention. Add-only: every existing policy is kept as is. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                PolicyMigrations.SQL_4_5.forEach(db::execSQL)
            }
        }

        /** v5 → v6: contact groups with priority; spam+fraud merge, "other labels" goes. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                PolicyMigrations.SQL_5_6.forEach(db::execSQL)
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = PolicyMigrations.SQL_6_7.forEach(db::execSQL)
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "callscribe.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
