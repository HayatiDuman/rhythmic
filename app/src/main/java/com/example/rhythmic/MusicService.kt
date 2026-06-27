package com.example.rhythmic

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification

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

    // 🔥 YENİ: Ses ve Kulaklık Yönetimi Değişkenleri
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false
    private var isNoisyReceiverRegistered = false

    // Seekbar & playback state updater
    private val updateSeekbarTask = object : Runnable {
        override fun run() {
            updatePlaybackState()
            if (MusicManager.isPlaying.value == true) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    // 🔥 YENİ: 1. SES ODAĞI DEĞİŞİM DİNLEYİCİSİ (Telefon aramaları vb. için)
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            // Tamamen odak kaybı (Örn: Başka bir müzik uygulaması başlatıldı)
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (MusicManager.isPlaying.value == true) {
                    MusicManager.pauseResume()
                }
            }
            // Geçici odak kaybı (Örn: Telefon çalıyor, arama cevaplandı veya asistan açıldı)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (MusicManager.isPlaying.value == true) {
                    wasPlayingBeforeFocusLoss = true
                    MusicManager.pauseResume()
                }
            }
            // Kısa süreli bildirim sesi geldiğinde (Ducking)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Spotify gibi uygulamalar burada sesi %20'ye düşürür.
                // İstersen doğrudan duraklatabilirsin de. Biz duraklatmayı seçiyoruz:
                if (MusicManager.isPlaying.value == true) {
                    wasPlayingBeforeFocusLoss = true
                    MusicManager.pauseResume()
                }
            }
            // Ses odağı geri kazanıldı (Örn: Telefon araması bitti)
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlayingBeforeFocusLoss) {
                    MusicManager.pauseResume()
                    wasPlayingBeforeFocusLoss = false
                }
            }
        }
    }

    // 🔥 YENİ: 2. KULAKLIK ÇIKMA DİNLEYİCİSİ (Becoming Noisy)
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                // Kulaklık (Kablolu veya Bluetooth) çıktığı an müziği duraklat
                if (MusicManager.isPlaying.value == true) {
                    MusicManager.pauseResume()
                    Log.d("MusicService", "Kulaklık bağlantısı kesildi, müzik duraklatıldı.")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initMediaSession()
        startForeground(NOTIFICATION_ID, createEmptyNotification())
        observeMusicChanges()
    }

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

    // 🔥 YENİ: SES ODAĞI İSTEME METODU (SDK 35 UYUMLU)
    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            return audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    // 🔥 YENİ: SES ODAĞINI SERBEST BIRAKMA METODU
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun updatePlaybackState() {
        val isPlaying = MusicManager.isPlaying.value == true
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, MusicManager.getCurrentPosition().toLong(), 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMetadata(song: MusicModel) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, MusicManager.getDuration().toLong())
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun observeMusicChanges() {
        MusicManager.currentSong.observeForever { song ->
            song ?: return@observeForever
            updateMetadata(song)
            loadArtAndShowNotification(song)
        }

        MusicManager.isPlaying.observeForever { isPlaying ->
            updatePlaybackState()

            if (isPlaying) {
                // Müzik başladığında ses odağı iste ve kulaklık gözlemcisini kaydet
                requestAudioFocus()
                registerNoisyReceiver()
                handler.post(updateSeekbarTask)
            } else {
                // Müzik durduğunda kulaklık gözlemcisini kaldır (odak kalabilir, arama gelirse diye)
                unregisterNoisyReceiver()
                handler.removeCallbacks(updateSeekbarTask)
            }

            MusicManager.currentSong.value?.let {
                loadArtAndShowNotification(it)
            }
        }
    }

    // 🔥 YENİ: KULAKLIK ALICISINI KAYDET
    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            isNoisyReceiverRegistered = true
        }
    }

    // 🔥 YENİ: KULAKLIK ALICISINI KALDIR
    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (e: Exception) { /**/ }
            isNoisyReceiverRegistered = false
        }
    }

    private fun loadArtAndShowNotification(song: MusicModel) {
        Thread {
            var coverBitmap: Bitmap? = null
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(song.filePath)
                val art = retriever.embeddedPicture
                retriever.release()

                if (art != null) {
                    coverBitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                }
            } catch (e: Exception) {
                Log.e("MusicService", "Kapak okunamadı: ${e.message}")
            }

            if (coverBitmap == null) {
                coverBitmap = BitmapFactory.decodeResource(resources, android.R.drawable.ic_menu_gallery)
            }

            handler.post {
                showNotification(song, coverBitmap)
            }
        }.start()
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
            .addAction(android.R.drawable.ic_media_previous, "Previous", servicePendingIntent(ACTION_PREV))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                "Play/Pause",
                servicePendingIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", servicePendingIntent(ACTION_NEXT))
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
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rhythmic")
            .setContentText("Hazır")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // -----------------------------
    // ⚙️ BİLDİRİM KANALI (Zorunlu)
    // -----------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Müzik Kontrolleri",
                NotificationManager.IMPORTANCE_LOW
            )
            // Kilit ekranında medyanın düzgün görünmesi için kilit ekranı görünürlüğünü ayarlıyoruz
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC

            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> MusicManager.playPrevious()
            ACTION_NEXT -> MusicManager.playNext()
            ACTION_PLAY_PAUSE -> MusicManager.pauseResume()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (MusicManager.isPlaying.value != true) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateSeekbarTask)
        unregisterNoisyReceiver()
        abandonAudioFocus() // Servis kapanırken odağı bırak
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }
}