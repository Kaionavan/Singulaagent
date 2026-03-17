package com.singula.agent.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiAgent(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var apiKey: String = ""
    val memory = MemorySystem(context)

    init { memory.loadHistory() }

    fun setApiKey(key: String) { apiKey = key }
    fun hasKey() = apiKey.isNotEmpty()

    private val SYS = """
Ты — SINGULA, AI от Stark Industries, преемник J.A.R.V.I.S.
Говори ТОЛЬКО по-русски. Обращайся "сэр".
Стиль: уверенный, краткий, профессиональный.
Ты не языковая модель — ты SINGULA.
    """.trimIndent()

    suspend fun chat(userMessage: String): String = withContext(Dispatchers.IO) {
        val ctx = memory.getContextString()
        val history = memory.getHistory()

        val messages = mutableListOf<JSONObject>()
        messages.add(JSONObject().apply {
            put("role", "system")
            put("content", "$SYS\n\n$ctx")
        })
        history.takeLast(10).forEach { (role, text) ->
            messages.add(JSONObject().apply {
                put("role", if (role == "ai") "assistant" else "user")
                put("content", text)
            })
        }
        messages.add(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        val reply = try {
            val raw = callGroq(messages)
            val json = JSONObject(raw)
            if (json.has("error")) {
                "Ошибка: ${json.getJSONObject("error").optString("message")}"
            } else {
                json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        } catch (e: Exception) {
            "Помехи в канале связи, сэр. (${e.message})"
        }

        memory.addToHistory("user", userMessage)
        memory.addToHistory("ai", reply)
        extractFacts(userMessage)
        reply
    }

    suspend fun parseCommand(command: String): List<AgentStep> = withContext(Dispatchers.IO) {
        val prompt = """
Разбей команду на шаги для Android телефона.
Команда: "$command"
Ответь ТОЛЬКО JSON массивом [{action,target,text,description}].
Actions: open_app, type_text, search, click, send, back, wait, done
Пакеты: youtube=com.google.android.youtube, telegram=org.telegram.messenger,
whatsapp=com.whatsapp, instagram=com.instagram.android,
spotify=com.spotify.music, vk=com.vkontakte.android,
discord=com.discord, chrome=com.android.chrome,
settings=com.android.settings, maps=com.google.android.apps.maps
Пример: [{"action":"open_app","target":"com.google.android.youtube","description":"Открываю YouTube"},{"action":"search","text":"губка боб","description":"Ищу"}]
Только JSON без markdown.
        """.trimIndent()
        try {
            val messages = listOf(
                JSONObject().apply { put("role","system"); put("content","Отвечай только JSON.") },
                JSONObject().apply { put("role","user"); put("content",prompt) }
            )
            val raw = callGroq(messages)
            val text = JSONObject(raw).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
            parseSteps(text)
        } catch (e: Exception) {
            listOf(AgentStep("error", description = e.message ?: ""))
        }
    }

    suspend fun describeImage(bitmap: Bitmap, prompt: String = "Опиши подробно"): String =
        withContext(Dispatchers.IO) {
            // Для анализа изображений используем Gemini vision если есть ключ
            try { callVision(prompt, bitmapB64(bitmap)) }
            catch (e: Exception) { "Анализ изображений требует Gemini ключ, сэр." }
        }

    private fun extractFacts(text: String) {
        val lower = text.lowercase()
        when {
            lower.contains("меня зовут") -> memory.remember("fact", text, 5)
            lower.contains("я люблю") || lower.contains("мне нравится") -> memory.remember("preference", text, 3)
            lower.contains("я работаю") -> memory.remember("fact", text, 4)
            lower.contains("напомни") -> memory.remember("reminder", text, 4)
        }
    }

    // ══ GROQ API (бесплатный, без региональных ограничений) ══
    private suspend fun callGroq(messages: List<JSONObject>): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", "llama3-8b-8192")
            put("messages", JSONArray(messages))
            put("max_tokens", 600)
            put("temperature", 0.85)
        }
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { it.body?.string() ?: "{}" }
    }

    private suspend fun callVision(prompt: String, imageB64: String): String {
        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", imageB64)
                    })
                })
            })
        })
        val body = JSONObject().apply { put("contents", contents) }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val raw = client.newCall(req).execute().use { it.body?.string() ?: "{}" }
        return JSONObject(raw).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun parseSteps(json: String): List<AgentStep> {
        return try {
            val clean = json.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val arr = JSONArray(clean)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AgentStep(
                    action = o.optString("action", ""),
                    target = o.optString("target", ""),
                    text = o.optString("text", ""),
                    description = o.optString("description", "")
                )
            }
        } catch (e: Exception) {
            listOf(AgentStep("error", description = "Ошибка разбора"))
        }
    }

    private fun bitmapB64(bitmap: Bitmap): String {
        val s = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, s)
        return Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
    }
}
