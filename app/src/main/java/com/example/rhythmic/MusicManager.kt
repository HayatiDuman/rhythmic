package com.example.rhythmic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.example.rhythmic.database.AppDatabase
import com.example.rhythmic.database.MusicEntity
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

object MusicManager {

    private val ARTIST_TITLE_REGEX = Regex("\\s*(?:-|—|–|\\||｜)\\s*")
    private val JUNK_KEYWORDS = listOf("official", "video", "audio", "visualiser", "lyrics", "lyric", "hd", "4k", "red bull", "music", "ai")

    var musicList = ArrayList<MusicModel>()
    var masterMusicList = ArrayList<MusicModel>()
    var mediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null
    private var database: AppDatabase? = null

    val isPlaying = MutableLiveData<Boolean>(false)
    val currentSong = MutableLiveData<MusicModel?>()
    val isShuffleMode = MutableLiveData<Boolean>(false)
    val isLoopMode = MutableLiveData<Boolean>(false)
    val liveMusicList = MutableLiveData<List<MusicModel>>()

    // MusicManager.kt içindeki mevcut init fonksiyonunun en altına ekleme yapıyoruz:
    fun init(context: Context) {
        appContext = context.applicationContext
        database = AppDatabase.getDatabase(context)

        // 🔥 HAFIZADAN ESKİ DURUMLARI ÇEK
        val prefs = context.getSharedPreferences("RhythmicPlayerPrefs", Context.MODE_PRIVATE)
        isShuffleMode.value = prefs.getBoolean("player_shuffle", false)
        isLoopMode.value = prefs.getBoolean("player_loop", false)

        // NewPipe motoru (Mevcut kodun aynen kalsın...)
        Thread {
            try {
                org.schabi.newpipe.extractor.NewPipe.init(
                    OkHttpDownloader.getInstance(),
                    Localization("tr", "TR"),
                    ContentCountry("TR")
                )
                Log.d("NewPipe_Setup", "NewPipe Extractor motoru (TR) başarıyla ilklendirildi.")
            } catch (e: Exception) { Log.e("NewPipe_Setup", "Motor ilklendirme hatası: ${e.message}") }
        }.start()

        Thread { loadFromDatabase() }.start()
    }

    // 🔥 2. ADIM: AudioFragment'tan çağrılacak akıllı kuyruk değiştirme fonksiyonu
    fun setTemporaryPlaylist(newList: List<MusicModel>) {
        val currentPlaying = currentSong.value

        // 1. Aktif çalma listesini (kuyruğu) yeni seçilen listeyle güncelliyoruz
        musicList.clear()
        musicList.addAll(newList)

        // 2. Eğer o an çalan bir şarkı varsa durum kontrolü yapıyoruz
        if (currentPlaying != null) {
            val newIndex = musicList.indexOfFirst { it.filePath == currentPlaying.filePath }

            if (newIndex != -1) {
                // 🎯 DURUM A: Çalan şarkı yeni açılan listede de var! Sıra bozulmasın, indisi eşitle.
                currentSongIndex = newIndex
            } else {
                // 🎯 DURUM B: İSTEDİĞİN DÜZELTME! Çalan şarkı yeni açılan listede YOK.
                // İndisi -1 yapıyoruz. Böylece kullanıcı mini player'dan "Sonraki Şarkı"ya bastığı an
                // playNext() fonksiyonundaki (currentSongIndex++) mantığı devreye girecek,
                // endeks (-1 + 1 = 0) yani listenin İLK şarkısı haline gelip B listesinden çalmaya başlayacaktır!
                currentSongIndex = -1
            }
        } else {
            // Eğer hiçbir şey çalmıyorsa sırayı en baştan başlatmak için sıfırla
            currentSongIndex = -1
        }
    }

    fun refreshLibrary(onComplete: () -> Unit) {
        val context = appContext ?: return
        Thread {
            scanWithMediaStore(context)
            scanWithFileSystem(context)
            loadFromDatabase()
            Handler(Looper.getMainLooper()).post { onComplete() }
        }.start()
    }

