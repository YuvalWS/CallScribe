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

    @Query("DELETE FROM recordings WHERE fileMissing = 1")
    suspend fun deleteMissing(): Int
}

@Dao
interface PolicyDao {
    @Query("SELECT * FROM policies ORDER BY groupKind IS NULL, displayName")
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
        val have = all().mapNotNull { it.groupKind }.toSet()
        LabelKind.entries.filter { it !in have }.forEach { insert(PolicyEntity.defaultFor(it)) }
    }
}

@Database(entities = [RecordingEntity::class, PolicyEntity::class], version = 2, exportSchema = true)
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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "callscribe.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
