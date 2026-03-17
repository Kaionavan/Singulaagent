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
    private var restartAttempts = 0

    var onWakeWord: (() -> Unit)? = null
    var onCommand: ((String) -> Unit)? = null

    private val wakeWords = listOf("сингула", "singula", "слушай сингула", "эй сингула")

    fun start() {
        if (isRunning) return
        isRunning = true
        isPaused = false
        restartAttempts = 0
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        handler.postDelayed({ listenCycle() }, 500)
    }

    fun stop() {
        isRunning = false
        isPaused = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
    }

    // Пауза пока SINGULA говорит — иначе слышит свой голос
    fun pause() {
        isPaused = true
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
    }

    // Возобновить после того как SINGULA замолчала
    fun resume() {
        if (!isRunning) return
        isPaused = false
        handler.postDelayed({ listenCycle() }, 300)
    }

    private fun destroyRecognizer() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun muteBeep() {
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {}
    }

    private fun unmuteBeep() {
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
    }

    private fun scheduleRestart(delayMs: Long = 800) {
        if (!isRunning || isPaused) return
        handler.postDelayed({
            if (isRunning && !isPaused) listenCycle()
        }, delayMs)
    }

    private fun listenCycle() {
        if (!isRunning || isPaused) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            scheduleRestart(3000)
            return
        }

        destroyRecognizer()
        muteBeep()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { restartAttempts = 0 }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(e: Int, p: Bundle?) {}

            override fun onResults(results: Bundle?) {
                unmuteBeep()
                if (isPaused) { scheduleRestart(1000); return }

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.lowercase().trim() } ?: emptyList()

                // Ищем wake word в любом из вариантов
                val matchedText = matches.firstOrNull { result ->
                    wakeWords.any { result.contains(it) }
                } ?: ""

                if (matchedText.isNotEmpty()) {
                    var command = matchedText
                    wakeWords.sortedByDescending { it.length }.forEach {
                        command = command.replace(it, "")
                    }
                    command = command.trim().trimStart(',', '.', ' ', '-')

                    if (command.length > 2) {
                        onCommand?.invoke(command)
                    } else {
                        onWakeWord?.invoke()
                    }
                }

                scheduleRestart(500)
            }

            override fun onError(error: Int) {
                unmuteBeep()
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 600L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        restartAttempts++
                        1500L + (restartAttempts * 300L)
                    }
                    SpeechRecognizer.ERROR_AUDIO -> 2000L
                    SpeechRecognizer.ERROR_CLIENT -> 1000L
                    SpeechRecognizer.ERROR_NETWORK -> 3000L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        isRunning = false; return
                    }
                    else -> 1000L
                }
                val finalDelay = if (restartAttempts > 5) { restartAttempts = 0; 5000L } else delay
                scheduleRestart(finalDelay)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            scheduleRestart(2000)
        }
    }
}
