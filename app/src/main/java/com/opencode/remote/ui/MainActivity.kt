package com.opencode.remote.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.opencode.remote.ui.theme.OConnectorTheme
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OConnectorTheme(darkTheme = AppLocale.darkMode) {
                OConnectorApp()
            }
        }
    }
}
