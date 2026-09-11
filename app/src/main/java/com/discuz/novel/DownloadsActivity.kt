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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
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
 * - 长按：进入多选，选完点右上按钮批量处理
 *
 * 批量处理两种模式（默认前者，安全）：
 *  1) 不勾「同时删除本地文件」→ 只把这批文件从下载列表里移除（写进 DownloadRecords，
 *     本地文件原封不动，之后还能点「恢复已移除记录」找回来）；
 *  2) 勾上「同时删除本地文件」→ 真删本地文件，不可恢复。
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
    private lateinit var chkDeleteLocal: CheckBox
    private lateinit var selectedCount: TextView
    private lateinit var deleteSelected: Button
    private lateinit var restoreRecords: Button
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
        chkDeleteLocal = findViewById(R.id.chkDeleteLocal)
        selectedCount = findViewById(R.id.selectedCount)
        deleteSelected = findViewById(R.id.btnDeleteSelected)
        restoreRecords = findViewById(R.id.btnRestoreRecords)

        // 打开本地下载目录
        findViewById<View>(R.id.btnOpenDir).setOnClickListener { openDownloadDir() }

        // 恢复被移除的列表记录（只还原列表，不下载任何东西）
        restoreRecords.setOnClickListener { confirmRestoreRecords() }

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
        // 勾选状态变化时，按钮文案跟着变（移除记录 / 删除文件）
        chkDeleteLocal.setOnCheckedChangeListener { _, _ -> updateSelectionUi() }
        deleteSelected.setOnClickListener {
            if (chkDeleteLocal.isChecked) confirmDeleteLocal() else confirmRemoveRecords()
        }
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

        // 过滤掉「已从列表移除」的记录（只影响列表显示，本地文件没动过）
        val hidden = DownloadRecords.hidden(this)
        if (hidden.isNotEmpty()) {
            items.removeAll { DownloadRecords.keyOf(it.name, it.size, it.time) in hidden }
        }

        selected.retainAll(items.map { keyOf(it) })
        applyFilter()
        renderList()
    }

    private fun keyOf(item: Item): String = item.uri?.toString() ?: item.file?.absolutePath ?: item.name

    private fun renderList() {
        val removed = DownloadRecords.count(this)

        // 空列表提示：区分「真的没有文件」和「记录被移除过」
        if (filteredItems.isEmpty()) {
            emptyView.text = if (query.isBlank() && removed > 0) {
                "列表已清空（本地文件仍在，未被删除）\n点上方「恢复已移除记录」可还原列表"
            } else if (query.isBlank()) {
                "暂无下载文件"
            } else {
                "没有匹配「$query」的文件"
            }
        }
        emptyView.visibility = if (filteredItems.isEmpty()) View.VISIBLE else View.GONE

        // 有移除记录时才显示恢复按钮，并把条数写在按钮上
        restoreRecords.visibility = if (removed > 0) View.VISIBLE else View.GONE
        if (removed > 0) restoreRecords.text = "恢复已移除记录（$removed）"

        selectedCount.text = "已选 ${selected.size} 项"
        deleteSelected.isEnabled = selected.isNotEmpty()
        deleteSelected.text = if (chkDeleteLocal.isChecked) "删除文件" else "移除记录"
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
        deleteSelected.text = if (chkDeleteLocal.isChecked) "删除文件" else "移除记录"
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

    // ---------------------------------------------------------------- 批量处理

    /** 默认动作：只把选中项从下载列表里移除，本地文件保留。 */
    private fun confirmRemoveRecords() {
        val targets = items.filter { selected.contains(keyOf(it)) }
        if (targets.isEmpty()) return
        val dirName = Prefs.getDownloadDir(this)
        AlertDialog.Builder(this)
            .setTitle("从列表移除")
            .setMessage(
                "把选中的 ${targets.size} 项从下载列表里去掉？\n\n" +
                    "本地文件不会被删除，仍然在 Download/$dirName 目录里，" +
                    "可以随时点「打开下载目录」查看，也能用「恢复已移除记录」还原列表。"
            )
            .setPositiveButton("移除记录") { _, _ ->
                DownloadRecords.hide(
                    this,
                    targets.map { DownloadRecords.keyOf(it.name, it.size, it.time) }
                )
                val n = targets.size
                exitSelection()
                Toast.makeText(this, "已从列表移除 $n 项，本地文件保留", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 勾选「同时删除本地文件」后的动作：真删本地文件，不可恢复。 */
    private fun confirmDeleteLocal() {
        val targets = items.filter { selected.contains(keyOf(it)) }
        if (targets.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除本地文件")
            .setMessage(
                "确定要删除选中的 ${targets.size} 个本地文件吗？\n\n" +
                    "删除后无法恢复，也不会进回收站。\n" +
                    "只想清空列表、保留文件的话，请先取消勾选「同时删除本地文件」。"
            )
            .setPositiveButton("删除文件") { _, _ ->
                var ok = 0
                targets.forEach {
                    if (deleteItem(it)) ok++
                    // 文件已删，对应的移除记录一并清掉，免得同名的新文件被误隐藏
                    DownloadRecords.reveal(
                        this,
                        listOf(DownloadRecords.keyOf(it.name, it.size, it.time))
                    )
                }
                val n = targets.size
                exitSelection()
                Toast.makeText(
                    this,
                    if (ok == n) "已删除 $n 个本地文件" else "已删除 $ok/$n 个文件，部分失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把所有被移除的记录恢复到列表里（只还原列表显示，不下载任何文件）。 */
    private fun confirmRestoreRecords() {
        val n = DownloadRecords.count(this)
        if (n <= 0) return
        AlertDialog.Builder(this)
            .setTitle("恢复已移除的记录")
            .setMessage("把 $n 条被移除的记录重新显示在列表里？\n不会下载任何文件，只是还原列表显示。")
            .setPositiveButton("恢复") { _, _ ->
                DownloadRecords.clear(this)
                Toast.makeText(this, "已恢复 $n 条记录", Toast.LENGTH_SHORT).show()
                loadFiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 退出多选状态并刷新列表；勾选状态一并复位（下次进来仍是安全的默认行为） */
    private fun exitSelection() {
        selected.clear()
        selectionMode = false
        selectionBar.visibility = View.GONE
        chkDeleteLocal.isChecked = false
        loadFiles()
    }

    private fun deleteItem(item: Item): Boolean = when {
        item.uri != null -> contentResolver.delete(item.uri, null, null) > 0
        item.file != null -> item.file.delete()
        else -> false
    }

    /** 用系统文件管理器打开本地下载目录（Download/自定义子目录），多级降级保证可用 */
    private fun openDownloadDir() {
        val dirName = Prefs.getDownloadDir(this)
        val docId = "primary:Download/$dirName"
        // 1) 优先用系统自带文件管理器(DocumentsUI)直接打开，不弹选择器
        if (tryOpenWithSystemDocumentsUi(docId)) return
        // 2) 浏览模式打开子目录（弹出应用选择器，第三方文件管理器可浏览并打开文件）
        if (tryBrowseDir(docId)) return
        // 3) 系统下载管理器（浏览模式，能打开文件，但为 Download 根目录）
        if (tryStart(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))) return
        // 4) SAF 选目录（最后兜底，能定位子目录但无法点文件打开）
        if (tryOpenSaf(docId)) return
        Toast.makeText(this, "无法打开下载目录，请用系统文件管理器手动打开 Download/$dirName", Toast.LENGTH_LONG).show()
    }

    /** 优先用系统自带 DocumentsUI 文件管理器直接打开目录（不弹选择器） */
    private fun tryOpenWithSystemDocumentsUi(documentId: String): Boolean {
        return try {
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
            for (mime in listOf(DocumentsContract.Document.MIME_TYPE_DIR, "resource/folder", "inode/directory")) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        setPackage("com.google.android.documentsui")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                    return true
                } catch (e: Exception) { /* 尝试下一个 MIME */ }
            }
            false
        } catch (e: Exception) {
            false
        }
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
