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

    class MusicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        /*val coverImage: ImageView = itemView.findViewById(R.id.imgLocalKapak)*/
        val playingIndicator: ImageView = itemView.findViewById(R.id.imgPlayingIndicator)
        val titleText: TextView = itemView.findViewById(R.id.tvLocalBaslik)
        val subtitleText: TextView = itemView.findViewById(R.id.tvLocalSure)
        val container: View = itemView
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
        bindClicks(holder, song)
    }

    override fun getItemCount(): Int = musicList.size

    /* --- Binding Helpers --- */

    private fun bindPlayingState(holder: MusicViewHolder, song: MusicModel) {
        val isPlaying = song.filePath == currentPlayingFilePath
        val context = holder.itemView.context

        // Temamızdaki renkleri çalışma anında (runtime) dinamik olarak çekiyoruz
        val typedValue = android.util.TypedValue()
        val theme = context.theme

        if (isPlaying) {
            // 1. Şarkı çalıyorsa: Yeni aksan rengimiz olan Bebek Mavisini (?attr/colorSecondary) ver
            theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, typedValue, true)
            holder.titleText.setTextColor(typedValue.data)
            holder.playingIndicator.visibility = View.VISIBLE
        } else {
            // 2. Şarkı çalmıyorsa: Evrensel ana yazı rengini (?android:attr/textColorPrimary) ver
            theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            holder.titleText.setTextColor(typedValue.data)
            holder.playingIndicator.visibility = View.GONE
        }
    }

    private fun bindTexts(holder: MusicViewHolder, song: MusicModel) {
        holder.titleText.text = song.title

        holder.subtitleText.text =
            if (song.durationText.isNotEmpty())
                "${song.artist} • ${song.durationText}"
            else song.artist
    }

    private fun bindCoverImage(holder: MusicViewHolder, song: MusicModel) {
        val imageSource = resolveCoverImage(holder, song)

        /*Glide.with(holder.itemView.context)
            .load(imageSource ?: R.drawable.ic_music_placeholder)
            .placeholder(R.drawable.ic_music_placeholder)
            *//*.into(holder.coverImage)*/
    }

    private fun resolveCoverImage(holder: MusicViewHolder, song: MusicModel): Any? {
        /*song.albumArtPath?.let { return File(it) }*/

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

    private fun bindClicks(holder: MusicViewHolder, song: MusicModel) {
        holder.container.setOnClickListener {
            onSongClick(song.filePath)
        }

        holder.container.setOnLongClickListener {
            onSongLongClick(song)
            true
        }
    }

    /* --- Public API --- */

    fun updateList(newList: List<MusicModel>) {
        musicList = newList
        notifyDataSetChanged()
    }

    fun updatePlayingSong(filePath: String?) {
        currentPlayingFilePath = filePath
        notifyDataSetChanged()
    }
}
