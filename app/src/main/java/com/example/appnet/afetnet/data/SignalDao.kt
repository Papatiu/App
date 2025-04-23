
package com.example.appnet.afetnet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow // Veritabanı değişikliklerini dinlemek için Flow kullanabiliriz

// Signal Entity için veritabanı işlemleri (DAO)
@Dao
interface SignalDao {

    // Yeni bir sinyal ekler. Eğer aynı ID'ye sahip sinyal zaten varsa değiştirir (veya yoksayar).
    // OnConflictStrategy.IGNORE: Eğer aynı ID varsa yeni sinyali yoksay.
    // OnConflictStrategy.REPLACE: Eğer aynı ID varsa mevcut sinyali yenisiyle değiştir.
    // Bizim senaryomuzda aynı ID'li paketi tekrar kaydetmek istemeyiz, IGNORE daha uygun.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSignal(signal: SignalEntity): Long // Insert edilen satır ID'sini döner, IGNORE'da -1 dönebilir

    // Kaydedilmiş tüm sinyalleri zaman damgasına göre azalan sırada getirir (en yeni en üstte)
    @Query("SELECT * FROM signals ORDER BY receivedTimestamp DESC")
    fun getAllSignals(): Flow<List<SignalEntity>> // Flow kullanarak veritabanı değiştikçe otomatik güncellemeler alabiliriz

    // Sadece 'DISTRESS' (Yardım Çağrısı) sinyallerini getirir
     @Query("SELECT * FROM signals WHERE type = 'DISTRESS' ORDER BY receivedTimestamp DESC")
    fun getDistressSignals(): Flow<List<SignalEntity>>

    // Belirli bir ID'ye sahip sinyali getirir
     @Query("SELECT * FROM signals WHERE id = :signalId LIMIT 1")
    suspend fun getSignalById(signalId: String): SignalEntity?

    // Belirli bir sinyali "işlenmiş" olarak işaretle (Opsiyonel)
     @Query("UPDATE signals SET isProcessed = :isProcessed WHERE id = :signalId")
    suspend fun updateSignalProcessedStatus(signalId: String, isProcessed: Boolean)

    // Tüm sinyalleri sil
     @Query("DELETE FROM signals")
    suspend fun deleteAllSignals()
}