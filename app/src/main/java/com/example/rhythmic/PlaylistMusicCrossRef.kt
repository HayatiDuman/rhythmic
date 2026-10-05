package com.example.rhythmic.database

import androidx.room.Entity

@Entity(
    tableName = "playlist_music_cross_ref",
    primaryKeys = ["playlistId", "videoId"] // İki anahtar birleşerek eşsiz bir ilişki kurar
)
data class PlaylistMusicCrossRef(
    val playlistId: Long,
    val videoId: String // MusicEntity içindeki @PrimaryKey ile tam eşleşir
)