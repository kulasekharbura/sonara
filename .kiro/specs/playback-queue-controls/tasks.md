# Implementation Plan: Playback Queue Controls

## Overview

This plan implements a full playback queue system, repeat/shuffle controls, library management (liked songs, custom playlists, recent history), progressive audio caching, and dynamic search behavior for the Sonara Android app. The implementation uses ExoPlayer's native playlist capabilities, Room database for persistence, Media3 CacheDataSource for progressive caching, and lazy stream URL resolution via a custom MediaSource.Factory.

## Tasks

- [x] 1. Room database setup (entities, DAOs, database class)
  - [x] 1.1 Add Room and media3-datasource dependencies to build.gradle.kts
    - Add `kapt` plugin to the plugins block
    - Add `androidx.room:room-runtime`, `androidx.room:room-ktx` implementation dependencies
    - Add `androidx.room:room-compiler` kapt dependency
    - Add `androidx.media3:media3-datasource` implementation dependency
    - Use version catalog or explicit version strings consistent with existing media3 dependencies
    - _Requirements: 16.1, 16.6 (cache infrastructure), 8.3, 9.2, 11.1 (persistence infrastructure)_

  - [x] 1.2 Create Room entity data classes
    - Create `app/src/main/java/com/example/sonara/data/db/` package
    - Create `LikedSongEntity.kt` with fields: videoId (PK), title, artist, thumbnailUrl, likedAt
    - Create `PlaylistEntity.kt` with fields: id (auto-generate PK), name, createdAt
    - Create `PlaylistSongEntity.kt` with composite PK (playlistId, videoId), foreign key to PlaylistEntity, fields: title, artist, thumbnailUrl, addedAt, orderIndex
    - Create `RecentSongEntity.kt` with fields: videoId (PK), title, artist, thumbnailUrl, playedAt
    - _Requirements: 8.3, 9.2, 9.5, 11.1, 11.2_

  - [x] 1.3 Create Room DAO interfaces
    - Create `LikedSongDao.kt` with: getAllLikedSongs() Flow, getAllLikedIds() Flow, insert(), delete(videoId), getCount() Flow
    - Create `PlaylistDao.kt` with: getAllPlaylists() Flow, createPlaylist(), getSongsForPlaylist(playlistId) Flow, addSongToPlaylist(), getSongCount() Flow, songExistsInPlaylist()
    - Create `RecentSongDao.kt` with: getRecentSongs() Flow (limit 50), getRecentSongsForHome() Flow (limit 10), upsert(), trimToMax()
    - _Requirements: 8.3, 8.4, 9.2, 9.5, 9.6, 10.1, 10.2, 11.1, 11.2, 11.3, 12.1_

  - [x] 1.4 Create SonaraDatabase RoomDatabase class
    - Create `SonaraDatabase.kt` in the `data/db/` package
    - Annotate with @Database listing all 4 entities, version = 1
    - Declare abstract DAO accessor functions for all 3 DAOs
    - Add companion object with singleton pattern using `Room.databaseBuilder()`
    - _Requirements: 8.3, 9.2, 11.1_

- [x] 2. SimpleCache and CacheDataSource setup in PlaybackService
  - [x] 2.1 Set up SimpleCache in PlaybackService
    - Create a `SimpleCache` instance in PlaybackService.onCreate() with a dedicated cache directory (`sonara_audio_cache`)
    - Configure LRU eviction with a 200 MB maximum size using `LeastRecentlyUsedCacheEvictor`
    - Use `StandaloneDatabaseProvider` for cache metadata
    - Store reference in a class field for cleanup in onDestroy()
    - _Requirements: 16.1, 16.4, 16.5_

  - [x] 2.2 Create CacheDataSource.Factory in PlaybackService
    - Build a `CacheDataSource.Factory` wrapping `DefaultDataSource.Factory` as the upstream factory
    - Pass the SimpleCache instance to the CacheDataSource.Factory constructor
    - Configure with `CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR` for fallback behavior
    - Store reference for use by LazyMediaSourceFactory
    - _Requirements: 16.1, 16.2, 16.3, 16.6_

