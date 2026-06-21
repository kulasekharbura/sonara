package com.example.sonara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sonara.data.models.RepeatMode
import com.example.sonara.ui.theme.SpotifyGreen
import com.example.sonara.ui.theme.SpotifyLightGray

@Composable
fun RepeatButton(
    repeatMode: RepeatMode,
    onToggle: () -> Unit
) {
    val tint = if (repeatMode == RepeatMode.OFF) SpotifyLightGray else SpotifyGreen

    Box(contentAlignment = Alignment.Center) {
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = Icons.Default.Repeat,
                contentDescription = "Repeat",
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        if (repeatMode == RepeatMode.ONE) {
            Text(
                text = "1",
                color = SpotifyGreen,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
fun ShuffleButton(
    shuffleEnabled: Boolean,
    onToggle: () -> Unit
) {
    val tint = if (shuffleEnabled) SpotifyGreen else SpotifyLightGray

    IconButton(onClick = onToggle) {
        Icon(
            imageVector = Icons.Default.Shuffle,
            contentDescription = "Shuffle",
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}
