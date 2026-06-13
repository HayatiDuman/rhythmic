package com.example.rhythmic.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "musics")
data class MusicEntity(
    @PrimaryKey val videoId: String, // Eşsiz Youtube ID
    val title: String,
    val artist: String,
    val duration: String,
    val filePath: String,
    val albumArtPath: String?,
    val dateAdded: Long = System.currentTimeMillis(),
    val originalFileName: String
)