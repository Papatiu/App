package com.example.appnet.afetnet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter // Eğer enum için TypeConverter kullanırsak

import com.example.appnet.afetnet.mesh.PacketType // PacketType enum'ı
import java.util.Date

// Room veritabanı sınıfı
@Database(entities = [SignalEntity::class], version = 1, exportSchema = false)
// Eğer PacketType enum'ı veya Date gibi özel tipler saklıyorsanız TypeConverters belirtmeniz gerekir.
// @TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun signalDao(): SignalDao // DAO'muzu döndüren soyut metot

    companion object {
        // Singleton pattern kullanarak veritabanının sadece bir örneği olmasını sağlıyoruz
        @Volatile // Tüm iş parçacıkları için bu değişikliğin anında görünür olmasını sağlar
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            // Eğer INSTANCE null ise, senkronize blok içinde veritabanını oluştur
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext, // Uygulama Context'i kullanmak bellek sızıntısını önler
                    AppDatabase::class.java,
                    "afetnet_database" // Veritabanı adı
                )
                // TypeConverter'ları buraya ekleyebilirsiniz eğer tanımlarsanız
                // .addTypeConverter(Converters())
                // Veritabanı sürümü yükseltildiğinde migrate etmek yerine yok edip yeniden oluştur (Geliştirme aşamasında kolaylık sağlar, üretimde dikkatli kullanılmalı)
                // .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance // Oluşturulan örneği kaydet
                instance
            }
        }
    }
}

/*
// Örnek TypeConverter sınıfı (Enum için String, Date için Long dönüştürmek gibi)
class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromPacketType(value: PacketType?): String? {
         return value?.name // Enum adını String olarak sakla
    }

    @TypeConverter
    fun toPacketType(value: String?): PacketType? {
        return value?.let { PacketType.valueOf(it) } // String'i Enum'a dönüştür
    }
}
*/