--- START OF FILE app/src/main/java/com.example/appnet/afetnet/mesh/BluetoothMeshManager.kt (UPDATED WITH GATT CLIENT) ---

package com.example.appnet.afetnet.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.appnet.afetnet.util.Constants
import com.example.appnet.afetnet.util.UniqueDeviceId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

// Bluetooth LE işlemlerini yöneten sınıf (Tarama, Reklam Verme, GATT Sunucusu, GATT Client)
// NOT: Birden fazla GATT Client bağlantısını yönetmek oldukça karmaşıktır ve bu kod temel bir başlangıçtır.
// Gerçek bir Mesh için çok daha gelişmiş bir bağlantı yönetimi ve yeniden deneme mantığı gereklidir.
class BluetoothMeshManager(private val context: Context) {

    private val TAG = "BluetoothMeshManager"

    // Bluetooth Adapter'ı
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // BLE Tarayıcı
    private var bleScanner: BluetoothLeScanner? = null

    // BLE Reklam Verici
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    // GATT Sunucusu (Diğer cihazlar bize bağlanır)
    private var gattServer: BluetoothGattServer? = null

    // GATT Client'lar (Biz diğer cihazlara bağlanırız) - Birden fazla bağlantı olabileceği için Map kullanıyoruz
    // Key: Cihazın MAC Adresi, Value: BluetoothGatt (Client) objesi
    private val connectedGattClients = ConcurrentHashMap<String, BluetoothGatt>()

    // Gelen Sinyalleri Yaymak İçin (Coroutine Flow) - Hem Server hem Client tarafından gelen paketler
    private val _receivedPackets = MutableSharedFlow<MeshPacket>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedPackets: SharedFlow<MeshPacket> = _receivedPackets // Dışarıya açılan immutable Flow

    // Keşfedilen Cihazları Yaymak İçin (Coroutine Flow) - Tarama ile algılanan cihazlar
    private val _discoveredNodes = MutableSharedFlow<MeshNode>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val discoveredNodes: SharedFlow<MeshNode> = _discoveredNodes

    // Cihaz Bağlantı Durumu Değişikliklerini Yaymak İçin (Client rolüyle bağlandığımız cihazlar için)
    // Key: Cihazın MAC Adresi, Value: Boolean (true=bağlandı, false=bağlantı koptu)
    private val _clientConnectionState = MutableSharedFlow<Pair<String, Boolean>>(
         extraBufferCapacity = 10,
         onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val clientConnectionState: SharedFlow<Pair<String, Boolean>> = _clientConnectionState.asSharedFlow()


    // Coroutine Kapsamı - Bu manager'ın yaşam döngüsüne bağlı olacak
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // Arka plan işlemleri için IO dispatcher

    // Cihazın Kendi Benzersiz ID'si
    private lateinit var myDeviceId: String

    // --- İzin Kontrolü ---
    // (Daha önceki kodla aynı, izinlerin verilip verilmediğini kontrol eder)
    @SuppressLint("MissingPermission")
    private fun hasPermissions(): Boolean {
       // ... (Önceki kodunuzdan kopyala) ...
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ (API 31+)
            val bluetoothScanPermission = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val bluetoothAdvertisePermission = context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val bluetoothConnectPermission = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
             val locationPermission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
             Log.d(TAG, "API ${Build.VERSION.SDK_INT}: SCAN=$bluetoothScanPermission, ADVERTISE=$bluetoothAdvertisePermission, CONNECT=$bluetoothConnectPermission, LOCATION=$locationPermission")
            return bluetoothScanPermission && bluetoothAdvertisePermission && bluetoothConnectPermission && locationPermission
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10, 11 (API 29, 30)
             val locationPermission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
             Log.d(TAG, "API ${Build.VERSION.SDK_INT}: LOCATION=$locationPermission")
             return locationPermission
        } else { // Android 6 - 9 (API 23-28)
             val locationPermission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
             Log.d(TAG, "API ${Build.VERSION.SDK_INT}: LOCATION=$locationPermission")
             return locationPermission
        }
    }

    // Bluetooth'un açık olup olmadığını ve desteklenip desteklenmediğini kontrol eder
    private fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

