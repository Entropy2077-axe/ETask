package com.android.calendar.etask

import android.graphics.Paint
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TaskListActivity : AppCompatActivity() {
    private lateinit var database: TaskDatabase
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        database = TaskDatabase(this)

        val root = verticalLayout().apply { pad(16) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "Add a task"
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val add = Button(this).apply { text = "Add" }
        add.setOnClickListener {
            if (input.text.toString().isNotBlank()) {
                database.add(input.text.toString())
                input.text.clear()
                reload()
            }
        }
        addRow.addView(input)
        addRow.addView(add)
        root.addView(addRow)
        root.addView(TextView(this).apply { text = "Tasks created by AI also appear here."; textSize = 13f })
        list = verticalLayout()
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        reload()
    }

    private fun reload() {
        list.removeAllViews()
        val tasks = database.list()
        if (tasks.isEmpty()) list.addView(TextView(this).apply { text = "No tasks yet"; pad(16) })
        tasks.forEach { task ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val check = CheckBox(this).apply { isChecked = task.completed }
            val content = verticalLayout().apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleView = TextView(this).apply {
                text = task.title
                textSize = 17f
                if (task.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            content.addView(titleView)
            val detail = listOfNotNull(task.due?.let { "Due: $it" }, task.notes.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (detail.isNotBlank()) content.addView(TextView(this).apply { text = detail; textSize = 13f })
            val delete = Button(this).apply { text = "Delete" }
            check.setOnCheckedChangeListener { _, value -> database.setCompleted(task.id, value); reload() }
            delete.setOnClickListener { database.delete(task.id); reload() }
            row.addView(check)
            row.addView(content)
            row.addView(delete)
            list.addView(row)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
