package com.example.rhythmic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnSync = view.findViewById<LinearLayout>(R.id.btnSyncLibrary)
        val progress = view.findViewById<ProgressBar>(R.id.progressSync)

        btnSync.setOnClickListener {
            // UI'ı kilitle
            btnSync.isEnabled = false
            btnSync.alpha = 0.5f
            progress.visibility = View.VISIBLE

            Toast.makeText(requireContext(), "Tarama başlatıldı...", Toast.LENGTH_SHORT).show()

            // MusicManager'daki yeni fonksiyonu çağır
            MusicManager.refreshLibrary {
                // İşlem bitince burası çalışır (Main Thread)
                if (isAdded) {
                    btnSync.isEnabled = true
                    btnSync.alpha = 1.0f
                    progress.visibility = View.GONE
                    Toast.makeText(requireContext(), "Kütüphane güncellendi! ✅", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}