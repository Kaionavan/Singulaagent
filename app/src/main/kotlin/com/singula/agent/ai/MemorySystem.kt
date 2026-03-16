package com.singula.agent.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class Memory(
    val id: String,
    val type: String,      // fact, preference, reminder, habit, event
    val content: String,
    val timestamp: Long,
    val importance: Int = 1  // 1-5
)

class MemorySystem(private val context: Context) {

    private val prefs = context.getSharedPreferences("singula_memory", Context.MODE_PRIVATE)
    private val memories = mutableListOf<Memory>()
    private val chatHistory = mutableListOf<Pair<String, String>>()

    init { loadMemories() }

    // ══ ДОБАВИТЬ ВОСПОМИНАНИЕ ══
    fun remember(type: String, content: String, importance: Int = 1) {
        val mem = Memory(
            id = UUID.randomUUID().toString(),
            type = type,
            content = content,
            timestamp = System.currentTimeMillis(),
            importance = importance
        )
        memories.add(mem)
        // Храним максимум 200 воспоминаний
        if (memories.size > 200) {
            memories.sortBy { it.importance }
            memories.removeAt(0)
        }
        saveMemories()
    }

    // ══ ДОБАВИТЬ В ИСТОРИЮ ЧАТА ══
    fun addToHistory(role: String, text: String) {
        chatHistory.add(Pair(role, text))
        if (chatHistory.size > 50) chatHistory.removeAt(0)
        saveHistory()
    }

    fun getHistory(): List<Pair<String, String>> = chatHistory.toList()

    // ══ НАЙТИ ВОСПОМИНАНИЯ ══
    fun recall(query: String = ""): List<Memory> {
        if (query.isEmpty()) return memories.sortedByDescending { it.timestamp }.take(10)
        val q = query.lowercase()
        return memories.filter { it.content.lowercase().contains(q) }
            .sortedByDescending { it.importance }
            .take(5)
    }

    // ══ ПОЛУЧИТЬ КОНТЕКСТ ДЛЯ AI ══
    fun getContextString(): String {
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date())
        val recent = memories.sortedByDescending { it.timestamp }.take(15)
        val sb = StringBuilder()
        sb.append("Текущее время: $now\n")
        if (recent.isNotEmpty()) {
            sb.append("Что я знаю о пользователе:\n")
            recent.forEach { sb.append("- [${it.type}] ${it.content}\n") }
        }
        return sb.toString()
    }

    // ══ СОХРАНИТЬ НАПОМИНАНИЕ ══
    fun addReminder(text: String, timeMillis: Long) {
        remember("reminder", "$text||$timeMillis", importance = 5)
    }

    // ══ ПОЛУЧИТЬ АКТИВНЫЕ НАПОМИНАНИЯ ══
    fun getActiveReminders(): List<Memory> {
        val now = System.currentTimeMillis()
        return memories.filter { mem ->
            if (mem.type != "reminder") return@filter false
            val parts = mem.content.split("||")
            if (parts.size < 2) return@filter false
            val time = parts[1].toLongOrNull() ?: return@filter false
            time <= now + 60000 && time >= now - 60000
        }
    }

    // ══ УДАЛИТЬ ВОСПОМИНАНИЕ ══
    fun forget(id: String) {
        memories.removeAll { it.id == id }
        saveMemories()
    }

    private fun saveMemories() {
        val arr = JSONArray()
        memories.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("type", m.type)
                put("content", m.content)
                put("timestamp", m.timestamp)
                put("importance", m.importance)
            })
        }
        prefs.edit().putString("memories", arr.toString()).apply()
    }

    private fun loadMemories() {
        memories.clear()
        val str = prefs.getString("memories", "[]") ?: "[]"
        try {
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                memories.add(Memory(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    type = obj.optString("type", "fact"),
                    content = obj.optString("content", ""),
                    timestamp = obj.optLong("timestamp", 0),
                    importance = obj.optInt("importance", 1)
                ))
            }
        } catch (e: Exception) {}
    }

    private fun saveHistory() {
        val arr = JSONArray()
        chatHistory.forEach { (role, text) ->
            arr.put(JSONObject().apply { put("role", role); put("text", text) })
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun loadHistory() {
        chatHistory.clear()
        val str = prefs.getString("history", "[]") ?: "[]"
        try {
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                chatHistory.add(Pair(obj.optString("role"), obj.optString("text")))
            }
        } catch (e: Exception) {}
    }
}
