package com.example.learning.repos

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val HOME_STOP_ID = stringPreferencesKey("home_stop_id")
        val SAVED_STOPS = stringSetPreferencesKey("saved_stops")
    }

    val darkTheme: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.DARK_THEME] ?: false }
    suspend fun setDarkTheme(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    val homeStopId: Flow<String?> = context.settingsDataStore.data
        .map { it[Keys.HOME_STOP_ID] }
    suspend fun setHomeStopId(stopId: String) {
        context.settingsDataStore.edit { it[Keys.HOME_STOP_ID] = stopId }
    }

    val savedStops: Flow<Set<String>> = context.settingsDataStore.data
        .map { it[Keys.SAVED_STOPS] ?: emptySet() }
    suspend fun addSavedStop(stopId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SAVED_STOPS] = (prefs[Keys.SAVED_STOPS] ?: emptySet()) + stopId
        }
    }
    suspend fun removeSavedStop(stopId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SAVED_STOPS] = (prefs[Keys.SAVED_STOPS] ?: emptySet()) - stopId
        }
    }
}