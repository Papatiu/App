--- START OF FILE app/src/main/java/com/example/appnet/afetnet/mesh/PacketHandler.kt ---

package com.example.appnet.afetnet.mesh

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.nio.charset.StandardCharsets

// MeshPacket objelerini byte dizilerine dönüştürme ve tersini yapma sınıfı
object PacketHandler {

    private val TAG = "PacketHandler"
    private val gson = Gson() // JSON işlemleri için Gson kütüphanesi

    // MeshPacket objesini byte dizisine dönüştürür (Serileştirme)
    // Eğer paket çok büyükse null dönebilir. BLE paketi 512 byte ile sınırlı olabilir.
    fun serialize(packet: MeshPacket): ByteArray? {
        return try {
            val jsonString = gson.toJson(packet)
            // JSON string'i UTF-8 byte dizisine dönüştür
            val bytes = jsonString.toByteArray(StandardCharsets.UTF_8)
             // BLE paketi genellikle max 512 byte olabilir, pratikte daha küçük tutmak daha güvenlidir.
             // Bu basit implementasyonda boyut kontrolü yapmıyoruz, gerçek projede yapılmalı.
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Serialization failed: ${e.message}", e)
            null
        }
    }

    // Byte dizisini MeshPacket objesine dönüştürür (Deserializasyon)
    fun deserialize(bytes: ByteArray): MeshPacket? {
        return try {
            // Byte dizisini UTF-8 string'e dönüştür
            val jsonString = String(bytes, StandardCharsets.UTF_8)
            // JSON string'i MeshPacket objesine dönüştür
            gson.fromJson(jsonString, MeshPacket::class.java)
        } catch (e: JsonSyntaxException) {
            // JSON formatı hatalıysa null döndür veya hata logla
            Log.w(TAG, "Deserialization failed: Invalid JSON syntax", e)
            null
        } catch (e: Exception) {
             // Diğer olası hatalar
            Log.e(TAG, "Deserialization failed: ${e.message}", e)
            null
        }
    }
}