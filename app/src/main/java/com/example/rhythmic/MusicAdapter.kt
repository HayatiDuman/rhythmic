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
    private val onActionClick: (MusicModel, String) -> Unit,
    private val onRetryClick: () -> Unit // Yeni eklendi
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() { // Genel ViewHolder'a geçirildi

    // -----------------------------
    // ⬇️ DOWNLOAD STATE (Mevcut yapı korundu)
    // -----------------------------
    data class DownloadState(
        var isDownloading: Boolean = false,
        var progress: Int = 0
    )

    private val downloadStates = mutableMapOf<String, DownloadState>()

    // -----------------------------
    // ⏳ FOOTER STATE (Yeni Eklendi)
    // -----------------------------
    enum class FooterState { HIDDEN, LOADING, ERROR }

    private var footerState = FooterState.HIDDEN
    private var errorMessage = ""

    companion object {
        const val ACTION_AUDIO = "audio"
        const val ACTION_INFO = "info"
        // ACTION_VIDEO ölü kod olduğu için temizlendi

        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_FOOTER = 1
    }

    // -----------------------------
    // DIŞARIDAN KONTROL FONKSİYONLARI (GÜNCELLENDİ)
    // -----------------------------
    fun showLoadingFooter() {
        val wasHidden = (footerState == FooterState.HIDDEN)
        footerState = FooterState.LOADING

        if (wasHidden) {
            notifyItemInserted(musicList.size) // Yoksa ekle
        } else {
            notifyItemChanged(musicList.size)  // Varsa güncelle (Örn: Hata ekranından yükleniyor'a geçerken)
        }
    }

    fun showErrorFooter(msg: String) {
        val wasHidden = (footerState == FooterState.HIDDEN)
        footerState = FooterState.ERROR
        errorMessage = msg

        if (wasHidden) {
            notifyItemInserted(musicList.size)
        } else {
            notifyItemChanged(musicList.size)
        }
    }

    fun hideFooter() {
        if (footerState != FooterState.HIDDEN) {
            footerState = FooterState.HIDDEN
            notifyItemRemoved(musicList.size) // Ekranda varsa sil
        }
    }

    fun updateList(newList: List<MusicModel>) {
        musicList = newList
        footerState = FooterState.HIDDEN
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
    // RECYCLERVIEW MİMARİSİ
    // -----------------------------

    override fun getItemCount(): Int {
        // Eğer footer aktifse (+1) ekleriz ki en altta çizilsin
        return musicList.size + if (footerState != FooterState.HIDDEN) 1 else 0
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == musicList.size && footerState != FooterState.HIDDEN) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_ITEM) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_music, parent, false)
            MusicViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pagination_footer, parent, false)
            FooterViewHolder(view)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is MusicViewHolder) {
            val song = musicList[position]

            // 📄 TEXT
            holder.tvBaslik.text = song.title
            holder.tvSure.text = "${song.artist} • ${song.durationText}"

            // 🖼️ IMAGE
            Glide.with(holder.itemView.context)
                .load(song.albumArtPath)
                .placeholder(R.drawable.ic_music_placeholder)
                .centerCrop()
                .into(holder.imgKapak)

            // ⬇️ DOWNLOAD STATE
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

            // 🎯 ACTIONS
            holder.btnInfo.setOnClickListener {
                onActionClick(song, ACTION_INFO)
            }

            holder.btnSes.setOnClickListener {
                onActionClick(song, ACTION_AUDIO)
            }
            //holder.btnListen.setOnClickListener { onActionClick(song, "play") }

        } else if (holder is FooterViewHolder) {
            // YENİ: Altbilgi davranışlarını yönetir
            holder.bind(footerState, errorMessage, onRetryClick)
        }
    }

    // -----------------------------
    // VİEWHOLDERS (GÖRÜNÜM TUTUCULAR)
    // -----------------------------

    class MusicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgKapak: ImageView = itemView.findViewById(R.id.imgLocalKapak)
        val tvBaslik: TextView = itemView.findViewById(R.id.tvBaslik)
        val tvSure: TextView = itemView.findViewById(R.id.tvSure)

        val btnSes: LinearLayout = itemView.findViewById(R.id.btnSesIndir)
        val btnInfo: LinearLayout = itemView.findViewById(R.id.btnInfo)
        //val btnListen: LinearLayout = itemView.findViewById(R.id.btnListenAudio)

        val progressLayout: LinearLayout = itemView.findViewById(R.id.layoutDownloadProgress)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        val progressText: TextView = itemView.findViewById(R.id.progressText)
    }

    class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val pbPagination: ProgressBar = itemView.findViewById(R.id.pbPagination)
        private val tvPaginationError: TextView = itemView.findViewById(R.id.tvPaginationError)
        private val btnPaginationRetry: Button = itemView.findViewById(R.id.btnPaginationRetry)

        fun bind(state: FooterState, errorMsg: String, onRetryClick: () -> Unit) {
            when (state) {
                FooterState.LOADING -> {
                    pbPagination.visibility = View.VISIBLE
                    tvPaginationError.visibility = View.GONE
                    btnPaginationRetry.visibility = View.GONE
                }
                FooterState.ERROR -> {
                    pbPagination.visibility = View.GONE
                    tvPaginationError.visibility = View.VISIBLE
                    btnPaginationRetry.visibility = View.VISIBLE
                    tvPaginationError.text = errorMsg

                    btnPaginationRetry.setOnClickListener {
                        onRetryClick()
                    }
                }
                FooterState.HIDDEN -> {} // Zaten getItemCount ile ekrandan siliniyor
            }
        }
    }

    // -----------------------------
    // 🧩 UI HELPERS (Mevcut yapı korundu)
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

        val alpha = if (enabled) 1f else 0.2f
        holder.btnSes.alpha = alpha
    }
}