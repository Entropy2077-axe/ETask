package com.android.calendar.etask

import android.graphics.Paint
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskListActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: TaskDatabase
    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "任务"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        database = TaskDatabase(applicationContext)
        adapter = TaskAdapter()

        val root = verticalLayout().apply { pad(12) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "添加任务"
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val add = Button(this).apply { text = "添加" }
        add.setOnClickListener {
            val title = input.text.toString().trim()
            if (title.isNotEmpty()) {
                input.text.clear()
                scope.launch {
                    withContext(Dispatchers.IO) { database.add(title) }
                    reload()
                }
            }
        }
        addRow.addView(input)
        addRow.addView(add)
        root.addView(addRow)
        root.addView(TextView(this).apply { text = "AI 创建的待办也会显示在这里"; textSize = 13f })
        root.addView(ListView(this).apply {
            dividerHeight = dp(1)
            adapter = this@TaskListActivity.adapter
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        reload()
    }

    private fun reload() {
        scope.launch {
            val tasks = withContext(Dispatchers.IO) { database.list() }
            adapter.submit(tasks)
        }
    }

    private inner class TaskAdapter : BaseAdapter() {
        private var tasks = emptyList<LocalTask>()

        fun submit(value: List<LocalTask>) {
            tasks = value
            notifyDataSetChanged()
        }

        override fun getCount() = tasks.size
        override fun getItem(position: Int) = tasks[position]
        override fun getItemId(position: Int) = tasks[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: TaskHolder
            val row = if (convertView == null) {
                val layout = LinearLayout(this@TaskListActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val check = CheckBox(this@TaskListActivity)
                val content = verticalLayout().apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val title = TextView(this@TaskListActivity).apply { textSize = 17f }
                val detail = TextView(this@TaskListActivity).apply { textSize = 13f }
                val delete = Button(this@TaskListActivity).apply { text = "删除" }
                content.addView(title); content.addView(detail)
                layout.addView(check); layout.addView(content); layout.addView(delete)
                holder = TaskHolder(check, title, detail, delete)
                layout.tag = holder
                layout
            } else {
                holder = convertView.tag as TaskHolder
                convertView
            }
            val task = tasks[position]
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = task.completed
            holder.title.text = task.title
            holder.title.paintFlags = if (task.completed) holder.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else holder.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.detail.text = listOfNotNull(
                task.due?.let { "截止：$it" }, task.notes.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            holder.detail.visibility = if (holder.detail.text.isBlank()) View.GONE else View.VISIBLE
            holder.check.setOnCheckedChangeListener { _, checked ->
                taskOperation { database.setCompleted(task.id, checked) }
            }
            holder.delete.setOnClickListener { taskOperation { database.delete(task.id) } }
            return row
        }
    }

    private fun taskOperation(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            reload()
        }
    }

    private data class TaskHolder(val check: CheckBox, val title: TextView, val detail: TextView, val delete: Button)

    override fun onDestroy() { scope.cancel(); database.close(); super.onDestroy() }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
