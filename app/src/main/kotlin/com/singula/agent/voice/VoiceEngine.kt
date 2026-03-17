package com.singula.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceEngine(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var onResult: ((String) -> Unit)? = null
    var onListening: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null

    init { initTTS() }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Ставим русский язык
                val ruResult = tts?.setLanguage(Locale("ru", "RU"))
                if (ruResult == TextToSpeech.LANG_MISSING_DATA ||
                    ruResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Если русского нет — ставим английский
                    tts?.setLanguage(Locale.ENGLISH)
                }
                tts?.setSpeechRate(0.88f)  // чуть медленнее — понятнее
                tts?.setPitch(0.85f)        // чуть ниже — мужественнее
                ttsReady = true

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { onSpeakDone?.invoke() }
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        tts?.stop()
        // Очищаем текст от спецсимволов перед озвучкой
        val clean = text
            .replace(Regex("\\[ACTION:[^\\]]*\\]"), "")
            .replace("▸", "")
            .replace("●", "")
            .trim()
        if (clean.isEmpty()) return
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "sng_${System.currentTimeMillis()}")
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
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) onResult?.invoke(text)
                else onError?.invoke("Ничего не услышал")
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Не понял, повторите"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Слишком тихо"
                    SpeechRecognizer.ERROR_AUDIO -> "Ошибка микрофона"
                    SpeechRecognizer.ERROR_NETWORK -> "Нет сети для распознавания"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
                    else -> "Ошибка $error"
                }
                onError?.invoke(msg)
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(e: Int, p: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        try { recognizer?.startListening(intent) } catch (e: Exception) {
            onError?.invoke("Ошибка запуска микрофона")
        }
    }

    fun stopListening() {
        try { recognizer?.stopListening() } catch (e: Exception) {}
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
    }

    fun destroy() {
        stopListening()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
    }
}
