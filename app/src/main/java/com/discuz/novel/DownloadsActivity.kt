package com.discuz.novel

import android.content.ContentUris
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载文件管理页：列出 Download/自定义目录 中的所有文件
 * - 点击：调用系统应用打开
 * - 长按：确认后删除
 */
class DownloadsActivity : AppCompatActivity() {

    private data class Item(
        val name: String,
        val size: Long,
        val time: Long,
        val uri: Uri?,      // MediaStore 内容 Uri（API 29+）
        val file: File?     // 传统文件（API 28-）
    )

    private val items = mutableListOf<Item>()
    private val filteredItems = mutableListOf<Item>()   // 搜索过滤后的显示列表
    private var query = ""
    private lateinit var searchInput: EditText
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var selectionBar: View
    private lateinit var checkAll: CheckBox
    private lateinit var selectedCount: TextView
    private lateinit var deleteSelected: View
    private var selectionMode = false
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        supportActionBar?.title = "下载文件"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        searchInput = findViewById(R.id.searchInput)
        listView = findViewById(R.id.listView)
        emptyView = findViewById(R.id.emptyView)
        selectionBar = findViewById(R.id.selectionBar)
        checkAll = findViewById(R.id.checkAll)
        selectedCount = findViewById(R.id.selectedCount)
        deleteSelected = findViewById(R.id.btnDeleteSelected)

        // 打开本地下载目录
        findViewById<View>(R.id.btnOpenDir).setOnClickListener { openDownloadDir() }

