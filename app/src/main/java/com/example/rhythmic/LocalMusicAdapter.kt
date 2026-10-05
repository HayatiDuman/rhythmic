package com.example.rhythmic

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File

class LocalMusicAdapter(
    private var musicList: List<MusicModel>,
    private val onSongClick: (filePath: String) -> Unit,
    private val onSongLongClick: (MusicModel) -> Unit
) : RecyclerView.Adapter<LocalMusicAdapter.MusicViewHolder>() {

    // Şu an çalan şarkının dosya yolu
    private var currentPlayingFilePath: String? = null

    // 🔥 ÇOĞUL SEÇİM MODU DURUM TAKİBİ
    private var isSelectionMode = false

    // 🔥 Seçim modu durumunu dışarıya fısıldayan dinleyici (Fragment'taki sayacı besleyecek)
    private var onSelectionChangedListener: (() -> Unit)? = null

    class MusicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val playingIndicator: ImageView = itemView.findViewById(R.id.imgPlayingIndicator)
        val titleText: TextView = itemView.findViewById(R.id.tvLocalBaslik)
        val subtitleText: TextView = itemView.findViewById(R.id.tvLocalSure)
        val container: View = itemView

        // 🔥 Not: Gelecek adımda eklenecek Checkbox'ı şimdiden buraya hazırlıyoruz.
        // Hata vermemesi için dinamik süzme (findViewByIdOrNull mantığı) yapıyoruz.
        val checkBox: android.widget.CheckBox? = itemView.findViewById(R.id.cbLocalSelect) ?: null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_local_music, parent, false)
        return MusicViewHolder(view)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        val song = musicList[position]

        bindPlayingState(holder, song)
        bindTexts(holder, song)
        bindCoverImage(holder, song)

        // 🔥 Yeni Eklenen Seçim Arayüzü Bağlayıcısı
        bindSelectionState(holder, song)

        bindClicks(holder, song, position)
    }

    override fun getItemCount(): Int = musicList.size

    /* --- Binding Helpers --- */

    private fun bindPlayingState(holder: MusicViewHolder, song: MusicModel) {
        val isPlaying = song.filePath == currentPlayingFilePath
        val context = holder.itemView.context
        val typedValue = android.util.TypedValue()
        val theme = context.theme

        if (isPlaying) {
            theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, typedValue, true)
            holder.titleText.setTextColor(typedValue.data)
            // 🔥 Eğer seçim modu aktifse çalma göstergesi gizlensin, Checkbox'a yer açılsın
            holder.playingIndicator.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        } else {
            theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            holder.titleText.setTextColor(typedValue.data)
            holder.playingIndicator.visibility = View.GONE
        }
    }

    private fun bindTexts(holder: MusicViewHolder, song: MusicModel) {
        holder.titleText.text = song.title
        holder.subtitleText.text =
            if (song.durationText.isNotEmpty()) "${song.artist} • ${song.durationText}" else song.artist
    }

    private fun bindCoverImage(holder: MusicViewHolder, song: MusicModel) {
        val imageSource = resolveCoverImage(holder, song)
    }

    private fun resolveCoverImage(holder: MusicViewHolder, song: MusicModel): Any? {
        val fileName = File(song.filePath).nameWithoutExtension
        val coversDir = File(holder.itemView.context.filesDir, "covers")
        val webp = File(coversDir, "$fileName.webp")
        val jpg = File(coversDir, "$fileName.jpg")
        return when {
            webp.exists() -> webp
            jpg.exists() -> jpg
            else -> null
        }
    }

    // 🔥 YENİ: Checkbox ve Satır Boyama Görsel Yönetimi
    private fun bindSelectionState(holder: MusicViewHolder, song: MusicModel) {
        if (isSelectionMode) {
            // Seçim modu açıksa Checkbox'ı göster ve şarkının durumuna göre işaretle
            holder.checkBox?.visibility = View.VISIBLE
            holder.checkBox?.isChecked = song.isSelected

            // Seçilen şarkıların arka planını hafif belirgin yap
            if (song.isSelected) {
                holder.container.setBackgroundColor(Color.parseColor("#1A7DD3FC")) // Bebek mavisinin %10 şeffaf hali
            } else {
                holder.container.setBackgroundResource(android.R.color.transparent)
            }
        } else {
            // Seçim modu kapalıysa Checkbox gizlensin, arka plan temizlensin
            holder.checkBox?.visibility = View.GONE
            holder.container.setBackgroundResource(android.R.color.transparent)
        }
    }

    // 🔥 DEĞİŞTİ: Tıklama dinamikleri seçim moduna göre esnetildi
    private fun bindClicks(holder: MusicViewHolder, song: MusicModel, position: Int) {
        holder.container.setOnClickListener {
            if (isSelectionMode) {
                // Seçim modu aktifse satıra tıklanınca Checkbox durumunu tersine çevir
                toggleSelection(position)
            } else {
                // Normal modda bildiğimiz çalma tetiğini çalıştır
                onSongClick(song.filePath)
            }
        }

        holder.container.setOnLongClickListener {
            if (!isSelectionMode) {
                // Eğer seçim modu kapalıyken uzun basılırsa, silmek yerine otomatik seçim modunu başlat
                onSongLongClick(song)
            }
            true
        }

        // Doğrudan Checkbox kutusuna tıklanırsa
        holder.checkBox?.setOnClickListener {
            if (isSelectionMode) toggleSelection(position)
        }
    }

    // 🔥 YENİ: Tek Bir Şarkının Seçim Durumunu Değiştirme Fonksiyonu
    private fun toggleSelection(position: Int) {
        if (position in musicList.indices) {
            musicList[position].isSelected = !musicList[position].isSelected
            notifyItemChanged(position)
            onSelectionChangedListener?.invoke()
        }
    }

    /* --- Public API (Fragment Tarafından Yönetilecek Alanlar) --- */

    fun setOnSelectionChangedListener(listener: () -> Unit) {
        this.onSelectionChangedListener = listener
    }

    // Seçim modunu açıp kapatan ana şalter
    fun setSelectionMode(enabled: Boolean) {
        if (this.isSelectionMode == enabled) return
        this.isSelectionMode = enabled

        // Mod kapatılırken seçilen tüm işaretleri RAM'de temizle
        if (!enabled) {
            musicList.forEach { it.isSelected = false }
        }
        notifyDataSetChanged()
    }

    fun getSelectionMode(): Boolean = isSelectionMode

    // Seçilen tüm şarkıların listesini döndüren fonksiyon
    fun getSelectedSongs(): List<MusicModel> {
        return musicList.filter { it.isSelected }
    }

    // Üst bar için pratik "Hepsini Seç" motoru
    fun selectAllSongs(select: Boolean) {
        musicList.forEach { it.isSelected = select }
        notifyDataSetChanged()
        onSelectionChangedListener?.invoke()
    }

    fun updateList(newList: List<MusicModel>) {
        musicList = newList
        notifyDataSetChanged()
    }

    fun updatePlayingSong(filePath: String?) {
        currentPlayingFilePath = filePath
        notifyDataSetChanged()
    }
}