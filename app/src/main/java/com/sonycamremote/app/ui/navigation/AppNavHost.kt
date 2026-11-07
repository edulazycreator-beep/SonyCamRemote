package com.sonycamremote.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sonycamremote.app.feature.compat.CompatibilityScreen
import com.sonycamremote.app.feature.connect.ConnectScreen
import com.sonycamremote.app.feature.dashboard.DashboardScreen
import com.sonycamremote.app.feature.diagnostics.DiagnosticsScreen
import com.sonycamremote.app.feature.info.CameraInfoScreen
import com.sonycamremote.app.feature.liveview.LiveViewScreen
import com.sonycamremote.app.feature.media.MediaScreen
import com.sonycamremote.app.feature.multicamera.MultiCameraScreen
import com.sonycamremote.app.feature.presets.PresetsScreen
import com.sonycamremote.app.feature.settings.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val CONNECT = "connect"
    const val LIVE_VIEW = "liveview"
    const val INFO = "info"
    const val MEDIA = "media"
    const val MULTI = "multicamera"
    const val PRESETS = "presets"
    const val DIAGNOSTICS = "diagnostics"
    const val COMPAT = "compatibility"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val go: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { DashboardScreen(onNavigate = go) }
        composable(Routes.CONNECT) {
            ConnectScreen(onBack = back, onConnected = {
                nav.navigate(Routes.LIVE_VIEW) { popUpTo(Routes.DASHBOARD); launchSingleTop = true }
            })
        }
        composable(Routes.LIVE_VIEW) { LiveViewScreen(onBack = back, onNavigate = go) }
        composable(Routes.INFO) { CameraInfoScreen(onBack = back) }
        composable(Routes.MEDIA) { MediaScreen(onBack = back) }
        composable(Routes.MULTI) { MultiCameraScreen(onBack = back, onOpenLiveView = { go(Routes.LIVE_VIEW) }, onAdd = { go(Routes.CONNECT) }) }
        composable(Routes.PRESETS) { PresetsScreen(onBack = back) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = back) }
        composable(Routes.COMPAT) { CompatibilityScreen(onBack = back) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = back) }
    }
}
