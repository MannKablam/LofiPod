package com.lofipod.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeHeatDao {

    @Query("SELECT * FROM episode_heat WHERE guid = :guid LIMIT 1")
    suspend fun get(guid: String): EpisodeHeatEntity?

    /** Player observes the current episode's row — Room re-emits on each
     *  ticker upsert, so the just-listened bucket brightens live. */
    @Query("SELECT * FROM episode_heat WHERE guid = :guid LIMIT 1")
    fun observe(guid: String): Flow<EpisodeHeatEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: EpisodeHeatEntity)

    @Query("DELETE FROM episode_heat WHERE guid = :guid")
    suspend fun delete(guid: String)
}
