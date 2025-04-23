--- START OF FILE app/src/main/java/com/example/appnet/afetnet/mesh/MeshNetworkManager.kt (UPDATED) ---

package com.example.appnet.afetnet.mesh

import android.content.Context
import android.util.Log
import com.example.appnet.afetnet.data.AppDatabase
import com.example.appnet.afetnet.data.SignalEntity
import com.example.appnet.afetnet.mesh.routing.FloodingRouter
import com.example.appnet.afetnet.util.UniqueDeviceId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine // Birden fazla Flow'u birleştirmek için
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID


// Mesh ağının yüksek seviye mantığını yöneten sınıf
// BluetoothMeshManager'ı kullanarak keşif, bağlantı yönetimi, paket işleme ve yönlendirme yapar.
class MeshNetworkManager(private val context: Context) {

    private val TAG = "MeshNetworkManager"

    // Alt seviye Bluetooth iletişim katmanı
    private val bluetoothMeshManager = BluetoothMeshManager(context.applicationContext)

    // Yönlendirme algoritması
    private val router = FloodingRouter()

    // Veritabanı erişimi
    private val signalDao = AppDatabase.getInstance(context.applicationContext).signalDao()

    // Coroutine Scope - Bu manager'ın yaşam döngüsüne bağlı olacak
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // Arka plan işlemleri için IO dispatcher

    // Keşfedilen/bilinen cihazları tutan liste (Basit in-memory cache)
    // Key: Cihazın AfetNet ID'si, Value: MeshNode objesi
    private val knownNodes = ConcurrentHashMap<String, MeshNode>()

    // İşlenmek üzere gelen paketleri yayınlayan Flow (UI veya diğer bileşenler dinleyebilir)
    private val _processedSignals = MutableSharedFlow<SignalEntity>() // İşlenmiş sinyalleri Entity olarak yayalım
    val processedSignals: SharedFlow<SignalEntity> = _processedSignals.asSharedFlow()

    // --- Başlatma ve Durdurma ---
    fun start() {
        Log.d(TAG, "MeshNetworkManager starting...")
        // Bluetooth katmanını başlat
        bluetoothMeshManager.start()

        // Bluetooth katmanından gelen paketleri ve keşfedilen cihazları dinlemeye başla
        startListeningForBluetoothEvents()

        Log.d(TAG, "MeshNetworkManager started.")
    }

    fun stop() {
        Log.d(TAG, "MeshNetworkManager stopping...")
        // Bluetooth katmanını durdur
        bluetoothMeshManager.stop()

        // Manager'ın Coroutine Scope'unu iptal et
        managerScope.cancel()

         // Bilinen düğümleri temizle
         knownNodes.clear()

        Log.d(TAG, "MeshNetworkManager stopped.")
    }

    // Bluetooth katmanından gelen event'leri (paketler, keşfedilen cihazlar, bağlantı durumu) dinleyen Coroutine'leri başlatır
    private fun startListeningForBluetoothEvents() {
        managerScope.launch {
            // GATT Server tarafından gelen paketleri dinle
            bluetoothMeshManager.receivedPackets.collect { packet ->
                handleReceivedPacket(packet)
            }
        }

        managerScope.launch {
            // Tarama ile keşfedilen cihazları dinle
            bluetoothMeshManager.discoveredNodes.collect { node ->
                handleDiscoveredNode(node)
            }
        }

        managerScope.launch {
            // GATT Client bağlantı durumu değişikliklerini dinle
            bluetoothMeshManager.clientConnectionState.collect { (deviceAddress, isConnected) ->
                handleClientConnectionStateChange(deviceAddress, isConnected)
            }
        }

        // TODO: Belirli aralıklarla keşfedilen ama bağlı olmadığımız cihazlara bağlanma denemesi planla
        // managerScope.launch { scheduleConnectionAttempts() }

    }

