package com.example.rhythmic

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rhythmic.database.PlaylistEntity

class PlaylistFragment : Fragment() {

    private lateinit var rvPlaylists: RecyclerView
    private lateinit var btnCreatePlaylist: ImageView
    private lateinit var tvNoPlaylist: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_playlist, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvPlaylists = view.findViewById(R.id.rvPlaylists)
        btnCreatePlaylist = view.findViewById(R.id.btnCreatePlaylist)
        tvNoPlaylist = view.findViewById(R.id.tvNoPlaylist)

        rvPlaylists.layoutManager = LinearLayoutManager(requireContext())

        setupListeners()
        loadPlaylists()
    }

    private fun setupListeners() {
        btnCreatePlaylist.setOnClickListener {
            val input = EditText(requireContext())
            AlertDialog.Builder(requireContext())
                .setTitle("Yeni Oynatma Listesi Oluştur")
                .setView(input)
                .setPositiveButton("Oluştur") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        MusicManager.createPlaylist(name)
                        loadPlaylists() // Ekranı tazele
                    }
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadPlaylists() // Sayfaya her dönüldüğünde güncel listeleri veritabanından çek
    }

    private fun loadPlaylists() {
        val playlists = MusicManager.getPlaylists()
        tvNoPlaylist.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        rvPlaylists.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE

        rvPlaylists.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val playlist = playlists[position]
                val text1 = holder.itemView.findViewById<TextView>(android.R.id.text1)
                val text2 = holder.itemView.findViewById<TextView>(android.R.id.text2)

                text1.text = playlist.playlistName
                text1.setTextColor(Color.WHITE)
                text1.textSize = 18f

                // Arka planda o listeye ait kaç şarkı olduğunu dinamik süzüp fısıldıyoruz
                val songCount = MusicManager.getSongsFromPlaylist(playlist.playlistId).size
                text2.text = "$songCount Şarkı"
                text2.setTextColor(Color.GRAY)

                // Kısa basılırsa listenin içini oynat veya detayını göster
                holder.itemView.setOnClickListener {
                    val songsInPlaylist = MusicManager.getSongsFromPlaylist(playlist.playlistId)
                    if (songsInPlaylist.isEmpty()) {
                        Toast.makeText(requireContext(), "Bu listede henüz şarkı yok.", Toast.LENGTH_SHORT).show()
                    } else {
                        // Basitlik adına listeye tıklanınca içindeki ilk şarkıyı çalmayı tetikler
                        // Buraya ilerleyen aşamada PlaylistDetailFragment köprüsü kurabiliriz
                        Toast.makeText(requireContext(), "'${playlist.playlistName}' oynatılıyor...", Toast.LENGTH_SHORT).show()
                    }
                }

                // Uzun basılırsa listeyi tamamen silme onay penceresi
                holder.itemView.setOnLongClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Oynatma Listesini Sil")
                        .setMessage("'${playlist.playlistName}' listesini silmek istiyor musunuz?\n(Cihazdaki dosyalarınız korunur.)")
                        .setPositiveButton("Sil") { _, _ ->
                            MusicManager.deletePlaylist(playlist.playlistId)
                            loadPlaylists()
                        }
                        .setNegativeButton("İptal", null)
                        .show()
                    true
                }
            }

            override fun getItemCount(): Int = playlists.size
        }
    }
}