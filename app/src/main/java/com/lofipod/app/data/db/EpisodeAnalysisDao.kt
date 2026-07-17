package com.lofipod.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeAnalysisDao {

    @Query("SELECT * FROM episode_analysis WHERE guid = :guid LIMIT 1")
    suspend fun get(guid: String): EpisodeAnalysisEntity?

    /** Player observes the displayed episode's row — null until a scan
     *  completes, then Room emits the finished analysis exactly once
     *  (plus once per rescan after a sensitivity change). */
    @Query("SELECT * FROM episode_analysis WHERE guid = :guid LIMIT 1")
    fun observe(guid: String): Flow<EpisodeAnalysisEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: EpisodeAnalysisEntity)

    @Query("DELETE FROM episode_analysis WHERE guid = :guid")
    suspend fun delete(guid: String)
}
