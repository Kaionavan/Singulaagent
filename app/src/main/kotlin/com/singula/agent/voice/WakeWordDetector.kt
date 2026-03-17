package com.singula.agent.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class WakeWordDetector(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var isPaused = false
    private val handler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null

    var onWakeWord: (() -> Unit)? = null
    var onCommand: ((String) -> Unit)? = null

    private val wakeWords = listOf("сингула", "singula")

    fun start() {
        if (isRunning) return
        isRunning = true
        isPaused = false
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        silenceAllSounds() // заглушаем ВСЕ звуки системы сразу и навсегда пока работаем
        handler.post { listenCycle() }
    }

    fun stop() {
        isRunning = false
        isPaused = false
        handler.removeCallbacksAndMessages(null)
        unsilenceAllSounds()
        destroyRecognizer()
    }

    fun pause() {
        isPaused = true
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
    }

    fun resume() {
        if (!isRunning) return
        isPaused = false
        // Небольшая задержка чтобы TTS успел закончить
        handler.postDelayed({ if (isRunning && !isPaused) listenCycle() }, 1200)
    }

    private fun silenceAllSounds() {
        try {
            // Заглушаем все потоки которые могут пищать при старте микрофона
            audioManager?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {}
    }

    private fun unsilenceAllSounds() {
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
    }

    private fun destroyRecognizer() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun restart(delayMs: Long = 100) {
        if (!isRunning || isPaused) return
        destroyRecognizer()
        handler.postDelayed({
            if (isRunning && !isPaused) listenCycle()
        }, delayMs)
    }

    private fun listenCycle() {
        if (!isRunning || isPaused) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            restart(3000); return
        }

        // Всегда заглушаем перед созданием нового recognizer
        silenceAllSounds()
        destroyRecognizer()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(e: Int, p: Bundle?) {}

            override fun onResults(results: Bundle?) {
                if (isPaused) { restart(1500); return }

                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.lowercase().trim() }
                    ?: emptyList()

                // Ищем wake word среди всех вариантов распознавания
                val hit = matches.firstOrNull { txt -> wakeWords.any { txt.contains(it) } }

                if (hit != null) {
                    // Вырезаем wake word — остаток это команда
                    var command: String = hit
                    wakeWords.sortedByDescending { it.length }.forEach { w ->
                        command = command.replace(w, "")
                    }
                    command = command.trim().trimStart(',', '.', ' ', '-')

                    if (command.length > 2) {
                        // "Сингула открой телеграм" — команда сразу
                        onCommand?.invoke(command)
                    } else {
                        // Только "Сингула" — ждём команду
                        onWakeWord?.invoke()
                    }
                }

                // Сразу перезапускаем — минимальная пауза
                restart(150)
            }

            override fun onError(error: Int) {
                val delay = when (error) {
                    // Тишина или нет совпадения — перезапускаем быстро
                    SpeechRecognizer.ERROR_NO_MATCH -> 100L
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 100L
                    // Занят — подождём немного
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                    SpeechRecognizer.ERROR_AUDIO -> 1500L
                    SpeechRecognizer.ERROR_CLIENT -> 500L
                    SpeechRecognizer.ERROR_NETWORK -> 3000L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        isRunning = false; return
                    }
                    else -> 500L
                }
                restart(delay)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Слушаем дольше — даём время сказать команду
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            restart(1000)
        }
    }
}
