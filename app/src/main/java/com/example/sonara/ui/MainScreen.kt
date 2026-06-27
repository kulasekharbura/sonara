package com.example.sonara.ui

import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.auth.YouTubeAuthManager
import com.example.sonara.data.models.RepeatMode
import com.example.sonara.network.*
import com.example.sonara.ui.SongDisplayItem
import com.example.sonara.ui.theme.SpotifyGreen
import com.example.sonara.ui.theme.SpotifyLightGray
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.util.Locale

enum class Screen(val title: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Search("Search", Icons.Default.Search),
    Library("Your Library", Icons.AutoMirrored.Filled.QueueMusic),
    Profile("Profile", Icons.Default.Person)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: PlaybackViewModel, initialOpenPlayer: Boolean = false) {
    // Navigation back stack: Home is always the root
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var navStackSize by remember { mutableIntStateOf(0) } // observable trigger for recomposition
    val navigationStack = remember { mutableListOf<Screen>() } // actual history
    var isPlayerSheetVisible by remember { mutableStateOf(initialOpenPlayer) }
    var selectedSongForGlobalMenu by remember { mutableStateOf<SongDisplayItem?>(null) }
    var showAddToPlaylistFor by remember { mutableStateOf<SongDisplayItem?>(null) }

    val userIdValue by viewModel.userId.collectAsState()
    val isLoggedIn = userIdValue.isNotEmpty() && userIdValue != "anonymous"

    // Back gesture handler: navigate back through tab history, close app from Home
    BackHandler(enabled = !isPlayerSheetVisible && navStackSize > 0) {
        if (navigationStack.isNotEmpty()) {
            currentScreen = navigationStack.removeAt(navigationStack.lastIndex)
            navStackSize = navigationStack.size
        }
    }

    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentTrack by viewModel.currentMediaItem.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val startupContext = LocalContext.current

    // Sync downloads folder on startup
    LaunchedEffect(Unit) {
        viewModel.syncDownloadsWithFolder(startupContext)
    }

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.background(Color.Black)) {
                if (currentTrack != null) {
                    MiniPlayerBar(
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        trackTitle = currentTrack?.mediaMetadata?.title?.toString() ?: "No Track Playing",
                        trackArtist = currentTrack?.mediaMetadata?.artist?.toString() ?: "Sonara Stream Engine",
                        thumbnailUrl = currentTrack?.mediaMetadata?.artworkUri?.toString(),
                        onPlayPauseToggle = { viewModel.playPause() },
                        onBarClick = { isPlayerSheetVisible = true },
                        onMenuClick = {
                            currentTrack?.let { track ->
                                selectedSongForGlobalMenu = SongDisplayItem(
                                    videoId = track.mediaId,
                                    title = track.mediaMetadata.title.toString(),
                                    artist = track.mediaMetadata.artist.toString(),
                                    thumbnailUrl = track.mediaMetadata.artworkUri.toString(),
                                )
                            }
                        }
                    )
                }

                NavigationBar(containerColor = Color.Black) {
                    Screen.entries.forEach { screen ->
                        val isSelected = currentScreen == screen
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (currentScreen != screen) {
                                    // Push current screen to back stack before navigating
                                    navigationStack.add(currentScreen)
                                    // Keep stack reasonable (max 10 entries)
                                    if (navigationStack.size > 10) navigationStack.removeAt(0)
                                    navStackSize = navigationStack.size
                                    currentScreen = screen
                                }
                            },
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
                Screen.Home -> {
                    if (!isLoggedIn) {
                        // Show login screen in Home tab when not authenticated
                        LoginScreen(onLoginComplete = { })
                    } else {
                        HomeScreen(viewModel)
                    }
                }
                Screen.Search -> SearchTabContent(viewModel, onAddToPlaylist = { showAddToPlaylistFor = it })
                Screen.Library -> LibraryScreen(viewModel, onNavigateToSearch = { currentScreen = Screen.Search })
                Screen.Profile -> ProfileTabContent(viewModel)
            }
        }

        if (isPlayerSheetVisible && (currentTrack != null)) {
            BackHandler { isPlayerSheetVisible = false }
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
                    isLoading = isLoading,
                    title = currentTrack?.mediaMetadata?.title?.toString() ?: "Unknown Title",
                    artist = currentTrack?.mediaMetadata?.artist?.toString() ?: "Unknown Artist",
                    thumbnailUrl = currentTrack?.mediaMetadata?.artworkUri?.toString(),
                    onPlayPause = { viewModel.playPause() },
                    onSkipNext = { viewModel.skipToNext() },
                    onSkipPrevious = { viewModel.skipToPrevious() },
                    onDismiss = { isPlayerSheetVisible = false },
                    onMenuClick = {
                        val track = currentTrack!!
                        selectedSongForGlobalMenu = SongDisplayItem(
                            videoId = track.mediaId,
                            title = track.mediaMetadata.title.toString(),
                            artist = track.mediaMetadata.artist.toString(),
                            thumbnailUrl = track.mediaMetadata.artworkUri.toString()
                        )
                    }
                )
            }
        }

        if (showAddToPlaylistFor != null) {
            AddToPlaylistSheet(
                viewModel = viewModel,
                song = showAddToPlaylistFor!!,
                onDismiss = { showAddToPlaylistFor = null }
            )
        }

        if (selectedSongForGlobalMenu != null) {
            val context = LocalContext.current
            val downloadProgress by viewModel.downloadProgress.collectAsState()
            val downloadedSongs by viewModel.downloadedSongs.collectAsState()
            val song = selectedSongForGlobalMenu!!
            val progress = downloadProgress[song.videoId] ?: -1
            val isDownloaded = downloadedSongs.any { it.videoId == song.videoId }

            ModalBottomSheet(
                onDismissRequest = { selectedSongForGlobalMenu = null },
                containerColor = Color(0xFF282828),
                dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(width = 40.dp, height = 4.dp).background(Color.Gray, RoundedCornerShape(2.dp))) }
            ) {
                SongActionMenu(
                    song = song,
                    downloadProgress = if (isDownloaded) 100 else progress,
                    onDismiss = { selectedSongForGlobalMenu = null },
                    onAddToPlaylist = { 
                        showAddToPlaylistFor = SongDisplayItem(
                            videoId = song.videoId,
                            title = song.title,
                            artist = song.artist,
                            thumbnailUrl = song.thumbnailUrl
                        )
                    },
                    onAddToQueue = { viewModel.addToQueue(song.videoId, song.title, song.artist, song.thumbnailUrl); selectedSongForGlobalMenu = null },
                    onGoToQueue = { /* navigate to queue */ selectedSongForGlobalMenu = null },
                    onDownload = { viewModel.downloadSong(context, song.videoId, song.title, song.artist, song.thumbnailUrl); selectedSongForGlobalMenu = null },
                    onRemoveDownload = { viewModel.deleteDownloadedSong(context, song.videoId, song.title, song.artist); selectedSongForGlobalMenu = null }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTabContent(viewModel: PlaybackViewModel, onAddToPlaylist: (SongDisplayItem) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsState()
    val searchPlaylists by viewModel.searchPlaylists.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val likedSongIds by viewModel.likedSongIds.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    LaunchedEffect(searchQuery) {
        viewModel.performDebouncedSearch(searchQuery.trim())
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
        } else if (searchQuery.isEmpty()) {
            val recentSearches by viewModel.recentSearches.collectAsState()
            if (recentSearches.isNotEmpty()) {
                Text("Recent Searches", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(8.dp))
                recentSearches.forEach { search ->
                    Text(
                        text = search.query,
                        color = SpotifyLightGray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { searchQuery = search.query }
                            .padding(vertical = 8.dp)
                    )
                }
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

                    itemsIndexed(searchResults) { _, trackItem ->
                        val videoId = trackItem.id.videoId ?: ""

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    keyboardController?.hide()
                                    // Record search history only when user actually selects a song
                                    viewModel.recordSearchQuery(searchQuery.trim())
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

                            LikeButton(
                                isLiked = likedSongIds.contains(videoId),
                                onToggle = { viewModel.toggleLike(videoId, trackItem.snippet.title, trackItem.snippet.channelTitle, trackItem.snippet.thumbnails.high.url) }
                            )

                            IconButton(onClick = {
                                viewModel.addToQueue(
                                    videoId = videoId,
                                    title = trackItem.snippet.title,
                                    artist = trackItem.snippet.channelTitle,
                                    thumbnailUrl = trackItem.snippet.thumbnails.high.url
                                )
                                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.AddCircleOutline,
                                    contentDescription = "Add to queue",
                                    tint = SpotifyLightGray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(onClick = { 
                                onAddToPlaylist(
                                    SongDisplayItem(
                                        videoId = videoId,
                                        title = trackItem.snippet.title,
                                        artist = trackItem.snippet.channelTitle,
                                        thumbnailUrl = trackItem.snippet.thumbnails.high.url
                                    )
                                ) 
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Add to Playlist",
                                    tint = SpotifyLightGray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Search Playlists section
                    if (searchPlaylists.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(16.dp))
                            Text("Playlists", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                        items(searchPlaylists) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { /* Browse playlist tracks - TBD */ },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = playlist.thumbnailUrl,
                                    contentDescription = "Playlist thumbnail",
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.DarkGray),
                                    contentScale = ContentScale.Crop
                                )
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(
                                        playlist.title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        playlist.channelName,
                                        color = SpotifyLightGray,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
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

/**
 * Global sheet to add a song to a playlist.
 * Handles both YoutubeSearchItem (from Search) and SongDisplayItem (from Player/Menu).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    viewModel: PlaybackViewModel,
    song: SongDisplayItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val playlists by viewModel.customPlaylists.collectAsState()
    var showCreateInline by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282828),
        dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(width = 40.dp, height = 4.dp).background(Color.Gray, RoundedCornerShape(2.dp))) }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Add to Playlist", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showCreateInline = !showCreateInline }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text("New Playlist", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }

            if (showCreateInline) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Name", color = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    TextButton(onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.createPlaylist(newName.trim())
                            Toast.makeText(context, "Playlist created. Tap it below to add.", Toast.LENGTH_SHORT).show()
                            showCreateInline = false
                            newName = ""
                        }
                    }) { Text("Create", color = SpotifyGreen) }
                }
            }

            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(playlists) { playlist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.addToPlaylist(playlist.id, song.videoId, song.title, song.artist, song.thumbnailUrl) { added ->
                                if (added) Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                                else Toast.makeText(context, "Already in ${playlist.name}", Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = SpotifyLightGray)
                        Spacer(Modifier.width(16.dp))
                        Text(playlist.name, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    isPlaying: Boolean,
    isLoading: Boolean = false,
    trackTitle: String,
    trackArtist: String,
    thumbnailUrl: String?,
    onPlayPauseToggle: () -> Unit,
    onBarClick: () -> Unit,
    onMenuClick: () -> Unit
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
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = thumbnailUrl ?: "https://storage.googleapis.com/exoplayer-test-media-0/art/placeholder.jpg",
                    contentDescription = "Mini Art",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Gray),
                    contentScale = ContentScale.Crop
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = SpotifyGreen,
                        strokeWidth = 2.dp
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = trackTitle, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = trackArtist, style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayPauseToggle, enabled = !isLoading) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = if (isLoading) Color.Gray else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Menu",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun FullPlayerOverlay(
    viewModel: PlaybackViewModel,
    isPlaying: Boolean,
    isLoading: Boolean = false,
    title: String,
    artist: String,
    thumbnailUrl: String?,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onMenuClick: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    var showQueue by remember { mutableStateOf(false) }
    var showAddToPlaylistOverlay by remember { mutableStateOf(false) }
    val queueItems by viewModel.queueItems.collectAsState()
    val currentQueueIndex by viewModel.currentQueueIndex.collectAsState()
    val overlayContext = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (showQueue) {
            QueueView(
                queueItems = queueItems,
                currentIndex = currentQueueIndex,
                onDismiss = { showQueue = false },
                onMoveItem = { from, to -> viewModel.moveQueueItem(from, to) }
            )
        } else {
        // Top row: Back/minimize on left, Queue on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Minimize Player",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = { showQueue = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue",
                    tint = Color.White
                )
            }
        }

        Box(contentAlignment = Alignment.Center) {
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
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    color = SpotifyGreen,
                    strokeWidth = 4.dp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
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

            val likedSongIds by viewModel.likedSongIds.collectAsState()
            val currentVideoId = viewModel.currentMediaItem.collectAsState().value?.mediaId ?: ""
            LikeButton(
                isLiked = likedSongIds.contains(currentVideoId),
                onToggle = { viewModel.toggleLike(currentVideoId, title, artist, thumbnailUrl ?: "") }
            )

            IconButton(onClick = { showAddToPlaylistOverlay = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add to Playlist",
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Song Menu",
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        PlaybackSliderComponent(viewModel = viewModel)

        val repeatMode by viewModel.repeatMode.collectAsState()
        val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RepeatButton(repeatMode = repeatMode, onToggle = { viewModel.toggleRepeatMode() })

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

            ShuffleButton(shuffleEnabled = shuffleEnabled, onToggle = { viewModel.toggleShuffle() })
        }

        Spacer(modifier = Modifier.height(16.dp))
        } // end else
    }

    // Add to Playlist dialog in FullPlayerOverlay
    if (showAddToPlaylistOverlay) {
        val playlists by viewModel.customPlaylists.collectAsState()
        val currentVideoId = viewModel.currentMediaItem.collectAsState().value?.mediaId ?: ""
        AlertDialog(
            onDismissRequest = { showAddToPlaylistOverlay = false },
            title = { Text("Add to Playlist", color = Color.White) },
            text = {
                Column {
                    if (playlists.isEmpty()) {
                        Text("No playlists yet. Create one first.", color = SpotifyLightGray)
                    } else {
                        playlists.forEach { playlist ->
                            Text(
                                text = playlist.name,
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addToPlaylist(
                                            playlist.id,
                                            currentVideoId,
                                            title,
                                            artist,
                                            thumbnailUrl ?: ""
                                        ) { added ->
                                            if (added) {
                                                Toast.makeText(overlayContext, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(overlayContext, "Already in ${playlist.name}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        showAddToPlaylistOverlay = false
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddToPlaylistOverlay = false }) {
                    Text("Cancel", color = SpotifyLightGray)
                }
            },
            containerColor = Color(0xFF282828)
        )
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

@Composable
fun ProfileTabContent(viewModel: PlaybackViewModel) {
    var showLogin by remember { mutableStateOf(false) }
    val auth = FirebaseAuth.getInstance()
    var user by remember { mutableStateOf(auth.currentUser) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (showLogin) {
        LoginScreen(
            onLoginComplete = {
                user = auth.currentUser
                showLogin = false
                viewModel.performCloudSync()
            }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (user != null) {
                AsyncImage(
                    model = user?.photoUrl,
                    contentDescription = "Profile Picture",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = user?.displayName ?: "Sonara User",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Authenticated playback & sync active",
                    color = SpotifyLightGray,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val tapClient = com.google.android.gms.auth.api.identity.Identity.getSignInClient(context)
                            tapClient.signOut().addOnCompleteListener {
                                auth.signOut()
                                YouTubeAuthManager.clearCookies()
                                user = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF282828)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Sign out",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "  Sign out",
                        color = Color.White
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Not signed in",
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Sign in to YouTube",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Required for audio playback. YouTube now requires authentication for all clients.",
                    color = SpotifyLightGray,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { showLogin = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyGreen
                    )
                ) {
                    Text(
                        text = "Sign in with Google",
                        color = Color.Black
                    )
                }
            }
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