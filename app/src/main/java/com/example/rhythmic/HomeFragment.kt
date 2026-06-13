package com.example.rhythmic

import SearchHistoryAdapter
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import java.io.FileInputStream
import java.util.function.IntConsumer

private const val AUDIO_BITRATE_KBPS = 192
private const val VIDEO_BITRATE_KBPS = 2500

private val PREF_SEARCH = "search_history"
private val KEY_HISTORY = "history"
private const val MAX_HISTORY = 5

class HomeFragment : Fragment() {
    /* --- UI --- */
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var statusText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var resultList: RecyclerView

    private var historyPopup: PopupWindow? = null

    /* --- Data --- */
    private lateinit var adapter: MusicAdapter
    private val searchResults = mutableListOf<MusicModel>()

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
        resultList.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupAdapter() {
        adapter = MusicAdapter(emptyList()) { song, action ->
            when (action) {
                "audio" -> handleAudioDownload(song)
                "video" -> downloadVideo(song)
                "info" -> showInfo(song)
            }
        }
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
            if (
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = searchInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    triggerSearch()
                }
                true // olayı tükettik
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
     * 🔍 SEARCH
     * ----------------------------- */

    private fun performSearch(query: String) {
        showLoading("Aranıyor...")
        searchResults.clear()

        Thread {
            try {
                val py = Python.getInstance()
                val results = py.getModule("script")
                    .callAttr("youtube_ara", query)
                    .asList()

                for (item in results) {
                    val parts = item.toString().split("|||")
                    if (parts.size < 8) continue

                    val videoId = parts[5]

                    val durationSec = durationToSeconds(parts[3])

                    val audioMb = estimateSizeMb(durationSec, AUDIO_BITRATE_KBPS)
                    val videoMb = estimateSizeMb(durationSec, VIDEO_BITRATE_KBPS)

                    searchResults.add(
                        MusicModel(
                            title = parts[0],
                            filePath = parts[1],
                            albumArtPath = parts[2],
                            durationText = parts[3],
                            artist = parts[4],
                            videoId = videoId,
                            audioSizeMb = audioMb.toString(),
                            videoSizeMb = videoMb.toString()
                        )
                    )

                }

                requireActivity().runOnUiThread {
                    hideLoading()
                    if (searchResults.isEmpty()) {
                        statusText.text = "Sonuç yok"
                    } else {
                        statusText.text = "${searchResults.size} sonuç bulundu"
                        adapter.updateList(searchResults)
                    }
                }

            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    hideLoading()
                    statusText.text = "Hata: ${e.message}"
                }
            }
        }.start()
    }

