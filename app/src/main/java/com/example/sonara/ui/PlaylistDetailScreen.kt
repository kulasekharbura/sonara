package com.example.sonara.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.sonara.data.db.LikedSongEntity
import com.example.sonara.data.db.PlaylistEntity
import com.example.sonara.data.db.PlaylistSongEntity
import com.example.sonara.data.db.DownloadedSongEntity
import com.example.sonara.ui.theme.SpotifyGreen
import com.example.sonara.ui.theme.SpotifyLightGray
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

// Unified song data for display
data class SongDisplayItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val addedAt: Long = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaybackViewModel,
    playlistType: String,
    playlistId: Long = 0,
    onBack: () -> Unit,
    onNavigateToSearch: () -> Unit
) {
    val context = LocalContext.current
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()

    LaunchedEffect(playlistType) {
        if (playlistType == "downloads") {
            viewModel.syncDownloadsWithFolder(context)
        }
    }

    val playlistMetadata by remember(playlistId, playlistType) {
        when (playlistType) {
            "custom" -> viewModel.getPlaylist(playlistId)
            "downloads" -> flowOf(PlaylistEntity(id = -1, name = "Downloads", userId = ""))
            else -> flowOf(null)
        }
    }.collectAsState(initial = null)

    val title = when (playlistType) {
        "liked" -> "Liked Songs"
        "recent" -> "Recent Songs"
        "downloads" -> "Downloads"
        else -> playlistMetadata?.name ?: "Playlist"
    }

    // Collect the appropriate song list based on playlist type
    val likedSongs by remember(playlistType) {
        viewModel.getLikedSongs()
    }.collectAsState(initial = emptyList<LikedSongEntity>())

    val recentSongs by viewModel.recentSongs.collectAsState()

    val playlistSongs by remember(playlistType, playlistId) {
        viewModel.getPlaylistSongs(playlistId)
    }.collectAsState(initial = emptyList<PlaylistSongEntity>())

    var sortType by remember { mutableStateOf("Default") }
    var isEditMode by remember { mutableStateOf(false) }

    val rawSongs: List<SongDisplayItem> = when (playlistType) {
        "liked" -> likedSongs.map { SongDisplayItem(it.videoId, it.title, it.artist, it.thumbnailUrl, it.likedAt) }
        "recent" -> recentSongs.map { SongDisplayItem(it.videoId, it.title, it.artist, it.thumbnailUrl, it.playedAt) }
        "downloads" -> downloadedSongs.map { SongDisplayItem(it.videoId, it.title, it.artist, it.thumbnailUrl, it.downloadedAt) }
        else -> playlistSongs.map { SongDisplayItem(it.videoId, it.title, it.artist, it.thumbnailUrl, it.addedAt) }
    }

    val songs = remember(rawSongs, sortType) {
        when (sortType) {
            "Title" -> rawSongs.sortedBy { it.title }
            "Artist" -> rawSongs.sortedBy { it.artist }
            "Time" -> rawSongs.sortedByDescending { it.addedAt }
            else -> rawSongs
        }
    }

    val localSongs = remember(songs) { songs.toMutableStateList() }
    LaunchedEffect(songs) {
        localSongs.clear()
        localSongs.addAll(songs)
    }

    var selectedSongForMenu by remember { mutableStateOf<SongDisplayItem?>(null) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val customPlaylists by viewModel.customPlaylists.collectAsState()

    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var currentOverIndex by remember { mutableIntStateOf(-1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemHeightPx = with(density) { 72.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF2E2E2E), Color.Black), startY = 0f, endY = 1000f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp), state = rememberLazyListState()) {
                item {
                    val playlistDownloadProgress = remember(songs, downloadProgress) {
                        val active = songs.filter { downloadProgress.containsKey(it.videoId) }
                        if (active.isEmpty()) -1
                        else active.sumOf { downloadProgress[it.videoId] ?: 0 } / active.size
                    }

                    PlaylistHeader(
                        title = title,
                        songs = songs,
                        showMoreOptions = playlistType == "custom",
                        showDownloadOption = playlistType != "downloads",
                        downloadProgress = playlistDownloadProgress,
                        onPlayClick = {
                            if (songs.isNotEmpty()) {
                                when (playlistType) {
                                    "liked" -> viewModel.playFromLikedSongs(0)
                                    "recent" -> viewModel.playFromRecent(0)
                                    "downloads" -> viewModel.playFromDownloads(0)
                                    else -> viewModel.playFromPlaylist(playlistId, 0)
                                }
                            }
                        },
                        onShuffleClick = {
                            viewModel.toggleShuffle()
                            if (songs.isNotEmpty()) {
                                when (playlistType) {
                                    "liked" -> viewModel.playFromLikedSongs(0)
                                    "recent" -> viewModel.playFromRecent(0)
                                    "downloads" -> viewModel.playFromDownloads(0)
                                    else -> viewModel.playFromPlaylist(playlistId, 0)
                                }
                            }
                        },
                        onDownloadAll = { viewModel.downloadAll(context, songs) },
                        onRename = { showRenameDialog = true },
                        onDelete = { showDeleteDialog = true }
                    )
                }

                item { ActionChipsRow(onAddClick = onNavigateToSearch, onSortClick = { showSortMenu = true }, isEditMode = isEditMode, onEditClick = { isEditMode = !isEditMode }) }

                if (songs.isEmpty()) {
                    item { Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text(text = "No songs yet", color = SpotifyLightGray, style = MaterialTheme.typography.bodyLarge) } }
                } else {
                    itemsIndexed(localSongs, key = { _, song -> song.videoId }) { index, song ->
                        val isDragging = draggedItemIndex == index
                        val elevation by animateDpAsState(targetValue = if (isDragging) 8.dp else 0.dp, label = "drag")
                        val progress = downloadProgress[song.videoId] ?: -1
                        val isDownloaded = downloadedSongs.any { it.videoId == song.videoId }

                        val slideOffset by animateFloatAsState(
                            targetValue = when {
                                draggedItemIndex == null -> 0f
                                isDragging -> 0f
                                index < draggedItemIndex!! && index >= currentOverIndex -> itemHeightPx
                                index > draggedItemIndex!! && index <= currentOverIndex -> -itemHeightPx
                                else -> 0f
                            }, label = "slide"
                        )

                        SongItem(
                            song = song,
                            isEditMode = isEditMode && (sortType == "Default"),
                            isDragging = isDragging,
                            dragOffsetY = dragOffsetY,
                            slideOffsetY = slideOffset,
                            elevation = elevation,
                            downloadProgress = if (isDownloaded) 100 else progress,
                            onItemClick = {
                                if (!isEditMode) {
                                    when (playlistType) {
                                        "liked" -> viewModel.playFromLikedSongs(index)
                                        "recent" -> viewModel.playFromRecent(index)
                                        "downloads" -> viewModel.playFromDownloads(index)
                                        else -> viewModel.playFromPlaylist(playlistId, index)
                                    }
                                }
                            },
                            onMenuClick = { selectedSongForMenu = song },
                            dragModifier = if (isEditMode && sortType == "Default") {
                                Modifier.pointerInput(index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggedItemIndex = index; dragOffsetY = 0f; currentOverIndex = index },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            val rawTargetIndex = index + (dragOffsetY / itemHeightPx).roundToInt()
                                            currentOverIndex = rawTargetIndex.coerceIn(0, localSongs.size - 1)
                                        },
                                        onDragEnd = {
                                            val from = draggedItemIndex
                                            val to = currentOverIndex
                                            if (from != null && from != to) {
                                                val item = localSongs.removeAt(from)
                                                localSongs.add(to, item)
                                                if (playlistType == "custom") viewModel.reorderPlaylistSongs(playlistId, from, to)
                                            }
                                            draggedItemIndex = null
                                            dragOffsetY = 0f
                                            currentOverIndex = -1
                                        },
                                        onDragCancel = { draggedItemIndex = null; dragOffsetY = 0f; currentOverIndex = -1 }
                                    )
                                }
                            } else Modifier
                        )
                    }
                }
            }
        }

        if (selectedSongForMenu != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedSongForMenu = null },
                sheetState = sheetState,
                containerColor = Color(0xFF282828),
                scrimColor = Color.Black.copy(alpha = 0.7f),
                dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(width = 40.dp, height = 4.dp).background(Color.Gray, RoundedCornerShape(2.dp))) }
            ) {
                val progress = downloadProgress[selectedSongForMenu!!.videoId] ?: -1
                val isDownloaded = downloadedSongs.any { it.videoId == selectedSongForMenu!!.videoId }
                val song = selectedSongForMenu!!
                SongActionMenu(
                    song = song,
                    playlistType = playlistType,
                    downloadProgress = if (isDownloaded) 100 else progress,
                    onDismiss = { selectedSongForMenu = null },
                    onAddToPlaylist = { showAddToPlaylistDialog = true },
                    onRemove = { if (playlistType == "custom") viewModel.removeSongFromPlaylist(playlistId, song.videoId); selectedSongForMenu = null },
                    onAddToQueue = { viewModel.addToQueue(song.videoId, song.title, song.artist, song.thumbnailUrl); selectedSongForMenu = null },
                    onGoToQueue = { showQueue = true; selectedSongForMenu = null },
                    onDownload = { viewModel.downloadSong(context, song.videoId, song.title, song.artist, song.thumbnailUrl); selectedSongForMenu = null },
                    onRemoveDownload = { viewModel.deleteDownloadedSong(context, song.videoId, song.title); selectedSongForMenu = null }
                )
            }
        }

        if (showAddToPlaylistDialog && (selectedSongForMenu != null)) {
            AlertDialog(
                onDismissRequest = { showAddToPlaylistDialog = false },
                title = { Text("Add to Playlist", color = Color.White) },
                text = {
                    LazyColumn {
                        itemsIndexed(customPlaylists) { _, playlist ->
                            Text(text = playlist.name, color = Color.White, modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.addToPlaylist(playlist.id, selectedSongForMenu!!.videoId, selectedSongForMenu!!.title, selectedSongForMenu!!.artist, selectedSongForMenu!!.thumbnailUrl)
                                showAddToPlaylistDialog = false; selectedSongForMenu = null
                            }.padding(vertical = 12.dp))
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showAddToPlaylistDialog = false }) { Text("Cancel", color = SpotifyLightGray) } },
                containerColor = Color(0xFF282828)
            )
        }

        if (showRenameDialog) {
            var newName by remember { mutableStateOf(title) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename Playlist", color = Color.White) },
                text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { viewModel.renamePlaylist(playlistId, newName.trim()); showRenameDialog = false } }) { Text("Rename", color = SpotifyGreen) } },
                dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = SpotifyLightGray) } },
                containerColor = Color(0xFF282828)
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete Playlist", color = Color.White) },
                text = { Text("Are you sure you want to delete this playlist?", color = Color.White) },
                confirmButton = { TextButton(onClick = { viewModel.deletePlaylist(playlistId); showDeleteDialog = false; onBack() }) { Text("Delete", color = Color.Red) } },
                dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = SpotifyLightGray) } },
                containerColor = Color(0xFF282828)
            )
        }

        if (showSortMenu) {
            AlertDialog(
                onDismissRequest = { showSortMenu = false },
                title = { Text("Sort by", color = Color.White) },
                text = {
                    Column {
                        listOf("Default", "Title", "Artist", "Time").forEach { type ->
                            Text(text = type, color = if (sortType == type) SpotifyGreen else Color.White, modifier = Modifier.fillMaxWidth().clickable { sortType = type; showSortMenu = false }.padding(vertical = 12.dp))
                        }
                    }
                },
                confirmButton = {},
                containerColor = Color(0xFF282828)
            )
        }

        if (showQueue) {
            val queueItems by viewModel.queueItems.collectAsState()
            val currentIndex by viewModel.currentQueueIndex.collectAsState()
            ModalBottomSheet(onDismissRequest = { showQueue = false }, containerColor = Color(0xFF121212), dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(width = 40.dp, height = 4.dp).background(Color.Gray, RoundedCornerShape(2.dp))) }) {
                QueueView(queueItems = queueItems, currentIndex = currentIndex, onDismiss = { showQueue = false }, onMoveItem = { from, to -> viewModel.moveQueueItem(from, to) })
            }
        }
    }
}

