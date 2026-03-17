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
        messages.add(JSONObject().apply { put("role", "system"); put("content", "$SYS\n\n$ctx") })
        history.takeLast(10).forEach { (role, text) ->
            messages.add(JSONObject().apply {
                put("role", if (role == "ai") "assistant" else "user")
                put("content", text)
            })
        }
        messages.add(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        val reply = try {
            val raw = callGroq(messages)
            val json = JSONObject(raw)
            if (json.has("error")) "Ошибка: ${json.getJSONObject("error").optString("message")}"
            else json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) { "Помехи в канале связи, сэр. (${e.message})" }
        memory.addToHistory("user", userMessage)
        memory.addToHistory("ai", reply)
        extractFacts(userMessage)
        reply
    }

    suspend fun parseCommand(command: String): List<AgentStep> = withContext(Dispatchers.IO) {
        val prompt = """
Управляй Android телефоном Samsung Galaxy M12 (экран 720x1600). Разбей команду на точные шаги.
Команда: "$command"
Ответь ТОЛЬКО JSON массивом без текста до/после.

ДЕЙСТВИЯ:
- open_app: target=package
- find_contact: text=имя (Telegram/WhatsApp)
- type_text: text=текст
- search: text=запрос (нажимает кнопку поиска потом вводит)
- click: target=точный текст на экране
- tap_coords: target="x,y" — тап по координатам
- send: отправить сообщение
- scroll_down: листать вниз
- scroll_up: листать вверх
- back: назад
- wait: text=мс
- done: description=ответ

ПАКЕТЫ:
telegram=org.telegram.messenger
youtube=com.google.android.youtube
whatsapp=com.whatsapp
instagram=com.instagram.android
spotify=com.spotify.music
soundcloud=com.soundcloud.android
vk=com.vkontakte.android
discord=com.discord
chrome=com.android.chrome
settings=com.android.settings
maps=com.google.android.apps.maps
tiktok=com.zhiliaoapp.musically
weather=com.samsung.android.weather
gallery=com.sec.android.gallery3d
camera=com.sec.android.app.camera
contacts=com.samsung.android.contacts
phone=com.samsung.android.dialer
files=com.samsung.android.myfiles
calculator=com.sec.android.app.popupcalculator
clock=com.sec.android.app.clockpackage

INSTAGRAM нижняя панель (координаты для Samsung M12 720x1600):
- Домой: tap_coords "72,1540"
- Поиск: tap_coords "216,1540"
- REELS: tap_coords "360,1540"  ← ВСЕГДА tap_coords для рилсов
- Директ: tap_coords "504,1540"
- Профиль: tap_coords "648,1540"
НИКОГДА не используй click для кнопок Instagram — только tap_coords

НАСТРОЙКИ Samsung (после open_app всегда добавляй click раздела):
- безопасность → "Безопасность и конфиденциальность"
- звук → "Звук и вибрация"
- дисплей/экран → "Дисплей"
- wifi/интернет/сеть → "Подключения"
- аккаунты → "Учётные записи и резервное копирование"
- общие → "Общие настройки"
- уведомления → "Уведомления"
- батарея → "Батарея"
- доступность → "Специальные возможности"
- память/хранилище → "Хранилище"
- обои → "Обои и стиль"

ПОГОДА: open_app com.samsung.android.weather (это отдельное приложение, НЕ в настройках)

YOUTUBE: open_app → wait 2000 → tap_coords "648,72" (иконка поиска) → type_text запрос → wait 1500 → click "first_result"

SOUNDCLOUD/SPOTIFY: open_app → wait 2000 → click "Поиск" → wait 500 → type_text запрос → wait 2000 → click "first_result"

TELEGRAM: open_app → find_contact имя → type_text текст → send → done

ПРИМЕРЫ:

"открой инстаграм рилсы":
[{"action":"open_app","target":"com.instagram.android","description":"Открываю Instagram"},{"action":"wait","text":"2500","description":"Жду загрузки"},{"action":"tap_coords","target":"360,1540","description":"Нажимаю Reels"},{"action":"done","description":"Открыл Reels, сэр."}]

"листай рилсы":
[{"action":"open_app","target":"com.instagram.android","description":"Открываю Instagram"},{"action":"wait","text":"2500"},{"action":"tap_coords","target":"360,1540","description":"Reels"},{"action":"wait","text":"1500"},{"action":"scroll_down","description":"Листаю"},{"action":"scroll_down"},{"action":"scroll_down"},{"action":"done","description":"Листаю рилсы, сэр."}]

"открой настройки звук":
[{"action":"open_app","target":"com.android.settings","description":"Открываю настройки"},{"action":"wait","text":"1500"},{"action":"click","target":"Звук и вибрация","description":"Открываю звук"},{"action":"done","description":"Открыл звук, сэр."}]

"открой настройки общие":
[{"action":"open_app","target":"com.android.settings","description":"Открываю настройки"},{"action":"wait","text":"1500"},{"action":"click","target":"Общие настройки","description":"Открываю общие настройки"},{"action":"done","description":"Открыл, сэр."}]

"открой погоду":
[{"action":"open_app","target":"com.samsung.android.weather","description":"Открываю погоду"},{"action":"done","description":"Открыл погоду, сэр."}]

"открой телеграм напиши Диме привет":
[{"action":"open_app","target":"org.telegram.messenger","description":"Открываю Telegram"},{"action":"find_contact","text":"Дима","description":"Ищу Диму"},{"action":"type_text","text":"привет","description":"Пишу"},{"action":"send","description":"Отправляю"},{"action":"done","description":"Отправлено, сэр."}]

"включи саундклауд поставь монтера":
[{"action":"open_app","target":"com.soundcloud.android","description":"Открываю SoundCloud"},{"action":"wait","text":"2000"},{"action":"click","target":"Поиск","description":"Поиск"},{"action":"wait","text":"500"},{"action":"type_text","text":"монтера"},{"action":"wait","text":"2000"},{"action":"click","target":"first_result","description":"Включаю"},{"action":"done","description":"Включаю, сэр."}]

Только JSON. Без markdown. Без объяснений.
        """.trimIndent()

        try {
            val messages = listOf(
                JSONObject().apply { put("role","system"); put("content","Только JSON массив. Никакого текста кроме JSON.") },
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
            put("temperature", 0.1)
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
