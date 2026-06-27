package com.example.rhythmic

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class FullScreenPlayerDialog : BottomSheetDialogFragment() {

    private lateinit var imgFullKapak: ImageView
    private lateinit var tvFullBaslik: TextView
    private lateinit var tvFullSanatci: TextView
    private lateinit var btnFullPlay: ImageView
    private lateinit var btnFullNext: ImageView
    private lateinit var btnFullPrev: ImageView
    private lateinit var fullSeekBar: SeekBar
    private lateinit var tvFullCurrentTime: TextView
    private lateinit var tvFullTotalTime: TextView
    private lateinit var imgFullKapakBlur: ImageView

    // 🔥 BAĞLANAN BUTONLAR
    private lateinit var btnFullShuffle: ImageView
    private lateinit var btnFullLoop: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private val updateTask = object : Runnable {
        override fun run() {
            if (MusicManager.mediaPlayer?.isPlaying == true) {
                val current = MusicManager.getCurrentPosition()
                fullSeekBar.progress = current
                tvFullCurrentTime.text = formatTime(current)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

            bottomSheet?.let { sheet ->
                val layoutParams = sheet.layoutParams
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                sheet.layoutParams = layoutParams

                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_full_player, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Mevcut bileşen eşlemeleri
        imgFullKapak = view.findViewById(R.id.imgFullKapak)
        imgFullKapakBlur = view.findViewById(R.id.imgFullKapakBlur)
        tvFullBaslik = view.findViewById(R.id.tvFullBaslik)
        tvFullSanatci = view.findViewById(R.id.tvFullSanatci)
        btnFullPlay = view.findViewById(R.id.btnFullPlay)
        btnFullNext = view.findViewById(R.id.btnFullNext)
        btnFullPrev = view.findViewById(R.id.btnFullPrev)
        fullSeekBar = view.findViewById(R.id.fullSeekBar)
        tvFullCurrentTime = view.findViewById(R.id.tvFullCurrentTime)
        tvFullTotalTime = view.findViewById(R.id.tvFullTotalTime)

        // 🔥 Shuffle ve Loop Butonlarını Tanımla
        btnFullShuffle = view.findViewById(R.id.btnFullShuffle)
        btnFullLoop = view.findViewById(R.id.btnFullLoop)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            imgFullKapakBlur.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    20f,
                    20f,
                    android.graphics.Shader.TileMode.CLAMP
                )
            )
        }

        /* -----------------------------
         * 🎯 LIVE DATA OBSERVERS (GÖZLEMCİLER)
         * ----------------------------- */

        // Şarkı Değişim Takibi
        MusicManager.currentSong.observe(viewLifecycleOwner) { song ->
            if (song == null) {
                dismiss()
                return@observe
            }

            tvFullBaslik.text = song.title
            tvFullSanatci.text = song.artist

            imgFullKapak.loadEmbeddedCover(song.filePath)
            imgFullKapakBlur.loadEmbeddedCover(song.filePath)

            val duration = MusicManager.getDuration()
            fullSeekBar.max = duration
            tvFullTotalTime.text = formatTime(duration)

            handler.post(updateTask)
        }

        // Oynat/Duraklat Buton Takibi
        MusicManager.isPlaying.observe(viewLifecycleOwner) { playing ->
            btnFullPlay.setImageResource(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }

        updateButtonTint(btnFullShuffle, try { MusicManager.javaClass.getField("isShuffle").getBoolean(MusicManager) } catch(e: Exception) { false })
        updateButtonTint(btnFullLoop, try { MusicManager.javaClass.getField("isLoop").getBoolean(MusicManager) } catch(e: Exception) { false })

        /* -----------------------------
         * 🔀 CLICK LISTENERS (TIKLAMALAR)
         * ----------------------------- */
        // Yerel durum takipleri (Manager'da LiveData yoksa arayüzü tetiklemek için)
        var localShuffleState = false
        var localLoopState = false

        // --- FullScreenPlayerDialog.kt -> onViewCreated() içindeki ilgili alanları güncelle ---

        // Tıklama İşlemleri
        btnFullPlay.setOnClickListener { MusicManager.pauseResume() }
        btnFullNext.setOnClickListener { MusicManager.playNext() }
        btnFullPrev.setOnClickListener { MusicManager.playPrevious() }
        btnFullShuffle.setOnClickListener { MusicManager.toggleShuffle() }
        btnFullLoop.setOnClickListener { MusicManager.toggleLoop() }

        // Canlı Durum Gözlemcileri (Full Player Rengini Yönetir)
        MusicManager.isShuffleMode.observe(viewLifecycleOwner) { isShuffle ->
            updateFullButtonTint(btnFullShuffle, isShuffle)
        }

        MusicManager.isLoopMode.observe(viewLifecycleOwner) { isLoop ->
            updateFullButtonTint(btnFullLoop, isLoop)
        }

        fullSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvFullCurrentTime.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { handler.removeCallbacks(updateTask) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.let {
                    MusicManager.seekTo(it.progress)
                    handler.post(updateTask)
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(updateTask)
    }

    private fun formatTime(ms: Int): String {
        val m = (ms / 1000) / 60
        val s = (ms / 1000) % 60
        return "%02d:%02d".format(m, s)
    }

    // 🔥 Aktiflik durumuna göre dinamik tema rengi veren yardımcı eklenti fonksiyonumuz
    private fun updateButtonTint(imageView: ImageView, isActive: Boolean) {
        val typedValue = android.util.TypedValue()
        val attrId = if (isActive) {
            com.google.android.material.R.attr.colorSecondary // Aktifse Bebek Mavisi parlasın
        } else {
            com.google.android.material.R.attr.colorControlNormal // Pasifse temadaki sönük gri/nötr ikon rengi olsun
        }
        context?.theme?.resolveAttribute(attrId, typedValue, true)
        imageView.setColorFilter(typedValue.data)
    }

    private fun updateFullButtonTint(imageView: ImageView, isActive: Boolean) {
        val typedValue = android.util.TypedValue()
        val attrId = if (isActive) com.google.android.material.R.attr.colorSecondary else com.google.android.material.R.attr.colorControlNormal
        context?.theme?.resolveAttribute(attrId, typedValue, true)
        imageView.setColorFilter(typedValue.data)
    }
}