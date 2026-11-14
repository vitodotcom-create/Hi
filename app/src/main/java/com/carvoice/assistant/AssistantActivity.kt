package com.carvoice.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Экран «Слушаю…»: распознаёт речь, спрашивает Claude, озвучивает ответ и выполняет действия.
 * Запускается кнопкой/ярлыком «Голос», из Automate или как голосовой ассистент системы.
 */
class AssistantActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var answerView: TextView
    private lateinit var micView: View

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var job: Job? = null

    private val fallbackRecognizer = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text.isNullOrBlank()) finishWithMessage("Не расслышал") else handleText(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)
        prefs = Prefs(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        statusView = findViewById(R.id.status)
        heardView = findViewById(R.id.heard)
        answerView = findViewById(R.id.answer)
        micView = findViewById(R.id.mic)
        findViewById<View>(R.id.root).setOnClickListener { cancelAndFinish() }

        if (prefs.apiKey.isBlank()) {
            openSettings("Укажите API-ключ Claude в настройках")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openSettings("Разрешите доступ к микрофону")
            return
        }
        initTts()
        startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Повторное нажатие кнопки — начинаем слушать заново.
        job?.cancel()
        tts?.stop()
        recognizer?.cancel()
        heardView.text = ""
        answerView.text = ""
        startListening()
    }

    // ---------- Распознавание речи ----------

    private fun startListening() {
        requestAudioFocus()
        statusView.text = "Слушаю…"
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Нет встроенного сервиса — используем системное окно распознавания.
            val intent = recognizerIntent()
            try {
                fallbackRecognizer.launch(intent)
            } catch (e: Exception) {
                finishWithMessage("На устройстве нет распознавания речи. Установите приложение Google.")
            }
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        rec.startListening(recognizerIntent())
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Слушаю…")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            statusView.text = "Слушаю…"
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 20f)
            micView.scaleX = scale
            micView.scaleY = scale
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            micView.scaleX = 1f
            micView.scaleY = 1f
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Recognizer error $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    finishWithMessage("Не расслышал")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    finishWithMessage("Нет интернета для распознавания речи")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    openSettings("Разрешите доступ к микрофону")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.destroy()
                    recognizer = null
                    micView.postDelayed({ startListening() }, 500)
                }
                else -> finishWithMessage("Ошибка распознавания ($error)")
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) finishWithMessage("Не расслышал") else handleText(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { heardView.text = "«$it»" }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- Claude + действия ----------

    private fun handleText(text: String) {
        heardView.text = "«$text»"
        statusView.text = "Думаю…"
        micView.alpha = 0.4f
        job = lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.Default) { AppCatalog.launchableApps(this@AssistantActivity) }
                val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
                val reply = withContext(Dispatchers.IO) {
                    ClaudeBrain(prefs.apiKey, prefs.model).ask(text, apps, now)
                }
                statusView.text = "Готово"
                answerView.text = reply.say
                speak(reply.say)
                abandonAudioFocus()
                val error = ActionExecutor(this@AssistantActivity).executeAll(reply.actions)
                if (error != null) {
                    answerView.text = error
                    delay(1500)
                }
                delay(300)
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Assistant failed", e)
                val msg = when (e) {
                    is UnauthorizedException -> "Неверный API-ключ. Проверьте настройки."
                    is RateLimitException -> "Слишком много запросов, попробуйте позже."
                    is AnthropicIoException -> "Нет связи с сервером. Проверьте интернет."
                    else -> "Ошибка: ${e.message?.take(120) ?: e.javaClass.simpleName}"
                }
                finishWithMessage(msg)
            }
        }
    }

    // ---------- Озвучка ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val res = engine.setLanguage(Locale("ru", "RU"))
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                    }
                })
                ttsReady.complete(res >= TextToSpeech.LANG_AVAILABLE)
            } else {
                ttsReady.complete(false)
            }
        }
    }

    private suspend fun speak(text: String) {
        if (!prefs.speak || text.isBlank()) return
        val ready = withTimeoutOrNull(3000) { ttsReady.await() } ?: false
        val engine = tts ?: return
        if (!ready) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pendingUtterances[id] = done
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        withTimeoutOrNull(15000) { done.await() }
    }

    // ---------- Аудиофокус: на время команды музыка ставится на паузу ----------

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audio.requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ---------- Завершение ----------

    private fun finishWithMessage(msg: String) {
        statusView.text = msg
        micView.alpha = 0.4f
        lifecycleScope.launch {
            speak(msg)
            abandonAudioFocus()
            delay(1200)
            finish()
        }
    }

    private fun openSettings(msg: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MESSAGE, msg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun cancelAndFinish() {
        job?.cancel()
        recognizer?.cancel()
        tts?.stop()
        abandonAudioFocus()
        finish()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        abandonAudioFocus()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CarVoice"
    }
}
