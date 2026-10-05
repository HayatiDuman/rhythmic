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
    private val suggestionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var suggestionRunnable: Runnable? = null
    private var suggestionAdapter: SearchHistoryAdapter? = null
    private var isSelectingSuggestion = false // 🎯 Öneriye tıklandığında TextWatcher'ı susturan bayrak
    private val downloadCancelFlags = mutableMapOf<String, Boolean>()
    private val activeDownloadThreads = mutableMapOf<String, Thread>()

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

        requireActivity().window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

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

    override fun onDestroyView() {
        suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }
        historyPopup?.dismiss()
        historyPopup = null
        super.onDestroyView()
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
            },
            downloadCancelFlags = downloadCancelFlags
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

        // setupListeners() içine ekle:
        // setupListeners() içindeki TextWatcher:
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Eğer kullanıcı bir öneriye tıklayıp kutuyu doldurduysa arama önerisi çalıştırma
                if (isSelectingSuggestion) {
                    return
                }

                val query = s?.toString()?.trim().orEmpty()

                suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }

                if (query.length >= 2) {
                    suggestionRunnable = Runnable {
                        fetchSuggestions(query)
                    }
                    suggestionHandler.postDelayed(suggestionRunnable!!, 300)
                } else if (query.isEmpty() && searchInput.hasFocus()) {
                    showSearchHistory()
                } else {
                    dismissPopup()
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 🎯 ÇÖZÜM: Şarkı listesine (RecyclerView) dokunulduğunda veya kaydırıldığında klavyeyi kapat
        resultList.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (searchInput.hasFocus()) {
                    hideKeyboard()
                }
            }
            false
        }

        // 🎯 ÇÖZÜM: Fragment'ın ana arka planına dokunulduğunda klavyeyi kapat
        view?.setOnClickListener {
            if (searchInput.hasFocus()) {
                hideKeyboard()
            }
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

    private fun getAvailableHeightAboveKeyboard(): Int {
        val view = view ?: return dpToPx(220)

        // 1. Ekranın görünür alanını (klavye hariç kalan pencereyi) al
        val visibleFrame = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(visibleFrame)
        val keyboardTop = visibleFrame.bottom

        // 2. Arama kutusunun ekrandaki alt koordinatını bul
        val location = IntArray(2)
        searchInput.getLocationOnScreen(location)
        val searchInputBottom = location[1] + searchInput.height

        // 3. İkisi arasındaki kullanılabilir dikey boşluk (biraz margin bırakarak)
        val availableHeight = keyboardTop - searchInputBottom - dpToPx(12)

        // Minimum 100dp, maksimum boşluk kadar olacak şekilde sınırla
        return availableHeight.coerceAtLeast(dpToPx(100))
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
                if (!isAdded || view == null) return@runOnUiThread
                resultList.post {
                    adapter.showLoadingFooter()
                }
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
                    if (!isAdded || view == null) return@runOnUiThread
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
                    if (!isAdded || view == null) return@runOnUiThread
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

    /* -----------------------------
 * 💡 AUTOCOMPLETE / SUGGESTIONS
 * ----------------------------- */
    private fun fetchSuggestions(query: String) {
        Thread {
            try {
                val suggestionExtractor = ServiceList.YouTube.getSuggestionExtractor()
                val suggestions: List<String> = suggestionExtractor.suggestionList(query)

                requireActivity().runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    // Kullanıcı o sırada kutuyu temizlemediyse veya aramayı değiştirmediyse göster
                    if (searchInput.text.toString().trim() == query && suggestions.isNotEmpty()) {
                        showSuggestionsPopup(suggestions)
                    } else if (suggestions.isEmpty()) {
                        historyPopup?.dismiss()
                    }
                }
            } catch (_: Exception) {
                // Öneri alınamazsa popup'ı sessizce kapat
                requireActivity().runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    historyPopup?.dismiss()
                }
            }
        }.start()
    }

    private fun dismissPopup() {
        suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }
        historyPopup?.dismiss()
        historyPopup = null
        suggestionAdapter = null
    }

    private fun showSuggestionsPopup(suggestions: List<String>) {
        // 🎯 Eğer popup zaten açıksa KESİNLİKLE KAPATMA! Sadece adaptörün içini güncelle
        if (historyPopup?.isShowing == true && suggestionAdapter != null) {
            suggestionAdapter?.updateItems(suggestions)
            return
        }

        // İlk defa açılıyorsa popup'ı kur
        val popupView = layoutInflater.inflate(R.layout.popup_search_history, null)
        val rv = popupView.findViewById<RecyclerView>(R.id.rvHistory)

        val maxAvailableHeight = getAvailableHeightAboveKeyboard()

        rv.layoutParams = rv.layoutParams?.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        rv.layoutManager = LinearLayoutManager(requireContext())

        suggestionAdapter = SearchHistoryAdapter(suggestions, isSuggestion = true) { selected ->
            isSelectingSuggestion = true // TextWatcher tetiklenmesini durdur
            searchInput.setText(selected)
            searchInput.setSelection(selected.length)
            isSelectingSuggestion = false

            dismissPopup()
            triggerSearch()
        }
        rv.adapter = suggestionAdapter

        historyPopup = PopupWindow(
            popupView,
            searchInput.width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            isClippingEnabled = true
            height = maxAvailableHeight
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                suggestionAdapter = null
            }
        }

        if (searchInput.isAttachedToWindow) {
            historyPopup?.showAsDropDown(searchInput, 0, 8)
        }
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

    // Yardımcı dp çevirici
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun showSearchHistory() {
        val history = getSearchHistory()
        if (history.isEmpty()) return

        dismissPopup()

        val view = layoutInflater.inflate(R.layout.popup_search_history, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvHistory)
        val maxAvailableHeight = getAvailableHeightAboveKeyboard()

        rv.layoutParams = rv.layoutParams?.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = SearchHistoryAdapter(history, isSuggestion = false) { selected ->
            isSelectingSuggestion = true
            searchInput.setText(selected)
            searchInput.setSelection(selected.length)
            isSelectingSuggestion = false

            dismissPopup()
            triggerSearch()
        }

        historyPopup = PopupWindow(
            view,
            searchInput.width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            isClippingEnabled = true
            height = maxAvailableHeight
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        if (searchInput.isAttachedToWindow) {
            historyPopup?.showAsDropDown(searchInput, 0, 8)
        }
    }

    // -----------------------------
    // 🔥 İNDİRMEDEN DİNLEME (STREAMING - YTDLP)
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
     * ⬇️ AUDIO DOWNLOAD & CANCEL MANAGEMENT
     * ----------------------------- */
    private fun handleAudioDownload(song: MusicModel) {
        if (MusicManager.isDownloaded(song.videoId)) return

        // 🎯 KESİN ÇÖZÜM: Eğer haritada bu videoId ZATEN VARSA, şarkı şu an aktif olarak iniyordur.
        // İkinci kez basıldıysa kullanıcı %100 iptal etmek istiyordur!
        if (downloadCancelFlags.containsKey(song.videoId)) {
            // Eğer zaten iptal sürecindeyse mükerrer basışları (çift thread) engellemek için doğrudan dön
            if (downloadCancelFlags[song.videoId] == true) return

            downloadCancelFlags[song.videoId] = true
            Toast.makeText(requireContext(), "🛑 İndirme iptal ediliyor...", Toast.LENGTH_SHORT).show()
            return
        }

        // Şarkı ilk kez inmeye başlıyor: Haritada kaydı oluştur ve kilitle!
        downloadCancelFlags[song.videoId] = false
        downloadAudio(song)
    }

    private fun downloadAudio(song: MusicModel) {
        adapter.updateDownloadState(song.videoId, true, 0)

        Thread {
            var tempPath: String? = null
            val tempDir = requireContext().externalCacheDir ?: requireContext().cacheDir

            try {
                val py = Python.getInstance()
                val script = py.getModule("script")

                val progressCallback = PyObject.fromJava(
                    IntConsumer { percent ->
                        if (downloadCancelFlags[song.videoId] == true) {
                            throw InterruptedException()
                        }

                        requireActivity().runOnUiThread {
                            if (!isAdded || view == null) return@runOnUiThread
                            adapter.updateDownloadState(song.videoId, true, percent)
                        }
                    }
                )

                val cancelCallback = PyObject.fromJava(
                    java.util.concurrent.Callable<Boolean> {
                        return@Callable downloadCancelFlags[song.videoId] == true
                    }
                )

                tempPath = script.callAttr(
                    "videoyu_indir",
                    song.filePath,
                    tempDir.absolutePath,
                    progressCallback,
                    cancelCallback
                ).toString()

                if (tempPath == "CANCELLED" || downloadCancelFlags[song.videoId] == true) {
                    throw InterruptedException()
                }

                if (tempPath.startsWith("Hata:")) {
                    throw Exception(tempPath)
                }

                requireActivity().runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    adapter.updateDownloadState(song.videoId, true, 100)
                }

                val cleanTitle = song.title.cleanJunkId()
                val (artist, title) = parseArtistTitle(cleanTitle, song.artist)
                val cleanedSong = song.copy(title = title, artist = artist)

                val coverFile = File(tempDir, "cover_${System.currentTimeMillis()}.jpg")
                try {
                    if (!song.albumArtPath.isNullOrEmpty()) {
                        val url = java.net.URL(song.albumArtPath)
                        val connection = url.openConnection() as java.net.HttpURLConnection
                        connection.connect()
                        val bitmap = android.graphics.BitmapFactory.decodeStream(connection.inputStream)
                        if (bitmap != null) {
                            val out = java.io.FileOutputStream(coverFile)
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                            out.flush()
                            out.close()
                        }
                    }
                } catch (_: Exception) {}

                val ffmpegOutFile = File(tempDir, "ff_${System.currentTimeMillis()}.m4a")
                val ffmpegCommand = if (coverFile.exists()) {
                    "-i \"$tempPath\" -i \"${coverFile.absolutePath}\" " +
                            "-map 0:a -map 1:v -c:a copy -c:v mjpeg -disposition:v attached_pic " +
                            "-metadata title=\"$title\" -metadata artist=\"$artist\" " +
                            "\"${ffmpegOutFile.absolutePath}\""
                } else {
                    "-i \"$tempPath\" -metadata title=\"$title\" -metadata artist=\"$artist\" -c:a copy \"${ffmpegOutFile.absolutePath}\""
                }

                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(ffmpegCommand)

                val finalFile = if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                    saveAudioToMediaStore(cleanedSong, ffmpegOutFile)
                } else {
                    saveAudioToMediaStore(cleanedSong, File(tempPath))
                }

                try {
                    File(tempPath).delete()
                    ffmpegOutFile.delete()
                    if (coverFile.exists()) coverFile.delete()
                } catch (_: Exception) {}

                requireActivity().runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    // 🎯 BAŞARI TEMİZLİĞİ: Bayrağı kaldırarak butonun bir sonraki indirmeler için sıfırlanmasını sağla
                    downloadCancelFlags.remove(song.videoId)
                    adapter.updateDownloadState(song.videoId, false, 100)

                    MusicManager.addSongToDatabase(
                        cleanedSong.copy(filePath = finalFile.toString(), originalFileName = finalFile.name)
                    )
                    Toast.makeText(requireContext(), "✅ Şarkı İndirildi", Toast.LENGTH_SHORT).show()
                }

            } catch (e: InterruptedException) {
                try { tempPath?.let { File(it).delete() } } catch (_: Exception) {}

                requireActivity().runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    // 🎯 İPTAL TEMİZLİĞİ: Bayrağı kaldırarak butonu taze haline geri döndür
                    downloadCancelFlags.remove(song.videoId)
                    adapter.updateDownloadState(song.videoId, false, 0)
                    Toast.makeText(requireContext(), "İndirme İptal Edildi", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    // 🎯 HATA TEMİZLİĞİ: Bayrağı kaldırarak kullanıcının tekrar denemesine izin ver
                    downloadCancelFlags.remove(song.videoId)
                    adapter.updateDownloadState(song.videoId, false, 0)
                    Toast.makeText(requireContext(), "⚠️ Hata: ${e.message}", Toast.LENGTH_LONG).show()
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

    // 🎯 ÇÖZÜM 1: Kullanıcı başka bir sekmeye (Fragment) geçtiği an klavyeyi zorla kapatıyoruz
    override fun onPause() {
        super.onPause()
        hideKeyboard()
        historyPopup?.dismiss()
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

    // 🎯 ÇÖZÜM 2: Klavyeyi kapatırken arama inputunun odağını da temizliyoruz
    private fun hideKeyboard() {
        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        searchInput.clearFocus() // İmleci ve odağı kaldır
        view?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
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