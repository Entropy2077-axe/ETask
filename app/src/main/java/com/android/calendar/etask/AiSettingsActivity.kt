package com.android.calendar.etask

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
    private lateinit var database: TaskDatabase
    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var models: Spinner
    private lateinit var status: TextView
    private lateinit var memory: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AI 设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        database = TaskDatabase(applicationContext)
        val prefs = getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE)
        val root = verticalLayout().apply { pad(20) }
        root.addView(heading("DeepSeek / OpenAI 兼容服务"))
        root.addView(TextView(this).apply { text = "默认使用 DeepSeek。API Key 只保存在当前设备。" })
        baseUrl = field("接口地址", prefs.getString(AiPreferences.BASE_URL, AiPreferences.DEFAULT_BASE).orEmpty())
        apiKey = field("API Key", prefs.getString(AiPreferences.API_KEY, "").orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        model = field("模型", prefs.getString(AiPreferences.MODEL, AiPreferences.DEFAULT_MODEL).orEmpty())
        root.addView(baseUrl); root.addView(apiKey); root.addView(model)
        models = Spinner(this)
        val placeholder = "拉取模型后可选择"
        models.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(placeholder))
        models.setOnItemSelectedListener(SimpleItemSelectedListener { value -> if (value != placeholder) model.setText(value) })
        root.addView(models)
        val save = Button(this).apply { text = "保存设置" }
        val pull = Button(this).apply { text = "拉取模型" }
        val test = Button(this).apply { text = "测试连接" }
        status = TextView(this).apply { pad(8) }
        save.setOnClickListener { save(); status.text = "设置已保存" }
        pull.setOnClickListener { fetchModels(false) }
        test.setOnClickListener { fetchModels(true) }
        root.addView(save); root.addView(pull); root.addView(test); root.addView(status)

        root.addView(heading("会话管理"))
        val clearConversation = Button(this).apply {
            text = "清除会话"
            setOnClickListener { confirmClearConversation() }
        }
        root.addView(clearConversation)

        root.addView(heading("用户习惯记忆"))
        root.addView(TextView(this).apply { text = "AI 只保存稳定、可复用的时间和任务偏好。清空对话不会删除习惯。" })
        memory = TextView(this).apply { pad(10) }
        val clearMemory = Button(this).apply {
            text = "清除习惯记忆"
            setOnClickListener { confirmClearMemory() }
        }
        root.addView(memory); root.addView(clearMemory)
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        loadMemory()
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(true)
    }

    private fun save() {
        getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putString(AiPreferences.BASE_URL, baseUrl.text.toString().trim().trimEnd('/'))
            .putString(AiPreferences.API_KEY, apiKey.text.toString().trim())
            .putString(AiPreferences.MODEL, model.text.toString().trim()).apply()
    }

    private fun fetchModels(testOnly: Boolean) {
        save()
        val requestedBase = baseUrl.text.toString().trim().trimEnd('/')
        val requestedKey = apiKey.text.toString().trim()
        status.text = if (testOnly) "正在测试连接…" else "正在拉取模型…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { requestModels(requestedBase, requestedKey) } }
                .onSuccess { values ->
                    if (!testOnly) models.adapter = ArrayAdapter(this@AiSettingsActivity, android.R.layout.simple_spinner_dropdown_item, values)
                    status.text = "连接成功，可用模型 ${values.size} 个"
                }.onFailure { status.text = "连接失败：${it.message}" }
        }
    }

    private fun requestModels(base: String, key: String): List<String> {
        if (key.isBlank()) error("必须填写 API Key")
        val connection = URL("$base/models").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) error("HTTP $code：${body.take(180)}")
            val data = JSONObject(body).optJSONArray("data") ?: error("模型列表格式无效")
            return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        } finally {
            connection.disconnect()
        }
    }

    private fun loadMemory() {
        scope.launch {
            val value = withContext(Dispatchers.IO) { database.getHabitMemory() }
            memory.text = value.ifBlank { "尚未形成习惯记忆" }
        }
    }

    private fun confirmClearMemory() {
        AlertDialog.Builder(this).setTitle("清除习惯记忆")
            .setMessage("AI 将不再使用之前学习到的时间和任务偏好。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { database.clearHabitMemory() }
                    loadMemory()
                    status.text = "习惯记忆已清除"
                }
            }.show()
    }

    private fun confirmClearConversation() {
        AlertDialog.Builder(this).setTitle("清除会话")
            .setMessage("将删除 AI 助手中的全部聊天记录，但不会删除习惯记忆。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { database.clearChat() }
                    status.text = "会话已清除"
                }
            }.show()
    }

    override fun onDestroy() { scope.cancel(); database.close(); super.onDestroy() }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

private class SimpleItemSelectedListener(val onSelected: (String) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected(parent?.getItemAtPosition(position)?.toString().orEmpty())
    }
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
