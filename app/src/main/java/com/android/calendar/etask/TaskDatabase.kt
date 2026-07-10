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

class TaskDatabase(context: Context) : SQLiteOpenHelper(context, "etask.db", null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(title: String, notes: String = "", due: String? = null): Long =
        writableDatabase.insert("tasks", null, ContentValues().apply {
            put("title", title.trim())
            put("notes", notes.trim())
            put("due", due)
            put("completed", 0)
            put("created_at", System.currentTimeMillis())
        })

    fun list(): List<LocalTask> {
        val result = mutableListOf<LocalTask>()
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
}
