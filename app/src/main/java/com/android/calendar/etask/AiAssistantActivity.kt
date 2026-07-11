package com.android.calendar.etask

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import ws.xsoh.etar.R

data class PlannedItem(
    val action: String,
    val type: String,
    val title: String,
    val description: String,
    val start: String?,
    val end: String?,
    val due: String?,
    val allDay: Boolean,
    val location: String,
    val isRecurring: Boolean,
    val recurrenceRule: String?,
    val calendarId: Long?,
    val calendarName: String,
    val eventId: Long?,
)

private data class DeviceCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val writable: Boolean,
)

private data class DeviceEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val calendarId: Long,
    val calendarName: String,
)

private data class AiTurn(val reply: String, val items: List<PlannedItem>, val memory: String)

class AiAssistantActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: TaskDatabase
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var status: TextView
    private lateinit var memoryStatus: TextView
    private lateinit var preview: TextView
    private lateinit var saveButton: Button
    private lateinit var chatList: ListView
    private lateinit var adapter: ChatAdapter
    private lateinit var apiSetupCard: AiInlineConfigView
    private var planned = emptyList<PlannedItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AI 任务助手"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        database = TaskDatabase(applicationContext)
        adapter = ChatAdapter()

        val root = verticalLayout().apply { pad(10) }
        memoryStatus = TextView(this).apply { textSize = 12f; text = "正在读取习惯记忆…" }
        apiSetupCard = AiInlineConfigView(this) {
            sendButton.isEnabled = true
            status.text = "API 已连接，可以开始对话"
        }
        chatList = ListView(this).apply {
            divider = null
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = true
            adapter = this@AiAssistantActivity.adapter
        }
        preview = TextView(this).apply {
            textSize = 14f
            visibility = View.GONE
            pad(8)
        }
        saveButton = Button(this).apply {
            text = "保存当前方案"
            isEnabled = false
            visibility = View.GONE
            setOnClickListener { saveAll() }
        }
        status = TextView(this).apply { textSize = 12f }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        input = EditText(this).apply {
            hint = "继续对话，例如：改到周五下午三点"
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        sendButton = Button(this).apply {
            text = "发送"
            isEnabled = isApiConfigured()
            setOnClickListener { sendMessage() }
        }
        inputRow.addView(input); inputRow.addView(sendButton)
        root.addView(memoryStatus)
        root.addView(apiSetupCard)
        root.addView(chatList, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(preview); root.addView(saveButton); root.addView(status); root.addView(inputRow)
        setContentView(root)
        loadConversation()
    }

    private fun loadConversation() {
        scope.launch {
            val state = withContext(Dispatchers.IO) {
                var messages = database.recentChat(50)
                if (messages.isEmpty()) {
                    database.addChat("assistant", "你好，我是你的 AI 任务助手。告诉我想做什么，我会通过对话补全时间、截止日期和安排，并记住你稳定的使用习惯。")
                    messages = database.recentChat(50)
                }
                messages to database.getHabitMemory()
            }
            adapter.submit(state.first)
            updateMemoryStatus(state.second)
            scrollToBottom()
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || !sendButton.isEnabled) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 42)
            status.text = "需要日历权限才能读取分类，请授权后再次发送"
            return
        }
        input.text.clear()
        val optimistic = ChatMessage(-System.nanoTime(), "user", text, System.currentTimeMillis())
        adapter.add(optimistic)
        scrollToBottom()
        setSending(true)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    database.addChat("user", text)
                    val memory = database.getHabitMemory()
                    val calendars = loadCalendars()
                    val turn = callAi(database.recentChat(16), memory, calendars, loadEvents(calendars))
                    val visibleReply = buildConversationReply(turn)
                    val savedReply = database.addChat("assistant", visibleReply)
                    if (turn.memory.isNotBlank() && turn.memory != memory) {
                        database.setHabitMemory(turn.memory.take(1500))
                    }
                    Triple(turn, savedReply, database.getHabitMemory())
                }
            }.onSuccess { (turn, reply, memory) ->
                adapter.add(reply)
                planned = turn.items
                renderPlan()
                updateMemoryStatus(memory)
                status.text = if (planned.isEmpty()) "请继续回答 AI 的问题" else "方案已生成，请确认后保存或继续修改"
                scrollToBottom()
            }.onFailure {
                adapter.add(ChatMessage(-System.nanoTime(), "assistant", "请求失败：${it.message}", System.currentTimeMillis()))
                status.text = "连接失败，请检查 AI 设置"
                scrollToBottom()
            }
            setSending(false)
        }
    }

    private fun callAi(
        history: List<ChatMessage>,
        memory: String,
        calendars: List<DeviceCalendar>,
        events: List<DeviceEvent>,
    ): AiTurn {
        val prefs = getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE)
        val base = prefs.getString(AiPreferences.BASE_URL, AiPreferences.DEFAULT_BASE).orEmpty().trimEnd('/')
        val key = prefs.getString(AiPreferences.API_KEY, "").orEmpty()
        val model = prefs.getString(AiPreferences.MODEL, AiPreferences.DEFAULT_MODEL).orEmpty()
        if (key.isBlank()) error("请先在 AI 设置中填写 API Key")
        val now = ZonedDateTime.now()
        val calendarCatalog = calendars.joinToString("\n") {
            "- id=${it.id}；名称=${it.name}；账户=${it.account}；${if (it.writable) "可写" else "只读"}"
        }.ifBlank { "- 当前设备没有日历" }
        val eventCatalog = events.joinToString("\n") {
            "- eventId=${it.id}; title=${it.title}; start=${formatEventTime(it.start)}; end=${formatEventTime(it.end)}; calendar=${it.calendarName}"
        }.ifBlank { "- No upcoming events are available for deletion." }
        val system = """For a request to delete a calendar event, identify one unambiguous event from the deletion catalog below. Return an item with action=\"delete_event\", type=\"event\", eventId set to that catalog ID, and its title/calendarName. Never invent an eventId. If more than one event could match, ask one concise clarification question and return no items. Deletion is only performed after the user taps the confirmation button. Always reply in Simplified Chinese.
Deletion catalog (recent and upcoming events):
$eventCatalog
For normal creation, action must be \"create\" (or omitted). The JSON shape of every item additionally accepts \"action\" and \"eventId\".

你是中文任务规划助手，通过多轮对话帮助用户创建待办和日程。
当前时间：$now；时区：${ZoneId.systemDefault()}。
已记住的用户习惯：${memory.ifBlank { "暂无" }}
设备上的全部日历：
$calendarCatalog

规则：
1. 始终用简体中文回复。
2. 如果日期、时间或意图存在关键歧义，先提出一个简短明确的问题，items 返回空数组；不要擅自猜测。
3. 信息足够时给出可保存方案。固定时间段用 event，待办或截止日期用 task。
   task 也会显示在周视图中，因此必须提供 due；缺少截止时间时先追问。
4. 相对日期必须转换为带时区的 ISO-8601 时间。日程未说明时长时优先使用已记住习惯，否则默认 1 小时。
5. memory 只记录稳定、可复用的偏好，例如常用开始时间、默认时长、工作日、提醒或任务分类习惯。不要记录一次性事件、隐私内容或推测。没有新习惯时原样返回已有记忆。
6. reply 必须概括当前理解；有 items 时清楚告诉用户可以保存或继续修改。
7. 从对话中提取地点到 location。没有地点时返回空字符串。
8. 识别“每天、每周、每月、工作日、每周几”等周期活动。周期活动设置 isRecurring=true，并生成合法 RFC5545 RRULE，例如 FREQ=WEEKLY;BYDAY=MO,WE。非周期活动 recurrenceRule 返回 null。
9. 根据内容从日历名称推断分类，例如数学内容优先选择“数学类”。calendarId 必须来自上面的设备日历且必须可写；calendarName 必须与选择的日历名称一致。无法判断时选择最通用的可写日历，不要编造 ID。
10. title 必须是精简的动作或活动名称，建议不超过 12 个汉字，不包含日期、时间、地点等已在其他字段表达的信息。

只返回合法 JSON，不要 Markdown：
{"reply":"中文回复","memory":"精炼的习惯摘要","items":[{"type":"task|event","title":"精简标题","description":"说明","start":"ISO-8601 或 null","end":"ISO-8601 或 null","due":"ISO-8601 或 null","allDay":false,"location":"地点或空字符串","isRecurring":false,"recurrenceRule":"RFC5545 RRULE 或 null","calendarId":设备日历ID,"calendarName":"日历名称"}]}"""
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { message ->
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", messages)
        }.toString()
        val connection = URL("$base/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (code !in 200..299) error("HTTP $code：${body.take(220)}")
            var content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            if (content.startsWith("```")) content = content.substringAfter('\n').substringBeforeLast("```").trim()
            val json = JSONObject(content)
            val array = json.optJSONArray("items") ?: JSONArray()
            val items = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val recurrence = item.nullableString("recurrenceRule")?.sanitizeRRule()
                PlannedItem(
                    item.optString("action", "create").lowercase(),
                    item.optString("type", "task").lowercase(), item.getString("title").trim().take(16),
                    item.optString("description"), item.nullableString("start"),
                    item.nullableString("end"), item.nullableString("due"), item.optBoolean("allDay", false),
                    item.optString("location"), item.optBoolean("isRecurring", false) || recurrence != null,
                    recurrence,
                    item.nullableLong("calendarId"), item.optString("calendarName"), item.nullableLong("eventId")
                )
            }
            return AiTurn(json.optString("reply", "请继续说明你的安排。"), items, json.optString("memory", memory))
        } finally {
            connection.disconnect()
        }
    }

    private fun buildConversationReply(turn: AiTurn): String {
        if (turn.items.isEmpty()) return turn.reply
        return turn.reply + "\n" + turn.items.joinToString("\n") { item ->
            val extras = listOfNotNull(
                item.calendarName.takeIf { it.isNotBlank() }?.let { "分类：$it" },
                item.location.takeIf { it.isNotBlank() }?.let { "地点：$it" },
                item.recurrenceRule?.let { "周期：$it" }
            ).joinToString("，")
            val suffix = if (extras.isBlank()) "" else "；$extras"
            if (item.action == "delete_event") "• 删除日程：${item.title}（${item.calendarName}）"
            else if (item.type == "event") "• 日程：${item.title}（${item.start.orEmpty()} 至 ${item.end.orEmpty()}$suffix）"
            else "• 任务：${item.title}${item.due?.let { "（截止 $it$suffix）" } ?: if (suffix.isBlank()) "" else "（${suffix.removePrefix("；")}）"}"
        }
    }

    private fun renderPlan() {
        if (planned.isEmpty()) {
            preview.visibility = View.GONE
            saveButton.visibility = View.GONE
            saveButton.isEnabled = false
            return
        }
        val deletionCount = planned.count { it.action == "delete_event" }
        preview.text = "待执行：${planned.count { it.type != "event" && it.action != "delete_event" }} 个任务，${planned.count { it.type == "event" && it.action != "delete_event" }} 个日程，删除 $deletionCount 个日程"
        preview.visibility = View.VISIBLE
        saveButton.visibility = View.VISIBLE
        saveButton.isEnabled = true
    }

    private fun saveAll() {
        if (planned.any { it.type == "event" } && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 41)
            status.text = "请授予日历权限，然后再次点击保存"
            return
        }
        val saving = planned
        saveButton.isEnabled = false
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    saving.forEach(::applyPlanItem)
                    database.addChat("assistant", "已执行 ${saving.size} 项安排。你可以继续告诉我下一项计划。")
                }
            }.onSuccess {
                planned = emptyList()
                renderPlan()
                adapter.add(ChatMessage(-System.nanoTime(), "assistant", "已执行 ${saving.size} 项安排。你可以继续告诉我下一项计划。", System.currentTimeMillis()))
                status.text = "执行成功"
                scrollToBottom()
            }.onFailure {
                status.text = "保存失败：${it.message}"
                saveButton.isEnabled = true
            }
        }
    }

    private fun applyPlanItem(item: PlannedItem) {
        if (item.action == "delete_event") {
            deleteEvent(item)
        } else {
            saveEvent(item)
        }
    }

    private fun deleteEvent(item: PlannedItem) {
        val eventId = item.eventId ?: error("删除日程缺少日程 ID")
        val event = loadEvents(loadCalendars()).firstOrNull { it.id == eventId }
            ?: error("要删除的日程已不存在或不在可删除范围内")
        if (contentResolver.delete(CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(event.id.toString()).build(), null, null) != 1) {
            error("系统日历拒绝删除日程")
        }
    }

    private fun saveEvent(item: PlannedItem) {
        val calendars = loadCalendars()
        val calendarId = calendars.firstOrNull { it.id == item.calendarId && it.writable }?.id
            ?: calendars.firstOrNull { it.writable && it.name == item.calendarName }?.id
            ?: calendars.firstOrNull { it.writable }?.id
            ?: error("没有可写入的日历")
        val start = parseTime(item.start ?: item.due ?: error("安排缺少时间"))
        val end = item.end?.let(::parseTime) ?: start + 30 * 60 * 1000L
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DESCRIPTION, item.description)
            put(CalendarContract.Events.EVENT_LOCATION, item.location)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (item.allDay) 1 else 0)
            val recurrence = item.recurrenceRule?.takeIf { item.isRecurring }
            if (recurrence != null) {
                val durationMillis = maxOf(end - start, 30 * 60 * 1000L)
                put(CalendarContract.Events.DURATION, if (item.allDay) "P1D" else "P${durationMillis / 1000}S")
                put(CalendarContract.Events.RRULE, recurrence)
            } else {
                put(CalendarContract.Events.DTEND, maxOf(end, start + 30 * 60 * 1000))
            }
        }
        contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("系统日历拒绝写入")
    }

    private fun loadCalendars(): List<DeviceCalendar> {
        val result = ArrayList<DeviceCalendar>()
        contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            ), CalendarContract.Calendars.ACCOUNT_TYPE + " = ?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL),
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += DeviceCalendar(
                    cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                    cursor.getInt(3), cursor.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                )
            }
        }
        return result
    }

    private fun loadEvents(calendars: List<DeviceCalendar>): List<DeviceEvent> {
        if (calendars.isEmpty()) return emptyList()
        val calendarNames = calendars.associate { it.id to it.name }
        val ids = calendars.joinToString(",") { it.id.toString() }
        val now = System.currentTimeMillis()
        val from = now - 30L * 24 * 60 * 60 * 1000
        val until = now + 180L * 24 * 60 * 60 * 1000
        val result = ArrayList<DeviceEvent>()
        contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.CALENDAR_ID,
            ),
            "${CalendarContract.Events.CALENDAR_ID} IN ($ids) AND ${CalendarContract.Events.DELETED} = 0 AND (${CalendarContract.Events.DTSTART} BETWEEN ? AND ? OR ${CalendarContract.Events.RRULE} IS NOT NULL)",
            arrayOf(from.toString(), until.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < 200) {
                val calendarId = cursor.getLong(4)
                result += DeviceEvent(
                    cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getLong(2),
                    if (cursor.isNull(3)) cursor.getLong(2) else cursor.getLong(3), calendarId,
                    calendarNames[calendarId].orEmpty()
                )
            }
        }
        return result
    }

    private fun formatEventTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

    private fun parseTime(value: String): Long = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrElse { java.time.LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    private fun setSending(sending: Boolean) {
        sendButton.isEnabled = !sending
        input.isEnabled = !sending
        status.text = if (sending) "DeepSeek 正在思考…" else status.text
    }

    private fun updateMemoryStatus(memory: String) {
        memoryStatus.text = if (memory.isBlank()) "习惯记忆：尚未形成" else "习惯记忆：$memory"
    }

    private fun scrollToBottom() = chatList.post { if (adapter.count > 0) chatList.setSelection(adapter.count - 1) }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, "AI 设置")
            .setIcon(R.drawable.outline_settings)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            MENU_SETTINGS -> startActivity(Intent(this, AiSettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun clearConversation() {
        scope.launch {
            withContext(Dispatchers.IO) { database.clearChat() }
            planned = emptyList()
            renderPlan()
            adapter.submit(emptyList())
            loadConversation()
            status.text = "对话已清空，习惯记忆仍然保留"
        }
    }

    private fun isApiConfigured(): Boolean = getSharedPreferences(AiPreferences.FILE, Context.MODE_PRIVATE)
        .getString(AiPreferences.API_KEY, "").orEmpty().isNotBlank()

    override fun onResume() {
        super.onResume()
        if (::apiSetupCard.isInitialized) {
            apiSetupCard.refreshVisibility()
            sendButton.isEnabled = isApiConfigured()
        }
    }

    private inner class ChatAdapter : BaseAdapter() {
        private val messages = ArrayList<ChatMessage>()
        fun submit(value: List<ChatMessage>) { messages.clear(); messages.addAll(value); notifyDataSetChanged() }
        fun add(value: ChatMessage) { messages.add(value); notifyDataSetChanged() }
        override fun getCount() = messages.size
        override fun getItem(position: Int) = messages[position]
        override fun getItemId(position: Int) = messages[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@AiAssistantActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
                addView(TextView(this@AiAssistantActivity).apply {
                    maxWidth = resources.displayMetrics.widthPixels * 4 / 5
                    textSize = 16f
                    pad(12)
                })
            }
            val message = messages[position]
            row.gravity = if (message.role == "user") Gravity.END else Gravity.START
            val bubble = row.getChildAt(0) as TextView
            bubble.text = message.content
            bubble.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (message.role == "user") 0xFFDCEBFF.toInt() else 0xFFF1F1F1.toInt())
            }
            return row
        }
    }

    override fun onDestroy() { scope.cancel(); database.close(); super.onDestroy() }

    companion object {
        private const val MENU_SETTINGS = 1001
    }
}

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.nullableLong(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key).takeIf { it > 0 }

private fun String.sanitizeRRule(): String? {
    val value = trim().removePrefix("RRULE:").uppercase().replace("\n", "").replace("\r", "")
    return value.takeIf { it.startsWith("FREQ=") && it.length <= 240 }
}
