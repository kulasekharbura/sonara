# Requirements Document

## Introduction

This feature adds a playback queue system with repeat and shuffle controls to the Sonara Android music app, along with library management (liked songs, custom playlists), recent playback history, and improved search behavior. Currently, when a user taps a song from search results, only that single song plays and search results persist after clearing the query. This feature introduces a proper queue (powered by ExoPlayer's native playlist via `setMediaItems()`), repeat/shuffle modes, a "like" system, custom playlist libraries, recent history, and responsive search that clears dynamically.

## Glossary

- **Queue**: The ordered list of media items loaded into ExoPlayer via `setMediaItems()`, representing the songs available for sequential playback.
- **ExoPlayer**: The Media3 ExoPlayer instance managed by PlaybackService, responsible for audio decoding and playback.
- **MediaController**: The Media3 MediaController that bridges the UI layer to the remote ExoPlayer session.
- **Repeat_Mode**: ExoPlayer's built-in repeat configuration with three states: OFF (no repeat), ONE (loop current track), ALL (loop entire queue).
- **Shuffle_Mode**: ExoPlayer's built-in shuffle flag that randomizes playback order within the queue when enabled.
- **Player_Bar**: The mini player bar displayed at the bottom of the screen showing current track info and basic controls.
- **Full_Player_Overlay**: The expanded bottom sheet showing full playback controls including artwork, seek bar, and transport buttons.
- **Search_Results**: The list of YouTube search results displayed in the Search tab.
- **Queue_View**: A UI component that displays the upcoming tracks in the current queue with drag-to-reorder support.
- **Search_Playlists**: Playlist or album results returned alongside individual song results during a search, representing curated collections of related songs (e.g., movie soundtracks, artist playlists).
- **PlaybackViewModel**: The ViewModel that bridges UI state to MusicRepository and exposes playback state as StateFlows.
- **MusicRepository**: The data layer class that manages the MediaController connection and issues playback commands.
- **Liked_Songs_Playlist**: A system-managed playlist that stores songs the user has explicitly liked.
- **Custom_Playlist**: A user-created named playlist that stores songs added by the user.
- **Library_Screen**: The "Your Library" tab that displays Liked_Songs_Playlist, Custom_Playlists, and Recent_Playlist.
- **Recent_Playlist**: A system-managed playlist that stores the most recently played songs in reverse chronological order.
- **Home_Screen**: The Home tab that displays recently played songs for quick access.
- **Add_To_Queue_Button**: A UI control that appends a song to the end of the current Queue without interrupting playback.
- **Like_Button**: A UI control (heart icon) that adds or removes a song from Liked_Songs_Playlist.
- **Local_Storage**: Persistent on-device storage (Room database or SharedPreferences) used to save liked songs, playlists, and recent history.

## Requirements

### Requirement 1: Queue Population from Search Results

**User Story:** As a user, I want all visible search results to become my playback queue when I tap a song, so that music continues playing after the selected track ends.

#### Acceptance Criteria

1. WHEN a user taps a song from Search_Results, THE MusicRepository SHALL load all currently visible Search_Results into ExoPlayer as a queue using `setMediaItems()` and seek to the tapped track's index.
2. WHEN a queue is loaded, THE ExoPlayer SHALL begin playback from the tapped track's position in the queue.
3. WHEN playback of a track completes and Repeat_Mode is OFF and the track is the last in the queue, THE ExoPlayer SHALL stop playback.
4. WHEN playback of a track completes and Repeat_Mode is OFF and subsequent tracks exist in the queue, THE ExoPlayer SHALL advance to the next track automatically.

### Requirement 2: Repeat Mode Control

**User Story:** As a user, I want to cycle through repeat modes (off, repeat all, repeat one), so that I can control how playback behaves when tracks end.

#### Acceptance Criteria

1. THE PlaybackViewModel SHALL expose the current Repeat_Mode as a StateFlow with three possible values: OFF, ONE, ALL.
2. WHEN the user taps the repeat button, THE PlaybackViewModel SHALL cycle the Repeat_Mode in the order: OFF → ALL → ONE → OFF.
3. WHEN Repeat_Mode changes, THE MusicRepository SHALL set ExoPlayer's `repeatMode` property to the corresponding value (REPEAT_MODE_OFF, REPEAT_MODE_ALL, REPEAT_MODE_ONE).
4. WHILE Repeat_Mode is ALL and the last track in the queue finishes, THE ExoPlayer SHALL restart playback from the first track in the queue.
5. WHILE Repeat_Mode is ONE, THE ExoPlayer SHALL continuously loop the current track.

### Requirement 3: Shuffle Mode Control

**User Story:** As a user, I want to toggle shuffle mode on and off, so that I can randomize the playback order of my queue.

#### Acceptance Criteria

1. THE PlaybackViewModel SHALL expose the current Shuffle_Mode as a StateFlow with two possible values: enabled or disabled.
2. WHEN the user taps the shuffle button, THE PlaybackViewModel SHALL toggle Shuffle_Mode between enabled and disabled.
3. WHEN Shuffle_Mode changes, THE MusicRepository SHALL set ExoPlayer's `shuffleModeEnabled` property to the corresponding boolean value.
4. WHILE Shuffle_Mode is enabled, THE ExoPlayer SHALL play tracks in a randomized order determined by its internal shuffle algorithm.
5. WHILE Shuffle_Mode is disabled, THE ExoPlayer SHALL play tracks in the original queue order.

### Requirement 4: Queue Navigation

**User Story:** As a user, I want skip next and skip previous to navigate through my queue, so that I can move between songs without manually selecting them.

#### Acceptance Criteria

1. WHEN the user taps skip next, THE MediaController SHALL advance to the next track in the queue (respecting Shuffle_Mode order).
2. WHEN the user taps skip previous within the first 3 seconds of a track, THE MediaController SHALL move to the previous track in the queue.
3. WHEN the user taps skip previous after 3 seconds of a track, THE MediaController SHALL seek to the beginning of the current track.
4. IF no next track exists in the queue and Repeat_Mode is OFF, THEN THE MediaController SHALL remain on the current track and stop playback.
5. IF no previous track exists in the queue, THEN THE MediaController SHALL seek to the beginning of the current track.

### Requirement 5: Add to Queue

**User Story:** As a user, I want to add a song to the end of my current queue without interrupting the currently playing track, so that I can build up my listening session.

#### Acceptance Criteria

1. THE Search_Results list SHALL display an Add_To_Queue_Button for each song item.
2. WHEN the user taps Add_To_Queue_Button on a song, THE MusicRepository SHALL append that song as a MediaItem to the end of ExoPlayer's current queue using `addMediaItem()`.
3. WHEN a song is added to the queue, THE Full_Player_Overlay SHALL display a brief confirmation message indicating the song was added.
4. IF no queue currently exists (nothing is playing), THEN THE MusicRepository SHALL create a new queue with the single song and begin playback.

### Requirement 6: Queue Visibility and Reordering

**User Story:** As a user, I want to see and reorder the upcoming tracks in my queue, so that I can control what plays next.

#### Acceptance Criteria

1. THE Full_Player_Overlay SHALL include a button to reveal the Queue_View.
2. WHEN the user taps the queue button, THE Queue_View SHALL display the list of remaining tracks in playback order (respecting Shuffle_Mode).
3. THE Queue_View SHALL highlight the currently playing track in the list.
4. THE Queue_View SHALL display each track's title and artist name.
5. WHEN Shuffle_Mode changes while Queue_View is visible, THE Queue_View SHALL update to reflect the new playback order.
6. THE Queue_View SHALL provide a drag handle on each track item to enable reordering.
7. WHEN the user drags a track to a new position in Queue_View, THE MusicRepository SHALL call ExoPlayer's `moveMediaItem()` to update the queue order accordingly.
8. WHILE a drag is in progress, THE Queue_View SHALL display visual feedback showing the item's new position.

### Requirement 7: Repeat and Shuffle UI Indicators

**User Story:** As a user, I want visual feedback on the current repeat and shuffle states, so that I always know which modes are active.

#### Acceptance Criteria

1. THE Full_Player_Overlay SHALL display a repeat button and a shuffle button alongside the existing transport controls.
2. WHILE Repeat_Mode is OFF, THE repeat button SHALL display in an inactive (dimmed) style.
3. WHILE Repeat_Mode is ALL, THE repeat button SHALL display in an active (highlighted) style with the standard repeat icon.
4. WHILE Repeat_Mode is ONE, THE repeat button SHALL display in an active (highlighted) style with a "1" badge indicator.
5. WHILE Shuffle_Mode is enabled, THE shuffle button SHALL display in an active (highlighted) style.
6. WHILE Shuffle_Mode is disabled, THE shuffle button SHALL display in an inactive (dimmed) style.

### Requirement 8: Like Song

**User Story:** As a user, I want to like songs so that I can save my favorites to a dedicated playlist for easy access later.

#### Acceptance Criteria

1. THE Full_Player_Overlay SHALL display a Like_Button (heart icon) for the currently playing track.
2. THE Search_Results list SHALL display a Like_Button for each song item.
3. WHEN the user taps Like_Button on a song that is not liked, THE PlaybackViewModel SHALL add the song to Liked_Songs_Playlist in Local_Storage.
4. WHEN the user taps Like_Button on a song that is already liked, THE PlaybackViewModel SHALL remove the song from Liked_Songs_Playlist in Local_Storage.
5. WHILE a song is in Liked_Songs_Playlist, THE Like_Button SHALL display in a filled (active) style.
6. WHILE a song is not in Liked_Songs_Playlist, THE Like_Button SHALL display in an outlined (inactive) style.

### Requirement 9: Custom Playlists and Library Management

**User Story:** As a user, I want to create named playlists and add songs to them, so that I can organize my music into collections.

#### Acceptance Criteria

1. THE Library_Screen SHALL display a "Create New Playlist" button.
2. WHEN the user taps "Create New Playlist", THE Library_Screen SHALL present a text input for the playlist name and create the Custom_Playlist in Local_Storage upon confirmation.
3. THE Search_Results list and Full_Player_Overlay SHALL provide an "Add to Playlist" option for each song.
4. WHEN the user taps "Add to Playlist", THE system SHALL display a list of available Custom_Playlists for selection.
5. WHEN the user selects a Custom_Playlist, THE system SHALL add the song to that playlist in Local_Storage.
6. IF a song already exists in the selected Custom_Playlist, THEN THE system SHALL display a message indicating the song is already in the playlist and take no duplicate action.
7. THE Library_Screen SHALL display all Custom_Playlists with their names and song counts.
8. WHEN the user taps a Custom_Playlist in Library_Screen, THE system SHALL display the songs in that playlist.
9. WHEN the user taps a song within a Custom_Playlist view, THE MusicRepository SHALL load all songs in that playlist as the queue and begin playback from the tapped song.

### Requirement 10: Liked Songs Playlist in Library

**User Story:** As a user, I want to see my liked songs as a playlist in my library, so that I can browse and play all my favorites.

#### Acceptance Criteria

1. THE Library_Screen SHALL display Liked_Songs_Playlist as the first item in the list.
2. WHEN the user taps Liked_Songs_Playlist in Library_Screen, THE system SHALL display all liked songs with title and artist.
3. WHEN the user taps a song within Liked_Songs_Playlist view, THE MusicRepository SHALL load all liked songs as the queue and begin playback from the tapped song.
4. THE Liked_Songs_Playlist entry in Library_Screen SHALL display the total count of liked songs.

### Requirement 11: Recent Playback History

**User Story:** As a user, I want to see my recently played songs, so that I can quickly replay tracks I've listened to.

#### Acceptance Criteria

1. WHEN a track begins playback, THE MusicRepository SHALL record the track's metadata (video ID, title, artist, thumbnail URL) and timestamp to Recent_Playlist in Local_Storage.
2. THE Recent_Playlist SHALL store a maximum of 50 recent entries, removing the oldest entry when the limit is exceeded.
3. IF the same song is played again, THEN THE Recent_Playlist SHALL move that entry to the top (most recent) rather than creating a duplicate.
4. THE Library_Screen SHALL display Recent_Playlist as a navigable entry.
5. WHEN the user taps Recent_Playlist in Library_Screen, THE system SHALL display the recently played songs in reverse chronological order.
6. WHEN the user taps a song within Recent_Playlist view, THE MusicRepository SHALL load recent songs as the queue and begin playback from the tapped song.

### Requirement 12: Home Screen Recent Songs

**User Story:** As a user, I want to see my recently played songs on the Home screen, so that I can quickly resume listening without navigating to the library.

#### Acceptance Criteria

1. THE Home_Screen SHALL display up to 10 most recently played songs from Recent_Playlist.
2. THE Home_Screen SHALL display each recent song with its thumbnail, title, and artist name.
3. WHEN the user taps a recent song on Home_Screen, THE MusicRepository SHALL load the visible recent songs as the queue and begin playback from the tapped song.
4. WHILE Recent_Playlist is empty, THE Home_Screen SHALL display a message indicating no recent songs are available.

### Requirement 13: Dynamic Search Behavior

**User Story:** As a user, I want search results to update dynamically as I type and clear when I delete my query, so that the search experience feels responsive like other music apps.

#### Acceptance Criteria

1. WHEN the user types in the search field, THE PlaybackViewModel SHALL debounce input and trigger a search after 400 milliseconds of inactivity.
2. WHEN the search query becomes empty (user deletes all text), THE PlaybackViewModel SHALL clear Search_Results immediately.
3. WHEN the search query has fewer than 3 characters, THE PlaybackViewModel SHALL clear Search_Results and not trigger a search request.
4. WHEN new Search_Results arrive, THE Search tab SHALL replace the previous results with the new results.
5. WHILE a search is in progress and the user modifies the query, THE PlaybackViewModel SHALL cancel the previous search request and issue a new one based on the latest query.

### Requirement 14: Search Playlists and Related Collections

**User Story:** As a user, I want to see playlists and related song collections alongside individual tracks in search results, so that I can discover and play curated groups of songs (like a movie soundtrack or artist mix).

#### Acceptance Criteria

1. WHEN a search is performed, THE PlaybackViewModel SHALL request both video results and playlist results from the search API.
2. THE Search tab SHALL display Search_Playlists in a distinct section (e.g., "Playlists" header) separate from individual song results.
3. THE Search_Playlists section SHALL display each playlist's title, channel name, and thumbnail.
4. WHEN the user taps a Search_Playlist item, THE system SHALL fetch the playlist's track listing and display it as a browseable list.
5. WHEN the user taps a song within a Search_Playlist track list, THE MusicRepository SHALL load all songs in that playlist as the queue and begin playback from the tapped song.
6. THE Search_Playlists section SHALL display a maximum of 5 playlist results per search query.

### Requirement 15: Stream Resolution for Queue Items

**User Story:** As a user, I want queue playback to work seamlessly even though stream URLs must be resolved on demand, so that there are no interruptions between tracks.

#### Acceptance Criteria

1. WHEN a track in the queue begins preparation for playback, THE MusicRepository SHALL resolve the audio stream URL for that track using the existing `fetchAudioStreamLink` method.
2. IF stream resolution fails for a track, THEN THE MusicRepository SHALL skip to the next track in the queue and log the failure.
3. THE MusicRepository SHALL resolve stream URLs lazily (on demand before playback) rather than eagerly for all queue items at once.

### Requirement 16: Low-Latency Seek with Progressive Cache

**User Story:** As a user, I want seeking (scrubbing the playback slider) within a playing song to be instant for portions already played, so that I can jump around in a track without waiting for it to reload.

#### Acceptance Criteria

1. THE ExoPlayer SHALL use a CacheDataSource backed by a SimpleCache to progressively cache audio data as the track streams.
2. WHILE the user seeks to a position within already-cached audio data, THE ExoPlayer SHALL resume playback from that position with no network re-fetch.
3. WHILE the user seeks to a position beyond the cached portion, THE ExoPlayer SHALL fetch from the network starting at the target position and resume playback once sufficient data is buffered.
4. THE SimpleCache SHALL store cached audio on device storage with a maximum cache size of 200 MB.
5. WHEN the cache exceeds the maximum size, THE SimpleCache SHALL evict the least recently used entries.
6. THE MusicRepository SHALL configure ExoPlayer with the CacheDataSource as its media source factory during initialization.
