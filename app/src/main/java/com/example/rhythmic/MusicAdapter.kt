package com.example.rhythmic

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class MusicAdapter(
    private var musicList: List<MusicModel>,
    private val onActionClick: (MusicModel, String) -> Unit
) : RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    data class DownloadState(
        var isDownloading: Boolean = false,
        var progress: Int = 0
    )

    private val downloadStates = mutableMapOf<String, DownloadState>()

    companion object {
        const val ACTION_AUDIO = "audio"
        const val ACTION_VIDEO = "video"
        const val ACTION_INFO = "info"
    }

    class MusicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgKapak: ImageView = itemView.findViewById(R.id.imgKapak)
        val tvBaslik: TextView = itemView.findViewById(R.id.tvBaslik)
        val tvSure: TextView = itemView.findViewById(R.id.tvSure)

        val btnVideo: LinearLayout = itemView.findViewById(R.id.btnVideoIndir)
        val btnSes: LinearLayout = itemView.findViewById(R.id.btnSesIndir)
        val btnInfo: LinearLayout = itemView.findViewById(R.id.btnInfo)

        val progressLayout: LinearLayout =
            itemView.findViewById(R.id.layoutDownloadProgress)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        val progressText: TextView = itemView.findViewById(R.id.progressText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_music, parent, false)
        return MusicViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        val song = musicList[position]

        // -----------------------------
        // 📄 TEXT
        // -----------------------------
        holder.tvBaslik.text = song.title
        holder.tvSure.text = "${song.artist} • ${song.durationText}"

        // -----------------------------
        // 🖼️ IMAGE
        // -----------------------------
        Glide.with(holder.itemView.context)
            .load(song.albumArtPath)
            .placeholder(R.drawable.ic_music_placeholder)
            .centerCrop()
            .into(holder.imgKapak)

        // -----------------------------
        // ⬇️ DOWNLOAD STATE
        // -----------------------------
        val state = downloadStates[song.videoId]

        when {
            state?.isDownloading == true -> {
                showProgress(holder, state.progress)
                setButtonsEnabled(holder, false)
            }

            MusicManager.isDownloaded(song.videoId) -> {
                hideProgress(holder)
                setButtonsEnabled(holder, false)
            }

            else -> {
                hideProgress(holder)
                setButtonsEnabled(holder, true)
            }
        }

        // -----------------------------
        // 🎯 ACTIONS
        // -----------------------------
        holder.btnInfo.setOnClickListener {
            onActionClick(song, ACTION_INFO)
        }

        holder.btnSes.setOnClickListener {
            onActionClick(song, ACTION_AUDIO)
        }

        holder.btnVideo.setOnClickListener {
            onActionClick(song, ACTION_VIDEO)
        }
    }

    override fun getItemCount(): Int = musicList.size

    fun updateList(newList: List<MusicModel>) {
        musicList = newList
        notifyDataSetChanged()
    }

    fun updateDownloadState(videoId: String, downloading: Boolean, progress: Int) {
        val state = downloadStates.getOrPut(videoId) { DownloadState() }
        state.isDownloading = downloading
        state.progress = progress

        val index = musicList.indexOfFirst { it.videoId == videoId }
        if (index != -1) notifyItemChanged(index)
    }


    // -----------------------------
    // 🧩 UI HELPERS
    // -----------------------------
    private fun showProgress(holder: MusicViewHolder, progress: Int) {
        holder.progressLayout.visibility = View.VISIBLE
        holder.progressBar.progress = progress
        holder.progressText.text = "$progress%"
    }

    private fun hideProgress(holder: MusicViewHolder) {
        holder.progressLayout.visibility = View.GONE
    }

    private fun setButtonsEnabled(holder: MusicViewHolder, enabled: Boolean) {
        holder.btnSes.isEnabled = enabled
        holder.btnVideo.isEnabled = enabled

        val alpha = if (enabled) 1f else 0.2f
        holder.btnSes.alpha = alpha
        holder.btnVideo.alpha = alpha
    }
}
