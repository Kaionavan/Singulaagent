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
Ты — система управления Android телефоном. Разбей команду на точные шаги.
Команда: "$command"
Ответь ТОЛЬКО JSON массивом. Никакого текста до или после JSON.

ДЕЙСТВИЯ (action):
- open_app: открыть приложение. target = package name
- find_contact: найти контакт в мессенджере. text = имя
- type_text: напечатать текст в активном поле ввода. text = текст
- search: нажать иконку поиска и ввести запрос. text = запрос
- click: нажать элемент. target = точный текст кнопки на экране
- send: нажать кнопку отправки сообщения
- scroll_down: листать вниз (3 свайпа)
- scroll_up: листать вверх (3 свайпа)
- back: кнопка назад
- wait: подождать. text = миллисекунды
- done: завершить. description = ответ пользователю

ПАКЕТЫ ПРИЛОЖЕНИЙ:
- Телеграм/Telegram: org.telegram.messenger
- Ютуб/YouTube: com.google.android.youtube
- WhatsApp/Вотсап: com.whatsapp
- Instagram/Инстаграм: com.instagram.android
- Spotify/Спотифай: com.spotify.music
- SoundCloud: com.soundcloud.android
- ВКонтакте/ВК: com.vkontakte.android
- Discord/Дискорд: com.discord
- Chrome/Браузер: com.android.chrome
- Настройки: com.android.settings
- Карты/Maps: com.google.android.apps.maps
- TikTok/Тикток: com.zhiliaoapp.musically
- Погода Samsung: com.samsung.android.weather
- Погода Google: com.google.android.googlequicksearchbox
- Галерея: com.sec.android.gallery3d
- Камера: com.sec.android.app.camera
- Контакты: com.samsung.android.contacts
- Телефон/Звонок: com.samsung.android.dialer
- Файлы: com.samsung.android.myfiles
- Магазин: com.android.vending

ИНСТРУКЦИИ ДЛЯ КОНКРЕТНЫХ ПРИЛОЖЕНИЙ:

НАСТРОЙКИ — когда просят открыть конкретный раздел:
- "открой настройки безопасность" → open_app настройки, потом click "Безопасность и конфиденциальность" или search текст раздела
- "открой настройки звук" → open_app настройки, потом click "Звук и вибрация"
- "открой настройки wifi" → open_app настройки, потом click "Подключения"
- НЕ застревай в настройках — всегда добавляй click с точным названием раздела

INSTAGRAM — кнопки на нижней панели:
- "Главная" = кнопка домика
- "Поиск" = кнопка лупы  
- "Reels/Рилсы" = кнопка с иконкой видео/кино (третья снизу, contentDesc="Reels")
- "Магазин" = четвёртая кнопка
- "Профиль" = пятая кнопка (аватарка)
- Для рилсов: click с target="Reels" (contentDesc в приложении именно "Reels")

SOUNDCLOUD — поиск и воспроизведение:
- Для поиска трека: open_app → click "Поиск" или Search → search текст трека → click "first_result"
- Первый результат = нужный трек, не playlist
- Для плейлиста: после поиска → scroll_down → click нужное название

SPOTIFY — аналогично SoundCloud:
- open_app → click "Поиск" → search трек → click "first_result"
- Для плейлиста: search название → scroll_down → click название плейлиста

YOUTUBE:
- open_app → click иконку поиска (лупа вверху) → type_text запрос → click "first_result"

ПОГОДА — это отдельное приложение, НЕ раздел настроек:
- "открой погоду" → open_app com.samsung.android.weather
- Если нет → open_app com.google.android.googlequicksearchbox → search "погода"

ПРИМЕРЫ:

"открой телеграм напиши Диме привет":
[
  {"action":"open_app","target":"org.telegram.messenger","description":"Открываю Telegram"},
  {"action":"find_contact","text":"Дима","description":"Ищу Диму"},
  {"action":"type_text","text":"привет","description":"Пишу сообщение"},
  {"action":"send","description":"Отправляю"},
  {"action":"done","description":"Сообщение отправлено, сэр."}
]

"открой инстаграм нажми рилсы":
[
  {"action":"open_app","target":"com.instagram.android","description":"Открываю Instagram"},
  {"action":"wait","text":"2000","description":"Жду загрузки"},
  {"action":"click","target":"Reels","description":"Нажимаю Reels"},
  {"action":"done","description":"Открыл Reels, сэр."}
]

"открой настройки безопасность":
[
  {"action":"open_app","target":"com.android.settings","description":"Открываю настройки"},
  {"action":"wait","text":"1500","description":"Жду загрузки"},
  {"action":"click","target":"Безопасность и конфиденциальность","description":"Открываю безопасность"},
  {"action":"done","description":"Открыл безопасность, сэр."}
]

"включи саундклауд поставь трек монтера":
[
  {"action":"open_app","target":"com.soundcloud.android","description":"Открываю SoundCloud"},
  {"action":"wait","text":"2000","description":"Жду загрузки"},
  {"action":"click","target":"Поиск","description":"Открываю поиск"},
  {"action":"search","text":"монтера","description":"Ищу трек"},
  {"action":"click","target":"first_result","description":"Открываю трек"},
  {"action":"done","description":"Включаю, сэр."}
]

"открой погоду":
[
  {"action":"open_app","target":"com.samsung.android.weather","description":"Открываю погоду"},
  {"action":"done","description":"Открыл погоду, сэр."}
]

"листай рилсы инстаграм":
[
  {"action":"open_app","target":"com.instagram.android","description":"Открываю Instagram"},
  {"action":"wait","text":"2000","description":"Жду загрузки"},
  {"action":"click","target":"Reels","description":"Нажимаю Reels"},
  {"action":"wait","text":"1500","description":"Жду загрузки"},
  {"action":"scroll_down","description":"Листаю"},
  {"action":"done","description":"Листаю рилсы, сэр."}
]

Только JSON. Без markdown. Без объяснений.
        """.trimIndent()

        try {
            val messages = listOf(
                JSONObject().apply {
                    put("role", "system")
                    put("content", "Ты генерируешь ТОЛЬКО JSON массив шагов для управления Android. Никакого текста кроме JSON.")
                },
                JSONObject().apply { put("role", "user"); put("content", prompt) }
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

    private suspend fun callGroq(messages: List<JSONObject>): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("messages", JSONArray(messages))
            put("max_tokens", 800)
            put("temperature", 0.2) // низкая температура = точнее следует инструкциям
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
            listOf(AgentStep("error", description = "Ошибка разбора: ${e.message}"))
        }
    }

    private fun bitmapB64(bitmap: Bitmap): String {
        val s = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, s)
        return Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
    }
}
