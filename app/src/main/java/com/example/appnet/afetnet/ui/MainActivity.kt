--- START OF FILE app/src/main/java/com/example/appnet/afetnet/ui/MainActivity.kt ---

package com.example.appnet.afetnet.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.appnet.R // Kaynak dosyalarınıza göre ayarlayın
import com.example.appnet.afetnet.data.SignalEntity
import com.example.appnet.afetnet.service.MeshService
import kotlinx.coroutines.launch

// Uygulamanın Ana Aktivitesi
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val viewModel: MainViewModel by viewModels()

    // UI Elemanları
    private lateinit var statusTextView: TextView
    private lateinit var sendDistressButton: Button
    private lateinit var sendSafeButton: Button
    private lateinit var signalsRecyclerView: RecyclerView
    // TODO: Konum izni durumu, Bluetooth durumu gibi bilgileri gösterecek UI elemanları eklenebilir

    // Servis Bağlantısı
    private var meshService: MeshService? = null
    private var isServiceBound = false

    // Servis Bağlantısı Callback'leri
    private val serviceConnection = object : ServiceConnection {
        // Servise bağlandığında çağrılır
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshService.LocalBinder
            meshService = binder.getService()
            isServiceBound = true
            Log.d(TAG, "MeshService bound successfully.")
            // ViewModel'a MeshNetworkManager'ı ata
            viewModel.setMeshNetworkManager(binder.getMeshNetworkManager())
            updateStatus("Servis Bağlandı")

            // TODO: Bağlantı kurulduktan sonra UI güncellemeleri veya sinyal gönderme aktif edilebilir
        }

        // Servis bağlantısı beklenmedik şekilde koptuğunda çağrılır
        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isServiceBound = false
            Log.w(TAG, "MeshService connection unexpectedly lost.")
            updateStatus("Servis Bağlantısı Kesildi")
            // TODO: Kullanıcıya bilgi ver ve belki yeniden bağlanmayı dene
        }
    }

    // İzin isteme sonuçları için ActivityResultLauncher
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // İzin sonuçlarını işle
            val granted = permissions.entries.all { it.value }
            if (granted) {
                Log.d(TAG, "All requested permissions granted.")
                // İzinler verildiyse servisi başlat ve bağla
                startAndBindMeshService()
            } else {
                Log.w(TAG, "Not all required permissions were granted.")
                // TODO: Kullanıcıya izinlerin neden gerekli olduğunu açıklayan bir mesaj göster
                showPermissionDeniedMessage()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // activity_main.xml layout'unuz olmalı

        // UI elemanlarını bağla
        statusTextView = findViewById(R.id.statusTextView) // Layout'ta olmalı
        sendDistressButton = findViewById(R.id.sendDistressButton) // Layout'ta olmalı
        sendSafeButton = findViewById(R.id.sendSafeButton)     // Layout'ta olmalı
        signalsRecyclerView = findViewById(R.id.signalsRecyclerView) // Layout'ta olmalı

        // RecyclerView ve Adapter kurulumu
        signalsRecyclerView.layoutManager = LinearLayoutManager(this)
        val adapter = SignalsAdapter() // SignalsAdapter sınıfını oluşturmalısınız
        signalsRecyclerView.adapter = adapter

        // ViewModel'dan gelen sinyalleri gözlemle ve UI'yi güncelle
        // LifecycleScope ve repeatOnLifecycle kullanarak Activity aktifken Flow'ları dinleriz
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // allSignals veya distressSignals Flow'unu dinleyin
                viewModel.allSignals.collect { signals ->
                    Log.d(TAG, "Received ${signals.size} signals from ViewModel.")
                    adapter.submitList(signals) // Adapter'ı güncelle
                    // En yeni sinyale otomatik kaydır (isteğe bağlı)
                    if (signals.isNotEmpty()) {
                       signalsRecyclerView.scrollToPosition(0)
                    }
                }
            }
        }


        // Buton tıklama listener'ları
        sendDistressButton.setOnClickListener {
            // TODO: Sinyal göndermeden önce konum almayı dene
            viewModel.sendDistressSignal()
             Toast.makeText(this, "Yardım sinyali gönderme başlatıldı...", Toast.LENGTH_SHORT).show()
        }

        sendSafeButton.setOnClickListener {
             // TODO: Sinyal göndermeden önce konum almayı dene
            viewModel.sendSafeSignal()
            Toast.makeText(this, "Güvendeyim sinyali gönderme başlatıldı...", Toast.LENGTH_SHORT).show()
        }

        // Başlangıç durumunu ayarla
        updateStatus("Başlatılıyor...")

        // İzinleri kontrol et ve iste
        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Activity görünür olduğunda servise bağlanmayı dene
        bindMeshService()
    }

    override fun onStop() {
        super.onStop()
        // Activity görünmez olduğunda servisten bağlantıyı kopar
        unbindMeshService()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Eğer Activity tamamen kapatılıyorsa ve servis hala çalışıyorsa, unbind önemlidir.
        // Ancak servis stop edildiyse unbind otomatik olur.
    }


    // --- Servis Yönetimi ---

    // MeshService'i başlatır (eğer çalışmıyorsa) ve bağlar
    private fun startAndBindMeshService() {
         // startService veya startForegroundService ile servisi başlat.
         // startForegroundService, API 26+ için önerilir.
         val serviceIntent = Intent(this, MeshService::class.java)
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             // Android 8.0 (Oreo) ve sonrası için startForegroundService kullanılır
             startForegroundService(serviceIntent)
         } else {
             // Eski Android versiyonları için startService kullanılır
             startService(serviceIntent)
         }
        Log.d(TAG, "Attempting to start service...")

        // Servise bağlan
        bindMeshService()
    }

    // MeshService'e bağlanır
    private fun bindMeshService() {
        if (!isServiceBound) {
            val serviceIntent = Intent(this, MeshService::class.java)
             // Context.BIND_AUTO_CREATE: Eğer servis çalışmıyorsa başlatır ve bağlar.
             // Ancak biz zaten startForegroundService ile başlattık. Bu flag yine de güvenli.
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Attempting to bind service...")
        }
    }

    // MeshService bağlantısını koparır
    private fun unbindMeshService() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            meshService = null
            Log.d(TAG, "Service unbound.")
        }
    }

    // --- İzin Yönetimi ---

    // Gerekli tüm izinlerin olup olmadığını kontrol eder
    private fun hasRequiredPermissions(): Boolean {
         val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Android 12+ için yeni Bluetooth izinleri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
            val bluetoothScanGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val bluetoothAdvertiseGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            val bluetoothConnectGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            return fineLocationGranted && bluetoothScanGranted && bluetoothAdvertiseGranted && bluetoothConnectGranted
        } else { // API 23 - 30
            // Konum izni yeterlidir (Bluetooth izinleri genellikle install zamanı verilir)
             return fineLocationGranted
        }
        // TODO: Arka plan konum izni (ACCESS_BACKGROUND_LOCATION) gerekebilir ve kontrol edilmelidir (API 29+).
        // TODO: Manifestteki temel BLUETOOTH ve BLUETOOTH_ADMIN izinlerinin kontrolü eklenebilir.
    }

    // İzinleri kontrol eder, eksikse ister
    private fun checkAndRequestPermissions() {
        if (!hasRequiredPermissions()) {
            // İzinler eksik, istememiz gerekiyor
            val permissionsToRequest = mutableListOf<String>()

            // Konum izni her zaman gerekli (BLE tarama için)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                 permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            // Android 12+ için yeni Bluetooth izinleri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
                 if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
                 }
                 if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                 }
                 if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                 }
            }
             // TODO: Arka plan konum izni (ACCESS_BACKGROUND_LOCATION) gerekiyorsa buraya eklenmeli

            if (permissionsToRequest.isNotEmpty()) {
                 // İzin isteme başlatıcısını kullanarak izinleri iste
                 requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
            }

        } else {
            // Tüm izinler zaten verilmiş
            Log.d(TAG, "All required permissions already granted. Starting service.")
            // İzinler varsa servisi başlat ve bağla
             startAndBindMeshService()
        }
    }

    // İzin reddedildiğinde kullanıcıya bilgi mesajı gösterir
    private fun showPermissionDeniedMessage() {
        AlertDialog.Builder(this)
            .setTitle("İzin Gerekli")
            .setMessage("AfetNet uygulamasının çevredeki diğer cihazlarla iletişim kurabilmesi için Bluetooth ve Konum izinlerine ihtiyacı var. Lütfen ayarlardan izinleri verin.")
            .setPositiveButton("Ayarlara Git") { dialog, _ ->
                // Kullanıcıyı uygulama ayarlarına yönlendir
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = android.net.Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent) // Ayarlar ekranından dönünce tekrar kontrol edilebilir
                dialog.dismiss()
            }
            .setNegativeButton("İptal") { dialog, _ ->
                // Kullanıcı izin vermeyi reddetti, uygulamayı kullanamayabilir
                Toast.makeText(this, "İzin verilmedi, uygulama düzgün çalışmayabilir.", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    // --- Bluetooth Durumu Kontrolü ---
    // Bluetooth adaptörünün açık olup olmadığını kontrol eder ve kapalıysa kullanıcıya açmasını önerir.
    @SuppressLint("MissingPermission") // İzin kontrolü checkAndRequestPermissions() içinde yapılıyor
    private fun checkBluetoothState() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
             Log.e(TAG, "Cihaz Bluetooth desteklemiyor.")
             updateStatus("Hata: Cihaz Bluetooth desteklemiyor.")
             // TODO: Kullanıcıya bilgi ver ve uygulamayı kapatmasını öner
        } else if (!bluetoothAdapter.isEnabled) {
             Log.w(TAG, "Bluetooth kapalı.")
             updateStatus("Bluetooth Kapalı")
             // Kullanıcıya Bluetooth'u açmasını öner
             val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
             // Bu intent sonucunu da ActivityResultLauncher ile yönetmek daha modern.
             // registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ... }.launch(enableBtIntent)
             startActivity(enableBtIntent) // Basitçe startActivity ile başlatalım şimdilik
             Toast.makeText(this, "Lütfen Bluetooth'u açın.", Toast.LENGTH_LONG).show()

        } else {
            Log.d(TAG, "Bluetooth açık.")
            // updateStatus("Bluetooth Açık") // Sürekli güncellemek yerine sadece başlangıçta veya değiştiğinde
        }
    }

    // --- UI Güncelleme ---
    private fun updateStatus(status: String) {
        statusTextView.text = "Durum: $status"
    }

    // TODO: Sinyal listesi için RecyclerView Adapter sınıfı (SignalsAdapter.kt) oluşturulmalıdır.
    // Bu adapter, SignalEntity listesini alıp RecyclerView'da gösterecektir.
}