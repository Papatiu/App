--- START OF FILE app/src/main/java/com/example/appnet/afetnet/ui/MainViewModel.kt ---

package com.example.appnet.afetnet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appnet.afetnet.data.SignalEntity // Room Entity
import com.example.appnet.afetnet.mesh.MeshNetworkManager
import com.example.appnet.afetnet.mesh.PacketType // PacketType enum'ı
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// MainActivity için ViewModel
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // MeshNetworkManager'a erişim için bir yol (Servis Binder üzerinden alacağız)
    // Bu değişken, servis bağlandığında ayarlanacak.
    private var meshNetworkManager: MeshNetworkManager? = null

    // MeshNetworkManager'dan gelen işlenmiş sinyalleri dinlemek için Flow
    // ViewModel, Activity yaşam döngüsüne duyarlı olduğu için burada toplanıp UI'ye sunulur.
    // Flow<List<SignalEntity>> kullanarak veritabanındaki tüm sinyalleri dinleyebiliriz.
     val allSignals: Flow<List<SignalEntity>> by lazy {
        // meshNetworkManager henüz null olabilir, bu yüzden manager atandığında başlatılması gerekir.
        // Ya da Manager'ı doğrudan Application sınıfında başlatıp buradan erişilebiliriz.
        // Servis bağlıyken Manager'a erişim daha standart bir yöntem.
         // Şimdilik getMeshNetworkManager() null dönerse exception fırlatacak.
        // TODO: meshNetworkManager null olduğunda ne yapılacağı ele alınmalı (örn: hata mesajı göster)
         getMeshNetworkManager().getAllSignals()
     }

     val distressSignals: Flow<List<SignalEntity>> by lazy {
        // Benzer şekilde manager'ın null olmaması gerekir
        getMeshNetworkManager().getDistressSignals()
     }


    // Servis bağlandığında bu metot çağrılır
    fun setMeshNetworkManager(manager: MeshNetworkManager) {
        if (this.meshNetworkManager == null) {
            this.meshNetworkManager = manager
            // Manager ayarlandıktan sonra, Flow'ları toplamaya başlayabiliriz
            // Ancak Flow'lar zaten lazy val olduğu için ilk erişildiklerinde başlatılırlar.
            // Eğer Manager ayarlanmadan Flow'lara erişilirse exception olur.
            // Daha güvenli bir yol: Manager'ı ayarlarken veya Flow'lara erişim metotlarında null kontrolü yapmak.
             // Veya Manager'ı Application sınıfında Singleton yapıp ViewModel'dan oraya doğrudan erişmek.

            // Örnek: Manager'dan gelen _processedSignals Flow'unu dinlemek (isteğe bağlı)
            // Eğer database'deki sinyalleri Flow ile dinliyorsak, bu Flow'u dinlemeye gerek kalmayabilir.
            // Ancak Manager başka türde veriler de yayacaksa (örn: cihaz durumu), burayı kullanabiliriz.
            /*
            viewModelScope.launch {
                manager.processedSignals.collect { signal ->
                     // UI'de göstermek için LiveData veya StateFlow'a ekle
                     // Örneğin: _uiState.value = _uiState.value.copy(latestSignal = signal)
                     Log.d("MainViewModel", "Received processed signal: ${signal.id}")
                }
            }
            */
        }
    }

    // Kullanıcının "Yardım İsteyin" butonuna basmasıyla çağrılacak metot
    fun sendDistressSignal(latitude: Double? = null, longitude: Double? = null) {
        // Manager'ı kontrol et ve sinyal gönderme metodunu çağır
        val manager = meshNetworkManager
        if (manager != null) {
            viewModelScope.launch {
                 // TODO: Gerçek konum bilgisini alıp lat/lon parametrelerini doldur.
                 manager.sendSignal(PacketType.DISTRESS, message = "Yardım İstiyorum", latitude = latitude, longitude = longitude)
            }
        } else {
            // TODO: Manager null ise kullanıcıya bir hata mesajı göster
            Log.e(TAG, "MeshNetworkManager is not set. Cannot send signal.")
        }
    }

     // Kullanıcının "Güvendeyim" butonuna basmasıyla çağrılacak metot
    fun sendSafeSignal(latitude: Double? = null, longitude: Double? = null) {
        val manager = meshNetworkManager
        if (manager != null) {
            viewModelScope.launch {
                 // TODO: Gerçek konum bilgisini alıp lat/lon parametrelerini doldur.
                 manager.sendSignal(PacketType.SAFE, message = "Güvendeyim", latitude = latitude, longitude = longitude)
            }
        } else {
            Log.e(TAG, "MeshNetworkManager is not set. Cannot send signal.")
        }
    }

    // TODO: Servis bağlantı durumunu UI'ye bildiren LiveData veya StateFlow eklenebilir.
    // TODO: İzin durumunu UI'ye bildiren LiveData veya StateFlow eklenebilir.

    // Manager'a erişim helper metodu, null kontrolü yapar ve hata fırlatır
     private fun getMeshNetworkManager(): MeshNetworkManager {
        return meshNetworkManager ?: throw IllegalStateException("MeshNetworkManager is not initialized. Ensure service is bound.")
     }

    companion object {
        private const val TAG = "MainViewModel"
    }
}