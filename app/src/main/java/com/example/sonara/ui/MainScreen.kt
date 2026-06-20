package com.example.sonara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.ui.theme.SpotifyGreen
import com.example.sonara.ui.theme.SpotifyLightGray
import kotlinx.coroutines.delay
import java.util.Locale

enum class Screen(val title: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Search("Search", Icons.Default.Search),
    Library("Your Library", Icons.AutoMirrored.Filled.QueueMusic),
    Profile("Profile", Icons.Default.Person)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: PlaybackViewModel) {
    var currentScreen by remember { mutableStateOf(Screen.Search) }
    var isPlayerSheetVisible by remember { mutableStateOf(false) }

    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentTrack by viewModel.currentMediaItem.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.background(Color.Black)) {
                MiniPlayerBar(
                    isPlaying = isPlaying,
                    trackTitle = currentTrack?.mediaMetadata?.title?.toString() ?: "No Track Playing",
                    trackArtist = currentTrack?.mediaMetadata?.artist?.toString() ?: "Sonara Stream Engine",
                    thumbnailUrl = currentTrack?.mediaMetadata?.artworkUri?.toString(),
                    onPlayPauseToggle = { viewModel.playPause() },
                    onBarClick = { isPlayerSheetVisible = true }
                )

                NavigationBar(containerColor = Color.Black) {
                    Screen.entries.forEach { screen ->
                        val isSelected = currentScreen == screen
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentScreen = screen },
                            label = { Text(screen.title) },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isSelected) SpotifyGreen else SpotifyLightGray
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = SpotifyGreen,
                                selectedTextColor = SpotifyGreen,
                                unselectedIconColor = SpotifyLightGray,
                                unselectedTextColor = SpotifyLightGray,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (currentScreen) {
                Screen.Home -> Text("Spotify Style Home Screen Coming Soon", color = Color.White)
                Screen.Search -> SearchTabContent(viewModel)
                Screen.Library -> Text("Your Saved Playlists & Downloads", color = Color.White)
                Screen.Profile -> Text("Two-User Private Account Settings", color = Color.White)
            }
        }

        if (isPlayerSheetVisible && currentTrack != null) {
            ModalBottomSheet(
                onDismissRequest = { isPlayerSheetVisible = false },
                sheetState = sheetState,
                containerColor = Color(0xFF121212),
                scrimColor = Color.Black.copy(alpha = 0.7f),
                dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(width = 40.dp, height = 4.dp).background(Color.Gray, RoundedCornerShape(2.dp))) }
            ) {
                FullPlayerOverlay(
                    viewModel = viewModel,
                    isPlaying = isPlaying,
                    title = currentTrack?.mediaMetadata?.title?.toString() ?: "Unknown Title",
                    artist = currentTrack?.mediaMetadata?.artist?.toString() ?: "Unknown Artist",
                    thumbnailUrl = currentTrack?.mediaMetadata?.artworkUri?.toString(),
                    onPlayPause = { viewModel.playPause() },
                    onSkipNext = { viewModel.skipToNext() },
                    onSkipPrevious = { viewModel.skipToPrevious() }
                )
            }
        }
    }
}

@Composable
fun SearchTabContent(viewModel: PlaybackViewModel) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchQuery) {
        val cleaned = searchQuery.trim()
        if (cleaned.length >= 3) {
            delay(400)
            viewModel.performSearch(cleaned)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What do you want to listen to?", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear text", tint = Color.Gray)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF282828),
                unfocusedContainerColor = Color(0xFF282828),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = SpotifyGreen
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isSearching) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotifyGreen)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (searchResults.isNotEmpty()) {
                    item {
                        Text("Search Results", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }

                    items(searchResults) { trackItem ->
                        val videoId = trackItem.id.videoId ?: ""

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    keyboardController?.hide()
                                    // FIXED: Parameter name changed from streamUrl to streamUrlOrId
                                    // and tracks raw videoId directly down to the pipeline
                                    viewModel.playAudioStream(
                                        streamUrlOrId = videoId,
                                        title = trackItem.snippet.title,
                                        artist = trackItem.snippet.channelTitle,
                                        thumbnailUrl = trackItem.snippet.thumbnails.high.url
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = trackItem.snippet.thumbnails.default.url,
                                contentDescription = "Track Artwork",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.DarkGray),
                                contentScale = ContentScale.Crop
                            )

                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    text = trackItem.snippet.title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = trackItem.snippet.channelTitle,
                                    color = SpotifyLightGray,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = if (searchQuery.trim().length >= 3) "No results found. Check network connection." else "Type a song title above to search!",
                            color = SpotifyLightGray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    isPlaying: Boolean,
    trackTitle: String,
    trackArtist: String,
    thumbnailUrl: String?,
    onPlayPauseToggle: () -> Unit,
    onBarClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF202020))
            .clickable { onBarClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = thumbnailUrl ?: "https://storage.googleapis.com/exoplayer-test-media-0/art/placeholder.jpg",
                contentDescription = "Mini Art",
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = trackTitle, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = trackArtist, style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        IconButton(onClick = onPlayPauseToggle, modifier = Modifier.padding(end = 8.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun FullPlayerOverlay(
    viewModel: PlaybackViewModel,
    isPlaying: Boolean,
    title: String,
    artist: String,
    thumbnailUrl: String?,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        AsyncImage(
            model = thumbnailUrl,
            contentDescription = "Big Album Art",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.titleMedium,
                color = SpotifyLightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        PlaybackSliderComponent(viewModel = viewModel)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSkipPrevious, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
            }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun PlaybackSliderComponent(viewModel: PlaybackViewModel) {
    val currentPos by viewModel.currentPosition.collectAsState()
    val totalDuration by viewModel.duration.collectAsState()

    var sliderValueOverride by remember { mutableStateOf<Float?>(null) }

    val activeDisplayPosition = sliderValueOverride?.toLong() ?: currentPos
    val sliderProgress = if (totalDuration > 0) activeDisplayPosition.toFloat() / totalDuration else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderProgress,
            onValueChange = { progressFraction ->
                sliderValueOverride = progressFraction * totalDuration
            },
            onValueChangeFinished = {
                sliderValueOverride?.let { targetMs ->
                    viewModel.seekTo(targetMs.toLong())
                }
                sliderValueOverride = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF1DB954),
                inactiveTrackColor = Color.DarkGray
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMillisecondsToTime(activeDisplayPosition),
                color = SpotifyLightGray,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = formatMillisecondsToTime(totalDuration),
                color = SpotifyLightGray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

fun formatMillisecondsToTime(milliseconds: Long): String {
    if (milliseconds <= 0) return "0:00"
    val totalSeconds = milliseconds / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}