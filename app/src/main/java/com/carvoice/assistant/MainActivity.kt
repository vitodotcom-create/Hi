package com.carvoice.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged

/** Экран настроек: ключ API, модель, разрешения, подсказка по Automate. */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var checks: TextView

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshChecks() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        checks = findViewById(R.id.checks)

        val banner = findViewById<TextView>(R.id.banner)
        intent.getStringExtra(EXTRA_MESSAGE)?.let {
            banner.text = it
            banner.visibility = View.VISIBLE
        }

        val keyInput = findViewById<EditText>(R.id.api_key)
        keyInput.setText(prefs.apiKey)

        val modelSpinner = findViewById<Spinner>(R.id.model)
        modelSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Prefs.MODELS.map { it.title }
        )
        modelSpinner.setSelection(Prefs.MODELS.indexOfFirst { it.id == prefs.model }.coerceAtLeast(0))

        val langSpinner = findViewById<Spinner>(R.id.language)
        langSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Prefs.LANGUAGES.map { it.second }
        )
        langSpinner.setSelection(Prefs.LANGUAGES.indexOfFirst { it.first == prefs.language }.coerceAtLeast(0))
        langSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.language = Prefs.LANGUAGES[pos].first
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        val speak = findViewById<CheckBox>(R.id.speak)
        speak.isChecked = prefs.speak

        val homeInput = findViewById<EditText>(R.id.home)
        val workInput = findViewById<EditText>(R.id.work)
        homeInput.setText(prefs.home)
        workInput.setText(prefs.work)
        // Сохраняем адреса сразу при вводе, даже без кнопки «Сохранить».
        homeInput.doAfterTextChanged { prefs.home = it?.toString().orEmpty(); refreshChecks() }
        workInput.doAfterTextChanged { prefs.work = it?.toString().orEmpty(); refreshChecks() }

        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.home = homeInput.text.toString()
            prefs.work = workInput.text.toString()
            prefs.apiKey = keyInput.text.toString()
            prefs.model = Prefs.MODELS[modelSpinner.selectedItemPosition].id
            prefs.speak = speak.isChecked
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            refreshChecks()
        }
        findViewById<Button>(R.id.get_key).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
        }
        findViewById<Button>(R.id.grant_mic).setOnClickListener {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<Button>(R.id.grant_a11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val logView = findViewById<TextView>(R.id.log_view)
        logView.text = DebugLog.text(this).lines().takeLast(40).joinToString("\n").ifBlank { "Журнал пуст" }
        findViewById<Button>(R.id.copy_log).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("CarVoice log", DebugLog.text(this)))
            Toast.makeText(this, "Журнал скопирован — вставьте его в чат с Claude", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.clear_log).setOnClickListener {
            DebugLog.clear(this)
            logView.text = "Журнал пуст"
        }
        findViewById<Button>(R.id.test).setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }

        findViewById<TextView>(R.id.automate_help).text = """
Запуск из Automate (нажатие шайбы):
замените блоки распознавания на один блок «App start»:
  • Package: $packageName
  • Activity class: ${AssistantActivity::class.java.name}
или блок «Activity start» с действием (Action):
  ${ACTION_LISTEN}
""".trim()
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
    }

    private fun refreshChecks() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = SpeechRecognizer.isRecognitionAvailable(this)
        val a11y = VoiceAccessibilityService.isEnabled
        val key = prefs.apiKey.startsWith("sk-ant-")
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        checks.text = "Версия CarVoice: $version\n" + """
${mark(key)} API-ключ Claude
${mark(mic)} Доступ к микрофону
${mark(rec)} Сервис распознавания речи
${mark(a11y)} Спецвозможности (голосовой ввод в приложениях, запуск из фона)
${mark(prefs.home.isNotBlank())} Адрес дома
""".trim()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val ACTION_LISTEN = "com.carvoice.assistant.LISTEN"
    }
}
