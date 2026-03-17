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
Ты управляешь Android Samsung Galaxy M12 (720x1600px). Разбей команду на шаги.
Команда: "$command"
ТОЛЬКО JSON массив. Никакого текста кроме JSON.

ДЕЙСТВИЯ:
- open_app: target=пакет
- find_contact: text=имя — найти контакт в Telegram/WhatsApp
- open_chat: text=название чата или группы — открыть конкретный чат/группу в Telegram
- type_text: text=текст — напечатать в поле ввода
- search: text=запрос — нажать поиск и ввести
- click: target=текст на экране
- tap_coords: target="x,y" — тап по координатам
- send: отправить сообщение
- scroll_down: листать вниз
- scroll_up: листать вверх
- back: назад
- wait: text=миллисекунды
- done: description=ответ пользователю

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
calculator=com.sec.android.app.popupcalculator
clock=com.sec.android.app.clockpackage

═══════════════════════════════
INSTAGRAM — координаты нижней панели (ВСЕГДА tap_coords, никогда не click):
- Домой:   tap_coords "72,1516"
- Reels:   tap_coords "216,1516"   ← ВТОРАЯ КНОПКА СЛЕВА
- Директ:  tap_coords "360,1516"
- Поиск:   tap_coords "504,1516"
- Профиль: tap_coords "634,1516"

═══════════════════════════════
TELEGRAM — три варианта:
1. Написать контакту (человеку): open_app → find_contact имя → type_text текст → send → done
2. Написать в группу/канал: open_app → open_chat название → type_text текст → send → done
3. Просто открыть: open_app → done

═══════════════════════════════
НАСТРОЙКИ Samsung — ВСЕГДА после open_app добавляй click нужного раздела:
безопасность       → "Безопасность и конфиденциальность"
звук/громкость     → "Звук и вибрация"
дисплей/яркость    → "Дисплей"
wifi/интернет/сеть → "Подключения"
аккаунты           → "Учётные записи и резервное копирование"
общие              → "Общие настройки"
уведомления        → "Уведомления"
батарея            → "Батарея"
доступность        → "Специальные возможности"
хранилище/память   → "Хранилище"
обои               → "Обои и стиль"
приложения         → "Приложения"
язык               → "Общие настройки" затем click "Язык"

═══════════════════════════════
НЕЙРОСЕТЬ/AI — когда говорят "открой нейросеть", "зайди в нейросеть", "открой AI":
→ open_app com.android.chrome → wait 2000 → tap_coords "360,120" (адресная строка) → type_text "chat.deepseek.com" → send → done
НЕ открывай настройки и не открывай камеру!

ПОГОДА — отдельное приложение, НЕ настройки:
→ open_app com.samsung.android.weather

YOUTUBE: open_app → wait 2000 → tap_coords "648,72" → type_text запрос → wait 1500 → click "first_result"

SOUNDCLOUD: open_app → wait 2000 → click "Поиск" → wait 500 → type_text запрос → wait 2000 → click "first_result"

SPOTIFY: open_app → wait 2000 → click "Поиск" → wait 500 → type_text запрос → wait 2000 → click "first_result"

═══════════════════════════════
ПРИМЕРЫ:

"открой инстаграм рилсы":
[
  {"action":"open_app","target":"com.instagram.android","description":"Открываю Instagram"},
  {"action":"wait","text":"2500"},
  {"action":"tap_coords","target":"216,1516","description":"Нажимаю Reels"},
  {"action":"done","description":"Открыл Reels, сэр."}
]

"листай рилсы инстаграм":
[
  {"action":"open_app","target":"com.instagram.android","description":"Открываю Instagram"},
  {"action":"wait","text":"2500"},
  {"action":"tap_coords","target":"216,1516","description":"Reels"},
  {"action":"wait","text":"1500"},
  {"action":"scroll_down","description":"Листаю"},
  {"action":"scroll_down"},
  {"action":"scroll_down"},
  {"action":"done","description":"Листаю рилсы, сэр."}
]

"открой телеграм напиши Диме как дела":
[
  {"action":"open_app","target":"org.telegram.messenger","description":"Открываю Telegram"},
  {"action":"find_contact","text":"Дима","description":"Ищу контакт Дима"},
  {"action":"type_text","text":"как дела","description":"Пишу сообщение"},
  {"action":"send","description":"Отправляю"},
  {"action":"done","description":"Сообщение отправлено, сэр."}
]

"открой телеграм напиши в группу Друзья привет всем":
[
  {"action":"open_app","target":"org.telegram.messenger","description":"Открываю Telegram"},
  {"action":"open_chat","text":"Друзья","description":"Открываю группу Друзья"},
  {"action":"type_text","text":"привет всем","description":"Пишу сообщение"},
  {"action":"send","description":"Отправляю"},
  {"action":"done","description":"Отправлено в группу, сэр."}
]

"открой настройки звук":
[
  {"action":"open_app","target":"com.android.settings","description":"Открываю настройки"},
  {"action":"wait","text":"1500"},
  {"action":"click","target":"Звук и вибрация","description":"Открываю звук"},
  {"action":"done","description":"Открыл звук, сэр."}
]

"открой нейросеть":
[
  {"action":"open_app","target":"com.android.chrome","description":"Открываю браузер"},
  {"action":"wait","text":"2000"},
  {"action":"tap_coords","target":"360,120","description":"Адресная строка"},
  {"action":"type_text","text":"chat.deepseek.com","description":"Ввожу адрес"},
  {"action":"send","description":"Перехожу"},
  {"action":"done","description":"Открываю нейросеть, сэр."}
]

"открой погоду":
[
  {"action":"open_app","target":"com.samsung.android.weather","description":"Открываю погоду"},
  {"action":"done","description":"Открыл погоду, сэр."}
]

"включи саундклауд поставь монтера":
[
  {"action":"open_app","target":"com.soundcloud.android","description":"Открываю SoundCloud"},
  {"action":"wait","text":"2000"},
  {"action":"click","target":"Поиск","description":"Поиск"},
  {"action":"wait","text":"500"},
  {"action":"type_text","text":"монтера"},
  {"action":"wait","text":"2000"},
  {"action":"click","target":"first_result","description":"Включаю"},
  {"action":"done","description":"Включаю, сэр."}
]

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
