package com.example.sonara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.sonara.ui.theme.SpotifyGreen
import com.example.sonara.ui.theme.SpotifyLightGray

/**
 * Represents the current detail view selection within the Library screen.
 * Used to conditionally render PlaylistDetailScreen inline since there is no
 * navigation system in the app.
 */
private sealed class LibraryDetail {
    data object LikedSongs : LibraryDetail()
    data object RecentSongs : LibraryDetail()
    data class CustomPlaylist(val playlistId: Long) : LibraryDetail()
}

@Composable
fun LibraryScreen(viewModel: PlaybackViewModel) {
    val likedSongIds by viewModel.likedSongIds.collectAsState()
    val customPlaylists by viewModel.customPlaylists.collectAsState()
    val recentSongs by viewModel.recentSongs.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedDetail by remember { mutableStateOf<LibraryDetail?>(null) }

    // If a detail view is selected, show PlaylistDetailScreen inline
    when (val detail = selectedDetail) {
        is LibraryDetail.LikedSongs -> {
            PlaylistDetailScreen(
                viewModel = viewModel,
                playlistType = "liked",
                onBack = { selectedDetail = null }
            )
            return
        }
        is LibraryDetail.RecentSongs -> {
            PlaylistDetailScreen(
                viewModel = viewModel,
                playlistType = "recent",
                onBack = { selectedDetail = null }
            )
            return
        }
        is LibraryDetail.CustomPlaylist -> {
            PlaylistDetailScreen(
                viewModel = viewModel,
                playlistType = "custom",
                playlistId = detail.playlistId,
                onBack = { selectedDetail = null }
            )
            return
        }
        null -> { /* Show library list below */ }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Your Library",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(16.dp))

        // Create New Playlist button
        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SpotifyGreen
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Create playlist",
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Create New Playlist",
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        // Create Playlist Dialog
        if (showCreateDialog) {
            var playlistName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Create Playlist", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        placeholder = { Text("Playlist name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (playlistName.isNotBlank()) {
                                viewModel.createPlaylist(playlistName.trim())
                                showCreateDialog = false
                            }
                        }
                    ) { Text("Create", color = SpotifyGreen) }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("Cancel", color = SpotifyLightGray) }
                },
                containerColor = Color(0xFF282828)
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Liked Songs entry
            item {
                LibraryItemRow(
                    icon = Icons.Default.Favorite,
                    iconTint = SpotifyGreen,
                    title = "Liked Songs",
                    subtitle = "${likedSongIds.size} songs",
                    onClick = { selectedDetail = LibraryDetail.LikedSongs }
                )
            }

            // Recent Songs entry
            item {
                LibraryItemRow(
                    icon = Icons.Default.History,
                    iconTint = SpotifyLightGray,
                    title = "Recent Songs",
                    subtitle = "${recentSongs.size} songs",
                    onClick = { selectedDetail = LibraryDetail.RecentSongs }
                )
            }

            // Custom playlists
            items(customPlaylists) { playlist ->
                LibraryItemRow(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    iconTint = SpotifyLightGray,
                    title = playlist.name,
                    subtitle = "Playlist",
                    onClick = { selectedDetail = LibraryDetail.CustomPlaylist(playlist.id) }
                )
            }
        }
    }
}

@Composable
private fun LibraryItemRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = SpotifyLightGray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    }
}
