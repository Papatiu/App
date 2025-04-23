--- START OF FILE app/src/main/java/com/example/appnet/afetnet/mesh/MeshNode.kt ---

package com.example.appnet.afetnet.mesh

import android.bluetooth.BluetoothDevice // BluetoothDevice objesi Android SDK'sında bulunur

// Mesh ağındaki bir cihazı (node) temsil eden veri sınıfı
data class MeshNode(
    val id: String,                     // Cihazın benzersiz AfetNet ID'si (Kendi ID'si)
    val bluetoothDevice: BluetoothDevice, // Android'in BluetoothDevice objesi (Cihazın BLE adresi vb. içerir)
    var lastSeen: Long,                 // En son ne zaman görüldüğü (Unix zaman damgası)
    var rssi: Int = 0,                  // En son algılandığındaki Sinyal Gücü Göstergesi (RSSI)
    var isConnected: Boolean = false    // Şu anda bu cihazla bağlantılı olup olmadığımız (Server veya Client olarak)
    // Ek bilgiler eklenebilir: desteklediği özellikler, adı vb.
)