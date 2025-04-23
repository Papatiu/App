--- START OF FILE app/src/main/java/com/example/appnet/afetnet/ui/adapters/SignalsAdapter.kt ---

package com.example.appnet.afetnet.ui.adapters // Paket yolu güncellendi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat // ContextCompat import edildi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.appnet.R // Kaynak dosyalarına göre ayarlayın
import com.example.appnet.afetnet.data.SignalEntity // Entity sınıfı
import com.example.appnet.afetnet.mesh.PacketType // Enum
import java.text.SimpleDateFormat
import java.util.*

// Sinyal listesi için RecyclerView Adapter'ı
class SignalsAdapter : ListAdapter<SignalEntity, SignalsAdapter.SignalViewHolder>(SignalDiffCallback()) {

    // ViewHolder - Her bir sinyal öğesinin görünümünü tutar
    class SignalViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val typeTextView: TextView = itemView.findViewById(R.id.signalTypeTextView)
        val senderTextView: TextView = itemView.findViewById(R.id.signalSenderTextView)
        val timeTextView: TextView = itemView.findViewById(R.id.signalTimeTextView)
        val messageTextView: TextView = itemView.findViewById(R.id.signalMessageTextView)

        // Sinyal verisini görünümlere bağlar
        fun bind(signal: SignalEntity) {
            typeTextView.text = when(signal.type) {
                PacketType.DISTRESS -> "Yardım Çağrısı"
                PacketType.SAFE -> "Güvendeyim"
                PacketType.INFO -> "Bilgi"
                PacketType.PING -> "Ağ Ping" // Bunları göstermek istemeyebiliriz
                PacketType.PONG -> "Ağ Pong" // Bunları göstermek istemeyebiliriz
                // TODO: Diğer tipler için metin ekle
            }

            senderTextView.text = "Gönderen: ${signal.senderId.take(8)}..." // ID'nin ilk 8 karakterini göster

            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val receivedTime = dateFormat.format(Date(signal.receivedTimestamp))
            timeTextView.text = "Alındı: $receivedTime - ${signal.hopCount} Hop"

            // Mesaj varsa göster, yoksa gizle
            if (signal.message.isNullOrBlank()) {
                messageTextView.visibility = View.GONE
            } else {
                messageTextView.visibility = View.VISIBLE
                messageTextView.text = "Mesaj: ${signal.message}"
            }

            // Sinyal tipine göre renk değişikliği
             when(signal.type) {
                 PacketType.DISTRESS -> typeTextView.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                 PacketType.SAFE -> typeTextView.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                 else -> typeTextView.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.black)) // Varsayılan renk
             }

             // TODO: Tıklama listener eklenebilir (Örn: Haritada göster)
             itemView.setOnClickListener {
                 // Tıklama olayını MainAcitivty'ye veya ViewModel'a bildirin
                 // Örneğin bir lambda fonksiyonu çağırarak: onItemClick?.invoke(signal)
             }
        }
    }

    // Yeni ViewHolder oluşturulduğunda çağrılır (liste öğesinin layout'unu inflate eder)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SignalViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_signal, parent, false) // list_item_signal layout'unu kullan
        return SignalViewHolder(view)
    }

    // ViewHolder bir veri öğesine bağlandığında çağrılır (veriyi görünümlere yerleştirir)
    override fun onBindViewHolder(holder: SignalViewHolder, position: Int) {
        val signal = getItem(position)
        holder.bind(signal)
    }

    // DiffUtil Callback - Liste güncellemelerini verimli bir şekilde hesaplar
    private class SignalDiffCallback : DiffUtil.ItemCallback<SignalEntity>() {
        override fun areItemsTheSame(oldItem: SignalEntity, newItem: SignalEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SignalEntity, newItem: SignalEntity): Boolean {
            return oldItem == newItem
        }
    }
}