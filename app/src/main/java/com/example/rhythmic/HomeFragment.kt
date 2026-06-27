package com.example.rhythmic

import SearchHistoryAdapter
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.io.File
import java.util.function.IntConsumer
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.InfoItem
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.bumptech.glide.Glide
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels

private const val AUDIO_BITRATE_KBPS = 192

private val PREF_SEARCH = "search_history"
private val KEY_HISTORY = "history"
private const val MAX_HISTORY = 5

class HomeFragment : Fragment() {
    // Hafızayı tutan ortak ViewModel
    private val detailViewModel: SongDetailViewModel by activityViewModels()

    /* --- UI --- */
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var statusText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var resultList: RecyclerView

    private var historyPopup: PopupWindow? = null

    /* --- Data --- */
    private lateinit var adapter: MusicAdapter
    //private val searchResults = mutableListOf<MusicModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecyclerView()
        setupAdapter()
        setupListeners()

        // Arama kutusunu geri doldur ve imleci sona al
        if (SearchManager.currentQuery.isNotEmpty()) {
            searchInput.setText(SearchManager.currentQuery)
            searchInput.setSelection(SearchManager.currentQuery.length)
        }

        // Listeyi geri doldur
        if (SearchManager.searchResults.isNotEmpty()) {
            adapter.updateList(SearchManager.searchResults)
            statusText.text = "${SearchManager.searchResults.size} sonuç bulundu"
        } else if (SearchManager.isSearchLoading) {
            showLoading("Aranıyor...")
        }
    }

    /* -----------------------------
     * SETUP
     * ----------------------------- */
    private fun bindViews(view: View) {
        searchInput = view.findViewById(R.id.etArama)
        searchButton = view.findViewById(R.id.btnAra)
        statusText = view.findViewById(R.id.tvDurum)
        loadingBar = view.findViewById(R.id.progressBar)
        resultList = view.findViewById(R.id.rvListe)
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(requireContext())
        resultList.layoutManager = layoutManager

        resultList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && !SearchManager.isSearchLoading && SearchManager.hasMoreResults) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 3) {
                        loadNextPage()
                    }
                }
            }
        })
    }

    private fun setupAdapter() {
        adapter = MusicAdapter(
            musicList = emptyList(),
            onActionClick = { song, action ->
                when (action) {
                    "audio" -> handleAudioDownload(song)
                    "info" -> showRichInfo(song) // YENİ FONKSİYON
                    //"play" -> playStream(song)
                }
            },
            onRetryClick = {
                adapter.showLoadingFooter()
                performSearch(SearchManager.currentQuery, isLoadMore = true)
            }
        )
        resultList.adapter = adapter
    }

    private fun setupListeners() {
        searchButton.setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isNotEmpty()) {
                triggerSearch()
            }
        }
        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = searchInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    triggerSearch()
                }
                true
            } else {
                false
            }
        }
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showSearchHistory()
            }
        }
        searchInput.setOnClickListener {
            showSearchHistory()
        }
    }

    /* -----------------------------
     * 🔍 SEARCH & PAGINATION
     * ----------------------------- */
    private fun triggerSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isNotEmpty()) {
            saveSearchQuery(query)
            SearchManager.currentQuery = query
            SearchManager.hasMoreResults = true
            performSearch(query, isLoadMore = false)
            hideKeyboard()
        }
    }

    private fun loadNextPage() {
        performSearch(SearchManager.currentQuery, isLoadMore = true)
    }

    private fun performSearch(query: String, isLoadMore: Boolean) {
        SearchManager.isSearchLoading = true

        if (!isLoadMore) {
            showLoading("Aranıyor...")
            SearchManager.searchResults.clear()
            adapter.updateList(emptyList())

            // Yeni aramada sayfalama durumunu sıfırla
            SearchManager.nextPage = null
            SearchManager.searchExtractor = null
        } else {
            requireActivity().runOnUiThread {
                adapter.showLoadingFooter()
            }
        }

        Thread {
            try {
                val newItems = mutableListOf<MusicModel>()

                if (!isLoadMore) {
                    // 1. İLK ARAMA (First Page)
                    // Sadece YouTube üzerinden arama yapacak şekilde motoru çağırıyoruz
                    SearchManager.searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                    SearchManager.searchExtractor?.fetchPage()

                    val initialPage = SearchManager.searchExtractor?.initialPage
                    val items = initialPage?.items ?: emptyList()
                    SearchManager.nextPage = initialPage?.nextPage // Sonraki sayfanın biletini sakla

                    newItems.addAll(mapNewPipeItems(items))
                } else {
                    // 2. SONRAKİ SAYFALAR (Pagination / Infinite Scroll)
                    if (SearchManager.nextPage != null && SearchManager.searchExtractor != null) {
                        val pageResult = SearchManager.searchExtractor?.getPage(SearchManager.nextPage)
                        val items = pageResult?.items ?: emptyList()
                        SearchManager.nextPage = pageResult?.nextPage // Bir sonraki sayfanın biletini güncelle

                        newItems.addAll(mapNewPipeItems(items))
                    }
                }

                requireActivity().runOnUiThread {
                    SearchManager.isSearchLoading = false
                    if (!isLoadMore) hideLoading()

                    if (newItems.isEmpty()) {
                        SearchManager.hasMoreResults = false
                        adapter.hideFooter()
                        if (!isLoadMore) statusText.text = "Sonuç yok"
                    } else {
                        SearchManager.searchResults.addAll(newItems)
                        statusText.text = "${SearchManager.searchResults.size} sonuç bulundu"
                        adapter.updateList(SearchManager.searchResults.toList())

                        // Eğer YouTube "Daha fazla sonuç yok" dediyse (nextPage null ise)
                        if (SearchManager.nextPage == null) {
                            SearchManager.hasMoreResults = false
                            adapter.hideFooter()
                        }
                    }
                }

            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    SearchManager.isSearchLoading = false
                    if (!isLoadMore) {
                        hideLoading()
                        statusText.text = "Hata: ${e.message}"
                    } else {
                        adapter.showErrorFooter(e.message ?: "Bağlantı hatası")
                    }
                }
            }
        }.start()
    }

    // 🔥 YENİ: NewPipe verilerini MusicModel'e çeviren dönüştürücü
    private fun mapNewPipeItems(items: List<InfoItem>): List<MusicModel> {
        val result = mutableListOf<MusicModel>()

        for (item in items) {
            // Sadece videoları/şarkıları al (Kanal veya oynatma listesi sonuçlarını atla)
            if (item is StreamInfoItem) {

                val durationSec = item.duration.toInt()
                if (durationSec <= 0) continue // Süresi olmayan canlı yayınları atla

                // Süreyi MM:SS veya HH:MM:SS formatına çevir
                val dk = durationSec / 60
                val sn = durationSec % 60
                val sureStr = if (durationSec >= 3600) {
                    val sa = durationSec / 3600
                    val kalanDk = (durationSec % 3600) / 60
                    String.format("%d:%02d:%02d", sa, kalanDk, sn)
                } else {
                    String.format("%d:%02d", dk, sn)
                }

                val audioMb = estimateSizeMb(durationSec, AUDIO_BITRATE_KBPS).toString()

                // YouTube ID'sini URL'den ayıkla
                val videoId = item.url.replace("https://www.youtube.com/watch?v=", "")

                // 🔥 HATA VEREN KISIM DÜZELTİLDİ
                // Kapak resimlerinin olduğu listeden ilkini alıyoruz, liste boşsa boş string atıyoruz
                val kapakUrl = item.thumbnails.lastOrNull()?.url ?: ""

                result.add(
                    MusicModel(
                        title = item.name,
                        filePath = item.url,
                        albumArtPath = kapakUrl, // Artık hata vermeyecek
                        durationText = sureStr,
                        artist = item.uploaderName,
                        videoId = videoId,
                        audioSizeMb = audioMb,
                        videoSizeMb = "0"
                    )
                )
            }
        }
        return result
    }

    private fun saveSearchQuery(query: String) {
        val prefs = requireContext().getSharedPreferences(PREF_SEARCH, Context.MODE_PRIVATE)
        val history = getSearchHistory().toMutableList()

        history.remove(query)
        history.add(0, query)

        if (history.size > MAX_HISTORY) {
            history.removeAt(history.lastIndex)
        }

        prefs.edit()
            .putString(KEY_HISTORY, history.joinToString("|||"))
            .apply()
    }

    private fun getSearchHistory(): List<String> {
        val prefs = requireContext().getSharedPreferences(PREF_SEARCH, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|||")
    }

    private fun showSearchHistory() {
        val history = getSearchHistory()
        if (history.isEmpty()) return

        historyPopup?.dismiss()

        val view = layoutInflater.inflate(R.layout.popup_search_history, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvHistory)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = SearchHistoryAdapter(history) { selected ->
            searchInput.setText(selected)
            searchInput.setSelection(selected.length)
            historyPopup?.dismiss()
        }

        historyPopup = PopupWindow(
            view,
            searchInput.width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            setOnDismissListener {
                searchInput.requestFocus()
            }
        }

        searchInput.requestFocus()
        showKeyboard()
        historyPopup?.showAsDropDown(searchInput, 0, 8)
    }

    // -----------------------------
    // 🔥 İNDİRMEDEN DİNLEME (STREAMING - YTDLP)
    // -----------------------------
    /*private fun playStream(song: MusicModel) {
        // HATA SEBEBİ: showLoading() kullanıldığında resultList tamamen gizleniyordu (Sayfa yenilenme hissi).
        // ÇÖZÜM: Listeyi bozmadan sadece progress bar ve bir Toast mesajı gösteriyoruz.
        requireActivity().runOnUiThread {
            loadingBar.visibility = View.VISIBLE
            Toast.makeText(requireContext(), "Bağlantı çözümleniyor...", Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                // Chaquopy üzerinden Python scriptimize bağlanıyoruz
                val py = Python.getInstance()
                val script = py.getModule("script")

                // Python'daki get_stream_url fonksiyonunu çağırıyoruz
                val streamUrl = script.callAttr("get_stream_url", song.filePath).toString()

                if (streamUrl.startsWith("Hata:")) {
                    throw Exception(streamUrl)
                }

                requireActivity().runOnUiThread {
                    loadingBar.visibility = View.GONE

                    // Python'dan gelen saf yayın (stream) adresini geçici şarkımıza ekliyoruz
                    val streamSong = song.copy(filePath = streamUrl)

                    // Oynatma listesinin en başına ekleyip başlatıyoruz
                    MusicManager.musicList.add(0, streamSong)
                    MusicManager.playMusic(0)

                    Toast.makeText(requireContext(), "🎵 Oynatılıyor: ${song.title}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                requireActivity().runOnUiThread {
                    loadingBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Oynatma hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }*/

    // -----------------------------
    // 🔥 YENİ: ZENGİN DETAYLAR VE VİDEO MENÜSÜ (BOTTOM SHEET)
    // -----------------------------
    private fun showRichInfo(song: MusicModel) {
        // Şarkı bilgilerini ViewModel'e yükle (Aynı şarkıysa önbellekten çeker)
        detailViewModel.loadDetailsIfNeeded(song)

        // Yeni tasarladığımız BottomSheet'i ekranın altından yukarı doğru aç
        val bottomSheet = MusicDetailBottomSheet()
        bottomSheet.show(parentFragmentManager, "MusicDetailBottomSheet")
    }

    private fun showInfo(song: MusicModel) {
        val audioMb = song.audioSizeMb ?: "?"

        AlertDialog.Builder(requireContext())
            .setTitle(song.title)
            .setMessage("Dosya Boyutu (Tahmini): ~$audioMb MB")
            .setPositiveButton("Tamam", null)
            .show()
    }

    /* -----------------------------
     * ⬇️ AUDIO DOWNLOAD
     * ----------------------------- */

    private fun downloadAudio(song: MusicModel) {
        adapter.updateDownloadState(song.videoId, true, 0)

        Thread {
            try {
                val py = Python.getInstance()
                val script = py.getModule("script")
                val tempDir = requireContext().externalCacheDir ?: requireContext().cacheDir

                val progressCallback = PyObject.fromJava(
                    IntConsumer { percent ->
                        requireActivity().runOnUiThread {
                            adapter.updateDownloadState(song.videoId, true, percent)
                        }
                    }
                )

                // 1. Python, sesi ham haliyle indirir (ffprobe/ffmpeg'e ihtiyaç duymaz)
                val tempPath = script.callAttr(
                    "videoyu_indir",
                    song.filePath,
                    tempDir.absolutePath,
                    progressCallback
                ).toString()

                if (tempPath.startsWith("Hata:")) {
                    throw Exception(tempPath)
                }

                // Başlık ve Sanatçı dosya ismi için temizlenir
                val cleanTitle = song.title.cleanJunkId()
                val (artist, title) = parseArtistTitle(cleanTitle, song.artist)
                val cleanedSong = song.copy(title = title, artist = artist)

                // 🔥 1.5: KAPAK FOTOĞRAFINI KOTLİN İLE İNDİR
                val coverFile = File(tempDir, "cover_${System.currentTimeMillis()}.jpg")
                try {
                    // HATA VEREN KISIM DÜZELTİLDİ: isNotEmpty() yerine isNullOrEmpty() kullanıyoruz
                    if (!song.albumArtPath.isNullOrEmpty()) {
                        val url = java.net.URL(song.albumArtPath)
                        val connection = url.openConnection() as java.net.HttpURLConnection
                        connection.doInput = true
                        connection.connect()
                        val input = connection.inputStream

                        // Android BitmapFactory, WebP gibi formatları otomatik okur
                        val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                        if (bitmap != null) {
                            val out = java.io.FileOutputStream(coverFile)
                            // FFmpeg'in en sevdiği format olan JPEG'e çevirip kaydediyoruz
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                            out.flush()
                            out.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Cover_Download", "Kapak indirilemedi: ${e.message}")
                }

                // 2. FFmpeg-Kit ile Metadata ve Kapak Gömme İşlemi
                val ffmpegOutFile = File(tempDir, "ff_${System.currentTimeMillis()}.m4a")

                // FFmpeg Komutu: Resim başarıyla indirildiyse ikisini birleştir, aksi halde sadece text metadata ekle
                val ffmpegCommand = if (coverFile.exists()) {
                    "-i \"$tempPath\" -i \"${coverFile.absolutePath}\" " +
                            "-map 0:a -map 1:v -c:a copy -c:v mjpeg -disposition:v attached_pic " +
                            "-metadata title=\"$title\" -metadata artist=\"$artist\" " +
                            "\"${ffmpegOutFile.absolutePath}\""
                } else {
                    "-i \"$tempPath\" -metadata title=\"$title\" -metadata artist=\"$artist\" -c:a copy \"${ffmpegOutFile.absolutePath}\""
                }

                Log.d("FFmpeg_Execution", "Komut çalıştırılıyor: $ffmpegCommand")

                // İşlemi FFmpeg-Kit ile senkronize olarak çalıştırıyoruz
                val session = FFmpegKit.execute(ffmpegCommand)
                val returnCode = session.returnCode

                val finalFile: File
                if (ReturnCode.isSuccess(returnCode)) {
                    Log.d("FFmpeg_Success", "Metadata ve kapak başarıyla gömüldü.")
                    finalFile = saveAudioToMediaStore(cleanedSong, ffmpegOutFile)
                    // Geçici dosyaları temizle (Kapak dosyasını da siliyoruz)
                    try {
                        File(tempPath).delete()
                        ffmpegOutFile.delete()
                        if (coverFile.exists()) coverFile.delete()
                    } catch (_: Exception) {}
                } else {
                    Log.e("FFmpeg_Fail", "FFmpeg başarısız oldu: ${session.failStackTrace}")
                    finalFile = saveAudioToMediaStore(cleanedSong, File(tempPath))
                }

                // 3. UI Güncelleme ve Veritabanı Kaydı
                requireActivity().runOnUiThread {
                    adapter.updateDownloadState(song.videoId, false, 100)

                    MusicManager.addSongToDatabase(
                        cleanedSong
                            .copy(filePath = finalFile.toString())
                            .copy(originalFileName = finalFile.name)
                    )

                    Toast.makeText(requireContext(), "✅ Şarkı İndirildi", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    adapter.updateDownloadState(song.videoId, false, 0)
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun parseArtistTitle(cleanName: String, originalArtist: String): Pair<String, String> {
        val ARTIST_TITLE_REGEX = Regex("\\s*(?:-|—|–|\\||｜)\\s*")
        val parts = cleanName.split(ARTIST_TITLE_REGEX).map { it.trim() }

        if (parts.size >= 3 && parts[0].equals(parts[1], true)) {
            return parts[0] to parts.subList(2, parts.size).joinToString(" - ")
        }
        if (parts.size >= 2) {
            val artist = parts[0]
            if (artist.length in 2..40) {
                return artist to parts.subList(1, parts.size).joinToString(" - ")
            }
        }
        return originalArtist to cleanName
    }

    private fun handleAudioDownload(song: MusicModel) {
        val locals = MusicManager.musicList
        if (
            (song.videoId.isNotEmpty() &&
                    !song.videoId.startsWith("local_") &&
                    locals.any { it.videoId == song.videoId })
        ) {
            Toast.makeText(requireContext(), "Bu şarkı zaten indirilmiş", Toast.LENGTH_SHORT).show()
            return
        }
        downloadAudio(song)
    }

    private fun saveAudioToMediaStore(song: MusicModel, tempFile: File): File {
        val safeTitle = song.title.sanitizeForFile()
        val safeArtist = song.artist.sanitizeForFile()

        var baseName = if (safeTitle.startsWith(safeArtist, ignoreCase = true)) {
            safeTitle
        } else {
            "$safeArtist - $safeTitle"
        }

        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (!musicDir.exists()) musicDir.mkdirs()

        val finalFileName = "$baseName.m4a"
        var finalFile = File(musicDir, finalFileName)

        if (finalFile.exists()) {
            if (!finalFile.delete()) {
                val timestamp = System.currentTimeMillis() % 1000
                finalFile = File(musicDir, "$baseName ($timestamp).m4a")
            }
        }

        tempFile.copyTo(finalFile, overwrite = true)
        try { tempFile.delete() } catch (_: Exception) {}

        return finalFile
    }

    private fun String.cleanJunkId(): String {
        val regex = Regex("\\s*\\(\\s*[A-Za-z0-9_\\-\\s]{9,16}\\s*\\)\\s*$")
        return this.replace(regex, "").trim()
    }

    /* -----------------------------
     * HELPERS
     * ----------------------------- */

    private fun showLoading(message: String) {
        loadingBar.visibility = View.VISIBLE
        resultList.visibility = View.GONE
        statusText.text = message
    }

    private fun hideLoading() {
        loadingBar.visibility = View.GONE
        resultList.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view?.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun durationToSeconds(duration: String): Int {
        val parts = duration.split(":")
        return when (parts.size) {
            2 -> parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: 0
            3 -> parts[0].toIntOrNull()?.times(3600)
                ?.plus(parts[1].toIntOrNull()?.times(60) ?: 0)
                ?.plus(parts[2].toIntOrNull() ?: 0) ?: 0
            else -> 0
        }
    }

    private fun estimateSizeMb(durationSec: Int, bitrateKbps: Int): Int {
        if (durationSec <= 0) return 0
        val bits = bitrateKbps * 1000L * durationSec
        val bytes = bits / 8
        val mb = bytes / (1024 * 1024)
        return mb.toInt().coerceAtLeast(1)
    }
}

/* -----------------------------
 * EXTENSIONS
 * ----------------------------- */

private fun String.sanitizeForFile(): String =
    replace(Regex("[\\\\/:*?\"<>|]"), "_")