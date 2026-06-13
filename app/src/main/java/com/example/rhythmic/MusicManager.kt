package com.example.rhythmic

import android.content.ContentUris
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
import java.net.URL
import java.util.Random
import java.util.concurrent.TimeUnit

object MusicManager {

    // --- Değişmeyen Kısımlar (Regex, Player, Init vs.) ---
    private val ARTIST_TITLE_REGEX = Regex("\\s*(?:-|—|–|\\||｜)\\s*")
    private val JUNK_KEYWORDS = listOf("official", "video", "audio", "visualiser", "lyrics", "lyric", "hd", "4k", "red bull", "music", "ai")

    var musicList = ArrayList<MusicModel>()
    var mediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null
    private var database: AppDatabase? = null

    val isPlaying = MutableLiveData(false)
    val currentSong = MutableLiveData<MusicModel?>()
    val isShuffleMode = MutableLiveData(false)
    val isLoopMode = MutableLiveData(false)
    val liveMusicList = MutableLiveData<List<MusicModel>>()

    fun init(context: Context) {
        appContext = context.applicationContext
        database = AppDatabase.getDatabase(context)
        Thread { loadFromDatabase() }.start()
    }

    // --------------------------------------------------
    // 🔄 REFRESH LIBRARY (MediaStore + FS Fallback)
    // --------------------------------------------------
    fun refreshLibrary(onComplete: () -> Unit) {
        val context = appContext ?: return

        Thread {
            scanWithMediaStore(context)
            scanWithFileSystem(context)   // 🔥 ASIL FARK BURADA
            loadFromDatabase()
            Handler(Looper.getMainLooper()).post { onComplete() }
        }.start()
    }

    // --------------------------------------------------
    // 1️⃣ MediaStore Scan (mevcut mantık korunuyor)
    // --------------------------------------------------
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
                val fileExists = File(filePath).exists()

                Log.d(
                    "MEDIA_STORE_SCAN",
                    """
                PATH=$filePath
                EXISTS=$fileExists
                TITLE=${cursor.getString(titleCol)}
                ARTIST=${cursor.getString(artistCol)}
                DISPLAY=${cursor.getString(dispCol)}
                """.trimIndent()
                )

                // 🔥 ASIL PROBLEM BURADA ÇIKACAK
                if (!fileExists) {
                    Log.w("MEDIA_STORE_GHOST", "Ghost MediaStore entry: $filePath")
                    continue
                }

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


    // --------------------------------------------------
    // 2️⃣ File System Scan (MediaStore'da yoksa)
    // --------------------------------------------------
    private fun scanWithFileSystem(context: Context) {
        val root = Environment.getExternalStorageDirectory()

        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            if (!file.extension.equals("mp3", true)
                && !file.extension.equals("m4a", true)
                && !file.extension.equals("wav", true)
            ) return@forEach

            val path = file.absolutePath

            Log.d(
                "FS_SCAN",
                "FOUND_FILE=$path EXISTS=${file.exists()}"
            )

            if (database?.musicDao()?.existsByPath(path) == true) return@forEach

            MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                arrayOf("audio/*"),
                null
            )

