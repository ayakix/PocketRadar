package com.ayakix.pocketradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayakix.pocketradar.ui.MapScreen
import com.ayakix.pocketradar.ui.RadarViewModel
import com.ayakix.pocketradar.ui.theme.PocketRadarTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketRadarTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: RadarViewModel = viewModel(
                        factory = RadarViewModel.Factory(applicationContext),
                    )
                    MapScreen(viewModel)
                }
            }
        }
    }
}
