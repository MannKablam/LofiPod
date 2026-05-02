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

    /** All episodes at exactly this favorite tier (1 = Excellent, 2 = Most-excellent). */
    @Query("SELECT * FROM episode_state WHERE favoriteTier = :tier ORDER BY lastPlayedMillis DESC")
    fun observeAtTier(tier: Int): Flow<List<EpisodeStateEntity>>

    /** All hearted episodes regardless of tier — descending by tier then recency. */
    @Query("SELECT * FROM episode_state WHERE favoriteTier > 0 ORDER BY favoriteTier DESC, lastPlayedMillis DESC")
    fun observeAllHearted(): Flow<List<EpisodeStateEntity>>

    @Query("SELECT * FROM episode_state")
    suspend fun getAll(): List<EpisodeStateEntity>

    @Query("SELECT * FROM episode_state WHERE guid IN (:guids)")
    suspend fun getByGuids(guids: List<String>): List<EpisodeStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: EpisodeStateEntity)

    @Query("UPDATE episode_state SET favoriteTier = :tier WHERE guid = :guid")
    suspend fun setFavoriteTier(guid: String, tier: Int)

    @Query("UPDATE episode_state SET eqDisabled = :disabled WHERE guid = :guid")
    suspend fun setEqDisabled(guid: String, disabled: Boolean)

    @Query("UPDATE episode_state SET archivedAt = :archivedAt WHERE guid = :guid")
    suspend fun setArchivedAt(guid: String, archivedAt: Long)

    /**
     * Auto-archive sweep. Marks rows for [feedUrl] that:
     *  - are completed (positionMs >= durationMs - 5_000) and have a known duration,
     *  - have not been touched since [olderThan] (lastPlayedMillis cutoff),
     *  - and are not already archived.
     *
     * Returns the GUIDs of the rows that were archived so callers can clean up
     * downloads or refresh UI without re-querying.
     */
    @Query(
        "SELECT guid FROM episode_state WHERE feedUrl = :feedUrl " +
            "AND archivedAt = 0 " +
            "AND durationMs > 0 " +
            "AND positionMs >= durationMs - 5000 " +
            "AND lastPlayedMillis > 0 " +
            "AND lastPlayedMillis < :olderThan"
    )
    suspend fun guidsEligibleForAutoArchive(feedUrl: String, olderThan: Long): List<String>

    @Query(
        "UPDATE episode_state SET archivedAt = :now WHERE guid IN (:guids) AND archivedAt = 0"
    )
    suspend fun bulkArchive(guids: List<String>, now: Long)

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

@Dao
interface QueueEntryDao {

    @Query("SELECT * FROM queue_entry ORDER BY position ASC")
    fun observeAll(): Flow<List<QueueEntryEntity>>

    @Query("SELECT * FROM queue_entry ORDER BY position ASC")
    suspend fun getAll(): List<QueueEntryEntity>

    @Query("SELECT * FROM queue_entry WHERE guid = :guid LIMIT 1")
    suspend fun get(guid: String): QueueEntryEntity?

    /**
     * Lowest-position entry whose [QueueEntryEntity.guid] is not in [excludingGuids].
     * Used to find "what to auto-play next" — pass the just-finished guid so we don't
     * pick it again.
     */
    @Query("SELECT * FROM queue_entry WHERE guid NOT IN (:excludingGuids) ORDER BY position ASC LIMIT 1")
    suspend fun nextAfter(excludingGuids: List<String>): QueueEntryEntity?

    @Query("SELECT MAX(position) FROM queue_entry")
    suspend fun maxPosition(): Long?

    @Query("SELECT COUNT(*) FROM queue_entry")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: QueueEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<QueueEntryEntity>)

    @Query("DELETE FROM queue_entry WHERE guid = :guid")
    suspend fun remove(guid: String)

    @Query("DELETE FROM queue_entry")
    suspend fun clear()

    @Query("UPDATE queue_entry SET position = :position WHERE guid = :guid")
    suspend fun setPosition(guid: String, position: Long)
}

