package com.example.rhythmic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }

    // Fragmentlar
    private val homeFragment = HomeFragment()
    private val audioFragment = AudioFragment()
    private val videoFragment = VideoFragment()
    private val settingsFragment = SettingsFragment()
    private var activeFragment: Fragment = homeFragment

    // Mini Player
    private lateinit var miniContainer: FrameLayout
    private lateinit var tvMiniBaslik: TextView
    private lateinit var btnMiniPlay: ImageView
    private lateinit var imgMiniKapak: ImageView
    private lateinit var btnNext: ImageView
    private lateinit var btnPrev: ImageView
    private lateinit var btnShuffle: ImageView
    private lateinit var btnLoop: ImageView
    private lateinit var miniSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val updateSeekBarTask = object : Runnable {
        override fun run() {
            if (MusicManager.mediaPlayer?.isPlaying == true) {
                val current = MusicManager.getCurrentPosition()
                miniSeekBar.progress = current
                tvCurrentTime.text = formatTime(current)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MusicManager.init(this)

        startService(Intent(this, MusicService::class.java))

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // 1. Önce İzinleri Kontrol Et (Sormaya gerek var mı?)
        if (!hasPermissions()) {
            requestAppPermissions()
        } else {
            // İzinler zaten var, belki ilk sync yapılmadı?
            checkFirstSync()
        }

        setupNavigation()
        setupMiniPlayer()
    }

    // -----------------------------
    // 🔀 NAVIGATION
    // -----------------------------
    private fun setupNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val prefs = getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)

        val lastTab = prefs.getInt("last_tab", R.id.nav_home)

        val targetFragment = when (lastTab) {
            R.id.nav_audio -> audioFragment
            R.id.nav_video -> videoFragment
            R.id.nav_settings -> settingsFragment
            else -> homeFragment
        }

        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragment_container, settingsFragment).hide(settingsFragment)
            add(R.id.fragment_container, videoFragment).hide(videoFragment)
            add(R.id.fragment_container, audioFragment).hide(audioFragment)
            add(R.id.fragment_container, homeFragment).hide(homeFragment)
            commit()
        }

        supportFragmentManager.executePendingTransactions()
        supportFragmentManager.beginTransaction().show(targetFragment).commit()
        activeFragment = targetFragment
        bottomNav.selectedItemId = lastTab

        bottomNav.setOnItemSelectedListener { item ->
            prefs.edit().putInt("last_tab", item.itemId).apply()
            when (item.itemId) {
                R.id.nav_home -> switchFragment(homeFragment)
                R.id.nav_audio -> switchFragment(audioFragment)
                R.id.nav_video -> switchFragment(videoFragment)
                R.id.nav_settings -> switchFragment(settingsFragment)
            }
            true
        }
    }

    private fun switchFragment(target: Fragment) {
        if (target == activeFragment) return
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(target)
            .commit()
        activeFragment = target
    }

    // -----------------------------
    // ▶️ MINI PLAYER
    // -----------------------------
    private fun setupMiniPlayer() {
        miniContainer = findViewById(R.id.miniPlayerContainer)
        tvMiniBaslik = findViewById(R.id.tvMiniBaslik)
        btnMiniPlay = findViewById(R.id.btnMiniPlay)
        imgMiniKapak = findViewById(R.id.imgMiniKapak)
        btnNext = findViewById(R.id.btnNext)
        btnPrev = findViewById(R.id.btnPrev)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnLoop = findViewById(R.id.btnLoop)
        miniSeekBar = findViewById(R.id.miniSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        MusicManager.currentSong.observe(this) { song ->
            song ?: return@observe

            miniContainer.visibility = View.VISIBLE
            tvMiniBaslik.text = song.title

            Glide.with(this)
                .load(song.albumArtPath)
                .placeholder(R.drawable.ic_music_placeholder)
                .into(imgMiniKapak)

            val duration = MusicManager.getDuration()
            miniSeekBar.max = duration
            tvTotalTime.text = formatTime(duration)

            handler.post(updateSeekBarTask)
        }

        MusicManager.isPlaying.observe(this) { playing ->
            btnMiniPlay.setImageResource(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }

        btnMiniPlay.setOnClickListener { MusicManager.pauseResume() }
        btnNext.setOnClickListener { MusicManager.playNext() }
        btnPrev.setOnClickListener { MusicManager.playPrevious() }
        btnShuffle.setOnClickListener { MusicManager.toggleShuffle() }
        btnLoop.setOnClickListener { MusicManager.toggleLoop() }

        miniSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvCurrentTime.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                handler.removeCallbacks(updateSeekBarTask)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.let {
                    MusicManager.seekTo(it.progress)
                    handler.post(updateSeekBarTask)
                }
            }
        })
    }

    // -----------------------------
    // ⏱️ UTILS
    // -----------------------------
    private fun formatTime(ms: Int): String {
        val m = (ms / 1000) / 60
        val s = (ms / 1000) % 60
        return "%02d:%02d".format(m, s)
    }

    // -----------------------------
    // 🔐 PERMISSIONS & FIRST SYNC
    // -----------------------------

    // İzinler verilmiş mi kontrol et
    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    // İzin iste
    private fun requestAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_AUDIO,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ),
                PERMISSION_REQUEST_CODE
            )
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    // İzin sonucu
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // İzin YENİ verildi, soralım
            showSyncDialog()
        }
    }

    // İzinler zaten varsa ama hiç sync yapılmadıysa belki sormak istersin (Opsiyonel)
    private fun checkFirstSync() {
        val prefs = getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)
        val isFirstSyncDone = prefs.getBoolean("first_sync_done", false)

        // Eğer izinler var ama hiç sync sorulmadıysa sor (Yedek plan)
        // Eğer her açılışta sorsun istemiyorsan burayı boş bırakabilirsin.
        // Ama genelde temiz kurulumdan sonra bu bayrak false olacağı için bir kere sorması iyidir.
        if (!isFirstSyncDone) {
            showSyncDialog()
        }
    }

    private fun showSyncDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Müzikler Taransın mı?")
            .setMessage("Cihazınızdaki müzikler kütüphaneye eklensin mi?")
            .setPositiveButton("Şimdi Tara") { _, _ ->

                // Kullanıcı Evet dedi, artık bir daha sorma
                val prefs = getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("first_sync_done", true).apply()

                val dialog = android.app.ProgressDialog(this).apply {
                    setMessage("Müzikler taranıyor...")
                    setCancelable(false)
                    show()
                }
                MusicManager.refreshLibrary {
                    dialog.dismiss()
                    recreate()
                }
            }
            .setNegativeButton("Daha Sonra") { _, _ ->
                // Kullanıcı Hayır dedi, yine de bir daha sorma (Ayarlardan yapabilir)
                val prefs = getSharedPreferences("RhythmicPrefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("first_sync_done", true).apply()
            }
            .show()
    }
}