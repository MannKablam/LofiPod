package com.lofipod.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeStateDao {

    @Query("SELECT * FROM episode_state WHERE guid = :guid LIMIT 1")
    suspend fun get(guid: String): EpisodeStateEntity?

    @Query("SELECT * FROM episode_state WHERE guid = :guid LIMIT 1")
    fun observe(guid: String): Flow<EpisodeStateEntity?>

    @Query("SELECT * FROM episode_state WHERE isFavorite = 1 ORDER BY lastPlayedMillis DESC")
    fun observeFavorites(): Flow<List<EpisodeStateEntity>>

    @Query("SELECT * FROM episode_state WHERE rating > 0 ORDER BY rating DESC, lastPlayedMillis DESC")
    fun observeRated(): Flow<List<EpisodeStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: EpisodeStateEntity)

    @Query("UPDATE episode_state SET rating = :rating WHERE guid = :guid")
    suspend fun setRating(guid: String, rating: Int)

    @Query("UPDATE episode_state SET isFavorite = :fav WHERE guid = :guid")
    suspend fun setFavorite(guid: String, fav: Boolean)

    @Query("UPDATE episode_state SET positionMs = :pos, durationMs = :dur, lastPlayedMillis = :now WHERE guid = :guid")
    suspend fun updatePosition(guid: String, pos: Long, dur: Long, now: Long)
}

@Dao
interface PodcastSourceDao {

    @Query("SELECT * FROM podcast_source ORDER BY addedAtMillis ASC")
    fun observeAll(): Flow<List<PodcastSourceEntity>>

    @Query("SELECT * FROM podcast_source ORDER BY addedAtMillis ASC")
    suspend fun getAll(): List<PodcastSourceEntity>

    /** Insert each entry only if its feedUrl isn't already present. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entries: List<PodcastSourceEntity>)

    @Query("DELETE FROM podcast_source WHERE feedUrl = :feedUrl")
    suspend fun remove(feedUrl: String)

    @Query("DELETE FROM podcast_source")
    suspend fun clear()
}

@Database(
    entities = [EpisodeStateEntity::class, PodcastSourceEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun episodeStateDao(): EpisodeStateDao
    abstract fun podcastSourceDao(): PodcastSourceDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS podcast_source (
                        feedUrl TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT,
                        addedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lofipod.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
