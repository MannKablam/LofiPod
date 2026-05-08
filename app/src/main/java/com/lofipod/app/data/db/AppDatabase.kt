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

    /**
     * Most recently played episode (by `lastPlayedMillis`). Used by the
     * cold-start restore path in `PlayerController.restoreLastEpisodeIfNeeded`
     * so reopening the app surfaces the last episode the user was on,
     * paused at its saved position. Excludes rows that have never been
     * played (lastPlayedMillis == 0).
     */
    @Query("SELECT * FROM episode_state WHERE lastPlayedMillis > 0 ORDER BY lastPlayedMillis DESC LIMIT 1")
    suspend fun mostRecentlyPlayed(): EpisodeStateEntity?

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

    /** Set or clear the per-episode EQ band override (CSV of dB gains, or null). */
    @Query("UPDATE episode_state SET eqBandsCsvOverride = :csv WHERE guid = :guid")
    suspend fun setEqBandsCsvOverride(guid: String, csv: String?)

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

    @Query("DELETE FROM playback_checkpoint")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM playback_checkpoint")
    suspend fun count(): Int
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

@Dao
interface PodcastStateDao {

    @Query("SELECT * FROM podcast_state WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun get(feedUrl: String): PodcastStateEntity?

    @Query("SELECT * FROM podcast_state WHERE feedUrl = :feedUrl LIMIT 1")
    fun observe(feedUrl: String): Flow<PodcastStateEntity?>

    @Query("SELECT * FROM podcast_state")
    suspend fun getAll(): List<PodcastStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PodcastStateEntity)

    /**
     * Set [defaultSpeed] for [feedUrl], creating the row if it doesn't exist
     * yet. Two-step (upsert + update) so the per-podcast default-speed UI
     * doesn't need to know whether a row exists first.
     */
    @Query("UPDATE podcast_state SET defaultSpeed = :speed WHERE feedUrl = :feedUrl")
    suspend fun setDefaultSpeed(feedUrl: String, speed: Float?)
}

@Dao
interface AutoDownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AutoDownloadEntity)

    @Query("SELECT * FROM auto_download WHERE guid = :guid LIMIT 1")
    suspend fun get(guid: String): AutoDownloadEntity?

    @Query("DELETE FROM auto_download WHERE guid = :guid")
    suspend fun delete(guid: String)

    /**
     * GUIDs of auto-download rows whose corresponding episode has finished
     * playing AND hasn't been engaged with for [cutoffMs] milliseconds.
     * Drives the 1-hour-after-finished expiration sweep. Inner-join on
     * episode_state so a leaked auto_download row without a matching
     * episode_state never blocks the result (we'd never expire what we
     * can't see).
     */
    @Query(
        "SELECT a.guid FROM auto_download a " +
            "INNER JOIN episode_state e ON a.guid = e.guid " +
            "WHERE e.durationMs > 0 " +
            "AND e.positionMs >= e.durationMs - 5000 " +
            "AND e.lastPlayedMillis > 0 " +
            "AND e.lastPlayedMillis < :cutoffMs"
    )
    suspend fun expiringFinishedGuids(cutoffMs: Long): List<String>

    /**
     * GUIDs of auto-download rows whose corresponding episode has NOT
     * finished playing AND has been idle for [cutoffMs] milliseconds.
     * "Idle" = max(auto_download.createdAt, episode_state.lastPlayedMillis)
     * is older than the cutoff — so the clock starts ticking from
     * whichever was most recent (the auto-download fire, or the user's
     * last playback tick). Catches both "never started" auto-downloads
     * (lastPlayedMillis = 0, clock = createdAt) and "started but
     * abandoned" ones (clock = last play tick).
     *
     * SQLite's `max(x, y)` is scalar (largest of the arguments per row),
     * not the aggregate `MAX(x)`. Confusing naming but unambiguous in
     * context.
     */
    @Query(
        "SELECT a.guid FROM auto_download a " +
            "INNER JOIN episode_state e ON a.guid = e.guid " +
            "WHERE NOT (e.durationMs > 0 AND e.positionMs >= e.durationMs - 5000) " +
            "AND max(a.createdAt, e.lastPlayedMillis) < :cutoffMs"
    )
    suspend fun expiringUnfinishedGuids(cutoffMs: Long): List<String>
}