- [x] 3. LazyMediaSourceFactory
  - [x] 3.1 Create LazyMediaSourceFactory class
    - Create `app/src/main/java/com/example/sonara/playback/LazyMediaSourceFactory.kt`
    - Implement `MediaSource.Factory` interface
    - Accept `CacheDataSource.Factory` and a `urlResolver: suspend (videoId: String) -> String` callback in the constructor
    - In `createMediaSource(mediaItem)`: extract `mediaItem.mediaId` as videoId, call urlResolver to get the stream URL, build a `ProgressiveMediaSource` with the resolved URI and CacheDataSource.Factory
    - Use `runBlocking` or a coroutine-based approach for the suspending URL resolution within the factory
    - _Requirements: 15.1, 15.3, 16.6_

  - [x] 3.2 Integrate LazyMediaSourceFactory into ExoPlayer builder in PlaybackService
    - Instantiate LazyMediaSourceFactory with the CacheDataSource.Factory and a URL resolver callback
    - The URL resolver callback needs access to the stream resolution logic (expose via a static/companion or service-level resolver)
    - Pass LazyMediaSourceFactory to `ExoPlayer.Builder().setMediaSourceFactory()`
    - Ensure existing playback still works (single-song and queue)
    - _Requirements: 15.1, 15.3, 16.6_

  - [x] 3.3 Implement stream failure skip logic in LazyMediaSourceFactory
    - When urlResolver returns an empty string (resolution failure), create a MediaSource that signals error to ExoPlayer
    - ExoPlayer will then fire onPlayerError and the Player.Listener should call `seekToNext()` to skip to the next track
    - Add error handling in the Player.Listener in MusicRepository to skip on playback errors
    - _Requirements: 15.2_

- [x] 4. Checkpoint - Verify foundational layers
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. MusicRepository queue management
  - [x] 5.1 Add queue state flows and setQueue method to MusicRepository
    - Add `_repeatMode`, `_shuffleEnabled`, `_queueItems`, `_currentQueueIndex` MutableStateFlows with public StateFlow accessors
    - Create `RepeatMode` enum (OFF, ALL, ONE) and `QueueTrack` data class in a new `data/models/` package
    - Implement `setQueue(items: List<MediaItem>, startIndex: Int)` that calls `mediaController?.setMediaItems(items, startIndex, 0L)` and `mediaController?.prepare()` and `mediaController?.play()`
    - Update Player.Listener to observe queue changes and update state flows
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 5.2 Implement addToQueue and moveQueueItem in MusicRepository
    - Implement `addToQueue(item: MediaItem)` that calls `mediaController?.addMediaItem(item)`, or if nothing is playing, calls `setQueue(listOf(item), 0)`
    - Implement `moveQueueItem(fromIndex: Int, toIndex: Int)` that calls `mediaController?.moveMediaItem(fromIndex, toIndex)`
    - Update queue state flow after each operation
    - _Requirements: 5.2, 5.4, 6.7_

  - [x] 5.3 Implement repeat and shuffle control in MusicRepository
    - Implement `setRepeatMode(mode: RepeatMode)` mapping OFF→Player.REPEAT_MODE_OFF, ALL→Player.REPEAT_MODE_ALL, ONE→Player.REPEAT_MODE_ONE
    - Implement `setShuffleEnabled(enabled: Boolean)` calling `mediaController?.shuffleModeEnabled = enabled`
    - Update corresponding state flows after each call
    - Observe repeat/shuffle state changes in Player.Listener for external changes
    - _Requirements: 2.3, 2.4, 2.5, 3.3, 3.4, 3.5_

  - [ ]* 5.4 Write property test for queue population (Property 1)
    - **Property 1: Queue population preserves items and start index**
    - Test that for any list of QueueTrack items and valid startIndex, the mapped MediaItem list has same size, same order of mediaIds, and startIndex is within bounds
    - **Validates: Requirements 1.1, 1.2**

  - [ ]* 5.5 Write property test for repeat mode cycling (Property 2)
    - **Property 2: Repeat mode cycles deterministically**
    - Test that for any N toggle presses from OFF, result equals cycle[N mod 3] where cycle = [OFF, ALL, ONE]
    - **Validates: Requirements 2.2**

  - [ ]* 5.6 Write property test for shuffle toggle (Property 3)
    - **Property 3: Shuffle toggle is a boolean flip**
    - Test that for any N toggles from disabled, result equals N mod 2 != 0
    - **Validates: Requirements 3.2**

  - [ ]* 5.7 Write property test for addToQueue (Property 4)
    - **Property 4: Add to queue appends to end**
    - Test that for any queue of size N, adding a song results in size N+1 with last item matching the added song and all previous items unchanged
    - **Validates: Requirements 5.2**

  - [ ]* 5.8 Write property test for moveQueueItem (Property 5)
    - **Property 5: Move queue item preserves all items**
    - Test that for any queue of size N≥2 and valid fromIndex/toIndex, result has same size, same set of items, with moved item at new position
    - **Validates: Requirements 6.7**

