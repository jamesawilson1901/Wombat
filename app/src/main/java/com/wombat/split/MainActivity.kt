package com.wombat.split

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wombat.split.ui.JobsScreen
import com.wombat.split.ui.theme.WombatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WombatTheme {
                JobsScreen()
            }
        }
    }
}
