package com.example.appnet.afetnet.data

// Başarılı bir sonuç veya hata durumunu temsil eden jenerik sarmalayıcı
sealed class Result<out T : Any> {
    data class Success<out T : Any>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
    // loading, idle gibi durumlar da eklenebilir
}