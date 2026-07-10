package com.android.calendar.etask

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class PlannedItem(
    val type: String,
    val title: String,
    val description: String,
    val start: String?,
    val end: String?,
    val due: String?,
    val allDay: Boolean,
)

class AiAssistantActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prompt: EditText
    private lateinit var status: TextView
    private lateinit var preview: LinearLayout
    private lateinit var saveButton: Button
    private var planned = emptyList<PlannedItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = verticalLayout().apply { pad(18) }
        root.addView(TextView(this).apply {
            text = "Tell AI what you need, for example:\nTomorrow at 3 PM meet Alex for one hour, and remind me to send the proposal by Friday."
            textSize = 15f
        })
        prompt = EditText(this).apply {
            hint = "Describe tasks and events in natural language"
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        val parse = Button(this).apply { text = "Create plan with AI" }
        status = TextView(this).apply { pad(8) }
        preview = verticalLayout()
        saveButton = Button(this).apply { text = "Save all to ETask"; isEnabled = false }
        parse.setOnClickListener { generate() }
        saveButton.setOnClickListener { saveAll() }
        root.addView(prompt); root.addView(parse); root.addView(status)
        root.addView(heading("Preview")); root.addView(preview); root.addView(saveButton)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add("AI settings").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        if (item.title == "AI settings") { startActivity(Intent(this, AiSettingsActivity::class.java)); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun generate() {
        if (prompt.text.toString().isBlank()) { status.text = "Please enter a request"; return }
        status.text = "DeepSeek is planning…"
        saveButton.isEnabled = false
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { callAi(prompt.text.toString()) } }
                .onSuccess { items -> planned = items; renderPreview(); status.text = "Review the plan before saving" }
                .onFailure { status.text = "AI request failed: ${it.message}" }
        }
    }

    private fun callAi(userText: String): List<PlannedItem> {
        val prefs = getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE)
        val base = prefs.getString(AiPreferences.BASE_URL, AiPreferences.DEFAULT_BASE).orEmpty().trimEnd('/')
        val key = prefs.getString(AiPreferences.API_KEY, "").orEmpty()
        val model = prefs.getString(AiPreferences.MODEL, AiPreferences.DEFAULT_MODEL).orEmpty()
        if (key.isBlank()) error("Configure an API key in AI settings first")
        val now = ZonedDateTime.now()
        val system = """You convert natural-language plans into structured data. Current time is $now and timezone is ${ZoneId.systemDefault()}. Return ONLY valid JSON, no markdown: {"items":[{"type":"task|event","title":"string","description":"string","start":"ISO-8601 datetime or null","end":"ISO-8601 datetime or null","due":"ISO-8601 datetime or null","allDay":false}]}. Use event when a fixed time block is requested; use task for a todo/deadline. Resolve relative dates. Never invent extra items."""
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.1)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", userText)))
        }.toString()
        val connection = URL("$base/chat/completions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.outputStream.use { it.write(payload.toByteArray()) }
        val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}: ${body.take(220)}")
        var content = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        if (content.startsWith("```")) content = content.substringAfter('\n').substringBeforeLast("```").trim()
        val array = JSONObject(content).getJSONArray("items")
        val result = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PlannedItem(
                item.optString("type", "task").lowercase(),
                item.getString("title"), item.optString("description"),
                item.nullableString("start"), item.nullableString("end"), item.nullableString("due"),
                item.optBoolean("allDay", false)
            )
        }
        if (result.isEmpty()) error("AI returned an empty plan")
        return result
    }

    private fun renderPreview() {
        preview.removeAllViews()
        planned.forEach { item ->
            preview.addView(TextView(this).apply {
                val whenText = if (item.type == "event") "${item.start.orEmpty()} → ${item.end.orEmpty()}" else item.due?.let { "Due $it" }.orEmpty()
                text = "${if (item.type == "event") "EVENT" else "TASK"} · ${item.title}\n$whenText${if (item.description.isNotBlank()) "\n${item.description}" else ""}"
                textSize = 16f
                pad(12)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            })
        }
        saveButton.isEnabled = planned.isNotEmpty()
    }

    private fun saveAll() {
        val events = planned.filter { it.type == "event" }
        if (events.isNotEmpty() && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 41)
            status.text = "Calendar permission is needed; tap Save again after granting it"
            return
        }
        runCatching {
            val db = TaskDatabase(this)
            var saved = 0
            planned.forEach { item ->
                if (item.type == "event") saveEvent(item) else db.add(item.title, item.description, item.due)
                saved++
            }
            planned = emptyList()
            preview.removeAllViews()
            saveButton.isEnabled = false
            status.text = "$saved item(s) saved"
        }.onFailure { status.text = "Save failed: ${it.message}" }
    }

    private fun saveEvent(item: PlannedItem) {
        val calendarId = firstWritableCalendar() ?: error("No writable calendar is available")
        val start = parseTime(item.start ?: error("Event start is missing"))
        val end = parseTime(item.end ?: item.start ?: error("Event end is missing"))
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DESCRIPTION, item.description)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, maxOf(end, start + 30 * 60 * 1000))
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (item.allDay) 1 else 0)
        }
        contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("Calendar provider rejected the event")
    }

    private fun firstWritableCalendar(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return null
    }

    private fun parseTime(value: String): Long = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrElse { java.time.LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