        // 搜索框：输入即时过滤已下载文件
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
                renderList()
            }
        })

        checkAll.setOnCheckedChangeListener { _, checked ->
            if (filteredItems.isNotEmpty()) {
                if (checked) selected.addAll(filteredItems.map { keyOf(it) }) else selected.clear()
                renderList()
            }
        }
        deleteSelected.setOnClickListener { confirmDeleteSelected() }
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = filteredItems[position]
            if (selectionMode) {
                toggleSelected(item)
            } else {
                openFile(item)
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (!selectionMode) {
                selectionMode = true
                selectionBar.visibility = View.VISIBLE
            }
            toggleSelected(filteredItems[position])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    /** 按当前搜索关键字过滤文件列表 */
    private fun applyFilter() {
        filteredItems.clear()
        if (query.isBlank()) {
            filteredItems.addAll(items)
        } else {
            filteredItems.addAll(items.filter { it.name.contains(query, ignoreCase = true) })
        }
    }

    private fun loadFiles() {
        items.clear()
        val dirName = Prefs.getDownloadDir(this)

        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
            )
            val selection = MediaStore.Downloads.RELATIVE_PATH + "=?"
            val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + dirName + "/")
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                MediaStore.Downloads.DATE_MODIFIED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    items.add(
                        Item(
                            name = c.getString(1) ?: "unknown",
                            size = c.getLong(2),
                            time = c.getLong(3) * 1000,
                            uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                            file = null
                        )
                    )
                }
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                dirName
            )
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                items.add(Item(f.name, f.length(), f.lastModified(), null, f))
            }
        }

        selected.retainAll(items.map { keyOf(it) })
        applyFilter()
        renderList()
    }

    private fun keyOf(item: Item): String = item.uri?.toString() ?: item.file?.absolutePath ?: item.name

    private fun renderList() {
        emptyView.visibility = if (filteredItems.isEmpty()) View.VISIBLE else View.GONE
        selectedCount.text = "已选 ${selected.size} 项"
        deleteSelected.isEnabled = selected.isNotEmpty()
        val adapter = object : ArrayAdapter<Item>(this, 0, filteredItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_download, parent, false)
                val item = getItem(position)!!
                row.findViewById<CheckBox>(R.id.itemCheck).visibility = if (selectionMode) View.VISIBLE else View.GONE
                row.setBackgroundColor(if (selected.contains(keyOf(item))) 0xFFE3F2FD.toInt() else Color.WHITE)
                row.findViewById<CheckBox>(R.id.itemCheck).apply {
                    setOnCheckedChangeListener(null)
                    isChecked = selected.contains(keyOf(item))
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(keyOf(item)) else selected.remove(keyOf(item))
                        updateSelectionUi()
                    }
                }
                row.findViewById<TextView>(R.id.itemName).text = item.name
                row.findViewById<TextView>(R.id.itemInfo).text = formatSize(item.size) + "  " + formatTime(item.time)
                return row
            }
        }
        listView.adapter = adapter
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        selectedCount.text = "已选 ${selected.size} 项"
        deleteSelected.isEnabled = selected.isNotEmpty()
        checkAll.setOnCheckedChangeListener(null)
        checkAll.isChecked = filteredItems.isNotEmpty() && selected.size == filteredItems.size
        checkAll.setOnCheckedChangeListener { _, checked ->
            if (filteredItems.isNotEmpty()) {
                if (checked) selected.addAll(filteredItems.map { keyOf(it) }) else selected.clear()
                renderList()
            }
        }
    }

    private fun toggleSelected(item: Item) {
        val key = keyOf(item)
        if (selected.contains(key)) selected.remove(key) else selected.add(key)
        renderList()
    }

    private fun openFile(item: Item) {
        // txt 文件进入内置阅读器（兼容系统 MediaStore 重名自动加的 "xxx.txt(1)" 后缀）
        if (extOf(item.name) == "txt") {
            openInReader(item)
            return
        }
        try {
            val uri: Uri = when {
                item.uri != null -> item.uri
                item.file != null -> FileProvider.getUriForFile(
                    this, "www.soushu2030.com.fileprovider", item.file
                )
                else -> return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(item.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "打开方式"))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可打开该文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 取文件真实扩展名（小写、不含点）。兼容系统 MediaStore 重名时自动追加的末尾后缀：
     * "斗破苍穹.txt(1)" → "txt"，"附件.pdf(2)" → "pdf"。
     */
    private fun extOf(name: String): String {
        var n = name.trim()
        // 剥离末尾的 "(数字)" 系统重名后缀（可连续多个，如 "x.txt(1)(2)"）
        while (Regex("\\(\\d+\\)$").containsMatchIn(n)) {
            n = n.replace(Regex("\\(\\d+\\)$"), "").trimEnd()
        }
        return n.substringAfterLast('.', "").lowercase()
    }

    /** 用内置阅读器打开 txt 文件，任何失效 URI 都留在下载列表页提示。 */
    private fun openInReader(item: Item) {
        val uri = item.uri
        val file = item.file
        if (uri == null && (file == null || !file.isFile)) {
            Toast.makeText(this, "文件不存在或无法访问", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_NAME, item.name)
                if (uri != null) {
                    putExtra(ReaderActivity.EXTRA_URI, uri.toString())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    putExtra(ReaderActivity.EXTRA_FILE, file!!.absolutePath)
                }
            }
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "无法打开该 TXT 文件", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteSelected() {
        val targets = items.filter { selected.contains(keyOf(it)) }
        if (targets.isEmpty()) return
        AlertDialog.Builder(this).setTitle("删除选中文件")
            .setMessage("确定删除 ${targets.size} 个文件吗？")
            .setPositiveButton("删除") { _, _ ->
                targets.forEach { deleteItem(it) }
                selected.clear()
                selectionMode = false
                loadFiles()
            }.setNegativeButton("取消", null).show()
    }

    private fun deleteItem(item: Item): Boolean = when {
        item.uri != null -> contentResolver.delete(item.uri, null, null) > 0
        item.file != null -> item.file.delete()
        else -> false
    }

    private inner class SwipeDeleteTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    if (dx < -160 && kotlin.math.abs(dx) > kotlin.math.abs(event.y - downY) * 1.5f) {
                        val pos = listView.pointToPosition(event.x.toInt(), event.y.toInt())
                        if (pos != ListView.INVALID_POSITION) {
                            val item = items[pos]
                            AlertDialog.Builder(this@DownloadsActivity).setTitle("删除文件")
                                .setMessage("确定删除「${item.name}」吗？")
                                .setPositiveButton("删除") { _, _ -> deleteItem(item); loadFiles() }
                                .setNegativeButton("取消", null).show()
                            return true
                        }
                    }
                }
            }
            return false
        }
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定删除「${item.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                val ok = when {
                    item.uri != null -> contentResolver.delete(item.uri, null, null) > 0
                    item.file != null -> item.file.delete()
                    else -> false
                }
                Toast.makeText(this, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                loadFiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用系统文件管理器打开本地下载目录（Download/自定义子目录），多级降级保证可用 */
    private fun openDownloadDir() {
        val dirName = Prefs.getDownloadDir(this)
        val docId = "primary:Download/$dirName"
        // 1) 浏览模式打开子目录（弹出应用选择器，第三方文件管理器可浏览并打开文件）
        if (tryBrowseDir(docId)) return
        // 2) 系统下载管理器（浏览模式，能打开文件，但为 Download 根目录）
        if (tryStart(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))) return
        // 3) SAF 选目录（最后兜底，能定位子目录但无法点文件打开）
        if (tryOpenSaf(docId)) return
        Toast.makeText(this, "无法打开下载目录，请用系统文件管理器手动打开 Download/$dirName", Toast.LENGTH_LONG).show()
    }

    /** 浏览模式打开目录：弹出「选择文件管理器」，第三方文件管理器可浏览并直接打开文件 */
    private fun tryBrowseDir(documentId: String): Boolean {
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
        for (mime in listOf(DocumentsContract.Document.MIME_TYPE_DIR, "resource/folder", "inode/directory")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "选择文件管理器"))
                return true
            } catch (e: Exception) { /* 尝试下一个 MIME */ }
        }
        return false
    }

    /** SAF 目录树打开，EXTRA_INITIAL_URI 初始定位到目标子目录，成功返回 true */
    private fun tryOpenSaf(documentId: String): Boolean {
        return try {
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
            startActivity(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 尝试启动一个 Intent，成功返回 true */
    private fun tryStart(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun mimeOf(name: String): String {
        return when (extOf(name)) {
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = size.toDouble()
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return String.format(Locale.US, "%.1f%s", v, units[u])
    }

    private fun formatTime(t: Long): String {
        if (t <= 0) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