- [x] 6. MusicRepository persistence (liked songs, playlists, recents)
  - [x] 6.1 Integrate Room database into MusicRepository
    - Obtain SonaraDatabase instance in MusicRepository constructor (via singleton)
    - Add DAO references: likedSongDao, playlistDao, recentSongDao
    - Expose `likedSongIds: StateFlow<Set<String>>` collected from likedSongDao.getAllLikedIds()
    - _Requirements: 8.3, 8.4_

  - [x] 6.2 Implement liked songs operations in MusicRepository
    - Implement `likeSong(videoId, title, artist, thumbnailUrl)` inserting a LikedSongEntity
    - Implement `unlikeSong(videoId)` deleting by videoId
    - Expose `getLikedSongs(): Flow<List<LikedSongEntity>>`
    - _Requirements: 8.3, 8.4, 10.2, 10.3_

  - [x] 6.3 Implement playlist operations in MusicRepository
    - Implement `createPlaylist(name: String): Long` creating a PlaylistEntity
    - Implement `addSongToPlaylist(playlistId, videoId, title, artist, thumbnailUrl)` with duplicate check using songExistsInPlaylist()
    - Implement `getPlaylistSongs(playlistId): Flow<List<PlaylistSongEntity>>`
    - Expose `getAllPlaylists(): Flow<List<PlaylistEntity>>`
    - _Requirements: 9.2, 9.5, 9.6, 9.7, 9.8_

  - [x] 6.4 Implement recent playback recording in MusicRepository
    - Implement `recordRecentPlay(videoId, title, artist, thumbnailUrl)` using upsert (REPLACE strategy updates playedAt timestamp for duplicates)
    - Call `trimToMax()` after each upsert to maintain 50-entry cap
    - Expose `getRecentSongs(): Flow<List<RecentSongEntity>>` and `getRecentSongsForHome(): Flow<List<RecentSongEntity>>`
    - Call `recordRecentPlay()` from the Player.Listener's `onMediaItemTransition` callback
    - _Requirements: 11.1, 11.2, 11.3, 12.1_

  - [ ]* 6.5 Write property test for like/unlike round trip (Property 6)
    - **Property 6: Like/unlike round trip**
    - Test that for any song, liking then unliking returns liked set to original state
    - Use in-memory Room database for testing
    - **Validates: Requirements 8.3, 8.4**

  - [ ]* 6.6 Write property test for playlist add idempotence (Property 7)
    - **Property 7: Playlist song addition is idempotent**
    - Test that first addToPlaylist increases count by 1, subsequent calls with same song leave count unchanged
    - **Validates: Requirements 9.5, 9.6**

  - [ ]* 6.7 Write property test for recent play recording (Property 8)
    - **Property 8: Recent play recording with upsert and cap**
    - Test that for any sequence of plays: list never exceeds 50, no duplicates, most recent is at index 0
    - **Validates: Requirements 11.1, 11.2, 11.3**

  - [ ]* 6.8 Write property test for recent list sort order (Property 9)
    - **Property 9: Recent list is sorted by playedAt descending**
    - Test that getRecentSongs() returns entries in strictly descending playedAt order
    - **Validates: Requirements 11.5**

