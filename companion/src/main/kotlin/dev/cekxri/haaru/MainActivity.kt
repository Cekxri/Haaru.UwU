package dev.cekxri.haaru

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

class MainActivity : ComponentActivity() {
    private val model by viewModels<MainActivityModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HaaruApp(model.bridge, model.preferences) }
    }
}

internal class MainActivityModel(application: Application) : AndroidViewModel(application) {
    val bridge = ModuleBridge(application)
    val preferences = AppearancePreferences(viewModelScope, bridge)
}
