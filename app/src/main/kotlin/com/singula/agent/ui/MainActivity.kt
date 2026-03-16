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
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {

    private lateinit var agent: GeminiAgent
    private lateinit var executor: CommandExecutor
    private lateinit var voice: VoiceEngine
    private lateinit var wakeDetector: WakeWordDetector
    private lateinit var sysMonitor: SystemMonitor

    // UI
    private lateinit var chatLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var voiceBtn: Button
    private lateinit var statusDot: TextView
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
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        buildUI()
        init()
        checkPermissions()
        startMetricsUpdate()
    }

    // ═══════════════════════════════════════
    // BUILD UI
    // ═══════════════════════════════════════
    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#020810"))
            fitsSystemWindows = true
        }

        // ── HEADER ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#030D20"))
            setPadding(24, 48, 24, 16)
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
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showKeyDialog() }
        }
        statusDot = TextView(this).apply {
            text = "●"; textSize = 10f
            setTextColor(Color.parseColor("#22FF88"))
        }
        statusLabel = TextView(this).apply {
            text = " ОНЛАЙН"
            textSize = 10f
            setTextColor(Color.parseColor("#22FF88"))
            letterSpacing = 0.1f
        }
        statusRow.addView(statusDot)
        statusRow.addView(statusLabel)
        topRow.addView(logo)
        topRow.addView(statusRow)
        header.addView(topRow)

        // Метрики
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        batteryText = metricView("🔋 ---%")
        netText = metricView("📶 ---")
        timeText = metricView("🕐 --:--")
        metricsRow.addView(batteryText)
        metricsRow.addView(netText)
        metricsRow.addView(timeText)
        header.addView(metricsRow)
        root.addView(header)

        // ── CHAT ──
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.parseColor("#020810"))
        }
        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        scrollView.addView(chatLayout)
        root.addView(scrollView)

        // ── QUICK COMMANDS ──
        val qcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
        }
        val cmds = listOf("📷 Фото", "💻 Статус", "🧠 Память", "⏰ Время")
        val actions = listOf(
            { openCamera() },
            { processCommand("статус всех систем") },
            { showMemory() },
            { processCommand("который час") }
        )
        cmds.forEachIndexed { i, label ->
            val btn = Button(this).apply {
                text = label; textSize = 10f
                setTextColor(Color.parseColor("#4A6080"))
                setBackgroundColor(Color.parseColor("#060F22"))
                layoutParams = LinearLayout.LayoutParams(0, 72, 1f).apply { setMargins(3, 0, 3, 0) }
                setPadding(4, 0, 4, 0)
                setOnClickListener { actions[i]() }
            }
            qcRow.addView(btn)
        }
        root.addView(qcRow)

        // ── VOICE BUTTON ──
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

        // ── WAKE WORD TOGGLE ──
        val wakeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 16, 4)
        }
        val wakeLabel = TextView(this).apply {
            text = "Слушать 'Сингула' всегда:"
            textSize = 11f
            setTextColor(Color.parseColor("#4A6080"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val wakeSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("wake_active", false)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C8A84B"))
            setOnCheckedChangeListener { _, checked ->
                wakeActive = checked
                prefs.edit().putBoolean("wake_active", checked).apply()
                if (checked) startWakeDetector() else stopWakeDetector()
                addMessage("system", if (checked) "Wake word активирован. Скажите 'Сингула' в любой момент." else "Wake word отключён.")
            }
        }
        wakeRow.addView(wakeLabel)
        wakeRow.addView(wakeSwitch)
        root.addView(wakeRow)

        // ── INPUT ROW ──
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 0, 12, 12)
            gravity = Gravity.BOTTOM
        }
        inputField = EditText(this).apply {
            hint = "Введите команду..."
            setHintTextColor(Color.parseColor("#2A3A50"))
            setTextColor(Color.parseColor("#C0D0E8"))
            setBackgroundColor(Color.parseColor("#040A1A"))
            setPadding(18, 14, 18, 14)
            textSize = 14f
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, 8, 0) }
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

    private fun metricView(text: String) = TextView(this).apply {
        this.text = text; textSize = 10f
        setTextColor(Color.parseColor("#4A6080"))
        setPadding(0, 4, 16, 0)
    }

    // ═══════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════
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
            voiceBtn.text = "🔴   СЛУШАЮ..."; voiceBtn.setTextColor(Color.parseColor("#FF4422"))
        }}
        voice.onResult = { text -> runOnUiThread {
            isListening = false
            voiceBtn.text = "🎤   НАЖМИ И ГОВОРИ"; voiceBtn.setTextColor(Color.parseColor("#4AB0FF"))
            processCommand(text)
        }}
        voice.onError = { err -> runOnUiThread {
            isListening = false
            voiceBtn.text = "🎤   НАЖМИ И ГОВОРИ"; voiceBtn.setTextColor(Color.parseColor("#4AB0FF"))
            if (err != "Ничего не услышал") addMessage("system", err)
        }}

        sysMonitor.onAlert = { alert -> runOnUiThread {
            addMessage("ai", alert); voice.speak(alert)
        }}
        sysMonitor.startMonitoring()

        startService(Intent(this, SingulaForegroundService::class.java))

        Handler(Looper.getMainLooper()).postDelayed({
            val greeting = sysMonitor.getCurrentTime()
            addMessage("ai", "$greeting Все системы в норме. Нажмите кнопку или скажите 'Сингула'.")
            voice.speak("$greeting Готова к работе.")
            if (key.isEmpty()) showKeyDialog()
        }, 800)

        if (prefs.getBoolean("wake_active", false)) {
            wakeActive = true
            startWakeDetector()
        }
    }

    // ═══════════════════════════════════════
    // VOICE
    // ═══════════════════════════════════════
    private fun toggleVoice() {
        if (isListening) { voice.stopListening(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        voice.startListening()
    }

    private fun startWakeDetector() {
        wakeDetector.onWakeWord = { runOnUiThread {
            addMessage("system", "Слушаю вас, сэр...")
            voice.speak("Да, сэр?")
            voice.startListening()
        }}
        wakeDetector.onCommand = { cmd -> runOnUiThread { processCommand(cmd) } }
        wakeDetector.start()
    }

    private fun stopWakeDetector() { wakeDetector.stop() }

    // ═══════════════════════════════════════
    // PROCESS COMMAND
    // ═══════════════════════════════════════
    private fun processCommand(text: String) {
        if (isBusy) return
        addMessage("user", text)
        isBusy = true; setStatus("Анализирую...")

        lifecycleScope.launch {
            try {
                val key = prefs.getString("gemini_key", "") ?: ""
                if (key.isEmpty()) {
                    addMessage("ai", "Нужен API ключ, сэр."); showKeyDialog(); return@launch
                }
                agent.setApiKey(key)

                // Специальные команды
                val lower = text.lowercase()
                when {
                    lower.contains("статус") && lower.contains("систем") -> {
                        val info = sysMonitor.getSystemInfo()
                        addMessage("ai", info); voice.speak("Статус систем готов, сэр.")
                        return@launch
                    }
                    lower.contains("память") || lower.contains("что ты помнишь") -> {
                        showMemory(); return@launch
                    }
                    lower.contains("который час") || lower.contains("сколько времени") || lower.contains("время") -> {
                        val t = sysMonitor.getCurrentTime()
                        addMessage("ai", t); voice.speak(t); return@launch
                    }
                    lower.contains("забудь") -> {
                        agent.memory.recall(text).forEach { agent.memory.forget(it.id) }
                        val r = "Удалено из памяти, сэр."
                        addMessage("ai", r); voice.speak(r); return@launch
                    }
                }

                // Нужно ли выполнять на телефоне?
                if (isPhoneCommand(lower)) {
                    setStatus("Планирую шаги...")
                    val steps = agent.parseCommand(text)

                    // Сначала отвечаем голосом
                    val chatReply = agent.chat(text)
                    val cleanReply = chatReply.replace(Regex("\\[ACTION:[^\\]]*\\]"), "").trim()
                    addMessage("ai", cleanReply)
                    voice.speak(cleanReply)

                    // Потом выполняем
                    if (isAccessibilityEnabled()) {
                        setStatus("Выполняю...")
                        executor.execute(steps)
                    } else {
                        executor.execute(steps) // Попробуем всё равно через url_launcher
                    }
                } else {
                    // Просто разговор
                    setStatus("Думаю...")
                    val reply = agent.chat(text)
                    val clean = reply.replace(Regex("\\[ACTION:[^\\]]*\\]"), "").trim()
                    addMessage("ai", clean)
                    voice.speak(clean)
                }
            } catch (e: Exception) {
                val err = "Помехи в канале, сэр."
                addMessage("ai", err); voice.speak(err)
            } finally {
                isBusy = false; setStatus("ОНЛАЙН")
            }
        }
    }

    // ═══════════════════════════════════════
    // CAMERA / PHOTO ANALYSIS
    // ═══════════════════════════════════════
    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, PHOTO_REQUEST)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PHOTO_REQUEST && resultCode == RESULT_OK) {
            val bitmap = data?.extras?.get("data") as? Bitmap ?: return
            addMessage("user", "📷 [Отправил фото]")
            setStatus("Анализирую фото...")
            isBusy = true
            lifecycleScope.launch {
                val reply = agent.describeImage(bitmap, "Опиши подробно что видишь на этом фото")
                addMessage("ai", reply)
                voice.speak("Анализ завершён, сэр.")
                isBusy = false; setStatus("ОНЛАЙН")
            }
        }
    }

    // ═══════════════════════════════════════
    // MEMORY VIEWER
    // ═══════════════════════════════════════
    private fun showMemory() {
        val memories = agent.memory.recall()
        if (memories.isEmpty()) {
            addMessage("ai", "Память пуста, сэр. Расскажите мне о себе.")
            voice.speak("Память пуста, сэр.")
            return
        }
        val sb = StringBuilder("Вот что я помню, сэр:\n")
        memories.forEach { sb.append("▸ [${it.type}] ${it.content}\n") }
        addMessage("ai", sb.toString())
        voice.speak("Показал что помню, сэр.")
    }

    // ═══════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════
    private fun addMessage(role: String, text: String) {
        val isAi = role == "ai"
        val isSystem = role == "system"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isAi || isSystem) Gravity.START else Gravity.END
            setPadding(0, 3, 0, 3)
        }
        if (!isSystem) {
            val nameView = TextView(this).apply {
                this.text = when (role) { "ai" -> "SINGULA"; "user" -> "ОПЕРАТОР"; else -> "" }
                textSize = 9f
                setTextColor(Color.parseColor("#3A4A60"))
                setPadding(6, 0, 6, 2)
            }
            container.addView(nameView)
        }
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(18, 12, 18, 12)
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
    }

    private fun setStatus(text: String) {
        val isOnline = text == "ОНЛАЙН"
        statusDot.setTextColor(if (isOnline) Color.parseColor("#22FF88") else Color.parseColor("#C8A84B"))
        statusLabel.text = " $text"
        statusLabel.setTextColor(if (isOnline) Color.parseColor("#22FF88") else Color.parseColor("#C8A84B"))
    }

    private fun startMetricsUpdate() {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                batteryText.text = "🔋 ${sysMonitor.getBatteryLevel()}%${if(sysMonitor.isCharging()) "⚡" else ""}"
                netText.text = "📶 ${sysMonitor.getNetworkInfo()}"
                val cal = java.util.Calendar.getInstance()
                timeText.text = "🕐 ${String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))}"
                handler.postDelayed(this, 30_000)
            }
        }
        handler.post(runnable)
    }

    private fun showKeyDialog() {
        val input = EditText(this).apply {
            hint = "AIza... (Gemini API Key)"
            setHintTextColor(Color.parseColor("#4A6080"))
            setTextColor(Color.parseColor("#C0D0E8"))
            setBackgroundColor(Color.parseColor("#040A1A"))
            setPadding(20, 16, 20, 16)
            setText(prefs.getString("gemini_key", "") ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setMessage("Бесплатно на aistudio.google.com")
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
    }

    private fun isPhoneCommand(text: String): Boolean {
        val keywords = listOf("открой","запусти","включи","найди","поищи","напиши",
            "отправь","позвони","youtube","ютуб","telegram","телеграм","whatsapp",
            "вотсап","instagram","spotify","тикток","discord","вконтакте","вк",
            "браузер","настройки","будильник","таймер","загугли","soundcloud",
            "netflix","карты","маршрут","deepseek","дипсик","зайди","перейди",
            "скачай","установи","сфотографируй")
        return keywords.any { text.contains(it) }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.contains("singula") }
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)

        if (!isAccessibilityEnabled()) {
            Handler(Looper.getMainLooper()).postDelayed({
                AlertDialog.Builder(this)
                    .setTitle("⚡ Включить управление")
                    .setMessage("Для управления приложениями включите SINGULA в:\n\nНастройки → Специальные возможности → Загруженные приложения → SINGULA Agent → ВКЛ")
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("Позже", null).show()
            }, 2500)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (wakeActive) startWakeDetector()
        }
    }

    override fun onDestroy() {
        voice.destroy()
        wakeDetector.stop()
        sysMonitor.stopMonitoring()
        super.onDestroy()
    }
}
