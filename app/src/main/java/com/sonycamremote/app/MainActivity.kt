package com.sonycamremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.sonycamremote.app.ui.navigation.AppNavHost
import com.sonycamremote.app.ui.theme.Cinema
import com.sonycamremote.app.ui.theme.SonyCamTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonyCamTheme {
                Box(Modifier.fillMaxSize().background(Cinema.Background)) {
                    AppNavHost()
                }
            }
        }
    }
}