    // --- Gelen Paketleri İşleme ---
    // Gelen bir paketi işler (kaydet, emit) ve yönlendirir (forward).
    private suspend fun handleReceivedPacket(packet: MeshPacket) {
        // Paketin Hop Count'unu bir artırıyoruz, çünkü bu paketi aldık ve şimdi işleyip yeniden göndermeye karar verdik (eğer gerekiyorsa).
        packet.hopCount++

        // Eğer paket FloodingRouter tarafından işlenmesi ve yönlendirilmesi uygun bulunursa
        if (router.shouldProcessAndForward(packet)) {
            Log.i(TAG, "Processing and potentially forwarding packet: ${packet.id} (Type: ${packet.type}, Hops: ${packet.hopCount}) from ${packet.senderId}")

            // 1. Paketi kaydet (Özellikle yardım çağrıları gibi kritik olanları)
            // Ping/Pong gibi ağ mesajlarını kaydetmek istemeyiz.
            if (packet.type == PacketType.DISTRESS || packet.type == PacketType.SAFE || packet.type == PacketType.INFO) {
                saveSignalToDatabase(packet)
            }

            // 2. UI veya diğer dinleyicilere bu sinyalin işlendiğini bildir (SignalEntity olarak yayalım)
             val signalEntity = SignalEntity(
                 id = packet.id,
                 senderId = packet.senderId,
                 type = packet.type,
                 timestamp = packet.timestamp,
                 receivedTimestamp = System.currentTimeMillis(), // Bu cihaza geldiği zaman
                 hopCount = packet.hopCount,
                 latitude = packet.latitude,
                 longitude = packet.longitude,
                 message = packet.message
             )
            _processedSignals.emit(signalEntity)


            // 3. Paketi diğer komşulara yönlendir/yay (Flood)
            forwardPacket(packet)
        } else {
            // Log.d(TAG, "Packet ${packet.id} ignored by router (already seen or max hop).") // Çok sık loglanabilir
        }
    }

    // Gelen veya oluşturulan bir paketi bağlı tüm komşulara yeniden gönderir (Flood mekanizması)
    private fun forwardPacket(packet: MeshPacket) {
        Log.d(TAG, "Forwarding packet ${packet.id} with hop count ${packet.hopCount}...")

        // BluetoothMeshManager'ın Server rolüyle bağlı olan client'lara gönder
        // Bu, bizim sunucumuza bağlanan cihazlardır.
        bluetoothMeshManager.sendPacketToConnectedServerClients(packet)

        // BluetoothMeshManager'ın Client rolüyle bağlı olduğu sunuculara gönder
        // Bu, bizim aktif olarak bağlanmaya çalıştığımız cihazlardır.
        bluetoothMeshManager.sendPacketToConnectedClientPeers(packet)

        // TODO: Belki de farklı gönderim öncelikleri veya strategileri eklenebilir.
        // Örneğin, önce en yüksek RSSI'ye sahip komşulara göndermek.
    }

