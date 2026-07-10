package com.android.calendar.etask

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiInlineConfigView(
    context: Context,
    private val onConfigured: () -> Unit,
) : LinearLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val baseUrl: EditText
    private val apiKey: EditText
    private val model: EditText
    private val status: TextView

    init {
        orientation = VERTICAL
        pad(14)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(14).toFloat()
            setColor(0xFFFFF3CD.toInt())
            setStroke(context.dp(1), 0xFFFFC107.toInt())
        }
        val prefs = context.getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE)
        addView(context.heading("当前未连接到 API，请设置您的 API"))
        addView(TextView(context).apply { text = "默认使用 DeepSeek，也支持 OpenAI 兼容接口。" })
        baseUrl = field("接口地址", prefs.getString(AiPreferences.BASE_URL, AiPreferences.DEFAULT_BASE).orEmpty())
        apiKey = field("API Key", prefs.getString(AiPreferences.API_KEY, "").orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        model = field("模型", prefs.getString(AiPreferences.MODEL, AiPreferences.DEFAULT_MODEL).orEmpty())
        addView(baseUrl); addView(apiKey); addView(model)
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
        val pull = Button(context).apply {
            text = "拉取模型"
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { pullModels() }
        }
        val connect = Button(context).apply {
            text = "保存并测试"
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { connect() }
        }
        buttons.addView(pull); buttons.addView(connect)
        status = TextView(context).apply { pad(6) }
        addView(buttons); addView(status)
        refreshVisibility()
    }

    private fun field(hint: String, value: String) = EditText(context).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
    }

    fun refreshVisibility() {
        val configured = context.getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE)
            .getString(AiPreferences.API_KEY, "").orEmpty().isNotBlank()
        visibility = if (configured) View.GONE else View.VISIBLE
    }

    private fun save() {
        context.getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putString(AiPreferences.BASE_URL, baseUrl.text.toString().trim().trimEnd('/'))
            .putString(AiPreferences.API_KEY, apiKey.text.toString().trim())
            .putString(AiPreferences.MODEL, model.text.toString().trim())
            .apply()
    }

    private fun connect() {
        status.text = "正在测试连接…"
        requestModels { models ->
            save()
            status.text = "连接成功"
            visibility = View.GONE
            onConfigured()
        }
    }

    private fun pullModels() {
        status.text = "正在拉取模型…"
        requestModels { values ->
            AlertDialog.Builder(context)
                .setTitle("选择模型")
                .setItems(values.toTypedArray()) { _, which -> model.setText(values[which]) }
                .show()
            status.text = "已拉取 ${values.size} 个模型"
        }
    }

    private fun requestModels(onSuccess: (List<String>) -> Unit) {
        val base = baseUrl.text.toString().trim().trimEnd('/')
        val key = apiKey.text.toString().trim()
        if (key.isBlank()) {
            status.text = "请填写 API Key"
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { loadModels(base, key) } }
                .onSuccess(onSuccess)
                .onFailure { status.text = "连接失败：${it.message}" }
        }
    }

    private fun loadModels(base: String, key: String): List<String> {
        val connection = URL("$base/models").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (code !in 200..299) error("HTTP $code：${body.take(160)}")
            val data = JSONObject(body).optJSONArray("data") ?: error("模型列表格式无效")
            return (0 until data.length()).mapNotNull {
                data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank)
            }.ifEmpty { error("接口未返回可用模型") }
        } finally {
            connection.disconnect()
        }
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
