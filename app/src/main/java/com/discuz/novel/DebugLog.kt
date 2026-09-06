package com.discuz.novel

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：记录下载链路各环节事件（导航/弹窗/下载监听/书名提取/文件名解析）。
 *
 * 默认关闭（零开销，不记录任何数据）。由 MainActivity 启动时按设置里的
 * 「显示诊断菜单」开关决定是否启用；关闭状态下 log() 直接返回，不产生数据。
 */
object DebugLog {

    @Volatile private var enabled = false
    private val entries = Collections.synchronizedList(mutableListOf<String>())
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun setEnabled(b: Boolean) { enabled = b }
    fun isEnabled(): Boolean = enabled

    fun log(tag: String, msg: String) {
        if (!enabled) return
        val line = "${fmt.format(Date())} [$tag] $msg"
        synchronized(entries) {
            entries.add(line)
            while (entries.size > 400) entries.removeAt(0)
        }
    }

    fun dump(): String = synchronized(entries) { entries.joinToString("\n") }

    fun clear() = synchronized(entries) { entries.clear() }
}
