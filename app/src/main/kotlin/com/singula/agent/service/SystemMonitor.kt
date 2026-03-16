package com.singula.agent.service

import android.app.NotificationManager
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

class SystemMonitor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastBatteryWarning = 0L
    private var lastWifiWarning = 0L

    var onAlert: ((String) -> Unit)? = null

    fun startMonitoring() {
        scope.launch {
            while (isActive) {
                checkBattery()
                checkConnectivity()
                delay(60_000) // Каждую минуту
            }
        }
    }

    fun stopMonitoring() {
        scope.cancel()
    }

    // ══ БАТАРЕЯ ══
    fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private fun checkBattery() {
        val level = getBatteryLevel()
        val now = System.currentTimeMillis()
        if (level <= 15 && !isCharging() && now - lastBatteryWarning > 300_000) {
            lastBatteryWarning = now
            onAlert?.invoke("Сэр, батарея $level%. Рекомендую подключить зарядку.")
        }
    }

    // ══ СЕТЬ ══
    fun getNetworkInfo(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "Нет сети"
        val caps = cm.getNetworkCapabilities(network) ?: return "Нет сети"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            else -> "Нет сети"
        }
    }

    private fun checkConnectivity() {
        val net = getNetworkInfo()
        val now = System.currentTimeMillis()
        if (net == "Нет сети" && now - lastWifiWarning > 120_000) {
            lastWifiWarning = now
            onAlert?.invoke("Сэр, потеряно подключение к сети.")
        }
    }

    // ══ СИСТЕМНАЯ ИНФОРМАЦИЯ ══
    fun getSystemInfo(): String {
        val battery = getBatteryLevel()
        val charging = if (isCharging()) "заряжается" else "разряжается"
        val network = getNetworkInfo()
        val ram = getAvailableRam()
        return """
Статус систем, сэр:
▸ Батарея: $battery% ($charging)
▸ Сеть: $network  
▸ RAM свободно: $ram МБ
▸ Android: ${Build.VERSION.RELEASE}
▸ Устройство: ${Build.MANUFACTURER} ${Build.MODEL}
        """.trimIndent()
    }

    private fun getAvailableRam(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / 1024 / 1024
    }

    // ══ ВРЕМЯ ══
    fun getCurrentTime(): String {
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val greeting = when {
            h < 6  -> "Доброй ночи"
            h < 12 -> "Доброе утро"
            h < 18 -> "Добрый день"
            else   -> "Добрый вечер"
        }
        return "$greeting, сэр. Сейчас ${String.format("%02d:%02d", h, m)}."
    }
}