    // --- Keşfedilen Cihazları İşleme ve Bağlantı Yönetimi ---
    // Tarama ile yeni bir cihaz algılandığında çağrılır
    private fun handleDiscoveredNode(node: MeshNode) {
        Log.d(TAG, "Handle discovered node: ${node.id} (${node.bluetoothDevice.address}), RSSI: ${node.rssi}")

        // Eğer bu düğüm zaten bilinen düğümler listesinde yoksa veya bilgisi güncellenmesi gerekiyorsa
        val existingNode = knownNodes[node.id]
        if (existingNode == null || existingNode.bluetoothDevice.address != node.bluetoothDevice.address || existingNode.lastSeen < node.lastSeen) {
             Log.d(TAG, "Adding/Updating known node: ${node.id}")
             knownNodes[node.id] = node

             // TODO: Eğer bu düğüme henüz bağlı değilsek ve bağlanmak istiyorsak (Client rolü), bağlantı girişimini başlat
             // Bağlanma kararı burada alınmalıdır. Her keşfedilen düğüme anında bağlanmak kaynakları tüketebilir.
             // Daha stratejik bir yaklaşım gerekebilir (örn: sadece belirli sinyal gücünün üzerindekilere bağlan, bir kerede sadece N cihaza bağlı ol).
             if (!node.isConnected) { // Eğer şu anda bağlı değilsek
                 Log.d(TAG, "Considering connecting to new discovered node: ${node.id}")
                 // Basit örnek: Hemen bağlanmayı dene
                 // TODO: Bağlantı havuzu yönetimi ekle (Aynı anda kaç client bağlantısı aktif olabilir?)
                 bluetoothMeshManager.connectToDevice(node.bluetoothDevice)
             }
        } else {
             // Log.d(TAG, "Node ${node.id} already known.") // Çok sık loglanabilir
             // Bilgilerini güncelle (lastSeen, rssi)
             existingNode.lastSeen = node.lastSeen
             existingNode.rssi = node.rssi
             // Bağlantı durumu ScanResult'tan doğru gelmiyor, clientConnectionState'ten güncellenmeli.
        }
    }

    // GATT Client bağlantı durumu değiştiğinde çağrılır (Bizim başkalarına bağlandığımızda)
    private fun handleClientConnectionStateChange(deviceAddress: String, isConnected: Boolean) {
        Log.d(TAG, "Client connection state change for ${deviceAddress}: ${if (isConnected) "CONNECTED" else "DISCONNECTED"}")

        // Bilinen düğümler listesini güncelle
        // MAC adresinden AfetNet ID'sini bulmak gerekebilir
        val nodeToUpdate = knownNodes.values.firstOrNull { it.bluetoothDevice.address == deviceAddress }
        if (nodeToUpdate != null) {
             nodeToUpdate.isConnected = isConnected // Bağlantı durumunu güncelle
             Log.d(TAG, "Updated connection state for node ${nodeToUpdate.id} to ${isConnected}")
             // TODO: UI'ye bağlantı durumu değişikliğini bildirmek için başka bir Flow eklenebilir.
        } else {
             Log.w(TAG, "Connection state changed for unknown device $deviceAddress.")
             // TODO: Eğer disconnect olduysa, belki de bilinmeyen düğümü listeden kaldırmalıyız?
        }

         // TODO: Eğer bağlantı koptuysa (isConnected == false), belirli bir süre sonra yeniden bağlanmayı planla.
         if (!isConnected) {
              Log.d(TAG, "Planning reconnect attempt for $deviceAddress...")
              managerScope.launch {
                   delay(10000) // 10 saniye sonra yeniden bağlanmayı dene
                   // reconnectToDevice(deviceAddress) // Bu metot yazılmalı ve BluetoothMeshManager'ı kullanmalı
              }
         }
    }

