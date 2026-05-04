package com.lofipod.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KabodPackDao {

    @Query("SELECT * FROM kabod_pack WHERE packId = :packId LIMIT 1")
    suspend fun get(packId: String): KabodPackEntity?

    @Query("SELECT * FROM kabod_pack WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun getByFeedUrl(feedUrl: String): KabodPackEntity?

    @Query("SELECT * FROM kabod_pack ORDER BY installedAt ASC")
    fun observeAll(): Flow<List<KabodPackEntity>>

    @Query("SELECT * FROM kabod_pack ORDER BY installedAt ASC")
    suspend fun getAll(): List<KabodPackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pack: KabodPackEntity)

    @Query("DELETE FROM kabod_pack WHERE packId = :packId")
    suspend fun remove(packId: String)
}
