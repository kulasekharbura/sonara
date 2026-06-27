package com.example.sonara.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.DefaultMediaNotificationProvider
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import com.example.sonara.R
import com.example.sonara.MainActivity
import com.google.common.collect.ImmutableList

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_LIKE = "com.example.sonara.ACTION_LIKE"
        var likeCallback: ((videoId: String, title: String, artist: String, thumbnailUrl: String) -> Unit)? = null
        
        @Volatile
        var globalLikedIds: Set<String> = emptySet()
            set(value) {
                field = value
                instance?.updateHeartIcon()
            }
        
        private var instance: PlaybackService? = null
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var cache: SimpleCache? = null
    private var cacheDataSourceFactory: CacheDataSource.Factory? = null

    private val CHANNEL_ID = "sonara_playback_channel"

    private fun updateHeartIcon() {
        val currentItem = player?.currentMediaItem ?: return
        val isLiked = globalLikedIds.contains(currentItem.mediaId)
        updateNotificationLayout(isLiked)
    }

    private fun updateNotificationLayout(isLiked: Boolean) {
        val iconRes = if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_border
        val displayName = if (isLiked) "Unlike" else "Like"
        
        mediaSession?.setCustomLayout(listOf(
            CommandButton.Builder()
                .setDisplayName(displayName)
                .setIconResId(iconRes)
                .setSessionCommand(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
                .build()
        ))
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sonara Audio Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Handles background music playback streams for Sonara"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // Custom Notification Provider to force the Heart icon to the right (after standard controls)
        setMediaNotificationProvider(object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showCustomButtonsInCompactView: Boolean
            ): ImmutableList<CommandButton> {
                val buttons = mutableListOf<CommandButton>()
                
                // 1. Standard Playback Buttons (Left/Center)
                if (playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) {
                    buttons.add(getStandardButton(session, Player.COMMAND_SEEK_TO_PREVIOUS))
                }
                buttons.add(getStandardButton(session, Player.COMMAND_PLAY_PAUSE))
                if (playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) {
                    buttons.add(getStandardButton(session, Player.COMMAND_SEEK_TO_NEXT))
                }
                
                // 2. Custom Like Button (Right Most)
                buttons.addAll(customLayout)
                
                return ImmutableList.copyOf(buttons)
            }
            
            private fun getStandardButton(session: MediaSession, command: Int): CommandButton {
                return when (command) {
                    Player.COMMAND_SEEK_TO_PREVIOUS -> CommandButton.Builder()
                        .setDisplayName("Previous")
                        .setIconResId(android.R.drawable.ic_media_previous)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .build()
                    Player.COMMAND_PLAY_PAUSE -> {
                        val isPlaying = session.player.isPlaying
                        CommandButton.Builder()
                            .setDisplayName(if (isPlaying) "Pause" else "Play")
                            .setIconResId(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                            .build()
                    }
                    Player.COMMAND_SEEK_TO_NEXT -> CommandButton.Builder()
                        .setDisplayName("Next")
                        .setIconResId(android.R.drawable.ic_media_next)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                        .build()
                    else -> throw IllegalArgumentException()
                }
            }
        })

        val cacheDir = File(cacheDir, "sonara_audio_cache")
        val cacheEvictor = LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024)
        val databaseProvider = StandaloneDatabaseProvider(this)
        cache = SimpleCache(cacheDir, cacheEvictor, databaseProvider)

        val upstreamFactory = DefaultDataSource.Factory(this)
        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache!!)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val lowLatencyLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 750, 2000)
            .build()

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        val progressiveFactory = ProgressiveMediaSource.Factory(cacheDataSourceFactory!!)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(progressiveFactory)
            .setLoadControl(lowLatencyLoadControl)
            .build().apply {
                setAudioAttributes(audioAttributes, true)
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            updateHeartIcon()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                    }
                    
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (mediaItem != null) {
                            updateHeartIcon()
                        }
                    }
                })
            }

        mediaSession = player?.let { exoPlayer ->
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("OPEN_FULL_PLAYER", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            MediaSession.Builder(this, exoPlayer)
                .setSessionActivity(pendingIntent)
                .setCallback(object : MediaSession.Callback {
                    override fun onCustomCommand(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        customCommand: SessionCommand,
                        args: Bundle
                    ): ListenableFuture<SessionResult> {
                        if (customCommand.customAction == ACTION_LIKE) {
                            val item = session.player.currentMediaItem
                            if (item != null) {
                                val videoId = item.mediaId
                                val title = item.mediaMetadata.title?.toString() ?: ""
                                val artist = item.mediaMetadata.artist?.toString() ?: ""
                                val thumbnail = item.mediaMetadata.artworkUri?.toString() ?: ""
                                
                                likeCallback?.invoke(videoId, title, artist, thumbnail)
                            }
                            return com.google.common.util.concurrent.Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS)
                            )
                        }
                        return super.onCustomCommand(session, controller, customCommand, args)
                    }

                    override fun onConnect(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo
                    ): MediaSession.ConnectionResult {
                        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
                            .build()
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(sessionCommands)
                            .build()
                    }
                })
                .build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        cache?.release()
        cache = null
        super.onDestroy()
    }
}