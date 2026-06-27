package com.example.rhythmic

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.chaquo.python.Python

data class SongDetailInfo(
    val channelName: String,
    val viewCount: String,
    val likeCount: String,
    val uploadDate: String,
    val exactVideoId: String
)

class SongDetailViewModel : ViewModel() {
    var currentSong: MusicModel? = null

    // UI'ın dinleyeceği canlı veriler
    val songDetailData = MutableLiveData<SongDetailInfo?>()
    val isLoading = MutableLiveData<Boolean>()
    val errorMessage = MutableLiveData<String?>()

    fun loadDetailsIfNeeded(song: MusicModel) {
        // Eğer tıklanan şarkı zaten hafızadakiyle aynıysa internete GİTME!
        if (currentSong?.videoId == song.videoId && songDetailData.value != null) {
            return
        }

        // Yeni şarkı geldiyse eski verileri temizle ve yüklemeyi başlat
        currentSong = song
        songDetailData.postValue(null)
        errorMessage.postValue(null)
        isLoading.postValue(true)

        Thread {
            try {
                val py = Python.getInstance()
                val script = py.getModule("script")

                val result = script.callAttr("get_video_info", song.filePath).toString()

                if (result.startsWith("Hata:")) {
                    throw Exception(result)
                }

                val parts = result.split("|||")
                val info = SongDetailInfo(
                    viewCount = parts.getOrNull(0) ?: "Bilinmiyor",
                    uploadDate = parts.getOrNull(1) ?: "Bilinmiyor",
                    likeCount = parts.getOrNull(2) ?: "Bilinmiyor",
                    exactVideoId = parts.getOrNull(4) ?: "",
                    channelName = song.artist // Şarkının sanatçı adını kanal olarak alıyoruz
                )

                songDetailData.postValue(info)
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage.postValue(e.message)
            } finally {
                isLoading.postValue(false)
            }
        }.start()
    }
}