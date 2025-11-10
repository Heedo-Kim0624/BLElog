package com.example.bletest

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.devicePrefsDataStore by preferencesDataStore(name = "ble_preferences")

object DevicePreferencesKeys {
    val DEVICE_NAME: Preferences.Key<String> = stringPreferencesKey("device_name")
}

fun Flow<Preferences>.mapDeviceName(defaultValue: String): Flow<String> =
    this.catch { exception ->
        if (exception is IOException) emit(emptyPreferences()) else throw exception
    }.map { prefs ->
        prefs[DevicePreferencesKeys.DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: defaultValue
    }
