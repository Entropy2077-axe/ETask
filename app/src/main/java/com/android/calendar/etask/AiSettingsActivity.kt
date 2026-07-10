package com.android.calendar.etask

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiPreferences {
    const val FILE = "ai_settings"
    const val BASE_URL = "base_url"
    const val API_KEY = "api_key"
    const val MODEL = "model"
    const val DEFAULT_BASE = "https://api.deepseek.com"
    const val DEFAULT_MODEL = "deepseek-chat"
}

class AiSettingsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var models: Spinner
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val prefs = getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE)
        val root = verticalLayout().apply { pad(20) }
        root.addView(heading("DeepSeek / OpenAI-compatible service"))
        root.addView(TextView(this).apply { text = "DeepSeek is selected by default. Your API key is stored only on this device." })
        baseUrl = field("Base URL", prefs.getString(AiPreferences.BASE_URL, AiPreferences.DEFAULT_BASE).orEmpty())
        apiKey = field("API key", prefs.getString(AiPreferences.API_KEY, "").orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        model = field("Model", prefs.getString(AiPreferences.MODEL, AiPreferences.DEFAULT_MODEL).orEmpty())
        root.addView(baseUrl); root.addView(apiKey); root.addView(model)
        models = Spinner(this)
        models.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Pull models to select"))
        models.setOnItemSelectedListener(SimpleItemSelectedListener { value ->
            if (value != "Pull models to select") model.setText(value)
        })
        root.addView(models)
        val save = Button(this).apply { text = "Save settings" }
        val pull = Button(this).apply { text = "Pull models" }
        val test = Button(this).apply { text = "Test connection" }
        status = TextView(this).apply { pad(8) }
        save.setOnClickListener { save(); status.text = "Saved" }
        pull.setOnClickListener { fetchModels(false) }
        test.setOnClickListener { fetchModels(true) }
        root.addView(save); root.addView(pull); root.addView(test); root.addView(status)
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
    }

    private fun save() {
        getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putString(AiPreferences.BASE_URL, baseUrl.text.toString().trim().trimEnd('/'))
            .putString(AiPreferences.API_KEY, apiKey.text.toString().trim())
            .putString(AiPreferences.MODEL, model.text.toString().trim())
            .apply()
    }

    private fun fetchModels(testOnly: Boolean) {
        save()
        val requestedBaseUrl = baseUrl.text.toString().trim().trimEnd('/')
        val requestedApiKey = apiKey.text.toString().trim()
        status.text = if (testOnly) "Testing…" else "Pulling models…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { requestModels(requestedBaseUrl, requestedApiKey) } }
                .onSuccess { values ->
                    if (!testOnly) {
                        models.adapter = ArrayAdapter(this@AiSettingsActivity, android.R.layout.simple_spinner_dropdown_item, values)
                    }
                    status.text = "Connected · ${values.size} model(s) available"
                }
                .onFailure { status.text = "Connection failed: ${it.message}" }
        }
    }

    private fun requestModels(requestedBaseUrl: String, requestedApiKey: String): List<String> {
        if (requestedApiKey.isBlank()) error("API key is required")
        val connection = URL(requestedBaseUrl + "/models").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $requestedApiKey")
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}: ${body.take(180)}")
        val data = JSONObject(body).optJSONArray("data") ?: error("Invalid model response")
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

private class SimpleItemSelectedListener(val onSelected: (String) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
        onSelected(parent?.getItemAtPosition(position)?.toString().orEmpty())
    }
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
