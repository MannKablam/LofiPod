package com.lofipod.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("lofipod_settings")

class Settings(private val context: Context) {

    private val KEY_SOURCES_URI = stringPreferencesKey("sources_uri")
    private val KEY_EQ_BANDS = stringPreferencesKey("eq_bands")    // CSV of gains
    private val KEY_GAIN_DB = floatPreferencesKey("gain_db")
    private val KEY_PAUSE_ON_NOTE = booleanPreferencesKey("pause_on_note")

    val sourcesUri: Flow<String?> =
        context.dataStore.data.map { it[KEY_SOURCES_URI] }

    suspend fun setSourcesUri(uri: String) {
        context.dataStore.edit { it[KEY_SOURCES_URI] = uri }
    }

    val eqBandsCsv: Flow<String?> =
        context.dataStore.data.map { it[KEY_EQ_BANDS] }

    suspend fun setEqBandsCsv(csv: String) {
        context.dataStore.edit { it[KEY_EQ_BANDS] = csv }
    }

    val gainDb: Flow<Float> =
        context.dataStore.data.map { it[KEY_GAIN_DB] ?: 0f }

    suspend fun setGainDb(v: Float) {
        context.dataStore.edit { it[KEY_GAIN_DB] = v }
    }

    /** Auto-pause playback while the user is composing a note. Default true. */
    val pauseOnNote: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_PAUSE_ON_NOTE] ?: true }

    suspend fun setPauseOnNote(v: Boolean) {
        context.dataStore.edit { it[KEY_PAUSE_ON_NOTE] = v }
    }
}