@Dao
interface FeedVisitDao {

    @Query("SELECT * FROM feed_visit")
    fun observeAll(): Flow<List<FeedVisitEntity>>

    @Query("SELECT lastVisitedAt FROM feed_visit WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun lastVisitedAt(feedUrl: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FeedVisitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<FeedVisitEntity>)

    @Query("INSERT OR IGNORE INTO feed_visit(feedUrl, lastVisitedAt) VALUES(:feedUrl, :ts)")
    suspend fun seedIfMissing(feedUrl: String, ts: Long)
}

@Database(
    entities = [
        EpisodeStateEntity::class,
        PodcastSourceEntity::class,
        EpisodeNoteEntryEntity::class,
        PlaybackCheckpointEntity::class,
        QueueEntryEntity::class,
        FeedVisitEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun episodeStateDao(): EpisodeStateDao
    abstract fun podcastSourceDao(): PodcastSourceDao
    abstract fun episodeNoteEntryDao(): EpisodeNoteEntryDao
    abstract fun playbackCheckpointDao(): PlaybackCheckpointDao
    abstract fun queueEntryDao(): QueueEntryDao
    abstract fun feedVisitDao(): FeedVisitDao

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

        /** v7 → v8: per-episode "disable EQ" override. Default 0 (use global). */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE episode_state ADD COLUMN eqDisabled INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v8 → v9: archive support. archivedAt = 0 means "not archived"; any other
         * value is the epoch-ms timestamp when the episode was archived. Default 0
         * so existing rows show up in the per-podcast list as they did before.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE episode_state ADD COLUMN archivedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v9 → v10: feed_visit table for the new-episodes badge on Library. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS feed_visit (
                        feedUrl TEXT NOT NULL PRIMARY KEY,
                        lastVisitedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v6 → v7: replace 1-5 star [rating] + [isFavorite] boolean with a single
         * [favoriteTier] int (0 none / 1 Excellent / 2 Most-excellent). SQLite can't
         * drop columns directly, so we recreate the table. Backfill rule:
         *   - rating == 5             → tier 2 (top of the old scale)
         *   - rating >= 4 OR isFavorite → tier 1
         *   - else                    → tier 0
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS episode_state_new (
                        guid TEXT NOT NULL PRIMARY KEY,
                        feedUrl TEXT NOT NULL,
                        title TEXT NOT NULL,
                        audioUrl TEXT NOT NULL,
                        artworkUrl TEXT,
                        favoriteTier INTEGER NOT NULL DEFAULT 0,
                        positionMs INTEGER NOT NULL DEFAULT 0,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        lastPlayedMillis INTEGER NOT NULL DEFAULT 0,
                        cumulativeListenMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO episode_state_new
                        (guid, feedUrl, title, audioUrl, artworkUrl, favoriteTier,
                         positionMs, durationMs, lastPlayedMillis, cumulativeListenMs)
                    SELECT
                        guid, feedUrl, title, audioUrl, artworkUrl,
                        CASE
                            WHEN rating >= 5 THEN 2
                            WHEN rating >= 4 OR isFavorite = 1 THEN 1
                            ELSE 0
                        END,
                        positionMs, durationMs, lastPlayedMillis, cumulativeListenMs
                    FROM episode_state
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE episode_state")
                db.execSQL("ALTER TABLE episode_state_new RENAME TO episode_state")
            }
        }

        /** v5 → v6: add queue_entry table for the playback queue. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS queue_entry (
                        guid TEXT NOT NULL PRIMARY KEY,
                        feedUrl TEXT NOT NULL,
                        title TEXT NOT NULL,
                        audioUrl TEXT NOT NULL,
                        artworkUrl TEXT,
                        position INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL
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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .build().also { instance = it }
            }
    }
}
