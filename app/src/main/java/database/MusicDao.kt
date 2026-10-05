package com.example.rhythmic.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MusicDao {
    @Query("SELECT * FROM musics ORDER BY dateAdded DESC")
    fun getAllMusics(): List<MusicEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM musics WHERE videoId = :id LIMIT 1)")
    fun isDownloaded(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMusic(music: MusicEntity)

    @Delete
    fun deleteMusic(music: MusicEntity)

    @Query("DELETE FROM musics WHERE videoId = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM musics")
    fun deleteAll()

    // YENİ: Dosya yolu veritabanında var mı?
    @Query("SELECT EXISTS(SELECT 1 FROM musics WHERE filePath = :path LIMIT 1)")
    fun isPathExists(path: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM musics WHERE videoId = :videoId)")
    fun existsByVideoId(videoId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM musics WHERE filePath = :path)")
    fun existsByPath(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(song: MusicEntity)

    @Transaction
    fun refreshLibraryTransaction(musicEntities: List<MusicEntity>) {
        deleteAll() // Önce tüm eski verileri sil
        insertAll(musicEntities) // Sonra yeni listeyi tek seferde ekle
    }

    // `refreshLibraryTransaction` içinde kullanılacak yardımcı fonksiyon
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(musics: List<MusicEntity>)

    @Query("SELECT * FROM musics ORDER BY dateAdded DESC")
    fun getAll(): List<MusicEntity>

    // --- OYNATMA LİSTESİ OPERASYONLARI ---

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun insertPlaylist(playlist: PlaylistEntity): Long

    @androidx.room.Query("SELECT * FROM playlists ORDER BY dateCreated DESC")
    fun getAllPlaylists(): List<PlaylistEntity>

    @androidx.room.Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    fun deletePlaylistById(playlistId: Long)

    // --- KÖPRÜ / İLİŞKİ OPERASYONLARI ---

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    fun insertMusicToPlaylist(crossRef: PlaylistMusicCrossRef)

    @androidx.room.Query("DELETE FROM playlist_music_cross_ref WHERE playlistId = :playlistId AND videoId = :videoId")
    fun removeMusicFromPlaylist(playlistId: Long, videoId: String)

    // Bir oynatma listesine ait tüm şarkıları getiren sihirli sorgu
    @androidx.room.Query("""
        SELECT musics.* FROM musics 
        INNER JOIN playlist_music_cross_ref ON musics.videoId = playlist_music_cross_ref.videoId 
        WHERE playlist_music_cross_ref.playlistId = :playlistId
        ORDER BY musics.dateAdded DESC
    """)
    fun getMusicFromPlaylist(playlistId: Long): List<MusicEntity>

    // Bir şarkı silindiğinde oynatma listelerindeki tüm referanslarını otomatik temizle
    @androidx.room.Query("DELETE FROM playlist_music_cross_ref WHERE videoId = :videoId")
    fun deleteMusicReferences(videoId: String)
}