package com.example.sonara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.sonara.ui.MainScreen
import com.example.sonara.ui.PlaybackViewModel
import com.example.sonara.ui.theme.SonaraTheme

class MainActivity : ComponentActivity() {

    // Initialize our playback controller ViewModel layer lazily
    private val playbackViewModel: PlaybackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SonaraTheme {
                // Mount the modern Spotify-style structural dock
                MainScreen(viewModel = playbackViewModel)
            }
        }
    }
}