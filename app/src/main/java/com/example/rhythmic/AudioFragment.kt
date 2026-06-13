package com.example.rhythmic

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.Collator
import java.util.Locale

class AudioFragment : Fragment() {

    /* --- UI --- */
    private lateinit var musicRecyclerView: RecyclerView
    private lateinit var titleText: TextView
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var noResultText: TextView
    private lateinit var openSearchButton: ImageView
    private lateinit var closeSearchButton: ImageView
    private lateinit var listStatusText: TextView
    private lateinit var sortButton: ImageView

    private lateinit var adapter: LocalMusicAdapter

    // Silme işlemi sırasında geçici olarak şarkıyı tutacağız
    private var songPendingDelete: MusicModel? = null

    // Sistemden gelen "Silmeye izin veriyor musun?" penceresinin cevabını dinleyen yapı
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Kullanıcı izin verdi, şimdi veritabanından ve listeden silebiliriz
            songPendingDelete?.let { song ->
                MusicManager.deleteSong(song)
                Toast.makeText(requireContext(), "Silindi", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "Silme işlemi iptal edildi", Toast.LENGTH_SHORT).show()
        }
        songPendingDelete = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_audio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecyclerView()
        setupListeners()
        observeMusic()
        setupSearch()
        updateUI()
    }

    /* --- Setup --- */
    private fun bindViews(view: View) {
        musicRecyclerView = view.findViewById(R.id.rvLocalMusic)
        titleText = view.findViewById(R.id.tvBaslikLocal)
        searchContainer = view.findViewById(R.id.searchLayout)
        searchInput = view.findViewById(R.id.etSearchLocal)
        noResultText = view.findViewById(R.id.tvNoResult)
        openSearchButton = view.findViewById(R.id.btnSearchOpen)
        closeSearchButton = view.findViewById(R.id.btnSearchClose)
        listStatusText = view.findViewById(R.id.tvListStatus)
        sortButton = view.findViewById(R.id.btnSort)
    }

    private fun setupRecyclerView() {
        musicRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupListeners() {
        openSearchButton.setOnClickListener { toggleSearch(true) }
        closeSearchButton.setOnClickListener { toggleSearch(false) }
        sortButton.setOnClickListener { showSortMenu(it) }
    }

    private fun observeMusic() {
        MusicManager.liveMusicList.observe(viewLifecycleOwner) { list ->
            listStatusText.text = "${list.size} şarkı"

            if (::adapter.isInitialized) {
                adapter.updateList(list)
            } else {
                setupAdapter(list)
            }

            toggleEmptyState(list.isEmpty())
        }

        MusicManager.currentSong.observe(viewLifecycleOwner) { song ->
            adapter.updatePlayingSong(song?.filePath)
        }
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performSearch(s.toString())
            }
        })
    }

    /* --- UI Update --- */
    private fun updateUI() {
        listStatusText.text = "${MusicManager.musicList.size} şarkı"
        setupAdapter(MusicManager.musicList)
        adapter.updatePlayingSong(MusicManager.currentSong.value?.filePath)
    }
    private fun setupAdapter(list: List<MusicModel>) {
        adapter = LocalMusicAdapter(
            musicList = list,
            onSongClick = { filePath ->
                val index = MusicManager.musicList.indexOfFirst { it.filePath == filePath }
                MusicManager.playMusic(index)
            },
            onSongLongClick = { song ->
                confirmDelete(song)
            }
        )
        musicRecyclerView.adapter = adapter
    }
    private fun toggleEmptyState(isEmpty: Boolean) {
        noResultText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        musicRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (isEmpty) listStatusText.text = "Müzik bulunamadı"
    }

    /* --- Search --- */
    private fun performSearch(query: String) {
        val searchText = query.lowercase().trim()

        if (searchText.isEmpty()) {
            adapter.updateList(MusicManager.musicList)
            toggleEmptyState(MusicManager.musicList.isEmpty())
            return
        }

        val filtered = MusicManager.musicList.filter {
            it.title.lowercase().contains(searchText) ||
                    it.artist.lowercase().contains(searchText)
        }

        if (filtered.isEmpty()) {
            adapter.updateList(emptyList())
            noResultText.text = "\"$query\"\nBulunamadı"
            toggleEmptyState(true)
        } else {
            adapter.updateList(filtered)
            toggleEmptyState(false)
        }
    }

    private fun toggleSearch(show: Boolean) {
        titleText.visibility = if (show) View.GONE else View.VISIBLE
        searchContainer.visibility = if (show) View.VISIBLE else View.GONE
        openSearchButton.visibility = if (show) View.GONE else View.VISIBLE
        closeSearchButton.visibility = if (show) View.VISIBLE else View.GONE

        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        if (show) {
            searchInput.requestFocus()
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            searchInput.text.clear()
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
            adapter.updateList(MusicManager.musicList)
            toggleEmptyState(false)
        }
    }

    /* --- Delete Logic (GÜNCELLENDİ) --- */
    private fun confirmDelete(song: MusicModel) {
        AlertDialog.Builder(requireContext())
            .setTitle("Şarkıyı Sil")
            .setMessage("${song.title}\n\nBu şarkıyı cihazdan silmek istiyor musunuz?")
            .setPositiveButton("Sil") { _, _ -> deleteSong(song) }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun deleteSong(song: MusicModel) {
        val file = File(song.filePath)

        // 1. Önce klasik yöntemle silmeyi dene (Uygulamanın kendi indirdiği dosya ise bu çalışır)
        if (file.exists() && file.delete()) {
            // Başarılıysa DB'den sil
            finishDelete(song)
            return
        }

        // 2. Eğer silinemezse (Legacy dosya), MediaStore üzerinden izin iste
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 ve üzeri
            val uri = getMediaUriFromPath(requireContext(), song.filePath)
            if (uri != null) {
                try {
                    // İşletim sisteminden silme talebi oluştur
                    val pendingIntent = MediaStore.createDeleteRequest(
                        requireContext().contentResolver,
                        listOf(uri)
                    )
                    songPendingDelete = song
                    deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "İzin hatası: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Dosya sistemde bulunamadı (MediaStore)", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Android 10 ve altı (Genelde file.delete() çalışır ama çalışmazsa buraya düşer)
            Toast.makeText(requireContext(), "Bu dosya silinemiyor (Korumalı)", Toast.LENGTH_SHORT).show()
        }
    }

    // Dosya yolundan (Path) MediaStore Uri'sini bulmaya yarayan yardımcı fonksiyon
    private fun getMediaUriFromPath(context: Context, path: String): android.net.Uri? {
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.DATA} = ?"
        val selectionArgs = arrayOf(path)

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                if (idIndex != -1) {
                    val id = cursor.getLong(idIndex)
                    return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        }
        return null
    }

    // Silme işlemi (Fiziksel silme başarılı olduktan sonra çağrılır)
    private fun finishDelete(song: MusicModel) {
        // Kapak resmini sil (Varsa)
        song.albumArtPath?.let {
            val cover = File(it)
            if (cover.exists()) cover.delete()
        }

        MusicManager.deleteSong(song)
        Toast.makeText(requireContext(), "Silindi", Toast.LENGTH_SHORT).show()
    }

    /* --- Sort --- */
    private fun showSortMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menu.add(0, 1, 0, "Yeniden Eskiye (Varsayılan)")
        popup.menu.add(0, 2, 0, "A'dan Z'ye")
        popup.menu.add(0, 3, 0, "Z'den A'ya")

        val collator = Collator.getInstance(Locale("tr", "TR"))

        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> MusicManager.musicList.sortByDescending { song -> song.dateAdded }
                2 -> MusicManager.musicList.sortWith { a, b -> collator.compare(a.title, b.title) }
                3 -> MusicManager.musicList.sortWith { a, b -> collator.compare(b.title, a.title) }
            }
            adapter.updateList(MusicManager.musicList)
            true
        }
        popup.show()
    }
}