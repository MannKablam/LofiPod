package com.lofipod.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

@Database(entities = [EpisodeStateEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun episodeStateDao(): EpisodeStateDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lofipod.db"
                ).build().also { instance = it }
            }
    }
}