    // --- Sinyal Gönderme (Kullanıcı Eylemi veya Otomatik) ---
    // UI veya başka bir bileşen bu metodu çağırarak sinyal gönderebilir.
    fun sendSignal(type: PacketType, message: String? = null, latitude: Double? = null, longitude: Double? = null) {
        managerScope.launch {
            val myDeviceId = UniqueDeviceId.getDeviceId(context)
            // Yeni bir MeshPacket oluştur
            val packet = MeshPacket(
                id = UUID.randomUUID().toString(), // Yeni benzersiz paket ID'si
                senderId = myDeviceId,
                type = type,
                timestamp = System.currentTimeMillis(),
                hopCount = 0, // Kendi oluşturduğumuz için başlangıç hop sayısı 0
                latitude = latitude,
                longitude = longitude,
                message = message
            )
            Log.i(TAG, "Initiating send for packet: ${packet.id} (Type: ${packet.type})")

            // Kendi oluşturduğumuz paketi de işleme alalım (Kaydedilmesi ve yönlendirilmesi için)
            // hopCount'u 0'dan 1 yapıp handleReceivedPacket metoduna göndermek mantıklı
            // handleReceivedPacket çağırılırsa, aynı işleme mantığından geçer. Hop count'u orada artırıyoruz.
            // Paketi gönderirken HopCount 0 olarak gitmeli, bu cihaz paketi alınca 1 yapıp yeniden göndermeli.
            // Yani handleReceivedPacket'e göndermeden önce HopCount'u ayarlamak daha doğru olabilir.
            // Veya handleReceivedPacket metodunu gelen paketler ve kendi gönderdiklerimiz için farklı işleyebiliriz.
            // Şimdilik, oluşturulan paketin HopCount'u 0 olsun ve forwardPacket metodunda gönderilirken artırılsın.
             // Ama Flood router'ın shouldProcessAndForward metodunun da buna göre ayarlanması gerekir (HopCount 0 iken hep işle).

             // Alternatif ve daha temiz: Paketi oluştur, kaydet (isteğe bağlı), emit et (isteğe bağlı), doğrudan forwardPacket'e gönder.
             val signalEntity = SignalEntity( // Entity oluşturup emit etmek
                 id = packet.id,
                 senderId = packet.senderId,
                 type = packet.type,
                 timestamp = packet.timestamp,
                 receivedTimestamp = System.currentTimeMillis(), // Kendi cihazımıza geldiği zaman
                 hopCount = packet.hopCount, // Kendi oluşturduğumuz için 0 olacak
                 latitude = packet.latitude,
                 longitude = packet.longitude,
                 message = packet.message
             )

            if (packet.type == PacketType.DISTRESS || packet.type == PacketType.SAFE || packet.type == PacketType.INFO) {
                 saveSignalToDatabase(packet) // Kendi gönderdiğimizi de kaydet
            }
            _processedSignals.emit(signalEntity) // UI'ye bildir


            // Paketi ağa yay (Flood)
            forwardPacket(packet) // HopCount burada artırılmalı veya forwardPacket içinde artırılıp gönderilmeli

            // TODO: Belki kendi cihaz durumumuzu belirten BLE reklam verisini güncelleme mekanizması ekle.
        }
    }


    // --- Veritabanı İşlemleri ---
    private suspend fun saveSignalToDatabase(packet: MeshPacket) {
        val signalEntity = SignalEntity(
            id = packet.id,
            senderId = packet.senderId,
            type = packet.type,
            timestamp = packet.timestamp,
            receivedTimestamp = System.currentTimeMillis(), // Bu cihaza geldiği zaman
            hopCount = packet.hopCount,
            latitude = packet.latitude,
            longitude = packet.longitude,
            message = packet.message
        )
        try {
            val result = signalDao.insertSignal(signalEntity)
            if (result != -1L) {
                // Log.d(TAG, "Signal ${packet.id} saved to database.") // Çok sık loglanabilir
            } else {
                 // Log.d(TAG, "Signal ${packet.id} already exists in database, ignored insertion.") // Çok sık loglanabilir
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving signal ${packet.id} to database: ${e.message}", e)
        }
    }

    // UI'nin veritabanından sinyalleri alması için metotlar (Flow döndürüyor)
    fun getDistressSignals(): Flow<List<SignalEntity>> {
        return signalDao.getDistressSignals() // Veritabanı değiştikçe otomatik güncellenir
    }

     fun getAllSignals(): Flow<List<SignalEntity>> {
        return signalDao.getAllSignals() // Tüm sinyalleri döndürür
    }

    // TODO: Belirli bir sinyali veritabanından al (detay ekranı için)
    // suspend fun getSignalById(signalId: String): SignalEntity? {
    //     return signalDao.getSignalById(signalId)
    // }

    // TODO: Ağdaki bilinen düğümleri (knownNodes) UI'ye bildiren bir Flow ekle
    // val knownNodesFlow = MutableStateFlow<List<MeshNode>>(emptyList())
    // knownNodes listesi değiştiğinde knownNodesFlow.value = knownNodes.values.toList() çağırılabilir.

}