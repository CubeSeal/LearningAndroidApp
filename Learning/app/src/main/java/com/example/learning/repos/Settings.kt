package com.example.learning.repos

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.learning.SavedStopEntry
import com.example.learning.TransitFilterOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Persisted user settings, as consumed by [com.example.learning.BusInfo].
 * Plain interface so tests can drive a state-based fake (see `src/test`).
 *
 * Saved stops are [SavedStopEntry]s: a stop id plus the filter **combos** saved against it. An entry
 * with no combos is a "naked" saved stop; [addSavedCombo] appends another combo; [removeSavedStop]
 * drops the whole entry (used by the clear-all action).
 */
interface SettingsSource {
    val homeStopId: Flow<String?>
    val savedStops: Flow<List<SavedStopEntry>>
    // Whether the focused stop should keep following the user's current location. Defaults to true
    // (matches the app's original always-follow behaviour); turned off whenever the user manually
    // picks a stop (saved stop, search, or a trip's stop tap).
    val followLocation: Flow<Boolean>
    suspend fun setHomeStopId(stopId: String)
    suspend fun addSavedStop(stopId: String)
    suspend fun addSavedCombo(stopId: String, combo: List<TransitFilterOptions>)
    suspend fun removeSavedCombo(stopId: String, combo: List<TransitFilterOptions>)
    suspend fun removeSavedStop(stopId: String)
    suspend fun setFollowLocation(enabled: Boolean)
}

/** Ensure an entry for [stopId] exists (naked), returning the possibly-extended list. */
private fun List<SavedStopEntry>.ensureStop(stopId: String): List<SavedStopEntry> =
    if (any { it.stopId == stopId }) this else this + SavedStopEntry(stopId)

/** Append [combo] to [stopId]'s entry (creating it if needed), skipping duplicates. */
private fun List<SavedStopEntry>.withCombo(stopId: String, combo: List<TransitFilterOptions>): List<SavedStopEntry> =
    ensureStop(stopId).map { entry ->
        if (entry.stopId == stopId && combo !in entry.combos) entry.copy(combos = entry.combos + listOf(combo))
        else entry
    }

/** Drop [combo] from [stopId]'s entry (the stop itself stays saved). */
private fun List<SavedStopEntry>.withoutCombo(stopId: String, combo: List<TransitFilterOptions>): List<SavedStopEntry> =
    map { entry ->
        if (entry.stopId == stopId) entry.copy(combos = entry.combos - listOf(combo)) else entry
    }

class SettingsRepository(private val context: Context) : SettingsSource {
    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val HOME_STOP_ID = stringPreferencesKey("home_stop_id")
        // Legacy: a bare set of saved stop ids, superseded by SAVED_STOP_ENTRIES (JSON). Still read
        // once for migration so existing users keep their saved stops.
        val SAVED_STOPS = stringSetPreferencesKey("saved_stops")
        val SAVED_STOP_ENTRIES = stringPreferencesKey("saved_stop_entries")
        val FOLLOW_LOCATION = booleanPreferencesKey("follow_location")
    }

    val darkTheme: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.DARK_THEME] ?: false }
    suspend fun setDarkTheme(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    override val homeStopId: Flow<String?> = context.settingsDataStore.data
        .map { it[Keys.HOME_STOP_ID] }
    override suspend fun setHomeStopId(stopId: String) {
        context.settingsDataStore.edit { it[Keys.HOME_STOP_ID] = stopId }
    }

    override val savedStops: Flow<List<SavedStopEntry>> = context.settingsDataStore.data
        .map { prefs -> prefs.readSavedStops() }

    override val followLocation: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.FOLLOW_LOCATION] ?: true }
    override suspend fun setFollowLocation(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FOLLOW_LOCATION] = enabled }
    }

    override suspend fun addSavedStop(stopId: String) = editSavedStops { it.ensureStop(stopId) }
    override suspend fun addSavedCombo(stopId: String, combo: List<TransitFilterOptions>) =
        editSavedStops { it.withCombo(stopId, combo) }
    override suspend fun removeSavedCombo(stopId: String, combo: List<TransitFilterOptions>) =
        editSavedStops { it.withoutCombo(stopId, combo) }
    override suspend fun removeSavedStop(stopId: String) =
        editSavedStops { list -> list.filterNot { it.stopId == stopId } }

    private suspend fun editSavedStops(transform: (List<SavedStopEntry>) -> List<SavedStopEntry>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SAVED_STOP_ENTRIES] = Json.encodeToString(transform(prefs.readSavedStops()))
            prefs.remove(Keys.SAVED_STOPS) // migrated in; drop the legacy key
        }
    }

    // Read the JSON entries, falling back to migrating the legacy id-set into naked entries.
    private fun Preferences.readSavedStops(): List<SavedStopEntry> =
        this[Keys.SAVED_STOP_ENTRIES]?.let { Json.decodeFromString<List<SavedStopEntry>>(it) }
            ?: (this[Keys.SAVED_STOPS] ?: emptySet()).map { SavedStopEntry(it) }
}

class FakeSettingsSource(
    homeStopId: String? = null,
    savedStops: List<SavedStopEntry> = emptyList(),
    followLocation: Boolean = true,
) : SettingsSource {
    private val _homeStopId = MutableStateFlow(homeStopId)
    override val homeStopId: Flow<String?> = _homeStopId

    private val _savedStops = MutableStateFlow(savedStops)
    override val savedStops: Flow<List<SavedStopEntry>> = _savedStops

    private val _followLocation = MutableStateFlow(followLocation)
    override val followLocation: Flow<Boolean> = _followLocation

    override suspend fun setHomeStopId(stopId: String) {
        _homeStopId.value = stopId
    }

    override suspend fun setFollowLocation(enabled: Boolean) {
        _followLocation.value = enabled
    }

    override suspend fun addSavedStop(stopId: String) {
        _savedStops.value = _savedStops.value.ensureStop(stopId)
    }

    override suspend fun addSavedCombo(stopId: String, combo: List<TransitFilterOptions>) {
        _savedStops.value = _savedStops.value.withCombo(stopId, combo)
    }

    override suspend fun removeSavedCombo(stopId: String, combo: List<TransitFilterOptions>) {
        _savedStops.value = _savedStops.value.withoutCombo(stopId, combo)
    }

    override suspend fun removeSavedStop(stopId: String) {
        _savedStops.value = _savedStops.value.filterNot { it.stopId == stopId }
    }
}
