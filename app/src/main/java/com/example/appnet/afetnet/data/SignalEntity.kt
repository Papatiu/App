
package com.example.appnet.afetnet.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.appnet.afetnet.mesh.PacketType // Enum'ı import et

// Veritabanında saklanacak bir sinyali temsil eden Entity
@Entity(tableName = "signals")
data class SignalEntity(
    @PrimaryKey val id: String, // Paketin benzersiz ID'si (Primary Key olarak kullanıyoruz)
    val senderId: String,        // Gönderen cihazın AfetNet ID'si
    val type: PacketType,        // Sinyal tipi (DISTRESS, SAFE vb.)
    val timestamp: Long,         // Sinyalin oluşturulma zamanı (Unix zaman damgası)
    val receivedTimestamp: Long = System.currentTimeMillis(), // Sinyalin bu cihaz tarafından alındığı zaman
    val hopCount: Int,           // Bu cihaza geldiğinde hop sayısı
    val latitude: Double?,       // Konum bilgisi
    val longitude: Double?,
    val message: String?,        // Mesaj içeriği
    val isProcessed: Boolean = false // Bu sinyalin UI'de gösterilip gösterilmediği gibi durumlar için
)