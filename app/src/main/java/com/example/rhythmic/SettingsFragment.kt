package com.example.rhythmic

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView

class SettingsFragment : Fragment() {

    private lateinit var cardLight: MaterialCardView
    private lateinit var cardDark: MaterialCardView
    private lateinit var cardBlack: MaterialCardView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnSync = view.findViewById<LinearLayout>(R.id.btnSyncLibrary)
        val progress = view.findViewById<ProgressBar>(R.id.progressSync)

        cardLight = view.findViewById(R.id.cardThemeLight)
        cardDark = view.findViewById(R.id.cardThemeDark)
        cardBlack = view.findViewById(R.id.cardThemeBlack)

        val prefs = requireContext().getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)
        val currentTheme = prefs.getInt("theme_choice", 0)

        // Mevcut seçili temanın etrafına kalın çizgiyi çek
        updateCardBorders(currentTheme)

        // --- Müzik Tarama Mantığı (Aynı kaldı) ---
        btnSync.setOnClickListener {
            btnSync.isEnabled = false
            btnSync.alpha = 0.5f
            progress.visibility = View.VISIBLE
            MusicManager.refreshLibrary {
                if (isAdded) {
                    btnSync.isEnabled = true
                    btnSync.alpha = 1.0f
                    progress.visibility = View.GONE
                    Toast.makeText(requireContext(), "Kütüphane güncellendi! ✅", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // --- Kart Tıklama Yönetimi ---
        cardLight.setOnClickListener { changeTheme(0, currentTheme) }
        cardDark.setOnClickListener { changeTheme(1, currentTheme) }
        cardBlack.setOnClickListener { changeTheme(2, currentTheme) }
    }

    private fun changeTheme(selectedTheme: Int, currentTheme: Int) {
        if (selectedTheme == currentTheme) return

        val prefs = requireContext().getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("theme_choice", selectedTheme).apply()

        // Çizgileri hemen güncelle (Görsel akıcılık için)
        updateCardBorders(selectedTheme)

        // Uygulamayı pürüzsüzce yeniden başlat
        requireActivity().finish()
        requireActivity().overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(requireActivity().intent)
    }

    private fun updateCardBorders(selectedTheme: Int) {
        // Hepsini sıfırla (Çizgi kalınlığı 0dp)
        cardLight.strokeWidth = 0
        cardDark.strokeWidth = 0
        cardBlack.strokeWidth = 0

        // Seçili olanın etrafına 3dp kalınlığında Bebek Mavisi çizgiyi çek
        when (selectedTheme) {
            0 -> cardLight.strokeWidth = dpToPx(3)
            1 -> cardDark.strokeWidth = dpToPx(3)
            2 -> cardBlack.strokeWidth = dpToPx(3)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}