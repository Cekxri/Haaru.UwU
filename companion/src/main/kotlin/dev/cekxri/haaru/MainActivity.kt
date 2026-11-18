package dev.cekxri.haaru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var bridge: ModuleBridge
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bridge = ModuleBridge(this)
        setContent {
            LaunchedEffect(Unit) { bridge.bootstrap() }
            HaaruApp(bridge)
        }
    }
    override fun onDestroy() { bridge.close(); super.onDestroy() }
}
