package com.example.rhythmic

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerCallback
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

class MusicDetailBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: SongDetailViewModel by activityViewModels()

    private var ytPlayer: YouTubePlayer? = null
    private var isVideoLoaded = false

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

        bottomSheet?.let {
            // Yüksekliği dinamik hesaplamak yerine doğrudan MATCH_PARENT veriyoruz
            it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT

            val behavior = BottomSheetBehavior.from(it)
            // Açılışta tam ekran olması için ekran yüksekliğini peekHeight olarak veriyoruz
            behavior.peekHeight = resources.displayMetrics.heightPixels
            behavior.state = BottomSheetBehavior.STATE_EXPANDED

            // Kullanıcı aşağı kaydırdığında ekranın yarısında durmasını engeller, doğrudan kapanır
            behavior.skipCollapsed = true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_song_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle = view.findViewById<TextView>(R.id.tvSheetTitle)
        val btnClose = view.findViewById<ImageView>(R.id.btnCloseSheet)
        val pbLoading = view.findViewById<ProgressBar>(R.id.pbSheetLoading)
        val layoutDetails = view.findViewById<LinearLayout>(R.id.layoutSheetDetails)
        val youtubePlayerView = view.findViewById<YouTubePlayerView>(R.id.youtubePlayerView)

        val tvKanal = view.findViewById<TextView>(R.id.tvSheetKanal)
        val tvBoyut = view.findViewById<TextView>(R.id.tvSheetBoyut)
        val tvIzlenme = view.findViewById<TextView>(R.id.tvSheetIzlenme)
        val tvBegeni = view.findViewById<TextView>(R.id.tvSheetBegeni)
        val tvTarih = view.findViewById<TextView>(R.id.tvSheetTarih)

        val song = viewModel.currentSong ?: return dismiss()
        tvTitle.text = song.title

        // 🔥 Oynatıcının uyku modunda kalmasını engellemek için viewLifecycleOwner kullanıyoruz
        viewLifecycleOwner.lifecycle.addObserver(youtubePlayerView)

        if (MusicManager.isPlaying.value == true) {
            MusicManager.pauseResume()
        }

        val instantVideoId = extractIdFromUrl(song.filePath)
        Log.d("RhythmicVideo", "Anlık Ayıklanan ID: '$instantVideoId'")

        // Kütüphanenin en güvenli asenkron ID bekleme fonksiyonunu kullanıyoruz
        youtubePlayerView.getYouTubePlayerWhenReady(object : YouTubePlayerCallback {
            override fun onYouTubePlayer(youTubePlayer: YouTubePlayer) {
                ytPlayer = youTubePlayer

                // Anlık ID var mı yoksa viewModel'de hazır olan bir id var mı kontrol et
                val finalId = instantVideoId.takeIf { it.length == 11 }
                    ?: viewModel.songDetailData.value?.exactVideoId?.takeIf { it.length == 11 }

                if (finalId != null && !isVideoLoaded) {
                    Log.d("RhythmicVideo", "Sorunsuz Yükleniyor: $finalId")
                    youTubePlayer.loadVideo(finalId, 0f)
                    isVideoLoaded = true
                }
            }
        })

        // İnternetten veri geç gelirse (anlık ID yoksa) oynatmak için observe ediyoruz
        viewModel.songDetailData.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                layoutDetails.visibility = View.VISIBLE
                tvKanal.text = "${info.channelName}"
                tvBoyut.text = "${song.audioSizeMb} MB"
                tvIzlenme.text = "${info.viewCount}"
                tvBegeni.text = "${info.likeCount}"
                tvTarih.text = "${info.uploadDate}"

                val exactId = info.exactVideoId

                // Oynatıcı hazırsa ve video yüklenmediyse yükle
                if (exactId.length == 11 && !isVideoLoaded && ytPlayer != null) {
                    Log.d("RhythmicVideo", "İnternet verisiyle yükleniyor: $exactId")
                    ytPlayer?.loadVideo(exactId, 0f)
                    isVideoLoaded = true
                }
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            pbLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Toast.makeText(requireContext(), "Detaylar alınamadı: $error", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun extractIdFromUrl(url: String): String {
        return try {
            when {
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&").trim()
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").trim()
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}