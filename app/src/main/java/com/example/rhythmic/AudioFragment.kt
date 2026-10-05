package com.example.rhythmic

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rhythmic.database.PlaylistEntity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.text.Collator
import java.util.Locale

class AudioFragment : Fragment() {

    /* --- UI Elemanları --- */
    private lateinit var topBar: LinearLayout
    private lateinit var tvBaslikLocal: TextView
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var btnSearchOpen: ImageView
    private lateinit var btnSearchClose: ImageView
    private lateinit var btnSort: ImageView
    private lateinit var btnToggleSelectionMode: ImageView
    private lateinit var btnSelectAll: TextView

    private lateinit var rvPlaylistsHorizontal: RecyclerView
    private lateinit var playlistSpacer: View
    private lateinit var tvListStatus: TextView
    private lateinit var layoutAddSongToPlaylist: LinearLayout

    private lateinit var rvLocalMusic: RecyclerView
    private lateinit var scrollbarOverlay: FrameLayout
    private lateinit var indicatorDot: View
    private lateinit var tvNoResult: TextView

    private lateinit var bottomActionBar: LinearLayout
    private lateinit var btnActionAddToPlaylist: LinearLayout
    private lateinit var btnActionRemoveFromPlaylist: LinearLayout
    private lateinit var btnActionDeleteSelected: LinearLayout

    /* --- Motorlar ve Çift Modlu Sistem --- */
    private lateinit var musicAdapter: LocalMusicAdapter
    private lateinit var chipAdapter: PlaylistChipAdapter

    private var activePlaylistId: Long? = null
    private var currentDisplayedSongs = ArrayList<MusicModel>()
    private var playlists = ArrayList<PlaylistEntity>()

    private var isPlaylistEditMode = false
    private var editingPlaylistId: Long? = null

    private var songsPendingDeletePool = ArrayList<MusicModel>()

