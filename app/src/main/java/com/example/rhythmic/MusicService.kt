package com.example.rhythmic

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "MusicChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_PREV = "action_prev"
        const val ACTION_NEXT = "action_next"
        const val ACTION_PLAY_PAUSE = "action_play_pause"
    }

    private lateinit var mediaSession: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())

    // Seekbar & playback state updater
    private val updateSeekbarTask = object : Runnable {
        override fun run() {
            updatePlaybackState()
            if (MusicManager.isPlaying.value == true) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        startForeground(NOTIFICATION_ID, createEmptyNotification())
        observeMusicChanges()
    }

    // -----------------------------
    // 🎛️ MEDIA SESSION
    // -----------------------------
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = MusicManager.pauseResume()
                override fun onPause() = MusicManager.pauseResume()
                override fun onSkipToNext() = MusicManager.playNext()
                override fun onSkipToPrevious() = MusicManager.playPrevious()
                override fun onSeekTo(pos: Long) {
                    MusicManager.seekTo(pos.toInt())
                    updatePlaybackState()
                }
            })
            isActive = true
        }
    }

    // -----------------------------
    // 🔄 STATE & METADATA
    // -----------------------------
    private fun updatePlaybackState() {
        val isPlaying = MusicManager.isPlaying.value == true

        val state = if (isPlaying)
            PlaybackStateCompat.STATE_PLAYING
        else
            PlaybackStateCompat.STATE_PAUSED

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                state,
                MusicManager.getCurrentPosition().toLong(),
                1.0f // Seekbar'ın ilerlemesi için önemli
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMetadata(song: MusicModel) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                MusicManager.getDuration().toLong()
            )
            .build()

        mediaSession.setMetadata(metadata)
    }

    // -----------------------------
    // 👀 OBSERVERS
    // -----------------------------
    private fun observeMusicChanges() {
        MusicManager.currentSong.observeForever { song ->
            song ?: return@observeForever
            updateMetadata(song)
            loadArtAndShowNotification(song)
        }

        MusicManager.isPlaying.observeForever { isPlaying ->
            updatePlaybackState()

            if (isPlaying) handler.post(updateSeekbarTask)
            else handler.removeCallbacks(updateSeekbarTask)

            MusicManager.currentSong.value?.let {
                loadArtAndShowNotification(it)
            }
        }
    }

    // -----------------------------
    // 🔔 NOTIFICATION
    // -----------------------------
    private fun loadArtAndShowNotification(song: MusicModel) {
        Glide.with(this)
            .asBitmap()
            .load(song.filePath)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    showNotification(song, resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    val fallback = BitmapFactory.decodeResource(
                        resources,
                        android.R.drawable.ic_menu_gallery
                    )
                    showNotification(song, fallback)
                }
            })
    }

    private fun showNotification(song: MusicModel, largeIcon: Bitmap?) {
        val isPlaying = MusicManager.isPlaying.value ?: false

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(largeIcon)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                servicePendingIntent(ACTION_PREV)
            )
            .addAction(
                if (isPlaying)
                    android.R.drawable.ic_media_pause
                else
                    android.R.drawable.ic_media_play,
                "Play/Pause",
                servicePendingIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                servicePendingIntent(ACTION_NEXT)
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createEmptyNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rhythmic")
            .setContentText("Hazır")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // -----------------------------
    // ⚙️ SYSTEM
    // -----------------------------
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> MusicManager.playPrevious()
            ACTION_NEXT -> MusicManager.playNext()
            ACTION_PLAY_PAUSE -> MusicManager.pauseResume()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Müzik Kontrolleri",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (MusicManager.isPlaying.value != true) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateSeekbarTask)
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }
}
