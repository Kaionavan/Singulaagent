package com.singula.agent.service

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.*

class SystemMonitor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastBatteryWarning = 0L

    var onAlert: ((String) -> Unit)? = null

    fun startMonitoring() {
        scope.launch {
            while (isActive) {
                try { checkBattery() } catch (e: Exception) {}
                delay(60_000)
            }
        }
    }

    fun stopMonitoring() {
        try { scope.cancel() } catch (e: Exception) {}
    }

    fun getBatteryLevel(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        } catch (e: Exception) { 100 }
    }

    fun isCharging(): Boolean {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.isCharging ?: false
        } catch (e: Exception) { false }
    }

    fun getNetworkInfo(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Нет сети"
            val network = cm.activeNetwork ?: return "Нет сети"
            val caps = cm.getNetworkCapabilities(network) ?: return "Нет сети"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G"
                else -> "Нет сети"
            }
        } catch (e: Exception) { "Нет сети" }
    }

    fun getSystemInfo(): String {
        return try {
            val battery = getBatteryLevel()
            val charging = if (isCharging()) "⚡" else ""
            val network = getNetworkInfo()
            val ram = getAvailableRam()
            "Статус систем, сэр:\n" +
            "▸ Батарея: $battery%$charging\n" +
            "▸ Сеть: $network\n" +
            "▸ RAM: $ram МБ\n" +
            "▸ Android: ${Build.VERSION.RELEASE}\n" +
            "▸ Устройство: ${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (e: Exception) {
            "Системы в норме, сэр."
        }
    }

    fun getCurrentTime(): String {
        return try {
            val cal = java.util.Calendar.getInstance()
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val m = cal.get(java.util.Calendar.MINUTE)
            val greeting = when {
                h < 6  -> "Доброй ночи"
                h < 12 -> "Доброе утро"
                h < 18 -> "Добрый день"
                else   -> "Добрый вечер"
            }
            "$greeting, сэр. Сейчас ${String.format("%02d:%02d", h, m)}."
        } catch (e: Exception) { "Добрый день, сэр." }
    }

    private fun getAvailableRam(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem / 1024 / 1024
        } catch (e: Exception) { 0 }
    }

    private fun checkBattery() {
        val level = getBatteryLevel()
        val now = System.currentTimeMillis()
        if (level <= 15 && !isCharging() && now - lastBatteryWarning > 300_000) {
            lastBatteryWarning = now
            onAlert?.invoke("Сэр, батарея $level%. Рекомендую подключить зарядку.")
        }
    }
}
