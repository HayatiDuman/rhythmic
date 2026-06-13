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
}