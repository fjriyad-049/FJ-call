package com.example.data.local

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AppLanguage
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fjradio_prefs")

data class UserSettings(
  val deviceId: String,
  val displayName: String,
  val language: AppLanguage,
  val darkThemeOverride: Boolean? = null,
)

class AppPreferences(private val context: Context) {

  private object Keys {
    val DEVICE_ID = stringPreferencesKey("device_id")
    val DISPLAY_NAME = stringPreferencesKey("display_name")
    val LANGUAGE = stringPreferencesKey("language")
    val DARK_THEME = booleanPreferencesKey("dark_theme_override")
    val HAS_DARK_OVERRIDE = booleanPreferencesKey("has_dark_override")
  }

  private val defaultDeviceName: String =
    Build.MODEL?.takeIf { it.isNotBlank() }?.let { model ->
      val manufacturer = Build.MANUFACTURER.orEmpty()
      if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    } ?: "Android Phone"

  val settingsFlow: Flow<UserSettings> =
    context.dataStore.data.map { prefs ->
      val id = prefs[Keys.DEVICE_ID] ?: "temp"
      val name = prefs[Keys.DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: defaultDeviceName
      val lang =
        when (prefs[Keys.LANGUAGE]) {
          AppLanguage.ENGLISH.name -> AppLanguage.ENGLISH
          else -> AppLanguage.BENGALI
        }
      val darkOverride =
        if (prefs[Keys.HAS_DARK_OVERRIDE] == true) prefs[Keys.DARK_THEME] else null
      UserSettings(
        deviceId = id,
        displayName = name,
        language = lang,
        darkThemeOverride = darkOverride,
      )
    }

  suspend fun ensureDeviceId(): String {
    val current = context.dataStore.data.first()[Keys.DEVICE_ID]
    if (!current.isNullOrBlank()) return current
    val generated = UUID.randomUUID().toString().take(8)
    context.dataStore.edit { prefs -> prefs[Keys.DEVICE_ID] = generated }
    return generated
  }

  suspend fun updateDisplayName(name: String) {
    val trimmed = name.trim().take(28)
    if (trimmed.isNotEmpty()) {
      context.dataStore.edit { prefs -> prefs[Keys.DISPLAY_NAME] = trimmed }
    }
  }

  suspend fun setLanguage(language: AppLanguage) {
    context.dataStore.edit { prefs -> prefs[Keys.LANGUAGE] = language.name }
  }

  suspend fun toggleDarkTheme(isDark: Boolean) {
    context.dataStore.edit { prefs ->
      prefs[Keys.HAS_DARK_OVERRIDE] = true
      prefs[Keys.DARK_THEME] = isDark
    }
  }
}
