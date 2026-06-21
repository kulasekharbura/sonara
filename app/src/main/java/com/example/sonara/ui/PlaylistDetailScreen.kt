package com.example.sonara.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.ui.theme.SpotifyLightGray

/**
 * A composable that displays songs from liked songs, recent songs, or a custom playlist.
 *
 * @param viewModel The PlaybackViewModel providing data and playback actions.
 * @param playlistType One of "liked", "recent", or "custom" to determine the data source.
 * @param playlistId The ID of the custom playlist (only used when playlistType is "custom").
 * @param onBack Callback invoked when the user taps the back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaybackViewModel,
    playlistType: String,
    playlistId: Long = 0,
    onBack: () -> Unit
) {
    val title = when (playlistType) {
        "liked" -> "Liked Songs"
        "recent" -> "Recent Songs"
        else -> "Playlist"
    }

    // Collect the appropriate song list based on playlist type
    val likedSongs by remember(playlistType) {
        viewModel.getLikedSongs()
    }.collectAsState(initial = emptyList())

    val recentSongs by viewModel.recentSongs.collectAsState()

    val playlistSongs by remember(playlistType, playlistId) {
        viewModel.getPlaylistSongs(playlistId)
    }.collectAsState(initial = emptyList())

    // Unified song data for display
    data class SongDisplayItem(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String
    )

    val songs: List<SongDisplayItem> = when (playlistType) {
        "liked" -> likedSongs.map { SongDisplayItem(it.videoId, it.title, it.artist, it.thumbnailUrl) }
        "recent" -> recentSongs.map { SongDisplayItem(it.videoId, it.title, it.artist, it.thumbnailUrl) }
        else -> playlistSongs.map { SongDisplayItem(it.videoId, it.title, it.artist, it.thumbnailUrl) }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black
            )
        )

        if (songs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No songs yet",
                    color = SpotifyLightGray,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                when (playlistType) {
                                    "liked" -> viewModel.playFromLikedSongs(index)
                                    "recent" -> viewModel.playFromRecent(index)
                                    else -> viewModel.playFromPlaylist(playlistId, index)
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = song.thumbnailUrl,
                            contentDescription = "Thumbnail for ${song.title}",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = song.artist,
                                color = SpotifyLightGray,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
