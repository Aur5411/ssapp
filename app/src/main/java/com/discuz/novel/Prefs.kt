package com.discuz.novel

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置持久化：论坛网址 + 下载目录 + 桌面模式开关 + 自定义脚本
 */
object Prefs {
    private const val NAME = "discuz_novel_prefs"

    private const val KEY_URL = "forum_url"
    private const val KEY_AD_BLOCK = "ad_block"
    private const val KEY_DESKTOP = "desktop_mode"
    private const val KEY_DOWNLOAD_DIR = "download_dir"
    private const val KEY_CUSTOM_JS = "custom_js"
    private const val KEY_HISTORY_RETENTION = "history_retention"
    private const val KEY_DEBUG_MENU = "debug_menu"
    private const val KEY_READER_THEME = "reader_theme"
    private const val KEY_READER_FONT_SIZE = "reader_font_size"
    private const val KEY_READER_POS_PREFIX = "reader_pos_"
    private const val KEY_READER_LINE_SPACING = "reader_line_spacing"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getUrl(ctx: Context): String = sp(ctx).getString(KEY_URL, "") ?: ""

    fun setUrl(ctx: Context, url: String) = sp(ctx).edit().putString(KEY_URL, url.trim()).apply()

    /** 去广告引擎：隐藏开关，始终启用。 */
    fun isAdBlock(ctx: Context): Boolean = true
    fun setAdBlock(ctx: Context, b: Boolean) = sp(ctx).edit().putBoolean(KEY_AD_BLOCK, true).apply()

    /** 桌面模式：隐藏开关，始终使用电脑版网页。 */
    fun isDesktopMode(ctx: Context): Boolean = true
    fun setDesktopMode(ctx: Context, b: Boolean) = sp(ctx).edit().putBoolean(KEY_DESKTOP, true).apply()

    /** 下载保存目录（位于系统 Download 文件夹内的子目录名，可自定义） */
    fun getDownloadDir(ctx: Context): String =
        sp(ctx).getString(KEY_DOWNLOAD_DIR, "DiscuzNovel")?.trim()?.trim('/')
            ?.ifBlank { "DiscuzNovel" } ?: "DiscuzNovel"
    fun setDownloadDir(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_DOWNLOAD_DIR, v.trim()).apply()

    /** 用户自定义 JS 脚本（网页加载完成后注入执行） */
    fun getCustomJs(ctx: Context): String = sp(ctx).getString(KEY_CUSTOM_JS, "") ?: ""
    fun setCustomJs(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_CUSTOM_JS, v).apply()

    /** 历史记录保留时长（毫秒），0 = 永久保留 */
    fun getHistoryRetention(ctx: Context): Long = sp(ctx).getLong(KEY_HISTORY_RETENTION, 0L)
    fun setHistoryRetention(ctx: Context, v: Long) = sp(ctx).edit().putLong(KEY_HISTORY_RETENTION, v).apply()

    /** 「诊断日志」菜单是否显示（默认关闭，需在设置里开启） */
    fun isDebugMenuVisible(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DEBUG_MENU, false)
    fun setDebugMenuVisible(ctx: Context, b: Boolean) = sp(ctx).edit().putBoolean(KEY_DEBUG_MENU, b).apply()

    /** 阅读器主题索引（背景配色，默认 0 = 白色）。兼容旧版本可能保存的字符串值。 */
    fun getReaderTheme(ctx: Context): Int = readNumber(ctx, KEY_READER_THEME, 0f).toInt()
    fun setReaderTheme(ctx: Context, themeIndex: Int) = sp(ctx).edit().putInt(KEY_READER_THEME, themeIndex).apply()

    /** 阅读器字体大小（sp，默认 18）。兼容旧版本的整数/字符串值。 */
    fun getReaderFontSize(ctx: Context): Float = readNumber(ctx, KEY_READER_FONT_SIZE, 18f)
    fun setReaderFontSize(ctx: Context, size: Float) = sp(ctx).edit().putFloat(KEY_READER_FONT_SIZE, size).apply()

    /** 阅读进度（按文件名记录字符偏移，-1 表示无记录）。 */
    fun getReaderPosition(ctx: Context, name: String): Int =
        readNumber(ctx, KEY_READER_POS_PREFIX + name, -1f).toInt()
    fun setReaderPosition(ctx: Context, name: String, offset: Int) =
        sp(ctx).edit().putInt(KEY_READER_POS_PREFIX + name, offset).apply()

    /** 阅读器行间距（lineSpacingMultiplier，默认 1.4）。兼容旧版本的整数/字符串值。 */
    fun getReaderLineSpacing(ctx: Context): Float = readNumber(ctx, KEY_READER_LINE_SPACING, 1.4f)
    fun setReaderLineSpacing(ctx: Context, v: Float) = sp(ctx).edit().putFloat(KEY_READER_LINE_SPACING, v).apply()

    private fun readNumber(ctx: Context, key: String, default: Float): Float {
        return try {
            when (val value = sp(ctx).all[key]) {
                is Number -> value.toFloat()
                is String -> value.toFloatOrNull() ?: default
                else -> default
            }
        } catch (_: Throwable) {
            default
        }
    }

    /** 阅读器段间距已移除（LineHeightSpan 撑行高与行距冲突，导致行距不统一）。 */

    /** 规范化网址：补协议、补末尾斜杠 */
    fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        if (!url.endsWith("/")) url += "/"
        return url
    }
}
