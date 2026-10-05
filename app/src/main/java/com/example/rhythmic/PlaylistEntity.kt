package com.example.rhythmic.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val playlistName: String,
    val dateCreated: Long = System.currentTimeMillis()
)