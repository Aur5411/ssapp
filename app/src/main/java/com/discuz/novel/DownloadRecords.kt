package com.discuz.novel

import android.content.Context

/**
 * 下载列表的「已移除记录」。
 *
 * 下载页的列表本身是扫描本地下载目录得到的，没有数据库，所以「从列表里移除」
 * 只能靠记住这条记录被移除过，下次扫目录时把它过滤掉 —— 全程不碰本地文件。
 *
 * 记录 key = 文件名 + 大小 + 修改时间，这样：
 *  - 移除了记录但文件还在，重新下载同名文件时系统会存成 "xxx(1).txt"，新文件照常显示；
 *  - 文件删掉后重新下载，修改时间变了，也会重新出现在列表里，不会被永久藏起来。
 */
object DownloadRecords {
    private const val NAME = "discuz_novel_download_records"
    private const val KEY_HIDDEN = "hidden"

    /** 字段分隔符，用控制字符避免和文件名冲突（文件名里不可能出现） */
    private const val FIELD = '\u0001'
    private const val STAMP = '\u0002'

    /** 记录保留 180 天，超期自动清理，避免无限增长 */
    private const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 由列表项生成记录 key */
    fun keyOf(name: String, size: Long, time: Long): String =
        name + FIELD + size + FIELD + time

    /**
     * 全部被移除的记录 key。顺手清掉超过 180 天的旧记录。
     */
    fun hidden(ctx: Context): Set<String> {
        val raw = sp(ctx).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val alive = LinkedHashSet<String>()
        var pruned = false
        for (entry in raw) {
            val idx = entry.indexOf(STAMP)
            if (idx <= 0) {
                // 老格式（只有 key）按当前时间续命，不直接丢
                alive.add(now.toString() + STAMP + entry)
                pruned = true
                continue
            }
            val at = entry.substring(0, idx).toLongOrNull() ?: 0L
            if (now - at > MAX_AGE_MS) {
                pruned = true
            } else {
                alive.add(entry)
            }
        }
        if (pruned) sp(ctx).edit().putStringSet(KEY_HIDDEN, alive).apply()
        return alive.mapNotNull { it.substringAfter(STAMP, "") }.toSet()
    }

    /** 移除记录（只影响列表显示） */
    fun hide(ctx: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis().toString()
        val merged = LinkedHashSet<String>()
        for (k in hidden(ctx)) merged.add(now + STAMP + k)
        for (k in keys) merged.add(now + STAMP + k)
        sp(ctx).edit().putStringSet(KEY_HIDDEN, merged).apply()
    }

    /** 恢复指定记录（取消隐藏） */
    fun reveal(ctx: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val keep = LinkedHashSet<String>()
        for (entry in sp(ctx).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()) {
            val k = entry.substringAfter(STAMP, "")
            if (k.isNotEmpty() && k !in keys) keep.add(entry)
        }
        sp(ctx).edit().putStringSet(KEY_HIDDEN, keep).apply()
    }

    /** 清空全部移除记录，返回清掉的条数 */
    fun clear(ctx: Context): Int {
        val n = hidden(ctx).size
        sp(ctx).edit().remove(KEY_HIDDEN).apply()
        return n
    }

    /** 当前被移除的记录条数 */
    fun count(ctx: Context): Int = hidden(ctx).size
}
