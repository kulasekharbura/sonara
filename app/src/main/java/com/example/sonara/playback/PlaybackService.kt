package com.example.sonara.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_LIKE = "com.example.sonara.ACTION_LIKE"
        /** Called by the notification like button. Set by MusicRepository. */
        var likeCallback: ((videoId: String, title: String, artist: String, thumbnailUrl: String) -> Unit)? = null
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var cache: SimpleCache? = null
    private var cacheDataSourceFactory: CacheDataSource.Factory? = null

    private val CHANNEL_ID = "sonara_playback_channel"
    private val NOTIFICATION_ID = 101

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // 1. Establish the OS Notification Channel to clear ActivityManager rules
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

        // 2. Build a persistent baseline notification layout wrapper
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sonara Stream Engine")
            .setContentText("Preparing active audio channel...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        // 3. Promote the worker to an authenticated Foreground Service immediately
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            android.util.Log.e("SONARA_SERVICE", "Foreground conversion rejected by OS", e)
        }

        // 4. Set up progressive audio cache (200 MB LRU)
        val cacheDir = File(cacheDir, "sonara_audio_cache")
        val cacheEvictor = LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024)
        val databaseProvider = StandaloneDatabaseProvider(this)
        cache = SimpleCache(cacheDir, cacheEvictor, databaseProvider)

        // 5. Build CacheDataSource.Factory wrapping DefaultDataSource.Factory
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

        // 6. Build ExoPlayer with CacheDataSource for progressive caching.
        // URL resolution is handled eagerly in MusicRepository before items are set,
        // not lazily in a MediaSource.Factory (which would block the main thread).
        val progressiveFactory = ProgressiveMediaSource.Factory(cacheDataSourceFactory!!)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(progressiveFactory)
            .setLoadControl(lowLatencyLoadControl)
            .build().apply {
                setAudioAttributes(audioAttributes, true)
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_ALL
            }

        mediaSession = player?.let { exoPlayer ->
            MediaSession.Builder(this, exoPlayer)
                .setCallback(object : MediaSession.Callback {
                    override fun onCustomCommand(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        customCommand: SessionCommand,
                        args: android.os.Bundle
                    ): ListenableFuture<SessionResult> {
                        if (customCommand.customAction == ACTION_LIKE) {
                            val item = session.player.currentMediaItem
                            if (item != null) {
                                val videoId = item.mediaId
                                val title = item.mediaMetadata.title?.toString() ?: ""
                                val artist = item.mediaMetadata.artist?.toString() ?: ""
                                val thumbnail = item.mediaMetadata.artworkUri?.toString() ?: ""
                                // Fire and forget - like the song
                                likeCallback?.invoke(videoId, title, artist, thumbnail)
                                android.util.Log.d("SONARA_SERVICE", "Like action from notification: $title")
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
                            .add(SessionCommand(ACTION_LIKE, android.os.Bundle.EMPTY))
                            .build()
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(sessionCommands)
                            .build()
                    }
                })
                .setCustomLayout(listOf(
                    CommandButton.Builder()
                        .setDisplayName("Like")
                        .setIconResId(android.R.drawable.btn_star_big_on)
                        .setSessionCommand(SessionCommand(ACTION_LIKE, android.os.Bundle.EMPTY))
                        .build()
                ))
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