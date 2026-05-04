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
}
