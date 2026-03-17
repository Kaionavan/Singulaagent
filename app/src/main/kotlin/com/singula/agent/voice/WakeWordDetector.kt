package com.singula.agent.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*

class WakeWordDetector(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var audioManager: AudioManager? = null

    var onWakeWord: (() -> Unit)? = null
    var onCommand: ((String) -> Unit)? = null

    private val wakeWords = listOf("сингула", "singula", "слушай")

    fun start() {
        if (isRunning) return
        isRunning = true
        // Отключаем системные звуки распознавания речи
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        listenCycle()
    }

    fun stop() {
        isRunning = false
        try { recognizer?.stopListening() } catch (e: Exception) {}
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        scope.cancel()
    }

    private fun muteBeep() {
        try {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.ADJUST_MUTE, 0
            )
        } catch (e: Exception) {}
    }

    private fun unmuteBeep() {
        try {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.ADJUST_UNMUTE, 0
            )
        } catch (e: Exception) {}
    }

    private fun listenCycle() {
        if (!isRunning) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return

        try { recognizer?.destroy() } catch (e: Exception) {}

        muteBeep() // Заглушаем бипы

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onResults(results: Bundle?) {
                unmuteBeep()
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: ""

                val hasWake = wakeWords.any { text.contains(it) }
                if (hasWake) {
                    var command = text
                    wakeWords.forEach { command = command.replace(it, "") }
                    command = command.trim()
                    if (command.isNotBlank()) {
                        onCommand?.invoke(command)
                    } else {
                        onWakeWord?.invoke()
                    }
                }
                // Перезапускаем
                scope.launch {
                    delay(300)
                    if (isRunning) listenCycle()
                }
            }

            override fun onError(error: Int) {
                unmuteBeep()
                scope.launch {
                    delay(1500)
                    if (isRunning) listenCycle()
                }
            }

            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(e: Int, p: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            // Отключаем звук начала/конца
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            scope.launch { delay(2000); if (isRunning) listenCycle() }
        }
    }
}
