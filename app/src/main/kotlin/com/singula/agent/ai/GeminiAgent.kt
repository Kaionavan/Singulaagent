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
        val contents = mutableListOf<JSONObject>()
        history.takeLast(10).forEach { (role, text) ->
            contents.add(JSONObject().apply {
                put("role", if (role == "ai") "model" else "user")
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
        }
        contents.add(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
        })
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", "$SYS\n\n$ctx"))))
            put("contents", JSONArray(contents))
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 600)
                put("temperature", 0.85)
            })
        }
        val reply = try {
            val raw = callRaw(body.toString())
            val json = JSONObject(raw)
            // Проверяем на ошибку от API
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                "Ошибка API: ${err.optString("message", "неизвестная ошибка")}"
            } else {
                json.getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text")
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
Actions: open_app, type_text, search, click, send, back, wait, done, error
Пакеты: youtube=com.google.android.youtube, telegram=org.telegram.messenger,
whatsapp=com.whatsapp, instagram=com.instagram.android,
spotify=com.spotify.music, vk=com.vkontakte.android,
discord=com.discord, chrome=com.android.chrome,
settings=com.android.settings, calculator=com.android.calculator2,
maps=com.google.android.apps.maps, gmail=com.google.android.gm
Пример: [{"action":"open_app","target":"com.google.android.youtube","description":"Открываю YouTube"},{"action":"search","text":"губка боб","description":"Ищу"}]
Только JSON без markdown.
        """.trimIndent()
        try {
            val raw = callText(prompt)
            parseSteps(raw)
        } catch (e: Exception) {
            listOf(AgentStep("error", description = e.message ?: ""))
        }
    }

    suspend fun describeImage(bitmap: Bitmap, prompt: String = "Опиши подробно"): String =
        withContext(Dispatchers.IO) {
            try { callVision(prompt, bitmapB64(bitmap)) }
            catch (e: Exception) { "Не удалось проанализировать изображение, сэр." }
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

    private suspend fun callText(prompt: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
        }
        val raw = callRaw(body.toString())
        return JSONObject(raw).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private suspend fun callVision(prompt: String, imageB64: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
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
            }))
        }
        val raw = callRaw(body.toString())
        return JSONObject(raw).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private suspend fun callRaw(bodyStr: String): String = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent?key=$apiKey"
        val req = Request.Builder().url(url)
            .post(bodyStr.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { it.body?.string() ?: "{}" }
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
