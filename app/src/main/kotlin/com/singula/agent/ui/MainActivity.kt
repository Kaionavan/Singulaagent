package com.singula.agent.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.singula.agent.ai.CommandExecutor
import com.singula.agent.ai.GeminiAgent
import com.singula.agent.service.SingulaForegroundService
import com.singula.agent.service.SystemMonitor
import com.singula.agent.voice.VoiceEngine
import com.singula.agent.voice.WakeWordDetector
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var agent: GeminiAgent
    private lateinit var executor: CommandExecutor
    private lateinit var voice: VoiceEngine
    private lateinit var wakeDetector: WakeWordDetector
    private lateinit var sysMonitor: SystemMonitor

    private lateinit var chatLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var voiceBtn: Button
    private lateinit var statusLabel: TextView
    private lateinit var batteryText: TextView
    private lateinit var netText: TextView
    private lateinit var timeText: TextView

    private var isListening = false
    private var isBusy = false
    private var wakeActive = false
    private val prefs by lazy { getSharedPreferences("singula", Context.MODE_PRIVATE) }
    private val PHOTO_REQUEST = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUI()
            init()
            requestMicPermission()
        } catch (e: Exception) {
            val tv = TextView(this)
            tv.text = "SINGULA\n\nОшибка запуска: ${e.message}"
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.parseColor("#020810"))
            tv.setPadding(40, 100, 40, 40)
            tv.textSize = 16f
            setContentView(tv)
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#020810"))
        }

        // HEADER
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#030D20"))
            setPadding(24, 48, 24, 12)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "SINGULA"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F0C040"))
            letterSpacing = 0.25f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusLabel = TextView(this).apply {
            text = "● ЗАПУСК"
            textSize = 10f
            setTextColor(Color.parseColor("#C8A84B"))
        }
        topRow.addView(logo)
        topRow.addView(statusLabel)
        header.addView(topRow)

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 0)
        }
        batteryText = TextView(this).apply { text = "🔋 --"; textSize = 10f; setTextColor(Color.parseColor("#4A6080")); setPadding(0,0,16,0) }
        netText = TextView(this).apply { text = "📶 --"; textSize = 10f; setTextColor(Color.parseColor("#4A6080")); setPadding(0,0,16,0) }
        timeText = TextView(this).apply { text = "🕐 --:--"; textSize = 10f; setTextColor(Color.parseColor("#4A6080")) }
        metricsRow.addView(batteryText)
        metricsRow.addView(netText)
        metricsRow.addView(timeText)
        header.addView(metricsRow)
        root.addView(header)

        // CHAT
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#020810"))
        }
        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        scrollView.addView(chatLayout)
        root.addView(scrollView)

        // QUICK BUTTONS
        val qRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
        }
        listOf("📷 Фото" to { openCamera() },
               "💻 Статус" to { processCommand("статус систем") },
               "🧠 Память" to { showMemory() },
               "⚙️ Ключ" to { showKeyDialog() }
        ).forEach { (label, action) ->
            val btn = Button(this).apply {
                text = label; textSize = 10f
                setTextColor(Color.parseColor("#4A6080"))
                setBackgroundColor(Color.parseColor("#060F22"))
                layoutParams = LinearLayout.LayoutParams(0, 68, 1f).apply { setMargins(3,0,3,0) }
                setPadding(2,0,2,0)
                setOnClickListener { action() }
            }
            qRow.addView(btn)
        }
        root.addView(qRow)

        // VOICE BUTTON
        voiceBtn = Button(this).apply {
            text = "🎤   НАЖМИ И ГОВОРИ"
            textSize = 14f
            setTextColor(Color.parseColor("#4AB0FF"))
            setBackgroundColor(Color.parseColor("#060F24"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 110
            ).apply { setMargins(12, 4, 12, 4) }
        }
        voiceBtn.setOnClickListener { toggleVoice() }
        root.addView(voiceBtn)

        // WAKE SWITCH
        val wakeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 2, 16, 2)
        }
        val wakeLabel = TextView(this).apply {
            text = "Слушать 'Сингула' постоянно:"
            textSize = 11f
            setTextColor(Color.parseColor("#4A6080"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val wakeSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("wake_active", false)
            setOnCheckedChangeListener { _, checked ->
                wakeActive = checked
                prefs.edit().putBoolean("wake_active", checked).apply()
                if (checked) startWake() else stopWake()
            }
        }
        wakeRow.addView(wakeLabel)
        wakeRow.addView(wakeSwitch)
        root.addView(wakeRow)

        // INPUT
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 0, 12, 16)
        }
        inputField = EditText(this).apply {
            hint = "Введите команду..."
            setHintTextColor(Color.parseColor("#2A3A50"))
            setTextColor(Color.parseColor("#C0D0E8"))
            setBackgroundColor(Color.parseColor("#040A1A"))
            setPadding(16, 14, 16, 14)
            textSize = 14f
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0,0,8,0) }
        }
        val sendBtn = Button(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(Color.parseColor("#C8A84B"))
            setBackgroundColor(Color.parseColor("#1A1200"))
            layoutParams = LinearLayout.LayoutParams(110, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        sendBtn.setOnClickListener {
            val t = inputField.text.toString().trim()
            if (t.isNotEmpty()) { inputField.setText(""); processCommand(t) }
        }
        inputField.setOnEditorActionListener { _, _, _ -> sendBtn.performClick(); true }
        inputRow.addView(inputField)
        inputRow.addView(sendBtn)
        root.addView(inputRow)

        setContentView(root)
    }

    private fun init() {
        agent = GeminiAgent(this)
        executor = CommandExecutor(this)
        voice = VoiceEngine(this)
        wakeDetector = WakeWordDetector(this)
        sysMonitor = SystemMonitor(this)

        val key = prefs.getString("gemini_key", "") ?: ""
        if (key.isNotEmpty()) agent.setApiKey(key)

        executor.onStatusUpdate = { s -> runOnUiThread { setStatus(s) } }

        voice.onListening = { runOnUiThread {
            isListening = true
            voiceBtn.text = "🔴   СЛУШАЮ..."
            voiceBtn.setTextColor(Color.parseColor("#FF4422"))
        }}
        voice.onResult = { text -> runOnUiThread {
            isListening = false
            voiceBtn.text = "🎤   НАЖМИ И ГОВОРИ"
            voiceBtn.setTextColor(Color.parseColor("#4AB0FF"))
            processCommand(text)
        }}
        voice.onError = { err -> runOnUiThread {
            isListening = false
            voiceBtn.text = "🎤   НАЖМИ И ГОВОРИ"
            voiceBtn.setTextColor(Color.parseColor("#4AB0FF"))
            if (err != "Ничего не услышал") addMessage("system", err)
        }}

        // ══ ГЛАВНОЕ ИСПРАВЛЕНИЕ WAKE WORD ══
        // Когда SINGULA начинает говорить — останавливаем прослушивание
        // Иначе слышит свой голос и уходит в бесконечный цикл
        voice.onSpeakStart = {
            if (wakeActive) wakeDetector.pause()
        }
        // Когда замолчала — возобновляем через 800мс
        voice.onSpeakEnd = {
            if (wakeActive) wakeDetector.resume()
        }

        sysMonitor.onAlert = { alert -> runOnUiThread {
            addMessage("ai", alert)
            voice.speak(alert)
        }}

        try { sysMonitor.startMonitoring() } catch (e: Exception) {}
        try { startService(Intent(this, SingulaForegroundService::class.java)) } catch (e: Exception) {}

        startMetricsUpdate()

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val greeting = sysMonitor.getCurrentTime()
                addMessage("ai", "$greeting Все системы в норме. Готова к работе.")
                voice.speak("$greeting Готова к работе.")
                if (key.isEmpty()) showKeyDialog()
                if (prefs.getBoolean("wake_active", false)) { wakeActive = true; startWake() }
            } catch (e: Exception) {
                addMessage("system", "SINGULA запущена.")
            }
        }, 1000)
    }

    private fun toggleVoice() {
        if (isListening) { voice.stopListening(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        try { voice.startListening() } catch (e: Exception) {
            addMessage("system", "Ошибка микрофона: ${e.message}")
        }
    }

    private fun startWake() {
        wakeDetector.onWakeWord = { runOnUiThread {
            // Услышали "Сингула" — отвечаем, wake detector сам продолжает слушать
            addMessage("system", "Слушаю, сэр...")
            voice.speak("Да, сэр?")
            // НЕ вызываем toggleVoice — wake detector уже слушает следующую фразу
        }}
        wakeDetector.onCommand = { cmd -> runOnUiThread { processCommand(cmd) } }
        try { wakeDetector.start() } catch (e: Exception) {}
    }

    private fun stopWake() {
        try { wakeDetector.stop() } catch (e: Exception) {}
    }

    private fun processCommand(text: String) {
        if (isBusy) return
        addMessage("user", text)
        isBusy = true
        setStatus("Думаю...")

        lifecycleScope.launch {
            try {
                val key = prefs.getString("gemini_key", "") ?: ""
                if (key.isEmpty()) {
                    addMessage("ai", "Нужен API ключ, сэр.")
                    showKeyDialog()
                    return@launch
                }
                agent.setApiKey(key)

                val lower = text.lowercase()

                // Быстрые команды без AI
                when {
                    lower.contains("статус") && !lower.contains("открой") -> {
                        val info = sysMonitor.getSystemInfo()
                        addMessage("ai", info)
                        voice.speak("Статус готов, сэр.")
                        return@launch
                    }
                    lower.contains("который час") || lower.contains("сколько времени") -> {
                        val t = sysMonitor.getCurrentTime()
                        addMessage("ai", t)
                        voice.speak(t)
                        return@launch
                    }
                    lower.contains("покажи память") || lower.contains("что помнишь") -> {
                        showMemory()
                        return@launch
                    }
                }

                if (isPhoneCommand(lower)) {
                    setStatus("Планирую...")

                    if (!isAccessibilityEnabled()) {
                        addMessage("ai", "⚠️ Служба управления отключена, сэр. Настройки → Спец. возможности → SINGULA Agent → Включить")
                        voice.speak("Служба управления отключена, сэр.")
                        return@launch
                    }

                    val steps = agent.parseCommand(text)

                    if (steps.isEmpty() || steps.first().action == "error") {
                        addMessage("ai", "Не смог разобрать команду, сэр.")
                        return@launch
                    }

                    addMessage("ai", "Выполняю, сэр.")
                    voice.speak("Выполняю, сэр.")

                    setStatus("Выполняю...")
                    val result = executor.execute(steps)

                    addMessage("ai", result)
                    voice.speak(result)

                } else {
                    setStatus("Думаю...")
                    val reply = agent.chat(text)
                    val clean = reply.replace(Regex("\\[ACTION:[^\\]]*\\]"), "").trim()
                    addMessage("ai", clean)
                    voice.speak(clean)
                }

            } catch (e: Exception) {
                addMessage("ai", "Помехи в канале, сэр.")
            } finally {
                isBusy = false
                setStatus("ОНЛАЙН")
            }
        }
    }

    private fun openCamera() {
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(packageManager) != null)
                startActivityForResult(intent, PHOTO_REQUEST)
        } catch (e: Exception) { addMessage("system", "Камера недоступна") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PHOTO_REQUEST && resultCode == RESULT_OK) {
            val bitmap = data?.extras?.get("data") as? Bitmap ?: return
            addMessage("user", "📷 [Фото]")
            isBusy = true
            lifecycleScope.launch {
                try {
                    val reply = agent.describeImage(bitmap)
                    addMessage("ai", reply)
                    voice.speak("Анализ завершён, сэр.")
                } catch (e: Exception) {
                    addMessage("ai", "Не удалось проанализировать фото, сэр.")
                } finally { isBusy = false }
            }
        }
    }

    private fun showMemory() {
        val mems = agent.memory.recall()
        if (mems.isEmpty()) {
            addMessage("ai", "Память пуста, сэр.")
            voice.speak("Память пуста, сэр.")
        } else {
            val sb = StringBuilder("Помню:\n")
            mems.forEach { sb.append("▸ ${it.content}\n") }
            addMessage("ai", sb.toString())
            voice.speak("Показал память, сэр.")
        }
    }

    private fun addMessage(role: String, text: String) {
        runOnUiThread {
            try {
                val isAi = role == "ai"
                val isSystem = role == "system"
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = if (!isAi && !isSystem) Gravity.END else Gravity.START
                    setPadding(0, 3, 0, 3)
                }
                if (!isSystem) {
                    val name = TextView(this).apply {
                        this.text = if (isAi) "SINGULA" else "ВЫ"
                        textSize = 9f
                        setTextColor(Color.parseColor("#3A4A60"))
                        setPadding(6, 0, 6, 2)
                    }
                    container.addView(name)
                }
                val bubble = TextView(this).apply {
                    this.text = text
                    textSize = 14f
                    setPadding(16, 12, 16, 12)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
                    setTextColor(when(role) {
                        "ai" -> Color.parseColor("#BCD6F8")
                        "system" -> Color.parseColor("#4A8060")
                        else -> Color.parseColor("#C0D0E8")
                    })
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14f
                        setColor(when(role) {
                            "ai" -> Color.parseColor("#0A1E38")
                            "system" -> Color.parseColor("#041208")
                            else -> Color.parseColor("#140C02")
                        })
                        setStroke(1, when(role) {
                            "ai" -> Color.parseColor("#1A3A60")
                            "system" -> Color.parseColor("#0A2818")
                            else -> Color.parseColor("#3A2808")
                        })
                    }
                }
                container.addView(bubble)
                chatLayout.addView(container)
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            } catch (e: Exception) {}
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            try {
                val online = text == "ОНЛАЙН"
                statusLabel.text = "● $text"
                statusLabel.setTextColor(
                    if (online) Color.parseColor("#22FF88") else Color.parseColor("#C8A84B")
                )
            } catch (e: Exception) {}
        }
    }

    private fun startMetricsUpdate() {
        val handler = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                try {
                    batteryText.text = "🔋 ${sysMonitor.getBatteryLevel()}%${if(sysMonitor.isCharging())"⚡" else ""}"
                    netText.text = "📶 ${sysMonitor.getNetworkInfo()}"
                    val c = java.util.Calendar.getInstance()
                    timeText.text = "🕐 ${String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))}"
                } catch (e: Exception) {}
                handler.postDelayed(this, 30_000)
            }
        }
        handler.post(r)
    }

    private fun showKeyDialog() {
        runOnUiThread {
            try {
                val input = EditText(this).apply {
                    hint = "gsk_... (Groq API Key)"
                    setHintTextColor(Color.parseColor("#4A6080"))
                    setTextColor(Color.parseColor("#C0D0E8"))
                    setBackgroundColor(Color.parseColor("#040A1A"))
                    setPadding(20, 16, 20, 16)
                    setText(prefs.getString("gemini_key", "") ?: "")
                }
                AlertDialog.Builder(this)
                    .setTitle("API Key")
                    .setMessage("Бесплатный Groq ключ: console.groq.com\nРегистрация без карты, без лимитов")
                    .setView(input)
                    .setPositiveButton("Сохранить") { _, _ ->
                        val key = input.text.toString().trim()
                        if (key.isNotEmpty()) {
                            prefs.edit().putString("gemini_key", key).apply()
                            agent.setApiKey(key)
                            addMessage("ai", "Ключ сохранён. Готова к работе, сэр!")
                            voice.speak("Ключ принят. Готова к работе, сэр!")
                        }
                    }
                    .setNegativeButton("Отмена", null).show()
            } catch (e: Exception) {}
        }
    }

    private fun isPhoneCommand(text: String): Boolean {
        val kw = listOf(
            "открой", "запусти", "включи", "найди", "поищи", "напиши",
            "отправь", "позвони", "youtube", "ютуб", "telegram", "телеграм",
            "whatsapp", "вотсап", "instagram", "инстаграм", "spotify", "спотифай",
            "тикток", "discord", "дискорд", "вконтакте", "вк", "браузер",
            "настройки", "будильник", "таймер", "загугли", "погугли",
            "зайди", "перейди", "набери", "листай", "прокрути", "скролл"
        )
        return kw.any { text.contains(it) }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id.contains("singula", ignoreCase = true) }
        } catch (e: Exception) { false }
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        try { voice.destroy() } catch (e: Exception) {}
        try { wakeDetector.stop() } catch (e: Exception) {}
        try { sysMonitor.stopMonitoring() } catch (e: Exception) {}
        super.onDestroy()
    }
}
