package com.example.appnet.afetnet

import android.app.Application
import android.util.Log
import com.example.appnet.afetnet.data.AppDatabase // Room veritabanı sınıfınız
import com.example.appnet.afetnet.util.UniqueDeviceId // Cihaz ID'sini başlatmak için

// Uygulama ana sınıfı
class AfetNetApplication : Application() {

    private val TAG = "AfetNetApplication"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AfetNetApplication onCreate")

        // Uygulama başladığında Singleton veritabanı örneğini alarak başlatılmasını sağla
        // Bu işlemi bir arka plan iş parçacığında yapmak daha iyidir.
        // Ancak getInstance metodu zaten synchronized blok içinde ve I/O yapabilir.
        // Yine de main thread'i bloklamamak için Coroutine kullanmak daha güvenli.
        // GlobalScope kullanmak önerilmez, uygun bir Application level scope oluşturulabilir.
        // Şimdilik basitçe getInstance çağırıyoruz.
        try {
            AppDatabase.getInstance(this) // Veritabanını başlat
            Log.d(TAG, "Room Database initialized.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Room Database: ${e.message}", e)
        }

        // Cihaz ID'sinin de burada oluşturulup kaydedilmesini sağlayabiliriz (ilk çalıştırmada)
        try {
            val deviceId = UniqueDeviceId.getDeviceId(this)
            Log.d(TAG, "Unique Device ID: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Unique Device ID: ${e.message}", e)
        }

         // TODO: Diğer genel başlatma işlemleri (izin kontrolü tetikleme, vb.)
    }
}