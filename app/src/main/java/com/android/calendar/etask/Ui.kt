package com.android.calendar.etask

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun View.pad(all: Int) = setPadding(context.dp(all), context.dp(all), context.dp(all), context.dp(all))

internal fun Context.verticalLayout(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

internal fun Context.heading(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 18f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(0), dp(12), dp(0), dp(6))
}