     // Bu sınıfı başlatırken çağrılacak metot
    fun start() {
        Log.d(TAG, "BluetoothMeshManager starting...")

        // Kendi cihaz ID'mizi al
        myDeviceId = UniqueDeviceId.getDeviceId(context)
        Log.d(TAG, "My Device ID: $myDeviceId")

        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled.")
            // TODO: Kullanıcıya Bluetooth'u açması gerektiğini bildirin (Genellikle Activity'de yapılır)
            return
        }

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "Bluetooth LE is not supported on this device.")
            // TODO: Kullanıcıya cihazının BLE desteklemediğini bildirin (Genellikle Activity'de yapılır)
            return
        }

         // Runtime izinleri kontrol et (Bu metod Service içinde çağrıldığında izinlerin zaten verilmiş olması beklenir)
        if (!hasPermissions()) {
            Log.e(TAG, "Required Bluetooth/Location permissions are not granted.")
             // TODO: Kullanıcıyı izinleri vermesi konusunda bilgilendirin (Genellikle Activity'de yapılır)
            return
        }

        // Tarama, reklam verme ve GATT sunucusunu başlat
        startScanning()
        startAdvertising()
        setupGattServer()

        Log.d(TAG, "BluetoothMeshManager started successfully.")
    }

    // Bu sınıfı durdururken çağrılacak metot
    fun stop() {
        Log.d(TAG, "BluetoothMeshManager stopping...")

        // Tarama, reklam verme ve GATT sunucusunu durdur
        stopScanning()
        stopAdvertising()
        closeGattServer()
         closeConnectedGattClients() // Bağlı GATT client'ları da kapat

        // Coroutine Scope'u iptal et
        managerScope.cancel() // serviceScope yerine managerScope kullanıyoruz

        Log.d(TAG, "BluetoothMeshManager stopped.")
    }


    // --- BLE Tarama (Scanning) ---
    // (Daha önceki kodla aynı, sadece log'lar ve filtreleme güncellendi)
    private val scanCallback = object : ScanCallback() {
       // ... (Önceki kodunuzdan kopyala) ...
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let {
                val scanRecord = it.scanRecord
                val serviceUuids = scanRecord?.serviceUuids

                if (serviceUuids != null && serviceUuids.contains(ParcelUuid(Constants.AFETNET_SERVICE_UUID))) {
                     // Reklam verisinden cihazın AfetNet ID'sini almaya çalış
                     val deviceIdBytes = scanRecord.getServiceData(ParcelUuid(Constants.AFETNET_SERVICE_UUID))
                     val discoveredDeviceId = if (deviceIdBytes != null && deviceIdBytes.isNotEmpty()) {
                          try { String(deviceIdBytes, StandardCharsets.UTF_8) } catch (e: Exception) { it.device.address } // Hata olursa MAC adresi
                     } else {
                         it.device.address // Reklam verisinde ID yoksa MAC adresi
                     }

                    if (discoveredDeviceId != myDeviceId) { // Kendi cihazımızı keşfettiğimizde yayınlama
                       Log.i(TAG, "Discovered AfetNet device: ${discoveredDeviceId} (${it.device.address}), RSSI: ${it.rssi}")
                       managerScope.launch { // serviceScope yerine managerScope kullanıyoruz
                           val meshNode = MeshNode(
                                id = discoveredDeviceId,
                                bluetoothDevice = it.device,
                                lastSeen = System.currentTimeMillis(),
                                rssi = it.rssi,
                                isConnected = connectedGattClients.containsKey(it.device.address) // Client olarak bağlı mıyız kontrol et
                            )
                           // Log.d(TAG, "Emitting discovered node: ${meshNode.id}") // Çok fazla log olabilir
                           _discoveredNodes.emit(meshNode)
                       }
                    }
                }
            }
        }
       // ... (Diğer callback'ler: onBatchScanResults, onScanFailed - Önceki kodunuzdan kopyala) ...
         override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { onScanResult(ScanCallback.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE Scan Failed: $errorCode")
            // TODO: Hata durumunu ele al ve yeniden denemeyi planla
             managerScope.launch { // managerScope kullanıyoruz
                 delay(Constants.SCAN_INTERVAL_MS) // Belirli bir süre bekle
                 startScanning() // Tekrar taramayı başlat
             }
        }
    }

    // BLE taramayı başlatır
    @SuppressLint("MissingPermission")
    fun startScanning() {
       // ... (Önceki kodunuzdan kopyala) ...
        if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot start scanning: Permissions or Bluetooth not ready.")
            return
        }

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            Log.e(TAG, "Failed to get BLE scanner.")
            // TODO: Kullanıcıya cihazının BLE desteklemediğini bildir (Reklam verme gibi)
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // Toplu alımdan ziyade anında bildirim
            .setReportDelay(0L) // Sonuçları anında bildir
            .build()

        // Sadece bizim servis UUID'mizi yayınlayan cihazları filtrele
        // Reklam verisindeki cihaz ID'sini de filtreye eklemek daha verimli olabilir ama reklam verisi boyutu sınırlı.
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.AFETNET_SERVICE_UUID))
             // TODO: Eğer reklam verisinde ID'yi taşıyabiliyorsak, burada filtreleme ekleyebiliriz:
             // .setServiceData(ParcelUuid(Constants.AFETNET_SERVICE_UUID), myDeviceId.toByteArray(StandardCharsets.UTF_8).copyOfRange(0, minOf(myDeviceId.toByteArray().size, Constants.ADVERTISED_ID_SIZE)))
            .build()

        val filters = listOf(scanFilter)

        try {
            bleScanner?.startScan(filters, scanSettings, scanCallback)
            Log.d(TAG, "BLE Scanning started.")
            // TODO: Pil tasarrufu için taramayı belirli aralıklarla durdurup tekrar başlatan bir mekanizma ekle
            // (onScanFailed içinde yeniden başlatma ekledik, bu sürekli çalıştırma sayılabilir ama daha kontrollü olabilir)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan: ${e.message}", e)
             // TODO: Hatayı ele al (izin yok, bluetooth kapalı gibi)
        }
    }

    // BLE taramayı durdurur
    @SuppressLint("MissingPermission")
    fun stopScanning() {
       // ... (Önceki kodunuzdan kopyala) ...
         if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot stop scanning: Permissions or Bluetooth not ready.")
            return
        }
        bleScanner?.stopScan(scanCallback)
        Log.d(TAG, "BLE Scanning stopped.")
        // bleScanner = null // Kaynakları serbest bırakmak iyi olabilir.
    }


    // --- BLE Reklam Verme (Advertising) ---
    // (Daha önceki kodla aynı, sadece reklam verisine ID ekleme denemesi ve loglar güncellendi)

    private val advertiseCallback = object : AdvertiseCallback() {
       // ... (Önceki kodunuzdan kopyala) ...
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "BLE Advertising started successfully.")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            val errorMsg = when(errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Failed to start advertising: Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Failed to start advertising: Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Failed to start advertising: Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Failed to start advertising: Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Failed to start advertising: Feature unsupported"
                else -> "Failed to start advertising: Unknown error $errorCode"
            }
            Log.e(TAG, errorMsg)
            // TODO: Hata durumunu ele al, belki yeniden denemeyi planla
             managerScope.launch { // managerScope kullanıyoruz
                 delay(5000) // 5 saniye sonra
                 startAdvertising() // Tekrar dene
             }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
       // ... (Önceki kodunuzdan kopyala) ...
         if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot start advertising: Permissions or Bluetooth not ready.")
            return
        }

        if (bluetoothAdapter?.isMultipleAdvertisementSupported != true) {
            Log.e(TAG, "BLE advertising is not supported on this device.")
             // TODO: Kullanıcıya bilgi ver
             return
        }

        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            Log.e(TAG, "Failed to get BLE advertiser.")
            return
        }

        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0) // Sürekli reklam ver (Pil tüketir!)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .build()

         // Reklam verisine servis UUID'si ve Cihaz ID'sinin bir kısmını ekle
         // Cihaz ID'si uzun olabilir, reklam verisi boyutu sınırlıdır (~31 byte).
         // Servis UUID'si 16 byte yer kaplar. ID için kalan 15 byte'ı kullanabiliriz.
        val deviceIdBytes = myDeviceId.toByteArray(StandardCharsets.UTF_8)
        val serviceData = deviceIdBytes.copyOfRange(0, minOf(deviceIdBytes.size, 15)) // ID'nin ilk 15 byte'ı gibi

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Constants.AFETNET_SERVICE_UUID))
            .addServiceData(ParcelUuid(Constants.AFETNET_SERVICE_UUID), serviceData) // Servis verisine ID ekle
            .setIncludeDeviceName(false)
            // .setIncludeTxPowerLevel(false) // Tx gücünü dahil etme (veri boyutunu artırır)
            .build()

         // Tarama yanıt verisi de eklenebilir (daha fazla veri taşımak için ama yine sınırlı)
         // val scanResponse = AdvertiseData.Builder()
         //      .setIncludeDeviceName(true) // Cihaz adını ekle
         //      .build()

        try {
            // startAdvertising(advertiseSettings, advertiseData, scanResponse, advertiseCallback) // Scan Response ile
            bleAdvertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback) // Sadece Advertise Data ile
            Log.d(TAG, "BLE Advertising started with data: ${advertiseData}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE advertising: ${e.message}", e)
             // TODO: Hatayı ele al (izin yok, donanım desteklemiyor, çok fazla advertiser vb.)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
       // ... (Önceki kodunuzdan kopyala) ...
        if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot stop advertising: Permissions or Bluetooth not ready.")
            return
        }
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        Log.d(TAG, "BLE Advertising stopped.")
        // bleAdvertiser = null // Kaynakları serbest bırakmak iyi olabilir.
    }


     // --- BLE GATT Sunucusu (Server) ---
    // (Daha önceki kodla aynı, Client Characteristic Configuration Descriptor (CCCD) yönetimi eklendi)

    private val gattServerCallback = object : BluetoothGattServerCallback() {
       // ... (Önceki kodunuzdan kopyala) ...
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
           // ... (Loglama) ...
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device ${device?.address} connected to GATT server.")
                 // TODO: Bağlanan cihazı (MeshNode) bağlantı listesine ekle (MeshNetworkManager'da tutulabilir)
                 // Bağlanan cihazın ID'sini nasıl alacağız? Belki ilk veri paketini gönderdiğinde?
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device ${device?.address} disconnected from GATT server.")
                // TODO: Ayrılan cihazı listeden çıkar (MeshNetworkManager'da)
                 // Bu cihaz eğer Client olarak bağlıysak, o bağlantıyı da yönetmeliyiz.
            }
        }

       // onCharacteristicWriteRequest: Gelen paketleri deserialize edip _receivedPackets'e emit eder (Önceki kodla aynı mantık)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            // ... (Önceki kodunuzdan kopyala - Paket işleme, deserializasyon, _receivedPackets'e emit) ...
            val deviceAddress = device?.address ?: "Unknown"
             if (characteristic?.uuid == Constants.MESSAGE_SEND_CHARACTERISTIC_UUID) {
                if (responseNeeded) {
                     gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                 if (value != null && value.isNotEmpty()) {
                     managerScope.launch { // managerScope kullanıyoruz
                        val meshPacket = PacketHandler.deserialize(value)
                         if (meshPacket != null) {
                             if (meshPacket.senderId != myDeviceId) {
                                 Log.i(TAG, "GATT Server: Received MeshPacket ${meshPacket.id} from ${meshPacket.senderId} (via ${deviceAddress})")
                                _receivedPackets.emit(meshPacket) // İşlenmek üzere paketi yay
                             } else {
                                 Log.d(TAG, "GATT Server: Received my own packet ${meshPacket.id} from $deviceAddress. Ignoring.")
                             }
                         } else {
                             Log.w(TAG, "GATT Server: Failed to deserialize received data from $deviceAddress")
                         }
                     }
                 }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

       // onDescriptorWriteRequest: Client'ın bildirimleri açtığını/kapattığını işler (CCCD)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
           // ... (Önceki kodunuzdan kopyala - CCCD yazma isteği işleme) ...
             val deviceAddress = device?.address ?: "Unknown"
             if (descriptor?.uuid == UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")) {
                 if (descriptor.characteristic.uuid == Constants.MESSAGE_RECEIVE_CHARACTERISTIC_UUID) {

                     val isNotificationsEnabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                     // TODO: Bu cihazın bildirimleri açtığını veya kapattığını kaydedin.
                     // notifyCharacteristicChanged metodunda bu bilgiyi kullanarak sadece bildirim isteyenlere gönderin.
                     // val clientAddress = device?.address // Bu adresi kaydedin ve durumu (enabled/disabled) ile eşleyin.

                     if (responseNeeded) {
                         gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                     }
                 } else {
                      if (responseNeeded) { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null) }
                 }
            } else {
                 if (responseNeeded) { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null) }
            }
        }
        // Diğer callback'ler (onCharacteristicReadRequest, onDescriptorReadRequest, onExecuteWrite, onNotificationSent, onServiceAdded)
       // (Önceki kodunuzdan kopyala - Temel implementasyonlar)
         override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, offset: Int) {
             super.onCharacteristicReadRequest(device, requestId, characteristic, offset)
              Log.d(TAG, "GATT Server: Read request for characteristic ${characteristic?.uuid} from ${device?.address}. Denying.")
              gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
         }

         override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, offset: Int) {
            super.onDescriptorReadRequest(device, requestId, descriptor, offset)
             Log.d(TAG, "GATT Server: Read request for descriptor ${descriptor?.uuid} from ${device?.address}. Denying (for now).")
            // CCCD okuma isteği geldiğinde bildirim durumunu döndürmeliyiz.
             // Şimdilik hepsini reddedelim veya varsayılan kapalı durumunu döndürelim.
             if (descriptor?.uuid == UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")) {
                  val valueToSend = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE // Varsayılan olarak kapalı
                 gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, valueToSend)
             } else {
                 gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
             }
         }

         override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
             super.onExecuteWrite(device, requestId, execute)
             Log.d(TAG, "GATT Server: onExecuteWrite from ${device?.address}, execute: $execute")
         }

         override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            super.onNotificationSent(device, status)
            val statusText = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILURE ($status)"
            // Log.d(TAG, "GATT Server: Notification sent to ${device?.address} status: $statusText") // Çok fazla log üretebilir
         }

         override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            val statusText = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILURE ($status)"
             Log.d(TAG, "GATT Server: Service added status: $statusText, UUID: ${service?.uuid}")
         }

    }

    // GATT sunucusunu kurar ve başlatır
    @SuppressLint("MissingPermission")
    fun setupGattServer() {
       // ... (Önceki kodunuzdan kopyala) ...
        if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot setup GATT server: Permissions or Bluetooth not ready.")
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        // openGattServer Main Thread'de çağrılmalıdır. callback'ler de Main Thread'de tetiklenir.
        // Bu callback'ler içindeki yoğun işlemleri (deserialize, emit) ayrı Coroutine'lerde yapmak bu yüzden önemli.
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server.")
            // TODO: Hata ele al, kullanıcıya bilgi ver
            return
        }

        val meshService = BluetoothGattService(
            Constants.AFETNET_SERVICE_UUID,
            BluetoothGattService.TYPE_PRIMARY
        )

        val messageSendCharacteristic = BluetoothGattCharacteristic(
            Constants.MESSAGE_SEND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val messageReceiveCharacteristic = BluetoothGattCharacteristic(
            Constants.MESSAGE_RECEIVE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, // Notify kullanıyoruz, Indication değil şimdilik (daha hafif)
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccdDescriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"), // CCCD
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        messageReceiveCharacteristic.addDescriptor(cccdDescriptor)


        meshService.addCharacteristic(messageSendCharacteristic)
        meshService.addCharacteristic(messageReceiveCharacteristic)

        gattServer?.addService(meshService)
        Log.d(TAG, "GATT Server setup attempt complete. Waiting for onServiceAdded callback.")
    }

    // GATT sunucusunu kapatır
    @SuppressLint("MissingPermission")
    fun closeGattServer() {
        Log.d(TAG, "Closing GATT server.")
         // Bağlı client'ların bağlantısını koparmak iyi bir pratiktir
         gattServer?.connectedDevices?.forEach { device ->
              try { gattServer?.cancelConnection(device) } catch (e: Exception) { Log.e(TAG, "Error cancelling server connection: ${e.message}") }
         }
        gattServer?.close()
        gattServer = null
    }

    // Bağlı tüm GATT client'lara (Server rolüyle bağlananlara) bir paket gönderir
    @SuppressLint("MissingPermission")
    fun sendPacketToConnectedServerClients(packet: MeshPacket) { // Metot adını daha açık hale getirdik
       // ... (Önceki kodunuzdan kopyala - Paket serileştirme, characteristic alma) ...
        if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot send packet to connected server clients: Permissions or Bluetooth not ready.")
            return
        }
        if (gattServer == null || gattServer?.getService(Constants.AFETNET_SERVICE_UUID) == null) {
             Log.w(TAG, "Cannot send packet to connected server clients: GATT server or service not ready.")
             return
        }

        val characteristic = gattServer?.getService(Constants.AFETNET_SERVICE_UUID)
            ?.getCharacteristic(Constants.MESSAGE_RECEIVE_CHARACTERISTIC_UUID)

        if (characteristic == null) {
            Log.e(TAG, "Cannot send packet to connected server clients: Message Receive Characteristic not found on server.")
            return
        }

        val bytes = PacketHandler.serialize(packet)
        if (bytes == null || bytes.isEmpty()) {
            Log.e(TAG, "Cannot send packet to connected server clients: Serialization resulted in null or empty data.")
            return
        }

        val connectedDevices = gattServer?.connectedDevices ?: emptyList()

        if (connectedDevices.isEmpty()) {
             // Log.d(TAG, "No clients connected to GATT server to send packet ${packet.id}.") // Çok sık loglanabilir
             return
        }

        Log.d(TAG, "Attempting to send packet ${packet.id} to ${connectedDevices.size} connected server clients.")

        connectedDevices.forEach { device ->
             // TODO: Sadece bildirimleri açmış client'lara gönderilmeli (onDescriptorWriteRequest'te tutulan listeye bakılmalı)
            characteristic.value = bytes
            try {
                 // Notification: client'tan onay beklemez, daha hızlı. Indication: client'tan onay bekler. Notification daha uygun.
                 val success = gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                 if (success == true) {
                     // Log.d(TAG, "Successfully sent notification for packet ${packet.id} to server client ${device.address}") // Çok sık loglanabilir
                 } else {
                     Log.w(TAG, "Failed to send notification for packet ${packet.id} to server client ${device.address}")
                     // TODO: Bağlantı sorununu ele al
                 }
            } catch (e: Exception) {
                Log.e(TAG, "Exception sending notification to server client ${device.address}: ${e.message}", e)
            }
        }
    }


    // --- BLE GATT Client ---
    // Bu kısım, bizim başka bir cihazın (Server) servisine bağlanıp veri alıp göndermemizi sağlar.

    // Bir BluetoothDevice objesine GATT Client olarak bağlanır
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot connect to device: Permissions or Bluetooth not ready.")
            return
        }
         // Cihaz zaten bağlıysa tekrar bağlanma
        if (connectedGattClients.containsKey(device.address)) {
             Log.d(TAG, "Already connected to device ${device.address} as client.")
             return
        }
         // Bağlantı kurma girişimini başlat
        Log.d(TAG, "Attempting to connect to device ${device.address} as GATT client.")
         // connectGatt Main thread'de çağrılmalı, callback'ler de Main thread'de tetiklenir
        try {
             val gattClient = device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
             if (gattClient != null) {
                 // Bağlantı girişimi başarılı. callback'leri bekleyeceğiz.
                 Log.d(TAG, "GATT client connection attempt started for ${device.address}")
                 // connectedGattClients[device.address] = gattClient // Bağlantı başarılı olduğunda ekleyeceğiz
             } else {
                 Log.e(TAG, "Failed to start GATT client connection for ${device.address}")
                 managerScope.launch { // Bağlantı hatasını bildir
                     _clientConnectionState.emit(Pair(device.address, false))
                 }
             }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting GATT client connection for ${device.address}: ${e.message}", e)
             managerScope.launch { // Bağlantı hatasını bildir
                 _clientConnectionState.emit(Pair(device.address, false))
             }
        }
    }

    // Bağlı tüm GATT client bağlantılarını (Client rolüyle kurduğumuz) kapatır
    @SuppressLint("MissingPermission")
    private fun closeConnectedGattClients() {
        Log.d(TAG, "Closing all connected GATT clients.")
        connectedGattClients.values.forEach { gatt ->
             try {
                 gatt.disconnect() // Disconnect isteği gönder
                 gatt.close() // Kaynakları serbest bırak
             } catch (e: Exception) {
                 Log.e(TAG, "Error closing GATT client connection: ${e.message}")
             }
        }
        connectedGattClients.clear()
         Log.d(TAG, "All GATT clients closed.")
    }

    // Bir belirli GATT client bağlantısını kapatır
    @SuppressLint("MissingPermission")
    fun disconnectFromDevice(deviceAddress: String) {
         val gattClient = connectedGattClients[deviceAddress]
         if (gattClient != null) {
             Log.d(TAG, "Disconnecting from GATT client ${deviceAddress}")
             try {
                 gattClient.disconnect()
                // disconnect() sonrası onConnectionStateChange(DISCONNECTED) tetiklenir, orada map'ten kaldırılır.
             } catch (e: Exception) {
                  Log.e(TAG, "Error disconnecting from GATT client ${deviceAddress}: ${e.message}")
                 // Hata oluştuysa doğrudan kapatmayı dene
                 try { gattClient.close() } catch (e2: Exception) { Log.e(TAG, "Error closing GATT client ${deviceAddress} after disconnect fail: ${e2.message}") }
                 connectedGattClients.remove(deviceAddress)
                  managerScope.launch { _clientConnectionState.emit(Pair(deviceAddress, false)) }
             }
         } else {
              Log.w(TAG, "Attempted to disconnect from unknown GATT client ${deviceAddress}")
         }
    }


    // GATT Client Geri Çağrımları (Callback) - Başka bir cihazın sunucusuna bağlandığımızda olaylar
    private val gattClientCallback = object : BluetoothGattCallback() {
        // Bağlantı durumu değiştiğinde çağrılır (Bizim başka bir cihaza bağlantımız)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            val deviceAddress = gatt?.device?.address ?: "Unknown"
            val statusText = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILURE ($status)"
             val newStateText = when(newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> newState.toString()
            }
            Log.i(TAG, "GATT Client: Connection state change for $deviceAddress, status: $statusText, newState: $newStateText")

            managerScope.launch { // managerScope kullanıyoruz
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                     Log.d(TAG, "GATT Client: Successfully connected to ${deviceAddress}. Discovering services...")
                     connectedGattClients[deviceAddress] = gatt!! // Bağlantı başarılı, objeyi kaydet
                     _clientConnectionState.emit(Pair(deviceAddress, true)) // Bağlantı başarılıyı bildir
                     // Hizmetleri keşfetmeye başla
                     @SuppressLint("MissingPermission") // İzin kontrolü start() veya Activity'de yapılıyor
                     val discovered = gatt.discoverServices()
                     if (!discovered) {
                         Log.e(TAG, "GATT Client: discoverServices failed for ${deviceAddress}")
                         // TODO: Bağlantıyı kopar ve yeniden dene
                         disconnectFromDevice(deviceAddress)
                     }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                     Log.w(TAG, "GATT Client: Disconnected from ${deviceAddress}. Status: $statusText")
                     connectedGattClients.remove(deviceAddress) // Map'ten kaldır
                    // Kaynakları serbest bırak
                     try { gatt?.close() } catch (e: Exception) { Log.e(TAG, "Error closing GATT client ${deviceAddress} during disconnect: ${e.message}") }
                     _clientConnectionState.emit(Pair(deviceAddress, false)) // Bağlantı koptu bildir
                    // TODO: Yeniden bağlanmayı planla (Arka planda sürekli denenmemeli, enerji tüketir)
                }
            }
        }

        // Hizmet keşfi tamamlandığında çağrılır
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            val deviceAddress = gatt?.device?.address ?: "Unknown"
            val statusText = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILURE ($status)"
            Log.d(TAG, "GATT Client: Services discovered for $deviceAddress, status: $statusText")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // AfetNet servisimizi bulmaya çalış
                val meshService = gatt?.getService(Constants.AFETNET_SERVICE_UUID)
                if (meshService != null) {
                    Log.i(TAG, "GATT Client: AfetNet Service found on $deviceAddress.")
                    // Mesaj Gönderme ve Alma characteristic'lerini bul
                    val sendCharacteristic = meshService.getCharacteristic(Constants.MESSAGE_SEND_CHARACTERISTIC_UUID)
                    val receiveCharacteristic = meshService.getCharacteristic(Constants.MESSAGE_RECEIVE_CHARACTERISTIC_UUID)

                    if (sendCharacteristic != null && receiveCharacteristic != null) {
                        Log.d(TAG, "GATT Client: Required characteristics found on $deviceAddress.")
                        // TODO: Artık bu cihaza veri gönderebilir (sendCharacteristic'e yazarak) ve
                        // ondan veri alabiliriz (receiveCharacteristic'in bildirimlerine abone olarak).

                        // Alıcı characteristic'in bildirimlerine (notifications) abone ol
                        enableNotifications(gatt, receiveCharacteristic)

                         // TODO: Cihazın AfetNet ID'sini öğrenmek için bir mekanizma ekle.
                        // Belki reklam verisinde taşındı (tarama callback'inde yakalandı).
                        // Belki bağlanınca ilk paket olarak gönderiliyor.
                        // Belki de ayrı bir characteristic'ten okunuyor (şimdilik yapmadık).

                    } else {
                         Log.e(TAG, "GATT Client: Missing required characteristics on $deviceAddress.")
                         // TODO: Bağlantıyı kopar, bu cihaz bizim protokolümüzü tam desteklemiyor olabilir.
                         disconnectFromDevice(deviceAddress)
                    }
                } else {
                    Log.e(TAG, "GATT Client: AfetNet Service not found on $deviceAddress.")
                    // TODO: Bağlantıyı kopar, bu cihaz AfetNet cihazı değil.
                     disconnectFromDevice(deviceAddress)
                }
            } else {
                Log.e(TAG, "GATT Client: Service discovery failed for $deviceAddress with status $status.")
                 // TODO: Bağlantıyı kopar ve yeniden denemeyi planla.
                 disconnectFromDevice(deviceAddress)
            }
        }

        // Characteristic'e yazma (send) işlemi tamamlandığında çağrılır (writeCharacteristic)
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            val deviceAddress = gatt?.device?.address ?: "Unknown"
            val statusText = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILURE ($status)"
            // Log.d(TAG, "GATT Client: Characteristic ${characteristic?.uuid} write status for $deviceAddress: $statusText") // Çok fazla log üretebilir
             if (status != BluetoothGatt.GATT_SUCCESS) {
                  Log.e(TAG, "GATT Client: Characteristic ${characteristic?.uuid} write failed for $deviceAddress with status $status.")
                 // TODO: Yazma hatasını ele al (paket kaybolmuş olabilir)
             }
        }

        // Characteristic'ten bildirim veya indication geldiğinde çağrılır (receive)
        // Burası diğer cihazlardan (Server'lardan) gelen sinyalleri aldığımız yerdir!
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
             val deviceAddress = gatt?.device?.address ?: "Unknown"
            val value = characteristic?.value // Gelen veri burada!

            if (characteristic?.uuid == Constants.MESSAGE_RECEIVE_CHARACTERISTIC_UUID) {
                if (value != null && value.isNotEmpty()) {
                     // Log.d(TAG, "GATT Client: Received data from $deviceAddress via Notification.") // Log'u sadeleştirelim
                     // Veriyi PacketHandler ile MeshPacket'e dönüştür ve işle
                     managerScope.launch { // managerScope kullanıyoruz
                        val meshPacket = PacketHandler.deserialize(value)
                         if (meshPacket != null) {
                             if (meshPacket.senderId != myDeviceId) {
                                 Log.i(TAG, "GATT Client: Received valid MeshPacket ${meshPacket.id} from ${meshPacket.senderId} (via ${deviceAddress})")
                                _receivedPackets.emit(meshPacket) // İşlenmek üzere paketi yay
                             } else {
                                Log.d(TAG, "GATT Client: Received my own packet ${meshPacket.id} from $deviceAddress. Ignoring.")
                             }
                         } else {
                             Log.w(TAG, "GATT Client: Failed to deserialize received data from $deviceAddress via Notification.")
                         }
                     }
                } else {
                    Log.w(TAG, "GATT Client: Received empty data from $deviceAddress via Notification.")
                }
            } else {
                 Log.w(TAG, "GATT Client: Received notification for unknown characteristic ${characteristic?.uuid} from $deviceAddress")
            }
        }

        // Descriptor'a yazma (CCCD'yi açma/kapama) işlemi tamamlandığında çağrılır
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
             val deviceAddress = gatt?.device?.address ?: "Unknown"
            val statusText = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILURE ($status)"
            Log.d(TAG, "GATT Client: Descriptor ${descriptor?.uuid} write status for $deviceAddress: $statusText")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                 // Bildirimler başarıyla açıldı veya kapatıldı
                 if (descriptor?.uuid == UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")) {
                      Log.d(TAG, "GATT Client: Successfully enabled/disabled notifications for ${descriptor.characteristic.uuid} on ${deviceAddress}")
                 }
            } else {
                 Log.e(TAG, "GATT Client: Failed to write descriptor ${descriptor?.uuid} for $deviceAddress with status $status.")
                 // TODO: Descriptor yazma hatasını ele al. Bildirimler açılmamış olabilir.
            }
        }

         // Diğer GATT Client callback'leri (onCharacteristicRead, onReliableWriteCompleted, onReadRemoteRssi vs.)
         // Şu anki temel implementasyon için gerekli değiller, ihtiyaca göre eklenebilir.

    }

    // Belirli bir characteristic için bildirimleri (notifications) etkinleştirir
    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!hasPermissions()) {
             Log.w(TAG, "Cannot enable notifications: Permissions not granted.")
             return false
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "Failed to set characteristic notification for ${characteristic.uuid} on ${gatt.device.address}.")
            return false
        }

        // CCCD (Client Characteristic Configuration Descriptor)'ı al
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"))
        if (descriptor == null) {
             Log.e(TAG, "CCCD descriptor not found for characteristic ${characteristic.uuid} on ${gatt.device.address}.")
             return false
        }

        // CCCD'ye "ENABLE_NOTIFICATION_VALUE" değerini yazarak bildirimleri etkinleştir
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @SuppressLint("MissingPermission") // writeDescriptor da izin gerektirir
        val success = gatt.writeDescriptor(descriptor)

        if (success) {
             Log.d(TAG, "Successfully wrote CCCD descriptor to enable notifications for ${characteristic.uuid} on ${gatt.device.address}. Waiting for onDescriptorWrite callback.")
        } else {
             Log.e(TAG, "Failed to write CCCD descriptor to enable notifications for ${characteristic.uuid} on ${gatt.device.address}.")
        }
        return success
    }


    // GATT Client rolüyle bağlı olduğumuz bir cihaza paket gönderir (Yazma Characteristic'ine yazarak)
    @SuppressLint("MissingPermission")
    fun sendPacketToPeer(deviceAddress: String, packet: MeshPacket) {
        if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot send packet to peer $deviceAddress: Permissions or Bluetooth not ready.")
            return
        }

        val gattClient = connectedGattClients[deviceAddress]
        if (gattClient == null) {
            Log.w(TAG, "Cannot send packet to peer $deviceAddress: GATT client not connected.")
            return
        }

         // Servis ve Characteristic'leri bul (Servis keşfi tamamlanmış olmalı)
        val meshService = gattClient.getService(Constants.AFETNET_SERVICE_UUID)
        val sendCharacteristic = meshService?.getCharacteristic(Constants.MESSAGE_SEND_CHARACTERISTIC_UUID)

        if (sendCharacteristic == null) {
            Log.e(TAG, "Cannot send packet to peer $deviceAddress: Message Send Characteristic not found on peer.")
             // TODO: Bağlantıyı kopar ve yeniden dene
             disconnectFromDevice(deviceAddress)
            return
        }

        // Paketi byte dizisine dönüştür
        val bytes = PacketHandler.serialize(packet)
        if (bytes == null || bytes.isEmpty()) {
            Log.e(TAG, "Cannot send packet to peer $deviceAddress: Serialization resulted in null or empty data.")
            return
        }
         // Byte dizisi boyutu kontrol edilmeli (< 512 byte)

        // Characteristic'in değerini ayarla ve yazma isteğini gönder
        sendCharacteristic.value = bytes
        // Yazma tipi: WRITE_TYPE_DEFAULT (yanıt bekler) veya WRITE_TYPE_NO_RESPONSE (yanıt beklemez)
        // Mesh'te genellikle WRITE_TYPE_NO_RESPONSE tercih edilebilir (daha hızlı).
        sendCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE // Yanıt bekleme

        @SuppressLint("MissingPermission") // writeCharacteristic da izin gerektirir
        val success = gattClient.writeCharacteristic(sendCharacteristic)

        if (success) {
             Log.d(TAG, "Successfully queued write request for packet ${packet.id} to peer $deviceAddress. Waiting for onCharacteristicWrite callback (if type is DEFAULT).")
             // WRITE_TYPE_NO_RESPONSE kullanıyorsak onCharacteristicWrite callback'i tetiklenmez.
        } else {
            Log.e(TAG, "Failed to queue write request for packet ${packet.id} to peer $deviceAddress.")
             // TODO: Yazma hatasını ele al
        }
    }

    // Bağlı tüm peer'lara (Client rolüyle bağlandıklarımıza) bir paket gönderir.
    fun sendPacketToConnectedClientPeers(packet: MeshPacket) {
         if (!hasPermissions() || !isBluetoothEnabled()) {
            Log.w(TAG, "Cannot send packet to connected client peers: Permissions or Bluetooth not ready.")
            return
        }

        if (connectedGattClients.isEmpty()) {
            // Log.d(TAG, "No client peers connected to send packet ${packet.id}.") // Çok sık loglanabilir
            return
        }

         Log.d(TAG, "Attempting to send packet ${packet.id} to ${connectedGattClients.size} connected client peers.")

         connectedGattClients.keys().forEach { deviceAddress ->
              sendPacketToPeer(deviceAddress, packet) // Her bir bağlı peer'a ayrı ayrı gönder
         }
    }
}