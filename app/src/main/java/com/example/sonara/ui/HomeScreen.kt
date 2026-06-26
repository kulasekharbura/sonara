package com.example.sonara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.ui.theme.SpotifyGreen
import com.example.sonara.ui.theme.SpotifyLightGray

@Composable
fun HomeScreen(viewModel: PlaybackViewModel) {
    val recentSongs by viewModel.recentSongsForHome.collectAsState()
    val customPlaylists by viewModel.customPlaylists.collectAsState()
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val likedSongIds by viewModel.likedSongIds.collectAsState()

    // Inline navigation: show PlaylistDetailScreen when a playlist is selected
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    var selectedPlaylistType by remember { mutableStateOf<String?>(null) }

    if (selectedPlaylistType != null) {
        BackHandler {
            selectedPlaylistType = null
            selectedPlaylistId = null
        }
        PlaylistDetailScreen(
            viewModel = viewModel,
            playlistType = selectedPlaylistType!!,
            playlistId = selectedPlaylistId ?: 0,
            onBack = { selectedPlaylistType = null; selectedPlaylistId = null },
            onNavigateToSearch = {}
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item {
            Text(
                text = "Good Evening",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
        }

        // Top row: Liked Songs + Downloads
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickAccessCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Favorite,
                    iconTint = SpotifyGreen,
                    title = "Liked Songs",
                    subtitle = "${likedSongIds.size} songs",
                    onClick = { selectedPlaylistType = "liked" }
                )
                QuickAccessCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Download,
                    iconTint = SpotifyGreen,
                    title = "Downloads",
                    subtitle = "${downloadedSongs.size} songs",
                    onClick = { selectedPlaylistType = "downloads" }
                )
            }
        }

        // Most recently accessed playlists (2 per row, max 4)
        val recentPlaylists = customPlaylists.take(4)
        if (recentPlaylists.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
            }
            val rows = recentPlaylists.chunked(2)
            rows.forEach { rowPlaylists ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowPlaylists.forEach { playlist ->
                            PlaylistCard(
                                modifier = Modifier.weight(1f),
                                name = playlist.name,
                                onClick = { 
                                    selectedPlaylistId = playlist.id
                                    selectedPlaylistType = "custom" 
                                }
                            )
                        }
                        // Fill empty space if odd number
                        if (rowPlaylists.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Recently Played section
        if (recentSongs.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Recently Played",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
            }

            itemsIndexed(recentSongs) { index, song ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.playFromRecent(index) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = "Thumbnail",
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            text = song.title,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = song.artist,
                            color = SpotifyLightGray,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        } else {
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "No recent songs yet. Start playing music!",
                    color = SpotifyLightGray,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun QuickAccessCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = subtitle,
                color = SpotifyLightGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PlaylistCard(
    modifier: Modifier = Modifier,
    name: String,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
