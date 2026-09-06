package com.discuz.novel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 浏览历史存储：用 SharedPreferences + JSON 记录浏览过的页面（url/title/time）。
 * - 同一 URL 去重（重复浏览移到最前、更新时间）；
 * - 最多保留 MAX 条，超出丢弃最旧；
 * - 供「历史记录」页展示与多选删除。
 */
object HistoryStore {

    private const val NAME = "discuz_history"
    private const val KEY = "history_list"
    private const val MAX = 200

    data class Item(val url: String, val title: String, val time: Long)

    fun load(ctx: Context): List<Item> {
        val raw = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val all = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Item(o.optString("url"), o.optString("title"), o.optLong("time"))
            }
        } catch (e: Exception) { emptyList() }
        // 按保留时长自动清理过期记录（retention=0 表示永久保留）
        val retention = Prefs.getHistoryRetention(ctx)
        if (retention <= 0) return all
        val cutoff = System.currentTimeMillis() - retention
        val filtered = all.filter { it.time >= cutoff }
        if (filtered.size != all.size) save(ctx, filtered)
        return filtered
    }

    fun add(ctx: Context, url: String, title: String) {
        if (url.isBlank()) return
        val list = load(ctx).toMutableList()
        list.removeAll { it.url == url }
        list.add(0, Item(url, title, System.currentTimeMillis()))
        if (list.size > MAX) list.subList(MAX, list.size).clear()
        save(ctx, list)
    }

    fun remove(ctx: Context, urls: Set<String>) {
        if (urls.isEmpty()) return
        save(ctx, load(ctx).filter { it.url !in urls })
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun save(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().apply {
                    put("url", it.url)
                    put("title", it.title)
                    put("time", it.time)
                }
            )
        }
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
