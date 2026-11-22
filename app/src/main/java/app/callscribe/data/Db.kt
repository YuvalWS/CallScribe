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
interface ContactRuleDao {
    @Query("SELECT * FROM contact_rules WHERE list = :list ORDER BY displayName")
    fun observe(list: RuleList): Flow<List<ContactRuleEntity>>

    @Query("SELECT * FROM contact_rules WHERE list = :list")
    suspend fun get(list: RuleList): List<ContactRuleEntity>

    @Insert
    suspend fun insert(r: ContactRuleEntity): Long

    @Query("DELETE FROM contact_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [RecordingEntity::class, ContactRuleEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun contactRules(): ContactRuleDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "callscribe.db").build()
    }
}