- [x] 7. Checkpoint - Verify data layer
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. PlaybackViewModel enhancements
  - [x] 8.1 Add queue state, repeat/shuffle, and library flows to PlaybackViewModel
    - Expose `repeatMode`, `shuffleEnabled`, `queueItems`, `currentQueueIndex` StateFlows from MusicRepository
    - Expose `likedSongIds`, `customPlaylists`, `recentSongs` StateFlows from MusicRepository
    - Add `searchPlaylists: StateFlow<List<SearchPlaylistItem>>` for playlist search results
    - _Requirements: 2.1, 3.1, 6.2, 8.5, 8.6, 9.7, 11.4_

  - [x] 8.2 Implement queue and mode actions in PlaybackViewModel
    - Implement `playQueueFromSearch(results: List<YoutubeSearchItem>, startIndex: Int)` mapping results to MediaItems (using videoId as mediaId, no URI yet) and calling `repository.setQueue()`
    - Implement `toggleRepeatMode()` cycling OFF→ALL→ONE→OFF and calling `repository.setRepeatMode()`
    - Implement `toggleShuffle()` toggling boolean and calling `repository.setShuffleEnabled()`
    - Implement `addToQueue(item: YoutubeSearchItem)` mapping to MediaItem and calling `repository.addToQueue()`
    - Implement `moveQueueItem(fromIndex, toIndex)` delegating to repository
    - _Requirements: 1.1, 2.2, 3.2, 5.2, 6.7_

  - [x] 8.3 Implement library actions in PlaybackViewModel
    - Implement `toggleLike(videoId, title, artist, thumbnailUrl)` checking likedSongIds and calling like/unlike
    - Implement `isLiked(videoId): Boolean` checking against likedSongIds StateFlow value
    - Implement `createPlaylist(name)` delegating to repository
    - Implement `addToPlaylist(playlistId, song)` delegating to repository
    - Implement `playFromPlaylist(playlistId, startIndex)` loading playlist songs as queue
    - Implement `playFromRecent(startIndex)` loading recent songs as queue
    - _Requirements: 8.3, 8.4, 9.2, 9.5, 9.9, 10.3, 11.6, 12.3_

  - [x] 8.4 Implement debounced search in PlaybackViewModel
    - Add `searchJob: Job?` field for cancellation
    - Implement `performDebouncedSearch(query: String)` that: cancels previous searchJob, if query.length < 3 clears results and returns, otherwise launches new coroutine with 400ms delay then performs search
    - Modify search to also fetch playlist results (SearchPlaylistItem) and cap at 5
    - Clear searchResults when query becomes empty
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 14.1, 14.6_

  - [ ]* 8.5 Write property test for search query length guard (Property 11)
    - **Property 11: Queries shorter than 3 characters do not trigger search**
    - Test that for any string of length 0, 1, or 2, no search is triggered and results are empty
    - **Validates: Requirements 13.3**

  - [ ]* 8.6 Write property test for search playlist cap (Property 12)
    - **Property 12: Search playlist results are capped at 5**
    - Test that for any K playlist results from API, displayed section shows min(K, 5) items
    - **Validates: Requirements 14.6**

  - [ ]* 8.7 Write property test for home screen limit (Property 10)
    - **Property 10: Home screen shows at most 10 recent songs**
    - Test that for any recent playlist of size M, home shows exactly min(M, 10) songs
    - **Validates: Requirements 12.1**

- [x] 9. UI: Repeat and Shuffle buttons in FullPlayerOverlay
  - [x] 9.1 Create RepeatButton and ShuffleButton composables
    - Create `RepeatButton` composable that displays repeat icon with inactive/active/one-badge styling based on RepeatMode
    - Create `ShuffleButton` composable that displays shuffle icon with inactive/active styling based on shuffle state
    - Use `Icons.Default.Repeat` and `Icons.Default.Shuffle` from material icons extended
    - Active state uses SpotifyGreen tint, inactive uses SpotifyLightGray
    - For ONE mode, overlay a small "1" badge on the repeat icon
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x] 9.2 Integrate repeat/shuffle buttons into FullPlayerOverlay transport controls
    - Add RepeatButton to the left of SkipPrevious in the transport row
    - Add ShuffleButton to the right of SkipNext in the transport row
    - Wire tap handlers to `viewModel.toggleRepeatMode()` and `viewModel.toggleShuffle()`
    - Observe repeatMode and shuffleEnabled StateFlows for button state
    - _Requirements: 7.1, 2.2, 3.2_

