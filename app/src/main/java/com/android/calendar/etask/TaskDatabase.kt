package com.android.calendar.etask

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalTask(
    val id: Long,
    val title: String,
    val notes: String,
    val due: String?,
    val completed: Boolean,
)

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)

class TaskDatabase(context: Context) : SQLiteOpenHelper(context, "etask.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                due TEXT,
                completed INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        createAiTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createAiTables(db)
    }

    private fun createAiTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS user_profile (
                profile_key TEXT PRIMARY KEY,
                profile_value TEXT NOT NULL
            )""".trimIndent()
        )
    }

    fun add(title: String, notes: String = "", due: String? = null): Long =
        writableDatabase.insert("tasks", null, ContentValues().apply {
            put("title", title.trim())
            put("notes", notes.trim())
            put("due", due)
            put("completed", 0)
            put("created_at", System.currentTimeMillis())
        })

    fun list(): List<LocalTask> {
        val result = ArrayList<LocalTask>()
        readableDatabase.query(
            "tasks", arrayOf("id", "title", "notes", "due", "completed"),
            null, null, null, null, "completed ASC, COALESCE(due, '9999') ASC, created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LocalTask(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    if (cursor.isNull(3)) null else cursor.getString(3), cursor.getInt(4) == 1
                )
            }
        }
        return result
    }

    fun setCompleted(id: Long, completed: Boolean) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("completed", if (completed) 1 else 0)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id = ?", arrayOf(id.toString()))
    }

    fun addChat(role: String, content: String): ChatMessage {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insertOrThrow("chat_messages", null, ContentValues().apply {
            put("role", role)
            put("content", content.trim())
            put("created_at", now)
        })
        return ChatMessage(id, role, content.trim(), now)
    }

    fun recentChat(limit: Int = 20): List<ChatMessage> {
        val result = ArrayList<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT id, role, content, created_at FROM chat_messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ChatMessage(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))
            }
        }
        result.reverse()
        return result
    }

    fun clearChat() {
        writableDatabase.delete("chat_messages", null, null)
    }

    fun getHabitMemory(): String = readableDatabase.query(
        "user_profile", arrayOf("profile_value"), "profile_key = ?", arrayOf(HABIT_KEY),
        null, null, null
    ).use { if (it.moveToFirst()) it.getString(0) else "" }

    fun setHabitMemory(memory: String) {
        writableDatabase.insertWithOnConflict("user_profile", null, ContentValues().apply {
            put("profile_key", HABIT_KEY)
            put("profile_value", memory.trim())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearHabitMemory() {
        writableDatabase.delete("user_profile", "profile_key = ?", arrayOf(HABIT_KEY))
    }

    companion object {
        private const val HABIT_KEY = "habit_memory"
    }
}