            insertMusicFromPath(path, null, null, null, file.name)
        }
    }


    // --------------------------------------------------
    // Ortak ekleme fonksiyonu (tek kaynak)
    // --------------------------------------------------
    private fun insertMusicFromPath(
        filePath: String,
        durationMs: Long?,
        rawTitle: String?,
        rawArtist: String?,
        rawDisplay: String?
    ) {
        try {
            val display = rawDisplay ?: File(filePath).name
            val fileNameNoExt = display.substringBeforeLast(".")
            val cleanName = sanitizeTitle(fileNameNoExt)
            val originalName = File(filePath).nameWithoutExtension

            var (artist, title) = parseArtistTitle(cleanName)

            if (artist == "Bilinmiyor" && !rawArtist.isNullOrBlank()) {
                artist = rawArtist
                title = sanitizeTitle(rawTitle ?: cleanName)
            }

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)

            val dur = durationMs ?: retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            retriever.release()

            val min = TimeUnit.MILLISECONDS.toMinutes(dur)
            val sec = TimeUnit.MILLISECONDS.toSeconds(dur) % 60
            val durationStr = String.format("%02d:%02d", min, sec)

            val cover = extractEmbeddedCover(filePath)

            database?.musicDao()?.insertMusic(
                MusicEntity(
                    videoId = "local_${filePath.hashCode()}",
                    title = title,
                    artist = artist,
                    duration = durationStr,
                    filePath = filePath,
                    albumArtPath = cover,
                    dateAdded = System.currentTimeMillis(),
                    originalFileName = originalName
                )
            )
        } catch (e: Exception) {
            Log.e("FS_SCAN", e.message ?: "error")
        }
    }


    // -----------------------------
    // ➕ ADD SONG (İNDİRME SONRASI)
    // -----------------------------
    fun addSongToDatabase(model: MusicModel) {
        if (isDownloaded(model.videoId)) return

        val cleanName = sanitizeTitle(model.title)
        val (artist, title) = parseArtistTitle(cleanName)
        val originalName = model.title

        // 1. Önce Dosyanın İçine Bak (Python gömmüş mü?)
        var localCoverPath: String? = extractEmbeddedCover(model.filePath)

        // 2. Eğer Python gömmemişse, İnternet URL'sinden İndir (Yedek Plan)
        if (localCoverPath == null && !model.albumArtPath.isNullOrEmpty() && model.albumArtPath!!.startsWith("http")) {
            localCoverPath = downloadCoverFromUrl(model.albumArtPath!!, model.videoId)
        }

        database?.musicDao()?.insertMusic(
            MusicEntity(
                videoId = model.videoId,
                title = title,
                artist = artist,
                duration = model.durationText,
                filePath = model.filePath,
                albumArtPath = localCoverPath,
                dateAdded = System.currentTimeMillis(),
                originalFileName = originalName
            )
        )
        loadFromDatabase()
    }

    // --------------------------------------------------
    // KAPAK, PARSE, PLAYER (DEĞİŞMEDİ)
    // --------------------------------------------------
    private fun extractEmbeddedCover(path: String): String? {
        val context = appContext ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val art = retriever.embeddedPicture
            retriever.release()
            if (art != null) {
                saveBitmapToLocal(
                    BitmapFactory.decodeByteArray(art, 0, art.size),
                    "cover_${path.hashCode()}"
                )
            } else null
        } catch (_: Exception) { null }
    }

    // URL'den (İnternetten) resim indirir
    private fun downloadCoverFromUrl(url: String, id: String): String? {
        try {
            val inputStream = URL(url).openStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            return saveBitmapToLocal(bitmap, "web_cover_$id")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
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

    // -----------------------------
    // DİĞER FONKSİYONLAR
    // -----------------------------

    private fun loadFromDatabase() {
        val entities = database?.musicDao()?.getAllMusics() ?: emptyList()
        musicList.clear()
        for (e in entities) {
            val exists = File(e.filePath).exists()

            Log.d(
                "DB_LOAD",
                """
            TITLE=${e.title}
            PATH=${e.filePath}
            EXISTS=$exists
            VIDEO_ID=${e.videoId}
            """.trimIndent()
            )
            musicList.add(
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
            )
        }
        liveMusicList.postValue(musicList)
    }

    fun isDownloaded(videoId: String): Boolean = database?.musicDao()?.isDownloaded(videoId) ?: false
    fun deleteSong(model: MusicModel) { database?.musicDao()?.deleteById(model.videoId); loadFromDatabase() }

    private fun parseArtistTitle(cleanName: String): Pair<String, String> {
        val parts = cleanName.split(ARTIST_TITLE_REGEX).map { it.trim() }
        if (parts.size >= 3 && parts[0].equals(parts[1], true)) return parts[0] to parts.subList(2, parts.size).joinToString(" - ")
        if (parts.size >= 2) {
            val artist = parts[0]
            val isJunk = JUNK_KEYWORDS.any { artist.lowercase().contains(it) }
            if (artist.length in 2..40 && !isJunk) return artist to parts.subList(1, parts.size).joinToString(" - ")
        }
        return "Bilinmiyor" to cleanName
    }

    private fun sanitizeTitle(rawTitle: String): String {
        var title = rawTitle
        title = title.replace(Regex("(?:\\[|\\(|_)[-a-zA-Z0-9_]{11}(?:\\]|\\)|_)?$"), "")
        val keywords = JUNK_KEYWORDS.joinToString("|")
        title = title.replace(Regex("[\\[\\(]?\\b($keywords)\\b[\\]\\)]?", RegexOption.IGNORE_CASE), "")
        return title.replace("_", " ").replace(Regex("\\s+"), " ").trim().removePrefix("-").removeSuffix("-").trim()
    }

    // Player...
    var currentSongIndex = -1
    fun playMusic(index: Int) {
        if (index !in musicList.indices) return
        val song = musicList[index]
        currentSongIndex = index
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val uri = if (song.filePath.startsWith("content://")) Uri.parse(song.filePath) else Uri.fromFile(File(song.filePath))
                appContext?.let { setDataSource(it, uri) }
                prepare(); start()
                setOnCompletionListener { playNext(auto = true) }
            }
            isPlaying.postValue(true); currentSong.postValue(song)
        } catch (e: Exception) { e.printStackTrace() }
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
    fun toggleShuffle() = isShuffleMode.postValue(!(isShuffleMode.value ?: false))
    fun toggleLoop() = isLoopMode.postValue(!(isLoopMode.value ?: false))
}