- [x] 10. UI: QueueView with drag-to-reorder
  - [x] 10.1 Create QueueView composable with track list
    - Create `app/src/main/java/com/example/sonara/ui/QueueView.kt`
    - Display list of QueueTrack items showing title and artist for each
    - Highlight the currently playing track (use currentQueueIndex)
    - Add a "Queue" header and close/dismiss button
    - _Requirements: 6.2, 6.3, 6.4_

  - [x] 10.2 Add drag-to-reorder support to QueueView
    - Use `org.burnoutcrew:reorderable` library or implement custom drag gesture handling with Compose
    - Add drag handle icon on each track item
    - On drag complete, call `viewModel.moveQueueItem(from, to)`
    - Show visual feedback during drag (item elevation/shadow)
    - Update list to reflect shuffle mode order changes
    - _Requirements: 6.5, 6.6, 6.7, 6.8_

  - [x] 10.3 Add queue button to FullPlayerOverlay and wire QueueView display
    - Add a queue icon button (Icons.AutoMirrored.Filled.QueueMusic) in the FullPlayerOverlay
    - On tap, show QueueView as a bottom sheet or inline panel within the overlay
    - _Requirements: 6.1_

- [x] 11. UI: Like button
  - [x] 11.1 Create LikeButton composable
    - Create a heart icon button that toggles between filled (Icons.Default.Favorite) and outlined (Icons.Default.FavoriteBorder)
    - Accept `isLiked: Boolean` and `onToggle: () -> Unit` parameters
    - Filled state uses SpotifyGreen tint, outlined uses SpotifyLightGray
    - _Requirements: 8.5, 8.6_

  - [x] 11.2 Integrate LikeButton into FullPlayerOverlay and search results
    - Add LikeButton in FullPlayerOverlay near the track title area
    - Add LikeButton to each search result row
    - Wire to `viewModel.toggleLike()` with current track or item metadata
    - Observe `likedSongIds` to determine filled/outlined state
    - _Requirements: 8.1, 8.2_

- [x] 12. UI: Library screen (liked songs, playlists, recents)
  - [x] 12.1 Create LibraryScreen composable
    - Replace placeholder text in `Screen.Library` with a new `LibraryScreen` composable
    - Display Liked Songs as the first item showing song count
    - Display Recent Songs as a navigable entry
    - Display list of custom playlists with names and song counts
    - Add "Create New Playlist" button at the top
    - _Requirements: 9.1, 9.7, 10.1, 10.4, 11.4_

  - [x] 12.2 Create PlaylistDetailScreen composable
    - Show list of songs in a playlist (title, artist, thumbnail)
    - On song tap, call `viewModel.playFromPlaylist(playlistId, startIndex)`
    - Handle liked songs playlist detail (uses `getLikedSongs()` flow)
    - Handle recent songs playlist detail (uses `getRecentSongs()` flow)
    - _Requirements: 9.8, 9.9, 10.2, 10.3, 11.5, 11.6_

  - [x] 12.3 Implement Create Playlist dialog
    - Show text input dialog when "Create New Playlist" is tapped
    - Validate non-empty/non-whitespace name
    - On confirm, call `viewModel.createPlaylist(name)`
    - _Requirements: 9.1, 9.2_

- [x] 13. UI: Home screen recent songs
  - [x] 13.1 Create HomeScreen composable with recent songs
    - Replace placeholder text in `Screen.Home` with a new `HomeScreen` composable
    - Display up to 10 most recent songs with thumbnail, title, artist
    - On song tap, call `viewModel.playFromRecent(startIndex)` loading visible recent songs as queue
    - Show "No recent songs yet" message when recent list is empty
    - _Requirements: 12.1, 12.2, 12.3, 12.4_

