package com.example.rhythmic.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 🔥 Versiyon 3 yapıldı, Yeni şemalar enjekte edildi
@Database(entities = [MusicEntity::class, PlaylistEntity::class, PlaylistMusicCrossRef::class], version = 3)
abstract class AppDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 🔥 GÜVENLİ VERİ KORUMA KÖPRÜSÜ (MIGRATION)
        // Versiyon 2'den 3'e geçerken Room'a yeni tabloları SQL komutlarıyla manuel oluşturmasını söylüyoruz.
        // Böylece "Destructive" tetiklenmez, mevcut müzik kayıtların sapasağlam korunur.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 🔥 SQLite standardına uygun olarak AUTOINCREMENT yazıldı
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `playlistId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `playlistName` TEXT NOT NULL, 
                        `dateCreated` INTEGER NOT NULL
                    )
                """)

                // Köprü tablosu (Burası zaten doğruydu ama bütünlük için kalabilir)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `playlist_music_cross_ref` (
                        `playlistId` INTEGER NOT NULL, 
                        `videoId` TEXT NOT NULL, 
                        PRIMARY KEY(`playlistId`, `videoId`)
                    )
                """)
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rhythmic_database"
                )
                    .allowMainThreadQueries()
                    .addMigrations(MIGRATION_2_3) // 🔥 Güvenlik köprüsü eklendi!
                    .fallbackToDestructiveMigration() // Güvenlik önlemi (Eğer üstteki başarısız olursa çökme, sıfırla)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}