    private val batchDeleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            songsPendingDeletePool.forEach { MusicManager.deleteSong(it) }
            Toast.makeText(requireContext(), "Cihazdan silindi", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
            refreshCurrentView()
        }
        songsPendingDeletePool.clear()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_audio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)
        val savedId = prefs.getLong("last_playlist_id", -1L)
        activePlaylistId = if (savedId == -1L) null else savedId

        bindViews(view)
        setupRecyclerViews()
        setupListeners()
        setupSearch()

        loadPlaylistsAndChips()
        refreshCurrentView()
        observeLiveMusic()
    }

    private fun bindViews(view: View) {
        topBar = view.findViewById(R.id.topBar)
        tvBaslikLocal = view.findViewById(R.id.tvBaslikLocal)
        searchContainer = view.findViewById(R.id.searchLayout)
        searchInput = view.findViewById(R.id.etSearchLocal)
        btnSearchOpen = view.findViewById(R.id.btnSearchOpen)
        btnSearchClose = view.findViewById(R.id.btnSearchClose)
        btnSort = view.findViewById(R.id.btnSort)
        btnToggleSelectionMode = view.findViewById(R.id.btnToggleSelectionMode)
        btnSelectAll = view.findViewById(R.id.btnSelectAll)

        rvPlaylistsHorizontal = view.findViewById(R.id.rvPlaylistsHorizontal)
        playlistSpacer = view.findViewById(R.id.playlistSpacer)
        tvListStatus = view.findViewById(R.id.tvListStatus)
        layoutAddSongToPlaylist = view.findViewById(R.id.layoutAddSongToPlaylist)
        layoutAddSongToPlaylist.visibility = View.GONE

        rvLocalMusic = view.findViewById(R.id.rvLocalMusic)
        scrollbarOverlay = view.findViewById(R.id.scrollbarOverlay)
        indicatorDot = view.findViewById(R.id.indicatorDot)
        tvNoResult = view.findViewById(R.id.tvNoResult)

        bottomActionBar = view.findViewById(R.id.bottomActionBar)
        btnActionAddToPlaylist = view.findViewById(R.id.btnActionAddToPlaylist)
        btnActionRemoveFromPlaylist = view.findViewById(R.id.btnActionRemoveFromPlaylist)
        btnActionDeleteSelected = view.findViewById(R.id.btnActionDeleteSelected)
    }

    private fun setupRecyclerViews() {
        rvPlaylistsHorizontal.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        chipAdapter = PlaylistChipAdapter()
        rvPlaylistsHorizontal.adapter = chipAdapter

        rvLocalMusic.layoutManager = LinearLayoutManager(requireContext())
        musicAdapter = LocalMusicAdapter(
            musicList = emptyList(),
            onSongClick = { filePath ->
                val index = MusicManager.musicList.indexOfFirst { it.filePath == filePath }
                if (index != -1) MusicManager.playMusic(index)
            },
            onSongLongClick = { _ ->
                // 🎯 İSTEK UYARINCA: Şarkılarda kazara açılmayı önlemek için UZUN BASMA SEÇİMİ TAMAMEN KALDIRILDI!
            }
        )
        musicAdapter.setOnSelectionChangedListener { updateSelectionCountHeader() }
        rvLocalMusic.adapter = musicAdapter

        rvLocalMusic.viewTreeObserver.addOnScrollChangedListener {
            if (MusicManager.currentSong.value != null) {
                updateIndicatorDotPosition()
                indicatorDot.alpha = 1f
                indicatorDot.animate().alpha(0f).setStartDelay(1000).setDuration(300).start()
            }
        }
    }

    private fun loadPlaylistsAndChips() {
        playlists.clear()
        playlists.addAll(MusicManager.getPlaylists())
        if (activePlaylistId != null && playlists.none { it.playlistId == activePlaylistId }) {
            activePlaylistId = null
        }
        chipAdapter.notifyDataSetChanged()
    }

    private fun refreshCurrentView() {
        // 🎯 KORUMA KALKANI 2: Eğer zaten seçim/düzenleme modundaysak, bu fonksiyonun
        // listeyi veritabanından sıfırlayıp kullanıcının seçtiklerini kaybettirmesini önlüyoruz.
        if (::musicAdapter.isInitialized && musicAdapter.getSelectionMode()) {
            // Sadece başlığı ve adetleri güncelle, listeye dokunma!
            updateSelectionCountHeader()
            return
        }

        requireContext().getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)
            .edit().putLong("last_playlist_id", activePlaylistId ?: -1L).apply()

        currentDisplayedSongs.clear()

        if (activePlaylistId == null) {
            // A) "HEPSİ" SEKMEDEYSEK (Motorun master kütüphanesini kopyala)
            currentDisplayedSongs.addAll(MusicManager.masterMusicList)
            tvBaslikLocal.text = "Şarkılarım"
            btnToggleSelectionMode.setImageResource(R.drawable.baseline_library_add_check_24)
            MusicManager.setTemporaryPlaylist(MusicManager.masterMusicList)
        } else {
            // B) ÖZEL BİR OYNATMA LİSTESİNDEYSEK
            val pName = playlists.find { it.playlistId == activePlaylistId }?.playlistName ?: "Oynatma Listesi"
            currentDisplayedSongs.addAll(MusicManager.getSongsFromPlaylist(activePlaylistId!!))
            tvBaslikLocal.text = pName
            btnToggleSelectionMode.setImageResource(android.R.drawable.ic_menu_edit)
            if (currentDisplayedSongs.isNotEmpty()) {
                MusicManager.setTemporaryPlaylist(currentDisplayedSongs)
            }
        }

        // Bu oynatma listesi için hafızaya alınmış sıralama türünü oku
        val sortPrefs = requireContext().getSharedPreferences("RhythmicPrefs_Sort", Context.MODE_PRIVATE)
        val currentPlaylistKey = "sort_state_${activePlaylistId ?: "null"}"
        val savedSortType = sortPrefs.getInt(currentPlaylistKey, 1) // Varsayılan: 1 (Yeniden eskiye)

        // Şarkıları hafızadaki türe göre sırala ve adaptörü tetikle
        applySortAndRefresh(savedSortType)

        tvListStatus.text = "${currentDisplayedSongs.size} şarkı"
        currentDisplayedSongs.forEach { it.isSelected = false }

        toggleEmptyState(currentDisplayedSongs.isEmpty())
        chipAdapter.notifyDataSetChanged()
    }

    private fun observeLiveMusic() {
        MusicManager.liveMusicList.observe(viewLifecycleOwner) {
            if (activePlaylistId == null) refreshCurrentView()
        }
        MusicManager.currentSong.observe(viewLifecycleOwner) { song ->
            musicAdapter.updatePlayingSong(song?.filePath)
            updateIndicatorDotPosition()
        }
    }

    private fun setupListeners() {
        btnSearchOpen.setOnClickListener { toggleSearch(true) }
        btnSearchClose.setOnClickListener { toggleSearch(false) }
        btnSort.setOnClickListener { showSortMenu(it) }

        btnToggleSelectionMode.setOnClickListener {
            if (musicAdapter.getSelectionMode()) {
                exitSelectionMode()
            } else {
                if (activePlaylistId != null) {
                    startPlaylistEditMode(activePlaylistId!!)
                } else {
                    enterSelectionMode()
                }
            }
        }

        var isAllSelected = false
        btnSelectAll.setOnClickListener {
            isAllSelected = !isAllSelected
            musicAdapter.selectAllSongs(isAllSelected)
            btnSelectAll.text = if (isAllSelected) "Seçimi Kaldır" else "Tümünü Seç"
        }

        // AKILLI ALT BAR: Ekle / Kaydet
        btnActionAddToPlaylist.setOnClickListener {
            val selected = musicAdapter.getSelectedSongs()

            if (isPlaylistEditMode) {
                MusicManager.removeSongsFromPlaylist(editingPlaylistId!!, MusicManager.musicList)
                MusicManager.addSongsToPlaylist(editingPlaylistId!!, selected)
                Toast.makeText(requireContext(), "Liste başarıyla güncellendi", Toast.LENGTH_SHORT).show()

                activePlaylistId = editingPlaylistId
                isPlaylistEditMode = false
                editingPlaylistId = null

                musicAdapter.setSelectionMode(false)
                bottomActionBar.visibility = View.GONE
                btnSelectAll.visibility = View.GONE
                rvPlaylistsHorizontal.visibility = View.VISIBLE
                refreshCurrentView()
            } else {
                if (selected.isEmpty()) return@setOnClickListener
                // 🎯 İSTEK UYARINCA: Eski düz liste silindi, şarkı eklerken de bizim premium penceremiz açılıyor!
                openManagePlaylistsDialog(isRoutingForAdding = true, songsToAdd = selected)
            }
        }

        btnActionDeleteSelected.setOnClickListener {
            val selected = musicAdapter.getSelectedSongs()
            if (selected.isEmpty()) return@setOnClickListener
            confirmBatchDelete(selected)
        }

        btnActionRemoveFromPlaylist.setOnClickListener {
            if (isPlaylistEditMode) return@setOnClickListener
            val targetId = activePlaylistId ?: editingPlaylistId
            if (targetId != null) {
                musicAdapter.setSelectionMode(false)
                startPlaylistEditMode(targetId)
            }
        }
    }

    private fun startPlaylistEditMode(playlistId: Long) {
        isPlaylistEditMode = true
        editingPlaylistId = playlistId

        activePlaylistId = null
        refreshCurrentView()

        val existingSongIds = MusicManager.getSongsFromPlaylist(playlistId).map { it.videoId }

        currentDisplayedSongs.forEach { song ->
            song.isSelected = existingSongIds.contains(song.videoId)
        }

        musicAdapter.updateList(currentDisplayedSongs)
        musicAdapter.notifyDataSetChanged()

        enterSelectionMode()
    }

    private fun exitSelectionMode() {
        if (isPlaylistEditMode) {
            activePlaylistId = editingPlaylistId
            isPlaylistEditMode = false
            editingPlaylistId = null
        }

        currentDisplayedSongs.forEach { it.isSelected = false }
        musicAdapter.updateList(currentDisplayedSongs)

        musicAdapter.setSelectionMode(false)
        bottomActionBar.visibility = View.GONE
        btnSelectAll.visibility = View.GONE
        rvPlaylistsHorizontal.visibility = View.VISIBLE
        btnSearchOpen.visibility = View.VISIBLE
        btnSort.visibility = View.VISIBLE

        refreshCurrentView()
    }

    private fun enterSelectionMode() {
        musicAdapter.setSelectionMode(true)
        bottomActionBar.visibility = View.VISIBLE
        btnSelectAll.visibility = View.VISIBLE
        btnSelectAll.text = "Tümünü Seç"
        btnToggleSelectionMode.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        btnSearchOpen.visibility = View.GONE
        btnSort.visibility = View.GONE
        rvPlaylistsHorizontal.visibility = View.GONE
        playlistSpacer.visibility = View.VISIBLE

        val tvAdd = btnActionAddToPlaylist.getChildAt(1) as TextView
        val ivAdd = btnActionAddToPlaylist.getChildAt(0) as ImageView

        val tvRemove = btnActionRemoveFromPlaylist.getChildAt(1) as TextView
        val ivRemove = btnActionRemoveFromPlaylist.getChildAt(0) as ImageView

        if (isPlaylistEditMode) {
            btnActionRemoveFromPlaylist.visibility = View.GONE
            btnActionDeleteSelected.visibility = View.GONE

            tvAdd.text = "Listeyi Kaydet"
            tvAdd.setTextColor(Color.parseColor("#38BDF8"))
            ivAdd.setImageResource(android.R.drawable.ic_menu_save)
            ivAdd.setColorFilter(Color.parseColor("#38BDF8"))
        } else {
            btnActionDeleteSelected.visibility = View.VISIBLE

            val defaultColor = android.util.TypedValue().apply { requireContext().theme.resolveAttribute(android.R.attr.textColorPrimary, this, true) }.data
            val secondaryColor = android.util.TypedValue().apply { requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, this, true) }.data

            tvAdd.text = "Listeye Ekle"
            tvAdd.setTextColor(defaultColor)
            ivAdd.setImageResource(android.R.drawable.ic_menu_add)
            ivAdd.setColorFilter(secondaryColor)

            if (activePlaylistId != null) {
                btnActionRemoveFromPlaylist.visibility = View.VISIBLE
                tvRemove.text = "Listeyi Düzenle"
                tvRemove.setTextColor(Color.parseColor("#38BDF8"))
                ivRemove.setImageResource(android.R.drawable.ic_menu_edit)
                ivRemove.setColorFilter(Color.parseColor("#38BDF8"))
            } else {
                btnActionRemoveFromPlaylist.visibility = View.GONE
            }
        }

        updateSelectionCountHeader()
    }

    private fun updateSelectionCountHeader() {
        val count = musicAdapter.getSelectedSongs().size
        if (isPlaylistEditMode) {
            tvBaslikLocal.text = "$count Şarkı Seçili"
        } else {
            tvBaslikLocal.text = "$count Seçildi"
        }
    }

    /* --- AKILLI PENCERELER (DIALOGS) --- */

    // 🌟 Tam Ekran Çoklu Seçim Destekli Gelişmiş Yönlendirme ve Yönetim Paneli
    private fun openManagePlaylistsDialog(isRoutingForAdding: Boolean = false, songsToAdd: List<MusicModel>? = null) {
        val bottomSheet = BottomSheetDialog(requireContext(), com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.dialog_manage_playlists, null)
        bottomSheet.setContentView(view)

        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        val tvDialogTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val btnDialogToggleSelectionMode = view.findViewById<ImageView>(R.id.btnDialogToggleSelectionMode)
        val rvGrid = view.findViewById<RecyclerView>(R.id.rvDialogPlaylistsGrid)
        val dialogBottomActionBar = view.findViewById<LinearLayout>(R.id.dialogBottomActionBar)
        val btnDialogDeleteSelected = view.findViewById<LinearLayout>(R.id.btnDialogDeleteSelected)

        // 🔥 ÇÖZÜM: Kapsam hatasını engellemek için görünümleri doğrudan burada, fonksiyonun ana gövdesinde tanımlıyoruz
        val tvDialogActionText = btnDialogDeleteSelected.getChildAt(1) as TextView
        val ivDialogActionIcon = btnDialogDeleteSelected.getChildAt(0) as ImageView

        rvGrid.layoutManager = GridLayoutManager(requireContext(), 2)

        var isDialogSelectionMode = isRoutingForAdding
        val selectedPlaylists = mutableSetOf<Long>()
        var dialogAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

        fun getThemeAttrColor(attr: Int): Int {
            val typedValue = TypedValue()
            requireContext().theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }

        val colorSurface = getThemeAttrColor(com.google.android.material.R.attr.colorSurface)
        val colorSecondary = getThemeAttrColor(com.google.android.material.R.attr.colorSecondary)
        val colorOnPrimary = getThemeAttrColor(com.google.android.material.R.attr.colorOnPrimary)
        val textColorPrimary = getThemeAttrColor(android.R.attr.textColorPrimary)
        val textColorSecondary = getThemeAttrColor(android.R.attr.textColorSecondary)

        if (isRoutingForAdding) {
            btnDialogToggleSelectionMode.visibility = View.GONE
            tvDialogTitle.text = "Listeleri Seçin (0)"
        }

        // Arayüz durumunu güncelleyen akıllı fonksiyon (Artık değişkenlere erişimi tam)
        fun updateDialogViews() {
            if (isRoutingForAdding) {
                // 🎯 ŞARKI EKLEME AKIŞI
                dialogBottomActionBar.visibility = if (selectedPlaylists.isNotEmpty()) View.VISIBLE else View.GONE

                tvDialogTitle.text = "Listeleri Seçin (${selectedPlaylists.size})"
                tvDialogActionText.text = "Seçili Listelere Ekle"
                tvDialogActionText.setTextColor(colorSecondary)
                ivDialogActionIcon.setImageResource(android.R.drawable.ic_menu_add)
                ivDialogActionIcon.setColorFilter(colorSecondary)
            } else {
                // 🎯 NORMAL YÖNETİM AKIŞI (Silme Modu)
                if (isDialogSelectionMode) {
                    dialogBottomActionBar.visibility = View.VISIBLE
                    tvDialogTitle.text = "${selectedPlaylists.size} Liste Seçildi"
                    btnDialogToggleSelectionMode.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                } else {
                    dialogBottomActionBar.visibility = View.GONE
                    tvDialogTitle.text = "Oynatma Listeleri"
                    btnDialogToggleSelectionMode.setImageResource(R.drawable.baseline_library_add_check_24)
                    selectedPlaylists.clear()
                }

                tvDialogActionText.text = "Seçilen Listeleri Sil"
                tvDialogActionText.setTextColor(Color.parseColor("#800020")) // Bordo renk sabitlemesi
                ivDialogActionIcon.setImageResource(android.R.drawable.ic_menu_delete)
                ivDialogActionIcon.setColorFilter(Color.parseColor("#800020"))
            }
            dialogAdapter?.notifyDataSetChanged()
        }

        dialogAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val cardView = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_card, parent, false)
                return object : RecyclerView.ViewHolder(cardView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val cardView = holder.itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.playlistCardView)
                val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvCardTitle)
                val tvCount = holder.itemView.findViewById<TextView>(R.id.tvCardCount)

                if (position == 0) {
                    tvTitle.text = "Tüm Şarkılar"
                    tvTitle.setTextColor(if (isRoutingForAdding) textColorSecondary else textColorPrimary)
                    tvCount.text = "${MusicManager.musicList.size} Şarkı"
                    tvCount.setTextColor(colorSecondary)
                    cardView.setCardBackgroundColor(colorSurface)

                    holder.itemView.setOnClickListener {
                        if (!isDialogSelectionMode && !isRoutingForAdding) {
                            activePlaylistId = null
                            refreshCurrentView()
                            bottomSheet.dismiss()
                        }
                    }
                }
                else if (position == 1) {
                    tvTitle.text = "Yeni Liste"
                    tvTitle.setTextColor(Color.parseColor("#38BDF8"))
                    tvCount.text = "+ Oluştur"
                    tvCount.setTextColor(textColorSecondary)
                    cardView.setCardBackgroundColor(colorSurface)

                    holder.itemView.setOnClickListener {
                        // 🎯 YENİ HAFİF VE PREMİUM PENCERE TETİKLENİYOR
                        createNewPlaylistDialog { createdName ->
                            val createdPlaylist = playlists.find { it.playlistName == createdName }
                            if (isRoutingForAdding) {
                                createdPlaylist?.let { selectedPlaylists.add(it.playlistId) }
                                updateDialogViews()
                            } else {
                                createdPlaylist?.let { activePlaylistId = it.playlistId }
                                refreshCurrentView()
                                bottomSheet.dismiss()
                            }
                        }
                    }
                }
                else {
                    val p = playlists[position - 2]
                    tvTitle.text = p.playlistName
                    val count = MusicManager.getSongsFromPlaylist(p.playlistId).size
                    tvCount.text = "$count Şarkı"

                    if (selectedPlaylists.contains(p.playlistId)) {
                        cardView.setCardBackgroundColor(colorSecondary)
                        tvTitle.setTextColor(colorOnPrimary)
                        tvCount.setTextColor(colorOnPrimary)
                    } else {
                        cardView.setCardBackgroundColor(colorSurface)
                        tvTitle.setTextColor(textColorPrimary)
                        tvCount.setTextColor(textColorSecondary)
                    }

                    holder.itemView.setOnClickListener {
                        if (isDialogSelectionMode || isRoutingForAdding) {
                            if (selectedPlaylists.contains(p.playlistId)) selectedPlaylists.remove(p.playlistId)
                            else selectedPlaylists.add(p.playlistId)
                            updateDialogViews()
                        } else {
                            activePlaylistId = p.playlistId
                            refreshCurrentView()
                            bottomSheet.dismiss()
                        }
                    }
                }
            }
            override fun getItemCount(): Int = playlists.size + 2
        }

        rvGrid.adapter = dialogAdapter
        updateDialogViews() // İlk açılış durumunu ayarla

        btnDialogToggleSelectionMode.setOnClickListener {
            isDialogSelectionMode = !isDialogSelectionMode
            updateDialogViews()
        }

        btnDialogDeleteSelected.setOnClickListener {
            if (selectedPlaylists.isEmpty()) return@setOnClickListener

            if (isRoutingForAdding && songsToAdd != null) {
                val selectedNames = playlists.filter { selectedPlaylists.contains(it.playlistId) }.map { it.playlistName }
                val namesString = selectedNames.joinToString(", ")

                AlertDialog.Builder(requireContext())
                    .setTitle("Şarkıları Ekle")
                    .setMessage("${songsToAdd.size} şarkı [ $namesString ] listelerine eklensin mi?")
                    .setPositiveButton("Ekle") { _, _ ->
                        selectedPlaylists.forEach { playlistId ->
                            MusicManager.addSongsToPlaylist(playlistId, songsToAdd)
                        }
                        Toast.makeText(requireContext(), "Şarkılar başarıyla eklendi", Toast.LENGTH_SHORT).show()

                        if (selectedPlaylists.size == 1) {
                            activePlaylistId = selectedPlaylists.first()
                        } else {
                            activePlaylistId = null
                        }

                        exitSelectionMode()
                        bottomSheet.dismiss()
                    }.setNegativeButton("İptal", null).show()

            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("Listeleri Sil")
                    .setMessage("Seçilen ${selectedPlaylists.size} oynatma listesi tamamen silinsin mi?\n(İçlerindeki müzikler cihazınızdan silinmez.)")
                    .setPositiveButton("Sil") { _, _ ->
                        selectedPlaylists.forEach { id ->
                            MusicManager.deletePlaylist(id)
                            if (activePlaylistId == id) activePlaylistId = null
                        }
                        loadPlaylistsAndChips()
                        refreshCurrentView()
                        bottomSheet.dismiss()
                        Toast.makeText(requireContext(), "Seçilen listeler başarıyla silindi", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("İptal", null).show()
            }
        }

        bottomSheet.show()
    }

    private fun createNewPlaylistDialog(onPlaylistCreated: ((String) -> Unit)? = null) {
        val bottomSheet = BottomSheetDialog(requireContext(), com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog)
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_playlist, null)
        bottomSheet.setContentView(dialogView)

        // 🔥 CRITICAL UX: Klavyenin pencereyi yukarı itmesini ve butonların görünür kalmasını zorunlu kılıyoruz
        bottomSheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val etPlaylistName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPlaylistName)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelCreate)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmCreate)

        bottomSheet.setOnShowListener {
            etPlaylistName.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etPlaylistName, InputMethodManager.SHOW_IMPLICIT)
        }

        btnCancel.setOnClickListener {
            // Klavyeyi kapat ve pencereyi gizle
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etPlaylistName.windowToken, 0)
            bottomSheet.dismiss()
        }

        btnConfirm.setOnClickListener {
            val name = etPlaylistName.text.toString().trim()
            if (name.isNotEmpty()) {
                MusicManager.createPlaylist(name)
                loadPlaylistsAndChips()

                // Klavyeyi kapat
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etPlaylistName.windowToken, 0)

                onPlaylistCreated?.invoke(name) ?: run {
                    val createdPlaylist = playlists.find { it.playlistName == name }
                    createdPlaylist?.let { activePlaylistId = it.playlistId }
                    refreshCurrentView()
                }
                bottomSheet.dismiss()
            } else {
                Toast.makeText(requireContext(), "Lütfen geçerli bir isim girin", Toast.LENGTH_SHORT).show()
            }
        }

        bottomSheet.show()
    }

    /* --- ARAÇLAR & ARAMA & SİLME --- */
    private fun confirmBatchDelete(songs: List<MusicModel>) {
        AlertDialog.Builder(requireContext())
            .setTitle("Kalıcı Olarak Sil")
            .setMessage("${songs.size} şarkı cihazdan tamamen silinecek. Onaylıyor musunuz?")
            .setPositiveButton("Sil") { _, _ -> executeBatchDelete(songs) }
            .setNegativeButton("İptal", null).show()
    }

    private fun executeBatchDelete(songs: List<MusicModel>) {
        songsPendingDeletePool.clear()
        songsPendingDeletePool.addAll(songs)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            songs.forEach { if (File(it.filePath).delete()) MusicManager.deleteSong(it) }
            exitSelectionMode()
            refreshCurrentView()
            return
        }
        val uris = songs.mapNotNull { getMediaUriFromPath(it.filePath) }
        if (uris.isNotEmpty()) {
            val pendingIntent = MediaStore.createDeleteRequest(requireContext().contentResolver, uris)
            batchDeleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
    }

    private fun getMediaUriFromPath(path: String): android.net.Uri? {
        val proj = arrayOf(MediaStore.Audio.Media._ID)
        requireContext().contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path), null)?.use {
            if (it.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
        }
        return null
    }

    private fun updateIndicatorDotPosition() {
        val currentPlaying = MusicManager.currentSong.value ?: return
        if (currentDisplayedSongs.size <= 1) return
        val index = currentDisplayedSongs.indexOfFirst { it.filePath == currentPlaying.filePath }
        if (index == -1) return

        scrollbarOverlay.post {
            val totalH = scrollbarOverlay.height
            if (totalH > 0) {
                indicatorDot.translationY = (index.toFloat() / (currentDisplayedSongs.size - 1)) * (totalH - indicatorDot.height)
                if (rvLocalMusic.scrollState != RecyclerView.SCROLL_STATE_IDLE) indicatorDot.alpha = 1f
            }
        }
    }

    private fun toggleEmptyState(isEmpty: Boolean) {
        tvNoResult.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvLocalMusic.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s.toString().lowercase().trim()
                if (q.isEmpty()) { musicAdapter.updateList(currentDisplayedSongs); toggleEmptyState(currentDisplayedSongs.isEmpty()); return }
                val filtered = currentDisplayedSongs.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
                musicAdapter.updateList(filtered)
                toggleEmptyState(filtered.isEmpty())
            }
        })
    }

    private fun toggleSearch(show: Boolean) {
        tvBaslikLocal.visibility = if (show) View.GONE else View.VISIBLE
        searchContainer.visibility = if (show) View.VISIBLE else View.GONE
        btnSearchOpen.visibility = if (show) View.GONE else View.VISIBLE
        btnSearchClose.visibility = if (show) View.GONE else View.VISIBLE
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) { searchInput.requestFocus(); imm.showSoftInput(searchInput, 0) }
        else { searchInput.text.clear(); imm.hideSoftInputFromWindow(searchInput.windowToken, 0) }
    }

    private fun showSortMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        val menu = popup.menu

        // SharedPreferences'tan bu oynatma listesine ait en son seçilen sıralama türünü oku (Varsayılan: 1 -> Yeniden Eskiye)
        val sortPrefs = requireContext().getSharedPreferences("RhythmicPrefs_Sort", Context.MODE_PRIVATE)
        val currentPlaylistKey = "sort_state_${activePlaylistId ?: "null"}"
        val activeSortType = sortPrefs.getInt(currentPlaylistKey, 1)

        // Menü elemanlarını ekle
        val itemNewest = menu.add(0, 1, 0, "Yeniden Eskiye")
        val itemAlphabetical = menu.add(0, 2, 0, "A'dan Z'ye")

        // 🎯 İSTEK UYARINCA: Seçenekleri işaretlenebilir yap ve aktif olanın yanına TİK (✓) koy
        itemNewest.isCheckable = true
        itemAlphabetical.isCheckable = true

        if (activeSortType == 1) itemNewest.isChecked = true else itemAlphabetical.isChecked = true

        popup.setOnMenuItemClickListener { item ->
            val selectedType = item.itemId

            // Yeni sıralama tercihini hafızaya kaydet
            sortPrefs.edit().putInt(currentPlaylistKey, selectedType).apply()

            // Listeyi ekranda ve çalma motorunda yeniden sırala
            applySortAndRefresh(selectedType)
            true
        }
        popup.show()
    }

    // Sıralama algoritmasını çalıştıran ve motoru tetikleyen yardımcı fonksiyon
    private fun applySortAndRefresh(sortType: Int) {
        // 🎯 KORUMA KALKANI 1: Eğer adaptör o an seçim modundaysa veya düzenleme modundaysa,
        // sıralama algoritmasının çalışmasını tamamen engelliyoruz ki seçilen tikler (isSelected) uçmasın.
        if (::musicAdapter.isInitialized && musicAdapter.getSelectionMode()) {
            return
        }

        val collator = Collator.getInstance(Locale("tr", "TR"))

        when (sortType) {
            1 -> currentDisplayedSongs.sortByDescending { it.dateAdded } // Yeniden Eskiye
            2 -> currentDisplayedSongs.sortWith { a, b -> collator.compare(a.title, b.title) } // A'dan Z'ye
        }

        // Ekrandaki arayüzü güncelle
        musicAdapter.updateList(currentDisplayedSongs)

        // Çalma kuyruğunu senkronize et
        if (currentDisplayedSongs.isNotEmpty()) {
            MusicManager.setTemporaryPlaylist(currentDisplayedSongs)
        }
    }

    /* --- YATAY KAPSÜL ADAPTÖRÜ (Inner Class) --- */
    inner class PlaylistChipAdapter : RecyclerView.Adapter<PlaylistChipAdapter.ChipViewHolder>() {
        inner class ChipViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvChipName)
            val imgManage: ImageView = view.findViewById(R.id.imgChipManage)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
            return ChipViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_chip, parent, false))
        }
        override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
            when (position) {
                0 -> {
                    holder.tvName.visibility = View.GONE
                    holder.imgManage.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener { openManagePlaylistsDialog(isRoutingForAdding = false) }
                }
                1 -> {
                    holder.tvName.text = "Hepsi"
                    holder.tvName.visibility = View.VISIBLE
                    holder.imgManage.visibility = View.GONE
                    setChipStyle(holder.tvName, activePlaylistId == null)
                    holder.itemView.setOnClickListener { activePlaylistId = null; refreshCurrentView() }
                }
                else -> {
                    val playlist = playlists[position - 2]
                    holder.tvName.text = playlist.playlistName
                    holder.tvName.visibility = View.VISIBLE
                    holder.imgManage.visibility = View.GONE
                    setChipStyle(holder.tvName, activePlaylistId == playlist.playlistId)
                    holder.itemView.setOnClickListener { activePlaylistId = playlist.playlistId; refreshCurrentView() }
                }
            }
        }
        override fun getItemCount(): Int = playlists.size + 2

        private fun setChipStyle(tv: TextView, isSelected: Boolean) {
            if (isSelected) {
                tv.setBackgroundResource(R.drawable.chip_background_selected)
                tv.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnPrimary))
            } else {
                tv.setBackgroundResource(R.drawable.chip_background)
                tv.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
        }
        private fun getThemeColor(attr: Int): Int {
            val typedValue = TypedValue()
            requireContext().theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}