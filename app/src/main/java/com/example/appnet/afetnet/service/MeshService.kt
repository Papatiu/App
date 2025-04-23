--- START OF FILE app/src/main/java/com/example/appnet/afetnet/service/MeshService.kt ---

package com.example.appnet.afetnet.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.example.appnet.afetnet.mesh.MeshNetworkManager
import com.example.appnet.afetnet.util.Constants
import com.example.appnet.afetnet.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// Mesh Network'ü arka planda çalıştıran Foreground Service
class MeshService : Service() {

    private val TAG = "MeshService"

    // Mesh Network yöneticisi
    private lateinit var meshNetworkManager: MeshNetworkManager

    // UI'nin bu servisle iletişim kurması için Binder
    private val binder = LocalBinder()

    // Servisin kendi Coroutine Scope'u (ihtiyaç olursa servis içindeki Coroutine'ler için)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob()) // Main Dispatcher UI ile etkileşim için (Binder üzerinden)

    // Binder sınıfı - Servis instance'ını Activity'ye döndürür
    inner class LocalBinder : Binder() {
        fun getService(): MeshService = this@MeshService
         fun getMeshNetworkManager(): MeshNetworkManager = meshNetworkManager // Manager'ı dışarıya aç
    }


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MeshService onCreate")

        // MeshNetworkManager'ı başlat
        // Application context'i kullanmak bellek sızıntısını önler
        meshNetworkManager = MeshNetworkManager(applicationContext)

        // TODO: BootCompletedReceiver'dan başlatılıyorsa burada ek işlemler yapılabilir

        // Servisi foreground olarak başlat
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MeshService onStartCommand")

        // Manager'ı başlatma (onCreate'de de yapıldı, burada tekrar çağırmak güvenli olabilir)
        meshNetworkManager.start()

        // START_STICKY: Sistem servisi öldürürse, kaynaklar yeterli olduğunda Intent olmadan yeniden oluşturmayı dene.
        // Bu, sürekli çalışan servisler için uygundur.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MeshService onDestroy")

        // Manager'ı durdur
        meshNetworkManager.stop()

        // Service Scope'u iptal et
        serviceScope.cancel()

        // Foreground servisi durdur
        stopForeground(true) // Bildirimi de kaldır

        Log.d(TAG, "MeshService destroyed")
    }

    // Binder döndür - Activity bu binder'ı kullanarak servisle etkileşime geçer
    override fun onBind(intent: Intent?): IBinder {
         Log.d(TAG, "MeshService onBind")
        return binder
    }

    // Unbind olduğunda (Activity bağlantıyı kopardığında) çağrılır
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "MeshService onUnbind")
         // false döndürmek, eğer servis tekrar bind edilirse onRebind'in çağrılmasını sağlar.
         // true döndürmek, bir sonraki bind'de onBind'in çağrılmasını sağlar.
        return true
    }

    // onUnbind'den sonra tekrar bind edildiğinde çağrılır (onBind yerine)
    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "MeshService onRebind")
        super.onRebind(intent)
    }


    // Servisi Foreground olarak başlatır
    private fun startForegroundService() {
        val notification = NotificationHelper(this).createForegroundServiceNotification()
        // startForeground çağrısı için Android 10+ target SDK'ları için foregroundServiceType belirtilmesi gerekir.
        // Manifest'te belirtmiştik: android:foregroundServiceType="connectedDevice|location"
        // Android 14+ için ek izinler ve özel kullanım tipi de gerekebilir (Manifest'te ekledik).
        try {
             // NOT: startForeground(notificationId, notification) metodunun doğru overloadd'unu kullanın.
             // Eğer Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q (Android 10) ise
             // startForeground(notificationId, notification, foregroundServiceType) şeklinde kullanılması daha doğrudur.
             // Ancak manifestte belirtmek de yeterli olabilir. Basitlik için şimdilik ID ve notification ile gidiyoruz.
            startForeground(Constants.FOREGROUND_SERVICE_NOTIFICATION_ID, notification)
            Log.d(TAG, "Service started as foreground.")
        } catch (e: Exception) {
             Log.e(TAG, "Failed to start service as foreground: ${e.message}", e)
             // TODO: Hatayı ele al
        }
    }
}