@Composable
fun PlaylistHeader(
    title: String,
    songs: List<SongDisplayItem>,
    showMoreOptions: Boolean,
    showDownloadOption: Boolean = true,
    downloadProgress: Int = -1,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onDownloadAll: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.Start) {
        Box(modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
            if (songs.isEmpty()) { Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.fillMaxSize().padding(48.dp), tint = Color.Gray) }
            else {
                val images = songs.take(4).map { it.thumbnailUrl }
                if (images.size >= 4) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f)) { AsyncImage(model = images[0], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop); AsyncImage(model = images[1], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) }
                        Row(modifier = Modifier.weight(1f)) { AsyncImage(model = images[2], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop); AsyncImage(model = images[3], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) }
                    }
                } else { AsyncImage(model = images.firstOrNull(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(text = title, style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // REMOVED: User metadata row

        Row(verticalAlignment = Alignment.CenterVertically) {
            // REMOVED: Globe icon
            Text("${songs.size} songs", color = SpotifyLightGray, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDownloadOption) {
                    Box(contentAlignment = Alignment.Center) {
                        if (downloadProgress in 0..99) {
                            CircularProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.size(32.dp),
                                color = SpotifyGreen,
                                strokeWidth = 2.dp,
                                trackColor = Color.DarkGray
                            )
                        }
                        IconButton(onClick = onDownloadAll) {
                            Icon(
                                imageVector = if (downloadProgress == 100) Icons.Default.CheckCircle else Icons.Default.ArrowCircleDown,
                                contentDescription = "Download All",
                                tint = if (downloadProgress == 100) SpotifyGreen else SpotifyLightGray,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
                IconButton(onClick = { /* Share placeholder */ }) { Icon(Icons.Default.Share, contentDescription = "Share", tint = SpotifyLightGray, modifier = Modifier.size(28.dp)) }
                if (showMoreOptions) {
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More", tint = SpotifyLightGray, modifier = Modifier.size(28.dp)) }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }, modifier = Modifier.background(Color(0xFF282828))) {
                            DropdownMenuItem(text = { Text("Rename", color = Color.White) }, onClick = { onRename(); showMoreMenu = false })
                            DropdownMenuItem(text = { Text("Delete Playlist", color = Color.White) }, onClick = { onDelete(); showMoreMenu = false })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffleClick) { Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = SpotifyGreen, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(8.dp)); Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(SpotifyGreen).clickable(onClick = onPlayClick), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(32.dp)) }
            }
        }
    }
}

@Composable
fun ActionChipsRow(onAddClick: () -> Unit, onSortClick: () -> Unit, isEditMode: Boolean, onEditClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip(label = "Add", icon = Icons.Default.Add, onClick = onAddClick)
        ActionChip(label = if (isEditMode) "Done" else "Edit", icon = if (isEditMode) Icons.Default.Check else Icons.Default.Edit, onClick = onEditClick, color = if (isEditMode) SpotifyGreen else Color(0xFF2A2A2A))
        ActionChip(label = "Sort", icon = Icons.AutoMirrored.Filled.Sort, onClick = onSortClick)
    }
}

@Composable
fun ActionChip(label: String, icon: ImageVector, onClick: () -> Unit, color: Color = Color(0xFF2A2A2A)) {
    Surface(color = color, shape = RoundedCornerShape(16.dp), modifier = Modifier.height(32.dp).clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = if (color == SpotifyGreen) Color.Black else Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp)); Text(text = label, color = if (color == SpotifyGreen) Color.Black else Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SongItem(
    song: SongDisplayItem,
    isEditMode: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    slideOffsetY: Float,
    elevation: androidx.compose.ui.unit.Dp,
    downloadProgress: Int = -1,
    onItemClick: () -> Unit,
    onMenuClick: () -> Unit,
    dragModifier: Modifier
) {
    Row(
        modifier = Modifier.fillMaxWidth().zIndex(if (isDragging) 1f else 0f).offset { if (isDragging) IntOffset(0, dragOffsetY.roundToInt()) else IntOffset(0, slideOffsetY.roundToInt()) }.shadow(elevation).clickable(onClick = onItemClick).padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isEditMode) { Icon(imageVector = Icons.Default.DragHandle, contentDescription = "Reorder", tint = SpotifyLightGray, modifier = dragModifier.padding(end = 12.dp)) }
        Box(contentAlignment = Alignment.BottomEnd) {
            AsyncImage(model = song.thumbnailUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
            if (downloadProgress == 100) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SpotifyGreen,
                    modifier = Modifier.size(14.dp).background(Color.Black, CircleShape).padding(1.dp)
                )
            } else if (downloadProgress in 0..99) {
                CircularProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.size(14.dp).background(Color.Black, CircleShape),
                    color = SpotifyGreen,
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = song.title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = song.artist, color = SpotifyLightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!isEditMode) { IconButton(onClick = onMenuClick) { Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = SpotifyLightGray) } }
    }
}
