package com.lofipod.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EpisodeKabodDao {

    @Query("SELECT * FROM episode_kabod WHERE guid = :guid LIMIT 1")
    suspend fun get(guid: String): EpisodeKabodEntity?

    @Query("SELECT * FROM episode_kabod WHERE packId = :packId ORDER BY partNumber ASC")
    suspend fun getForPack(packId: String): List<EpisodeKabodEntity>

    @Query("SELECT * FROM episode_kabod")
    suspend fun getAll(): List<EpisodeKabodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EpisodeKabodEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<EpisodeKabodEntity>)

    @Query("DELETE FROM episode_kabod WHERE packId = :packId")
    suspend fun removeForPack(packId: String)
}
