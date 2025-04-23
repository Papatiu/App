package com.example.appnet.afetnet.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.appnet.R // R dosyanızın doğru paket altında olduğundan emin olun
import com.example.appnet.afetnet.ui.MainActivity // MainActivity'nin doğru paket altında olduğundan emin olun

// Foreground Service için bildirim oluşturmaya yardımcı sınıf
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        // Bildirim kanalını başlatırken oluştur
        createNotificationChannel()
    }

    // Foreground Service bildirimi oluşturur
    fun createForegroundServiceNotification(): Notification {
        val notificationIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT // Flag'ler önemli
        )

        return NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AfetNet Aktif")
            .setContentText("Arka planda sinyal alıp gönderiyor...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Uygulama ikonunuzu kullanın
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Kullanıcı tarafından kaydırılıp kapatılamaz
            .setCategory(Notification.CATEGORY_SERVICE) // Bildirim kategorisi
            .setPriority(NotificationCompat.PRIORITY_LOW) // Düşük öncelik (kullanıcıyı rahatsız etmemek için)
            .build()
    }

    // Bildirim kanalını oluşturur (Android Oreo ve sonrası için)
    private fun createNotificationChannel() {
        // Android 8.0 (Oreo) ve sonrası için bildirim kanalı gerekli
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                Constants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Düşük önem seviyesi
            )
            serviceChannel.description = "Kritik afet ağı servisi için bildirim kanalı."
             // serviceChannel.setSound(null, null) // İsterseniz bildirim sesini kapatabilirsiniz
             // serviceChannel.enableVibration(false) // İsterseniz titreşimi kapatabilirsiniz

            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

     // Bildirimi güncelleyebilir veya kaldırabilirsiniz (şimdilik gerek yok)
}