@Database(
    entities = [
        EpisodeStateEntity::class,
        PodcastSourceEntity::class,
        EpisodeNoteEntryEntity::class,
        PlaybackCheckpointEntity::class,
        QueueEntryEntity::class,
        FeedVisitEntity::class,
        PodcastStateEntity::class,
        KabodPackEntity::class,
        EpisodeKabodEntity::class,
        EpisodeTranscriptEntity::class,
        AutoDownloadEntity::class
    ],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun episodeStateDao(): EpisodeStateDao
    abstract fun podcastSourceDao(): PodcastSourceDao
    abstract fun episodeNoteEntryDao(): EpisodeNoteEntryDao
    abstract fun playbackCheckpointDao(): PlaybackCheckpointDao
    abstract fun queueEntryDao(): QueueEntryDao
    abstract fun feedVisitDao(): FeedVisitDao
    abstract fun podcastStateDao(): PodcastStateDao
    abstract fun kabodPackDao(): KabodPackDao
    abstract fun episodeKabodDao(): EpisodeKabodDao
    abstract fun episodeTranscriptDao(): EpisodeTranscriptDao
    abstract fun autoDownloadDao(): AutoDownloadDao

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

        /** v9 → v10: feed_visit table for the new-episodes badge on Catalog. */
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
         * v10 → v11: podcast_state table for per-podcast user prefs.
         * defaultSpeed nullable — null means "no override, use 1.0x";
         * an explicit 1.0 is the user's choice and remains their choice
         * even if the global default is ever changed.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS podcast_state (
                        feedUrl TEXT NOT NULL PRIMARY KEY,
                        defaultSpeed REAL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v11 → v12: Kabod Pack support. Three new tables — none touch existing
         * tables. `kabod_pack` holds channel-level metadata for installed packs,
         * `episode_kabod` holds per-episode metadata (scripture, transcript URL,
         * part number), `episode_transcript` caches fetched transcript text so
         * re-opening doesn't hit the network.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS kabod_pack (
                        packId TEXT NOT NULL PRIMARY KEY,
                        feedUrl TEXT NOT NULL,
                        title TEXT NOT NULL,
                        speaker TEXT,
                        bookOfBible TEXT,
                        sourceSite TEXT,
                        archived INTEGER NOT NULL,
                        installedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS episode_kabod (
                        guid TEXT NOT NULL PRIMARY KEY,
                        packId TEXT NOT NULL,
                        partNumber INTEGER,
                        scripture TEXT,
                        scriptureBook TEXT,
                        scriptureStartCh INTEGER,
                        scriptureStartV INTEGER,
                        scriptureEndCh INTEGER,
                        scriptureEndV INTEGER,
                        transcriptUrl TEXT,
                        transcriptSelector TEXT,
                        speaker TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS episode_transcript (
                        guid TEXT NOT NULL PRIMARY KEY,
                        sourceUrl TEXT NOT NULL,
                        paragraphsJson TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL
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

        /**
         * v12 → v13: per-episode EQ override. Adds `eqBandsCsvOverride`
         * (nullable comma-separated list of band gains in dB). When non-null,
         * the [com.lofipod.app.player.PlayerController] applies these bands to
         * the live EQ for that episode instead of the global Settings preset.
         * Default null = no override (global EQ applies). Distinct from the
         * existing `eqDisabled` flag — disabled-for-episode and override-bands
         * are independent, so a user can disable EQ per-episode AND have a
         * standby override that re-enables only when they flip eqDisabled off.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE episode_state ADD COLUMN eqBandsCsvOverride TEXT"
                )
            }
        }

        /**
         * v13 → v14: add auto_download table. Tracks which downloads were
         * fired automatically by playEpisode (vs manually via the download
         * button) so the periodic sweep can expire auto-downloads 1 hour
         * after the episode finishes playing without touching user-triggered
         * downloads. Empty on first migration (no historical auto-flag data
         * to backfill — every existing download is treated as manual).
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS auto_download (
                        guid TEXT NOT NULL PRIMARY KEY,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Wrap a [Migration] so its `migrate(...)` execution time is
         * recorded into [com.lofipod.app.diagnostics.StartupTimings].
         * The wrapper preserves [startVersion] and [endVersion] so Room
         * still picks the right hop when chaining migrations across
         * multiple version jumps.
         *
         * Migrations only run on the first DB query after a version
         * bump, so on a steady-state install the wrappers are inert.
         * On a first-install-after-upgrade or fresh install with seeded
         * data, this surfaces which hop is the slow one in the
         * Startup section of the diagnostics screen.
         */
        private fun timed(m: Migration): Migration = object : Migration(m.startVersion, m.endVersion) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val t0 = System.nanoTime()
                try {
                    m.migrate(db)
                } finally {
                    com.lofipod.app.diagnostics.StartupTimings.record(
                        "migration_${startVersion}_to_${endVersion}",
                        t0
                    )
                }
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
                        timed(MIGRATION_1_2), timed(MIGRATION_2_3),
                        timed(MIGRATION_3_4), timed(MIGRATION_4_5),
                        timed(MIGRATION_5_6), timed(MIGRATION_6_7),
                        timed(MIGRATION_7_8), timed(MIGRATION_8_9),
                        timed(MIGRATION_9_10), timed(MIGRATION_10_11),
                        timed(MIGRATION_11_12), timed(MIGRATION_12_13),
                        timed(MIGRATION_13_14)
                    )
                    .build().also { instance = it }
            }
    }
}
