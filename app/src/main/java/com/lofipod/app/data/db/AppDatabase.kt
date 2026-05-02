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

    @Query("SELECT * FROM episode_state")
    suspend fun getAll(): List<EpisodeStateEntity>

    @Query("SELECT * FROM episode_state WHERE guid IN (:guids)")
    suspend fun getByGuids(guids: List<String>): List<EpisodeStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: EpisodeStateEntity)

    @Query("UPDATE episode_state SET rating = :rating WHERE guid = :guid")
    suspend fun setRating(guid: String, rating: Int)

    @Query("UPDATE episode_state SET isFavorite = :fav WHERE guid = :guid")
    suspend fun setFavorite(guid: String, fav: Boolean)

    @Query(
        "UPDATE episode_state SET positionMs = :pos, durationMs = :dur, " +
            "lastPlayedMillis = :now, cumulativeListenMs = cumulativeListenMs + :listenDelta " +
            "WHERE guid = :guid"
    )
    suspend fun updatePosition(guid: String, pos: Long, dur: Long, now: Long, listenDelta: Long)
}

@Dao
interface PodcastSourceDao {

    @Query("SELECT * FROM podcast_source ORDER BY addedAtMillis ASC")
    fun observeAll(): Flow<List<PodcastSourceEntity>>

    @Query("SELECT * FROM podcast_source ORDER BY addedAtMillis ASC")
    suspend fun getAll(): List<PodcastSourceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entries: List<PodcastSourceEntity>)

    @Query("DELETE FROM podcast_source WHERE feedUrl = :feedUrl")
    suspend fun remove(feedUrl: String)

    @Query("DELETE FROM podcast_source")
    suspend fun clear()
}

@Dao
interface PlaybackCheckpointDao {

    @Insert
    suspend fun insert(checkpoint: PlaybackCheckpointEntity): Long

    @Query("SELECT * FROM playback_checkpoint ORDER BY recordedAt DESC")
    suspend fun getAll(): List<PlaybackCheckpointEntity>

    @Query("SELECT * FROM playback_checkpoint WHERE guid = :guid ORDER BY recordedAt DESC LIMIT :limit")
    suspend fun recentForEpisode(guid: String, limit: Int = 50): List<PlaybackCheckpointEntity>

    @Query("DELETE FROM playback_checkpoint WHERE id = :id")
    suspend fun delete(id: Long)

    /** Keep only the most-recent [keepCount] rows globally. Called after each insert. */
    @Query(
        "DELETE FROM playback_checkpoint WHERE id NOT IN (" +
            "SELECT id FROM playback_checkpoint ORDER BY recordedAt DESC LIMIT :keepCount" +
            ")"
    )
    suspend fun pruneToCount(keepCount: Int)
}

@Dao
interface EpisodeNoteEntryDao {

    @Query("SELECT * FROM episode_note_entry WHERE guid = :guid ORDER BY createdAt ASC")
    fun observeForEpisode(guid: String): Flow<List<EpisodeNoteEntryEntity>>

    @Query("SELECT * FROM episode_note_entry WHERE guid = :guid ORDER BY createdAt ASC")
    suspend fun getForEpisode(guid: String): List<EpisodeNoteEntryEntity>

    @Query("SELECT * FROM episode_note_entry ORDER BY createdAt DESC")
    suspend fun getAll(): List<EpisodeNoteEntryEntity>

    @Query("SELECT * FROM episode_note_entry ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getMostRecent(limit: Int, offset: Int = 0): List<EpisodeNoteEntryEntity>

    @Query("SELECT COUNT(*) FROM episode_note_entry WHERE createdAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query(
        "SELECT * FROM episode_note_entry WHERE text LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int = 200): List<EpisodeNoteEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EpisodeNoteEntryEntity)

    @Query("DELETE FROM episode_note_entry WHERE guid = :guid AND createdAt = :createdAt")
    suspend fun delete(guid: String, createdAt: Long)
}

@Database(
    entities = [
        EpisodeStateEntity::class,
        PodcastSourceEntity::class,
        EpisodeNoteEntryEntity::class,
        PlaybackCheckpointEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun episodeStateDao(): EpisodeStateDao
    abstract fun podcastSourceDao(): PodcastSourceDao
    abstract fun episodeNoteEntryDao(): EpisodeNoteEntryDao
    abstract fun playbackCheckpointDao(): PlaybackCheckpointDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE episode_state ADD COLUMN cumulativeListenMs INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS episode_note (
                        guid TEXT NOT NULL PRIMARY KEY,
                        text TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 → v4: replace single-text episode_note (UI never shipped, so no real
         * data to migrate) with multi-entry episode_note_entry.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS episode_note")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS episode_note_entry (
                        guid TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        playbackPosMs INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        PRIMARY KEY (guid, createdAt)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v4 → v5: add playback_checkpoint table for jump-back / session history. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_checkpoint (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        guid TEXT NOT NULL,
                        positionMs INTEGER NOT NULL,
                        recordedAt INTEGER NOT NULL,
                        reason TEXT NOT NULL
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
    }
}