    private fun scanWithMediaStore(context: Context) {
        val resolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dispCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val filePath = cursor.getString(pathCol) ?: continue
                if (!File(filePath).exists()) continue
                if (database?.musicDao()?.existsByPath(filePath) == true) continue

                insertMusicFromPath(
                    filePath,
                    cursor.getLong(durCol),
                    cursor.getString(titleCol),
                    cursor.getString(artistCol),
                    cursor.getString(dispCol)
                )
            }
        }
    }

    private fun scanWithFileSystem(context: Context) {
        val root = Environment.getExternalStorageDirectory()
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            if (!file.extension.equals("mp3", true) && !file.extension.equals("m4a", true) && !file.extension.equals("wav", true)) return@forEach
            val path = file.absolutePath
            if (database?.musicDao()?.existsByPath(path) == true) return@forEach
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("audio/*"), null)

            insertMusicFromPath(path, null, null, null, file.name)
        }
    }

    private fun insertMusicFromPath(filePath: String, durationMs: Long?, rawTitle: String?, rawArtist: String?, rawDisplay: String?) {
        // Retriever'ı try dışında tanımlıyoruz
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)

            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

            val display = rawDisplay ?: File(filePath).name
            val fileNameNoExt = display.substringBeforeLast(".")
            val title = metaTitle ?: rawTitle ?: sanitizeTitle(fileNameNoExt)
            val artist = metaArtist ?: rawArtist ?: "Bilinmiyor"
            val originalName = File(filePath).nameWithoutExtension
            val dur = durationMs ?: durStr?.toLongOrNull() ?: 0L
            val cover = extractEmbeddedCover(retriever, filePath)

            val min = TimeUnit.MILLISECONDS.toMinutes(dur)
            val sec = TimeUnit.MILLISECONDS.toSeconds(dur) % 60
            val durationStr = String.format("%02d:%02d", min, sec)

            database?.musicDao()?.insertMusic(
                MusicEntity(
                    videoId = "local_${filePath.hashCode()}",
                    title = title, artist = artist, duration = durationStr,
                    filePath = filePath, albumArtPath = cover,
                    dateAdded = System.currentTimeMillis(), originalFileName = originalName
                )
            )
        } catch (e: Exception) {
            Log.e("FS_SCAN", e.message ?: "error")
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
    }

    fun addSongToDatabase(model: MusicModel) {
        if (isDownloaded(model.videoId)) return

        database?.musicDao()?.insertMusic(
            MusicEntity(
                videoId = model.videoId,
                title = model.title,
                artist = model.artist,
                duration = model.durationText,
                filePath = model.filePath,
                albumArtPath = null,
                dateAdded = System.currentTimeMillis(),
                originalFileName = model.title
            )
        )
        loadFromDatabase()
    }

    private fun extractEmbeddedCover(retriever: MediaMetadataRetriever, path: String): String? {
        val context = appContext ?: return null
        return try {
            val art = retriever.embeddedPicture
            if (art != null) {
                saveBitmapToLocal(BitmapFactory.decodeByteArray(art, 0, art.size), "cover_${path.hashCode()}")
            } else null
        } catch (_: Exception) { null }
    }

    private fun saveBitmapToLocal(bitmap: Bitmap, fileName: String): String? {
        val context = appContext ?: return null
        val dir = File(context.filesDir, "covers")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$fileName.jpg")
        if (file.exists()) return file.absolutePath
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file.absolutePath
    }

    private fun loadFromDatabase() {
        val entities = database?.musicDao()?.getAllMusics() ?: emptyList()

        masterMusicList.clear()
        musicList.clear()

        for (e in entities) {
            val model = MusicModel(
                title = e.title,
                artist = e.artist,
                durationText = e.duration,
                filePath = e.filePath,
                videoId = e.videoId,
                albumArtPath = e.albumArtPath,
                dateAdded = e.dateAdded,
                originalFileName = e.originalFileName
            )
            masterMusicList.add(model)
        }

        // 🎯 KRİTİK DÜZELTME: Uygulama açıldığında en son hangi oynatma listesinin aktif olduğunu öğreniyoruz
        val context = appContext
        if (context != null) {
            val prefs = context.getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)
            val lastPlaylistId = prefs.getLong("last_playlist_id", -1L)

            if (lastPlaylistId != -1L) {
                // Eğer en son bir oynatma listesi açıksa, çalma kuyruğunu o listenin elemanlarıyla doldur
                val playlistSongs = getSongsFromPlaylist(lastPlaylistId)
                if (playlistSongs.isNotEmpty()) {
                    musicList.addAll(playlistSongs)
                } else {
                    musicList.addAll(masterMusicList) // Liste boşsa kütüphaneye dön
                }
            } else {
                musicList.addAll(masterMusicList) // En son "Hepsi" sekmesindeysek kütüphaneyi doldur
            }
        } else {
            musicList.addAll(masterMusicList)
        }

        liveMusicList.postValue(masterMusicList)
    }

    fun isDownloaded(videoId: String): Boolean = database?.musicDao()?.isDownloaded(videoId) ?: false
    fun deleteSong(model: MusicModel) {
        database?.musicDao()?.deleteMusicReferences(model.videoId)
        database?.musicDao()?.deleteById(model.videoId)
        loadFromDatabase()
    }
    private fun sanitizeTitle(rawTitle: String): String {
        var title = rawTitle
        title = title.replace(Regex("(?:\\[|\\(|_)[-a-zA-Z0-9_]{11}(?:\\]|\\)|_)?$"), "")
        val keywords = JUNK_KEYWORDS.joinToString("|")
        title = title.replace(Regex("[\\[\\(]?\\b($keywords)\\b[\\]\\)]?", RegexOption.IGNORE_CASE), "")
        return title.replace("_", " ").replace(Regex("\\s+"), " ").trim().removePrefix("-").removeSuffix("-").trim()
    }

    // --- 🛠️ PLAYLIST MANTIKSAL KÖPRÜLERİ ---

    fun createPlaylist(name: String): Long {
        val dao = database?.musicDao() ?: return -1L
        // Dao'nun insert fonksiyonu genellikle eklenen satırın ID'sini (Long) döner
        return dao.insertPlaylist(com.example.rhythmic.database.PlaylistEntity(playlistName = name))
    }

    fun getPlaylists(): List<com.example.rhythmic.database.PlaylistEntity> {
        return database?.musicDao()?.getAllPlaylists() ?: emptyList()
    }

    fun removeSongsFromPlaylist(playlistId: Long, songs: List<MusicModel>) {
        val dao = database?.musicDao() ?: return
        songs.forEach { song ->
            dao.removeMusicFromPlaylist(playlistId, song.videoId)
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songs: List<MusicModel>) {
        val dao = database?.musicDao() ?: return
        songs.forEach { song ->
            dao.insertMusicToPlaylist(com.example.rhythmic.database.PlaylistMusicCrossRef(playlistId, song.videoId))
        }
    }

    fun getSongsFromPlaylist(playlistId: Long): List<MusicModel> {
        val dao = database?.musicDao() ?: return emptyList()
        return dao.getMusicFromPlaylist(playlistId).map { e ->
            MusicModel(
                title = e.title,
                artist = e.artist,
                durationText = e.duration,
                filePath = e.filePath,
                videoId = e.videoId,
                albumArtPath = e.albumArtPath,
                dateAdded = e.dateAdded,
                originalFileName = e.originalFileName
            )
        }
    }

    fun deletePlaylist(playlistId: Long) {
        database?.musicDao()?.deletePlaylistById(playlistId)
    }

    var currentSongIndex = -1
    fun playMusic(index: Int) {
        if (index !in musicList.indices) return
        val song = musicList[index]

        val activeSong = currentSong.value
        if (activeSong != null && activeSong.filePath == song.filePath && mediaPlayer?.isPlaying == true) {
            return
        }

        currentSongIndex = index
        try {
            this@MusicManager.mediaPlayer?.setOnPreparedListener(null)
            this@MusicManager.mediaPlayer?.setOnCompletionListener(null)

            this@MusicManager.mediaPlayer?.let {
                try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
                it.reset()
                it.release()
            }
            this@MusicManager.mediaPlayer = null

            // Yeni player nesnesini pürüzsüzce oluşturuyoruz
            mediaPlayer = MediaPlayer().apply {
                val uri = if (song.filePath.startsWith("content://")) {
                    Uri.parse(song.filePath)
                } else {
                    Uri.fromFile(File(song.filePath))
                }
                appContext?.let { setDataSource(it, uri) }
                prepareAsync()

                setOnPreparedListener { mp ->
                    mp.start() // Ses motoru hazır olduğunda pürüzsüzce başlasın
                    this@MusicManager.isPlaying.postValue(true)
                    this@MusicManager.currentSong.postValue(song)
                }

                setOnCompletionListener {
                    playNext(auto = true)
                }

                setOnErrorListener { mp, what, extra ->
                    Log.e("MediaPlayer", "Hata oluştu: Ne:$what Ekstra:$extra")
                    mp.reset() // Hata anında player'ı kurtar
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pauseResume() { mediaPlayer?.let { if (it.isPlaying) { it.pause(); isPlaying.postValue(false) } else { it.start(); isPlaying.postValue(true) } } }

    fun playNext(auto: Boolean = false) {
        if (musicList.isEmpty()) return
        if (auto && isLoopMode.value == true) { playMusic(currentSongIndex); return }
        var nextIndex = currentSongIndex
        if (isShuffleMode.value == true) nextIndex = Random().nextInt(musicList.size)
        else { nextIndex++; if (nextIndex >= musicList.size) nextIndex = 0 }
        playMusic(nextIndex)
    }

    fun playPrevious() {
        if (musicList.isEmpty()) return
        var prev = currentSongIndex - 1
        if (prev < 0) prev = musicList.size - 1
        playMusic(prev)
    }

    fun seekTo(pos: Int) = mediaPlayer?.seekTo(pos)
    fun getDuration() = mediaPlayer?.duration ?: 0
    fun getCurrentPosition() = mediaPlayer?.currentPosition ?: 0
    // Mevcut toggle fonksiyonlarını hafızaya kaydedecek şekilde güncelliyoruz:
    fun toggleShuffle() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("RhythmicPlayerPrefs", Context.MODE_PRIVATE)
        val newValue = !(isShuffleMode.value ?: false)

        isShuffleMode.postValue(newValue)
        prefs.edit().putBoolean("player_shuffle", newValue).apply()
    }

    fun toggleLoop() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("RhythmicPlayerPrefs", Context.MODE_PRIVATE)
        val newValue = !(isLoopMode.value ?: false)

        isLoopMode.postValue(newValue)
        prefs.edit().putBoolean("player_loop", newValue).apply()
    }

    // --------------------------------------------------
    // 🛠️ FFMPEG BINARY KÖPRÜSÜ (JNI SYMLINK)
    // --------------------------------------------------
    fun getFFmpegPath(context: Context): String {
        // Android'in az önce çıkarttığı resmi ve yetkili kütüphane klasörünü döndür
        return context.applicationInfo.nativeLibraryDir
    }
}