    private fun triggerSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isNotEmpty()) {
            saveSearchQuery(query)
            performSearch(query)
            hideKeyboard()
        }
    }

    private fun saveSearchQuery(query: String) {
        val prefs = requireContext().getSharedPreferences(PREF_SEARCH, Context.MODE_PRIVATE)
        val history = getSearchHistory().toMutableList()

        history.remove(query)          // aynı varsa sil
        history.add(0, query)           // başa ekle

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

        // Önce varsa eski popup'ı kapat
        historyPopup?.dismiss()

        val view = layoutInflater.inflate(R.layout.popup_search_history, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvHistory)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = SearchHistoryAdapter(history) { selected ->
            // ❗ Tıklanınca SADECE input doldurulacak
            searchInput.setText(selected)
            searchInput.setSelection(selected.length)
            historyPopup?.dismiss()
        }

        historyPopup = PopupWindow(
            view,
            searchInput.width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false // 🔥 focusable = FALSE (en önemli satır)
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            setOnDismissListener {
                // 🔒 Focus EditText’te kalsın
                searchInput.requestFocus()
            }
        }

        // 🔒 Popup açılırken klavye SABİT kalsın
        searchInput.requestFocus()
        showKeyboard()

        historyPopup?.showAsDropDown(searchInput, 0, 8)
    }


    private fun showInfo(song: MusicModel) {
        val audioMb = song.audioSizeMb ?: "?"
        val videoMb = song.videoSizeMb ?: "?"

        AlertDialog.Builder(requireContext())
            .setTitle(song.title)
            .setMessage(
                "Ses (tahmini): ~$audioMb MB\n" +
                        "Video (tahmini): ~$videoMb MB"
            )
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
                            adapter.updateDownloadState(
                                song.videoId,
                                downloading = true,
                                progress = percent
                            )
                        }
                    }
                )

                val tempPath = script.callAttr(
                    "videoyu_indir",
                    song.filePath,
                    tempDir.absolutePath,
                    "audio",
                    progressCallback
                ).toString()

                val finalFile = saveAudioToMediaStore(song, File(tempPath))

                requireActivity().runOnUiThread {
                    adapter.updateDownloadState(song.videoId, false, 100)

                    MusicManager.addSongToDatabase(
                        song
                            .copy(filePath = finalFile.toString())
                            .copy(originalFileName = finalFile.name)
                    )

                    Toast.makeText(requireContext(), "✅ İndirildi", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    adapter.updateDownloadState(song.videoId, false, 0)
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }


    private fun handleAudioDownload(song: MusicModel) {
        val locals = MusicManager.musicList
        // -------------------------------------------------
        // 1️⃣ GERÇEK videoId eşleşmesi → KESİN ENGEL
        // -------------------------------------------------
        if (
            (
                song.videoId.isNotEmpty() &&
                !song.videoId.startsWith("local_") &&
                locals.any { it.videoId == song.videoId }
            )
        ) {
            Toast.makeText(
                requireContext(),
                "Bu şarkı zaten indirilmiş",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        downloadAudio(song)
    }

    private fun saveAudioToMediaStore(song: MusicModel, tempFile: File): File {
        // 1. İsimleri Dosya Sistemi İçin Güvenli Hale Getir
        val safeTitle = song.title.sanitizeForFile()
        val safeArtist = song.artist.sanitizeForFile()

        // Akıllı isimlendirme (Çift isim engelleme)
        var baseName = if (safeTitle.startsWith(safeArtist, ignoreCase = true)) {
            safeTitle
        } else {
            "$safeArtist - $safeTitle"
        }

        /*// -------------------------------------------------------------
        // 🖼️ KAPAK RESMİNİ BUL VE GİZLİ KLASÖRE TAŞI
        // -------------------------------------------------------------
        // Python temp klasörüne "Baslik.jpg" veya "Baslik.webp" diye indirdi.
        // Ses dosyasıyla (tempFile) aynı isimdedir.
        val tempDir = tempFile.parentFile
        val rawNameWithoutExt = tempFile.nameWithoutExtension // Python'un verdiği ham isim

        // Olası uzantıları tara (.jpg, .webp, .png)
        val imageExtensions = listOf("jpg", "webp", "png")
        var foundImage: File? = null

        for (ext in imageExtensions) {
            val imgCheck = File(tempDir, "$rawNameWithoutExt.$ext")
            if (imgCheck.exists()) {
                foundImage = imgCheck
                break
            }
        }

        if (foundImage != null) {
            // Hedef: /data/user/0/com.example.../files/covers/Artist - Title.jpg
            val internalCoversDir = File(requireContext().filesDir, "covers")
            if (!internalCoversDir.exists()) internalCoversDir.mkdirs()

            val uniqueCoverName =
                "${baseName}_${song.videoId.hashCode()}.jpg"

            val finalCoverFile =
                File(internalCoversDir, uniqueCoverName)

            try {
                foundImage.copyTo(finalCoverFile, overwrite = true)
                foundImage.delete() // Temp'tekini sil
            } catch (e: Exception) { e.printStackTrace() }
        }
        // -------------------------------------------------------------*/

        // 2. Ses Dosyasını Taşı (Eski kodun aynısı)
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (!musicDir.exists()) musicDir.mkdirs()

        val finalFileName = "$baseName.m4a" // Ses dosyası
        var finalFile = File(musicDir, finalFileName)

        // Çakışma kontrolü
        if (finalFile.exists()) {
            if (!finalFile.delete()) {
                val timestamp = System.currentTimeMillis() % 1000
                // Çakışma varsa isim değişir, bu durumda kapak ismiyle eşleşmez ama
                // en azından ses dosyası kurtulur.
                // (Çok nadir bir senaryo, şimdilik göz ardı edilebilir)
                finalFile = File(musicDir, "$baseName ($timestamp).m4a")
            }
        }

        tempFile.copyTo(finalFile, overwrite = true)
        try { tempFile.delete() } catch (_: Exception) {}

        return finalFile
    }

    /* -----------------------------
     * ⬇️ VIDEO DOWNLOAD
     * ----------------------------- */

    private fun downloadVideo(song: MusicModel) {
        Toast.makeText(requireContext(), "Video indiriliyor...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val py = Python.getInstance()
                val script = py.getModule("script")

                val cachePath =
                    requireContext().externalCacheDir?.absolutePath
                        ?: requireContext().cacheDir.absolutePath

                val tempPath =
                    script.callAttr("videoyu_indir", song.filePath, cachePath, "video")
                        .toString()

                saveVideo(File(tempPath))

                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "✅ Video kaydedildi",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveVideo(tempFile: File): Uri? {
        val resolver = requireContext().contentResolver

        val uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, tempFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Rhythmic/"
                )
            }
        )

        uri?.let {
            FileInputStream(tempFile).use { input ->
                resolver.openOutputStream(it)?.use { output ->
                    input.copyTo(output)
                }
            }
        }

        tempFile.delete()
        return uri
    }

    /* -----------------------------
     * HELPERS
     * ----------------------------- */

    /*private fun updateDownloadState(
        song: MusicModel,
        isDownloading: Boolean = false,
        progress: Int = 0
    ) {
        song.isDownloading = isDownloading
        song.downloadProgressPercent = progress
        adapter.notifyDataSetChanged()
    }*/

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
        val imm =
            requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view?.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm =
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
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
