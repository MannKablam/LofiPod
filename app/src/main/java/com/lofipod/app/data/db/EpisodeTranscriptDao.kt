package com.lofipod.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EpisodeTranscriptDao {

    @Query("SELECT * FROM episode_transcript WHERE guid = :guid LIMIT 1")
    suspend fun get(guid: String): EpisodeTranscriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EpisodeTranscriptEntity)

    @Query("DELETE FROM episode_transcript WHERE guid = :guid")
    suspend fun delete(guid: String)

    @Query("DELETE FROM episode_transcript")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM episode_transcript")
    suspend fun count(): Int

    /**
     * Substring search inside the stored transcript text. LIKE over the
     * paragraphsJson blob — case-insensitive for ASCII per SQLite default,
     * fine at this table's scale (one row per transcript the user has ever
     * opened; dozens, not thousands). The JSON encoding only escapes
     * quotes/backslashes, so ordinary word searches match the raw text.
     */
    @Query(
        "SELECT * FROM episode_transcript WHERE paragraphsJson LIKE '%' || :needle || '%' " +
            "ORDER BY fetchedAt DESC LIMIT :limit"
    )
    suspend fun search(needle: String, limit: Int = 50): List<EpisodeTranscriptEntity>
}
