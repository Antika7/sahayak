package com.sahayak.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserPreferences(private val context: Context) {

    companion object {
        private val KEY_NAME     = stringPreferencesKey("user_name")
        private val KEY_LANGUAGE = stringPreferencesKey("user_language")
        private val KEY_DOB      = stringPreferencesKey("user_dob")
        private val KEY_CITY     = stringPreferencesKey("user_city")
    }

    val userName:     Flow<String?> = context.dataStore.data.map { it[KEY_NAME] }
    val userLanguage: Flow<String>  = context.dataStore.data.map { it[KEY_LANGUAGE] ?: "English" }
    val userDob:      Flow<String>  = context.dataStore.data.map { it[KEY_DOB]  ?: "" }
    val userCity:     Flow<String>  = context.dataStore.data.map { it[KEY_CITY] ?: "" }

    suspend fun save(name: String, language: String, dob: String = "", city: String = "") {
        context.dataStore.edit { prefs ->
            prefs[KEY_NAME]     = name
            prefs[KEY_LANGUAGE] = language
            prefs[KEY_DOB]      = dob
            prefs[KEY_CITY]     = city
        }
    }
}
