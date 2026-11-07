package com.sonycamremote.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class GridMode(val label: String) { OFF("Off"), THIRDS("Rule of thirds"), CROSSHAIR("Center crosshair"), THIRDS_CROSSHAIR("Thirds + crosshair") }

data class AppSettings(
    val grid: GridMode = GridMode.THIRDS,
    val showHistogram: Boolean = true,
    val showSafeArea: Boolean = false,
    val showClippingWarning: Boolean = true,
    val largeLiveView: Boolean = false,
    val autoReconnect: Boolean = true,
    val keepScreenOn: Boolean = true,
    val halfPressBeforeCapture: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val grid = stringPreferencesKey("grid")
        val histogram = booleanPreferencesKey("histogram")
        val safeArea = booleanPreferencesKey("safe_area")
        val clipping = booleanPreferencesKey("clipping")
        val largeLv = booleanPreferencesKey("large_lv")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val halfPress = booleanPreferencesKey("half_press")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            grid = p[Keys.grid]?.let { v -> GridMode.entries.firstOrNull { it.name == v } } ?: d.grid,
            showHistogram = p[Keys.histogram] ?: d.showHistogram,
            showSafeArea = p[Keys.safeArea] ?: d.showSafeArea,
            showClippingWarning = p[Keys.clipping] ?: d.showClippingWarning,
            largeLiveView = p[Keys.largeLv] ?: d.largeLiveView,
            autoReconnect = p[Keys.autoReconnect] ?: d.autoReconnect,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            halfPressBeforeCapture = p[Keys.halfPress] ?: d.halfPressBeforeCapture,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = AppSettings(
                grid = p[Keys.grid]?.let { v -> GridMode.entries.firstOrNull { it.name == v } } ?: GridMode.THIRDS,
                showHistogram = p[Keys.histogram] ?: true,
                showSafeArea = p[Keys.safeArea] ?: false,
                showClippingWarning = p[Keys.clipping] ?: true,
                largeLiveView = p[Keys.largeLv] ?: false,
                autoReconnect = p[Keys.autoReconnect] ?: true,
                keepScreenOn = p[Keys.keepScreenOn] ?: true,
                halfPressBeforeCapture = p[Keys.halfPress] ?: false,
            )
            val n = transform(cur)
            p[Keys.grid] = n.grid.name
            p[Keys.histogram] = n.showHistogram
            p[Keys.safeArea] = n.showSafeArea
            p[Keys.clipping] = n.showClippingWarning
            p[Keys.largeLv] = n.largeLiveView
            p[Keys.autoReconnect] = n.autoReconnect
            p[Keys.keepScreenOn] = n.keepScreenOn
            p[Keys.halfPress] = n.halfPressBeforeCapture
        }
    }
}
