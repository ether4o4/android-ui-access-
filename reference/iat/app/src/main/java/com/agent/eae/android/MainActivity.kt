package com.agent.eae.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.agent.eae.android.core.orchestrator.AgentEngine
import com.agent.eae.android.ui.AgentScreen
import com.agent.eae.android.ui.IatTheme
import com.agent.eae.android.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentEngine.init(applicationContext)
        setContent {
            IatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Plain state instead of a nav graph: Navigation-Compose is not a dependency.
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    if (showSettings) {
                        BackHandler { showSettings = false }
                        SettingsScreen(onBack = { showSettings = false })
                    } else {
                        AgentScreen(onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}
