--- START OF FILE app/src/main/java/com/example/appnet/afetnet/mesh/routing/FloodingRouter.kt ---

package com.example.appnet.afetnet.mesh.routing

import android.util.Log
import com.example.appnet.afetnet.mesh.MeshPacket
import com.example.appnet.afetnet.util.Constants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Basit Flood (Sel) yönlendirme algoritmasını implemente eder.
// Gelen paketleri daha önce görüp görmediğini kontrol eder ve TTL/Hop Count limitine bakar.
class FloodingRouter {

    private val TAG = "FloodingRouter"

    // Daha önce görülen paketleri kaydetmek için bir önbellek
    // Key: Paket ID'si, Value: Son görülme zamanı
    private val seenPacketsCache = ConcurrentHashMap<String, Long>()

    // Bir paketin işlenip yeniden yönlendirilmesi gerekip gerekmediğini kontrol eder.
    // true dönerse paket yeni veya günceldir ve işlenip yönlendirilmelidir.
    // false dönerse paket daha önce görülmüş veya Hop Count limitini aşmıştır.
    fun shouldProcessAndForward(packet: MeshPacket): Boolean {
        // TTL/Hop Count kontrolü
        if (packet.hopCount >= Constants.MAX_HOP_COUNT) {
            Log.d(TAG, "Packet ${packet.id} dropped: Max hop count reached (${packet.hopCount})")
            return false // Hop Count limitini aştı
        }

        // Daha önce görülen paketleri temizle (eskimiş olanları kaldır)
        cleanOldPackets()

        // Paketin ID'sine bakarak daha önce görülüp görülmediğini kontrol et
        val lastSeenTime = seenPacketsCache[packet.id]

        // Eğer paket daha önce görülmediyse
        if (lastSeenTime == null) {
            Log.d(TAG, "Processing and forwarding new packet: ${packet.id}")
            // Paketi önbelleğe ekle (şimdiki zaman ile)
            seenPacketsCache[packet.id] = System.currentTimeMillis()
            return true // İşle ve yönlendir
        } else {
            // Paket daha önce görüldü, ama belki daha iyi bir yoldan (daha az hop ile) gelmiştir?
            // Basit Flooding'de buna bakmayız, sadece ID'ye bakarız.
            // Gelişmiş mesh algoritmaları buna bakabilir.
            Log.d(TAG, "Packet ${packet.id} already seen.")
            return false // Daha önce görülmüş, yoksay
        }
    }

    // Belirli bir süre önce görülen paketleri önbellekten temizler.
    private fun cleanOldPackets() {
        val currentTime = System.currentTimeMillis()
        val iterator = seenPacketsCache.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val packetId = entry.key
            val timestamp = entry.value

            // Eğer paket belirli bir süre önce görüldüyse (veya önbellek çok büyüdüyse) kaldır
            if (currentTime - timestamp > Constants.PACKET_HISTORY_TIMEOUT_MS || seenPacketsCache.size > Constants.PACKET_HISTORY_SIZE) {
                 Log.d(TAG, "Removing old packet from cache: $packetId")
                iterator.remove()
            }
        }
         // Log.d(TAG, "Packet cache size after cleaning: ${seenPacketsCache.size}")
    }
}