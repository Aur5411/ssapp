package com.discuz.novel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录页：列出浏览过的页面（最新在前）。
 * - 点击：回到主页面并打开该网址；
 * - 长按：进入多选模式，可勾选多项后批量删除。
 */
class HistoryActivity : AppCompatActivity() {

    private val items = mutableListOf<HistoryStore.Item>()
    private val filteredItems = mutableListOf<HistoryStore.Item>()   // 搜索过滤后的显示列表
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
        setContentView(R.layout.activity_history)

        supportActionBar?.title = "历史记录"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        searchInput = findViewById(R.id.searchInput)
        listView = findViewById(R.id.listView)
        emptyView = findViewById(R.id.emptyView)
        selectionBar = findViewById(R.id.selectionBar)
        checkAll = findViewById(R.id.checkAll)
        selectedCount = findViewById(R.id.selectedCount)
        deleteSelected = findViewById(R.id.btnDeleteSelected)

        // 搜索框：输入即时过滤（按标题或网址）
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
                if (checked) selected.addAll(filteredItems.map { it.url }) else selected.clear()
                renderList()
            }
        }
        deleteSelected.setOnClickListener { confirmDeleteSelected() }
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = filteredItems[position]
            if (selectionMode) {
                toggleSelected(item)
            } else {
                openUrl(item.url)
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
        loadHistory()
    }

    /** 按当前搜索关键字过滤历史记录（匹配标题或网址） */
    private fun applyFilter() {
        filteredItems.clear()
        if (query.isBlank()) {
            filteredItems.addAll(items)
        } else {
            filteredItems.addAll(items.filter {
                it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
            })
        }
    }

    private fun loadHistory() {
        items.clear()
        items.addAll(HistoryStore.load(this))
        selected.retainAll(items.map { it.url })
        applyFilter()
        renderList()
    }

    private fun renderList() {
        emptyView.visibility = if (filteredItems.isEmpty()) View.VISIBLE else View.GONE
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        selectedCount.text = "已选 ${selected.size} 项"
        deleteSelected.isEnabled = selected.isNotEmpty()
        checkAll.setOnCheckedChangeListener(null)
        checkAll.isChecked = filteredItems.isNotEmpty() && selected.size == filteredItems.size
        checkAll.setOnCheckedChangeListener { _, checked ->
            if (filteredItems.isNotEmpty()) {
                if (checked) selected.addAll(filteredItems.map { it.url }) else selected.clear()
                renderList()
            }
        }
        val adapter = object : ArrayAdapter<HistoryStore.Item>(this, 0, filteredItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_history, parent, false)
                val item = getItem(position)!!
                val check = row.findViewById<CheckBox>(R.id.itemCheck)
                check.visibility = if (selectionMode) View.VISIBLE else View.GONE
                row.setBackgroundColor(if (selected.contains(item.url)) 0xFFE3F2FD.toInt() else Color.WHITE)
                check.setOnCheckedChangeListener(null)
                check.isChecked = selected.contains(item.url)
                check.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(item.url) else selected.remove(item.url)
                    updateSelectionUi()
                }
                row.findViewById<TextView>(R.id.itemName).text = item.title.ifBlank { item.url }
                row.findViewById<TextView>(R.id.itemInfo).text = item.url + "\n" + formatTime(item.time)
                return row
            }
        }
        listView.adapter = adapter
    }

    private fun updateSelectionUi() {
        selectedCount.text = "已选 ${selected.size} 项"
        deleteSelected.isEnabled = selected.isNotEmpty()
        checkAll.setOnCheckedChangeListener(null)
        checkAll.isChecked = filteredItems.isNotEmpty() && selected.size == filteredItems.size
        checkAll.setOnCheckedChangeListener { _, checked ->
            if (filteredItems.isNotEmpty()) {
                if (checked) selected.addAll(filteredItems.map { it.url }) else selected.clear()
                renderList()
            }
        }
    }

    private fun toggleSelected(item: HistoryStore.Item) {
        if (selected.contains(item.url)) selected.remove(item.url) else selected.add(item.url)
        renderList()
    }

    private fun openUrl(url: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun confirmDeleteSelected() {
        val count = selected.size
        if (count == 0) return
        AlertDialog.Builder(this)
            .setTitle("删除选中记录")
            .setMessage("确定删除 $count 条浏览记录吗？")
            .setPositiveButton("删除") { _, _ ->
                HistoryStore.remove(this, selected.toSet())
                selected.clear()
                selectionMode = false
                loadHistory()
            }
            .setNegativeButton("取消", null)
            .show()
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
