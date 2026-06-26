package com.example.sonara.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.ui.theme.SpotifyGreen
import com.example.sonara.ui.theme.SpotifyLightGray

@Composable
fun SongActionMenu(
    song: SongDisplayItem,
    playlistType: String = "none",
    downloadProgress: Int = -1,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: (SongDisplayItem) -> Unit = {},
    onAddToQueue: (SongDisplayItem) -> Unit,
    onGoToQueue: () -> Unit,
    onDownload: (SongDisplayItem) -> Unit,
    onRemoveDownload: (SongDisplayItem) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(song.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(song.artist, color = SpotifyLightGray, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
        HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
        MenuItem(Icons.Default.Share, "Share", onClick = onDismiss)
        MenuItem(Icons.Default.Add, "Add to playlist") { onAddToPlaylist(); onDismiss() }
        
        if (playlistType == "custom") {
            MenuItem(Icons.Default.RemoveCircleOutline, "Remove from this playlist") { onRemove(song) }
        }
        
        if (downloadProgress == 100) {
            MenuItem(Icons.Default.RemoveCircle, "Remove from downloads", iconTint = Color.Red) { onRemoveDownload(song) }
        } else {
            MenuItem(
                icon = if (downloadProgress in 0..99) Icons.Default.CheckCircle else Icons.Default.ArrowCircleDown,
                label = if (downloadProgress in 0..99) "Downloading... ($downloadProgress%)" else "Download",
                iconTint = if (downloadProgress in 0..99) SpotifyGreen else Color.White
            ) {
                if (downloadProgress == -1) onDownload(song)
            }
        }

        MenuItem(Icons.AutoMirrored.Filled.QueueMusic, "Add to Queue") { onAddToQueue(song) }
        MenuItem(Icons.AutoMirrored.Filled.FormatListBulleted, "Go to Queue") { onGoToQueue() }
        MenuItem(Icons.Default.Album, "Go to album", onClick = onDismiss)
        MenuItem(Icons.Default.Person, "Go to artists", onClick = onDismiss)
    }
}

@Composable
fun MenuItem(icon: ImageVector, label: String, iconTint: Color = Color.White, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp, horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}
