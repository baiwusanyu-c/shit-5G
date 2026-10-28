package com.baiwusanyu.shit5g.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 磁贴事件环形日志：用于诊断"磁贴无法点击"这类只有 SystemUI 侧才能观察到的问题 */
object TileLog {

    private const val KEY = "tile_events"

    fun add(context: Context, msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val p = context.getSharedPreferences("net_mode_prefs", Context.MODE_PRIVATE)
        val old = p.getString(KEY, "") ?: ""
        var next = old + "\n[" + stamp + "] " + msg
        if (next.length > 4000) next = next.takeLast(3000)
        p.edit().putString(KEY, next).apply()
    }

    fun read(context: Context): String {
        val v = context.getSharedPreferences("net_mode_prefs", Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return if (v.isBlank()) "(暂无磁贴事件)" else v.trim()
    }
}