- [x] 14. UI: Dynamic search and search playlists
  - [x] 14.1 Update SearchTabContent to use debounced search
    - Replace existing `LaunchedEffect` search trigger with calls to `viewModel.performDebouncedSearch(query)`
    - Clear results immediately when query becomes empty
    - Do not trigger search for queries under 3 characters
    - Cancel in-flight searches when query changes
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_

  - [x] 14.2 Add search playlists section to SearchTabContent
    - Display a "Playlists" header section below or above individual song results
    - Show each playlist with title, channel name, and thumbnail
    - Cap display at 5 playlist results
    - On playlist tap, fetch track listing and display as a browseable list
    - On song tap within playlist, load all playlist songs as queue
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6_

- [x] 15. UI: Add to queue and Add to playlist
  - [x] 15.1 Add AddToQueue button to search results
    - Add a small queue-add icon button to each search result row
    - On tap, call `viewModel.addToQueue(item)` and show brief snackbar/toast confirmation
    - _Requirements: 5.1, 5.2, 5.3_

  - [x] 15.2 Add "Add to Playlist" option to search results and FullPlayerOverlay
    - Add a menu/icon button that triggers an AddToPlaylistSheet (bottom sheet)
    - Sheet displays list of available custom playlists
    - On playlist selection, call `viewModel.addToPlaylist(playlistId, song)`
    - Show "Already in playlist" message if song exists, or confirmation on success
    - _Requirements: 9.3, 9.4, 9.5, 9.6_

- [x] 16. Integration: Queue population from search
  - [x] 16.1 Wire search result tap to queue population
    - Modify the search result `clickable` handler to call `viewModel.playQueueFromSearch(searchResults, tappedIndex)` instead of `viewModel.playAudioStream()`
    - This loads all visible results as the queue and starts playback from the tapped song
    - Maintain backward compatibility: single playback still works if queue has 1 item
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 16.2 Wire playlist and library song taps to queue population
    - Ensure liked songs tap loads all liked songs as queue
    - Ensure custom playlist tap loads all playlist songs as queue
    - Ensure recent songs tap loads recent songs as queue
    - Ensure home screen recent tap loads visible recent songs as queue
    - _Requirements: 9.9, 10.3, 11.6, 12.3_

  - [ ]* 16.3 Write property test for stream failure skip (Property 13)
    - **Property 13: Failed stream resolution skips to next track**
    - Test with mock resolver that for any queue where a track fails resolution, playback advances to next available track
    - **Validates: Requirements 15.2**

- [x] 17. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The app uses Kotlin, Jetpack Compose, Media3 ExoPlayer, and Kotlin Coroutines
- Room dependencies require kapt annotation processor
- Existing single-song playback must continue working throughout all changes
- LazyMediaSourceFactory requires `@UnstableApi` opt-in annotation
- The `reorderable` library for drag-to-reorder in QueueView may need to be added as a dependency, or a custom implementation using Compose drag gestures can be used

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["1.3", "2.2"] },
    { "id": 3, "tasks": ["1.4", "3.1"] },
    { "id": 4, "tasks": ["3.2", "5.1"] },
    { "id": 5, "tasks": ["3.3", "5.2", "5.3", "6.1"] },
    { "id": 6, "tasks": ["5.4", "5.5", "5.6", "5.7", "5.8", "6.2", "6.3", "6.4"] },
    { "id": 7, "tasks": ["6.5", "6.6", "6.7", "6.8", "8.1"] },
    { "id": 8, "tasks": ["8.2", "8.3", "8.4"] },
    { "id": 9, "tasks": ["8.5", "8.6", "8.7", "9.1", "11.1", "13.1"] },
    { "id": 10, "tasks": ["9.2", "10.1", "11.2", "12.1", "14.1"] },
    { "id": 11, "tasks": ["10.2", "10.3", "12.2", "12.3", "14.2", "15.1", "15.2"] },
    { "id": 12, "tasks": ["16.1", "16.2"] },
    { "id": 13, "tasks": ["16.3"] }
  ]
}
```
