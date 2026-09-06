package com.discuz.novel

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etDownloadDir: EditText
    private lateinit var etCustomJs: EditText
    private lateinit var tvRetention: TextView

    // 历史记录保留时长选项（值 = 毫秒，0 = 不自动删除）
    private data class RetentionOption(val label: String, val millis: Long)
    private val retentionOptions = listOf(
        RetentionOption("不自动删除", 0L),
        RetentionOption("1 天", 24L * 60 * 60 * 1000),
        RetentionOption("1 周", 7L * 24 * 60 * 60 * 1000),
        RetentionOption("1 个月", 30L * 24 * 60 * 60 * 1000)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 标题栏显示版本号，便于确认手机上装的是哪个版本
        val ver = try {
            " · v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "" }
        supportActionBar?.title = "设置$ver"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etUrl = findViewById(R.id.etUrl)
        etDownloadDir = findViewById(R.id.etDownloadDir)
        etCustomJs = findViewById(R.id.etCustomJs)
        tvRetention = findViewById(R.id.tvRetention)

        // 回填当前配置
        etUrl.setText(Prefs.getUrl(this))
        Prefs.setDesktopMode(this, true)
        etDownloadDir.setText(Prefs.getDownloadDir(this))
        Prefs.setAdBlock(this, true)
        etCustomJs.setText(Prefs.getCustomJs(this))
        updateRetentionLabel()

        // 诊断菜单开关已移除：诊断功能的开启/关闭仅通过「连击重置5次」弹窗操作
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener { onResetClicked() }
        findViewById<MaterialButton>(R.id.btnClearCache).setOnClickListener { confirmClearCache() }
        findViewById<View>(R.id.retentionRow).setOnClickListener { chooseRetention() }
    }

    // —— 连击「重置」按钮弹出诊断功能控制 ——
    private var resetClickCount = 0
    private var lastResetClickTs = 0L
    private val RESET_TAP_WINDOW_MS = 1500L   // 连续点击的时间窗口，超过则重新计数
    private val RESET_TAP_TARGET = 5          // 连击目标次数

    private fun onResetClicked() {
        val now = System.currentTimeMillis()
        // 超过时间窗口则重新计数（不是连续快速点击）
        if (now - lastResetClickTs > RESET_TAP_WINDOW_MS) resetClickCount = 0
        lastResetClickTs = now
        resetClickCount++

        if (resetClickCount >= RESET_TAP_TARGET) {
            resetClickCount = 0
            lastResetClickTs = 0
            showDebugControlDialog()
        } else {
            // 未达连击目标：执行正常的「重置」操作
            reset()
        }
    }

    /**
     * 连击 5 次后弹出：两个明确按钮「开启诊断功能」「关闭诊断功能」+ 取消。
     *  - 开启：开启诊断 + 立即生效；已开启时再点开启无反应。
     *  - 关闭：关闭诊断 + 立即生效；已关闭时再点关闭无反应。
     */
    private fun showDebugControlDialog() {
        val enabled = Prefs.isDebugMenuVisible(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle("诊断功能")
            .setMessage("当前状态：${if (enabled) "已开启" else "已关闭"}")
            .setPositiveButton("开启诊断功能") { _, _ ->
                if (Prefs.isDebugMenuVisible(this)) return@setPositiveButton  // 已开启：无反应
                applyDebugState(true)
            }
            .setNegativeButton("关闭诊断功能") { _, _ ->
                if (!Prefs.isDebugMenuVisible(this)) return@setNegativeButton  // 已关闭：无反应
                applyDebugState(false)
            }
            .setNeutralButton("取消", null)
            .create()
        dialog.show()
        // 已处于的状态对应按钮置灰禁用（点击无反应，且状态一目了然）
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !enabled
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = enabled
    }

    /** 应用诊断开关状态：立即生效（同步 DebugLog 全局开关，无需重启） */
    private fun applyDebugState(enabled: Boolean) {
        Prefs.setDebugMenuVisible(this, enabled)
        DebugLog.setEnabled(enabled)   // 立即同步全局诊断开关，返回主界面菜单即时更新
        Toast.makeText(
            this,
            if (enabled) "已开启诊断功能" else "已关闭诊断功能",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateRetentionLabel() {
        val cur = Prefs.getHistoryRetention(this)
        val opt = retentionOptions.firstOrNull { it.millis == cur } ?: retentionOptions[0]
        tvRetention.text = opt.label
    }

    private fun chooseRetention() {
        val cur = Prefs.getHistoryRetention(this)
        val labels = retentionOptions.map { it.label }.toTypedArray()
        val checked = retentionOptions.indexOfFirst { it.millis == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("历史记录保留时长")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val opt = retentionOptions[which]
                Prefs.setHistoryRetention(this, opt.millis)
                updateRetentionLabel()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun save() {
        Prefs.setUrl(this, etUrl.text.toString())
        Prefs.setDesktopMode(this, true)
        Prefs.setDownloadDir(this, etDownloadDir.text.toString())
        Prefs.setAdBlock(this, true)
        Prefs.setCustomJs(this, etCustomJs.text.toString())
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun reset() {
        etUrl.setText("")
        Prefs.setDesktopMode(this, true)
        etDownloadDir.setText("DiscuzNovel")
        Prefs.setAdBlock(this, true)
        etCustomJs.setText("")
        Toast.makeText(this, "已恢复默认（未保存）", Toast.LENGTH_SHORT).show()
    }

    /** 清空网页缓存、Cookie、登录状态与浏览历史（不影响已下载文件） */
    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("清除缓存与浏览数据")
            .setMessage("将清空网页缓存、Cookie、登录状态与全部浏览记录，下载好的文件不受影响。确定继续吗？")
            .setPositiveButton("清除") { _, _ -> clearWebData() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearWebData() {
        try {
            // Cookie（含登录态）
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) { /* 忽略 */ }
        try {
            // localStorage / sessionStorage
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) { /* 忽略 */ }
        try {
            // 表单数据与基本认证
            val db = WebViewDatabase.getInstance(this)
            db.clearFormData()
            db.clearHttpAuthUsernamePassword()
        } catch (e: Exception) { /* 忽略 */ }
        try {
            // WebView 磁盘缓存（HTTP 缓存等）
            cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) { /* 忽略 */ }
        // 浏览历史记录一并清空
        HistoryStore.clear(this)
        // 诊断日志数据一并清空
        DebugLog.clear()
        Toast.makeText(this, "已清除缓存与浏览数据", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
