package com.example.rhythmic

data class MusicModel(
    val title: String,
    val filePath: String = "",
    val durationText: String = "",
    val artist: String = "",
    val originalFileName: String? = null,
    val videoId: String = "",
    val dateAdded: Long = System.currentTimeMillis(),
    val albumArtPath: String? = null,
    val audioSizeMb: String? = null,
    val videoSizeMb: String? = null
)
