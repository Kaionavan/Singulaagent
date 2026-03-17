package com.singula.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceEngine(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var onResult: ((String) -> Unit)? = null
    var onListening: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init { initTTS() }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(0.9f)
                ttsReady = true
            }
        }
    }

    fun speak(text: String) {
        if (ttsReady) {
            tts?.stop()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sng_${System.currentTimeMillis()}")
        }
    }

    fun startListening() {
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Распознавание речи недоступно")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { onListening?.invoke() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onResult?.invoke(text)
                else onError?.invoke("Ничего не услышал")
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Не понял, повторите"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Слишком тихо"
                    SpeechRecognizer.ERROR_AUDIO -> "Ошибка микрофона"
                    SpeechRecognizer.ERROR_NETWORK -> "Нет сети"
                    else -> "Ошибка распознавания"
                }
                onError?.invoke(msg)
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(e: Int, p: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    fun destroy() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
    }
}
