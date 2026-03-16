package com.singula.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*

class WakeWordDetector(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var onWakeWord: (() -> Unit)? = null
    var onCommand: ((String) -> Unit)? = null

    private val wakeWords = listOf("сингула", "singula", "сингула", "слушай")

    fun start() {
        isRunning = true
        listenCycle()
    }

    fun stop() {
        isRunning = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        scope.cancel()
    }

    private fun listenCycle() {
        if (!isRunning) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {
                val partial = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: return
                if (wakeWords.any { partial.contains(it) }) {
                    recognizer?.stopListening()
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: ""

                val hasWakeWord = wakeWords.any { text.contains(it) }
                if (hasWakeWord) {
                    // Извлекаем команду после wake word
                    var command = text
                    for (w in wakeWords) {
                        command = command.replace(w, "").trim()
                    }
                    if (command.isNotBlank()) {
                        onCommand?.invoke(command)
                    } else {
                        onWakeWord?.invoke()
                    }
                }
                // Перезапускаем цикл
                scope.launch {
                    delay(500)
                    if (isRunning) listenCycle()
                }
            }

            override fun onError(error: Int) {
                scope.launch {
                    delay(1000)
                    if (isRunning) listenCycle()
                }
            }

            override fun onEvent(e: Int, p: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        }

        try { recognizer?.startListening(intent) } catch (e: Exception) {
            scope.launch { delay(2000); if (isRunning) listenCycle() }
        }
    }
}
