package com.discuz.novel

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.roundToInt

/**
 * 内置 TXT 阅读器（分块流式读取版，避免大文件 OOM 闪退）。
 *
 * 全文按「每页 16K 字符」分页，翻页时才按需读取对应字符块并渲染，内存恒定，
 * 源文件上限 128MB。上下滑动翻页：页内滚动，到底/到顶翻页。支持背景、字号、
 * 行距、段距（文字档位）、目录定位、阅读位置记忆。支持 UTF-8、UTF-16、GBK。
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "reader_uri"
        const val EXTRA_FILE = "reader_file"
        const val EXTRA_NAME = "reader_name"
        const val MIN_TEXT_SIZE = 12f
        const val MAX_TEXT_SIZE = 30f
        const val TEXT_SIZE_STEP = 2f
        const val MIN_LINE_SPACING = 0.5f
        const val MAX_LINE_SPACING = 2.0f

        // 源文件大小上限（分块读取下内存恒定，仅限制磁盘/扫描耗时）
        private const val MAX_SOURCE_BYTES = 128L * 1024L * 1024L
        private const val PREFIX_BYTES = 64 * 1024 + 4
        private const val MAX_CHAPTERS = 2000
        // 扫描时单行缓冲上限，防止超长行 OOM
        private const val MAX_LINE_BUF = 4096
        // 前言（第一个章节前的内容）短于此字符数则跳过，避免广告/书名占用独立页
        private const val PREFACE_MIN_CHARS = 2000
        // 章节标题间隔小于此字符数视为「目录」密集排列，跳过目录
        private const val MIN_CHAPTER_GAP = 200
        // 相邻两个「章节标题」间隔小于此字符数，判定前者是「卷/部」级空壳容器（其标题后紧跟更细的章/正文标题）。
        // 这种容器若单独成页会得到极短空页（如"第一卷\n"后紧跟"第一章"仅隔几字），打开即"只见几字且翻不动"，
        // 因此从正文分页中剔除，让实际正文章节落在页首。
        private const val CONTAINER_MIN_GAP = 60

        private const val PAGE_PADDING_DP = 18
    }

    private val spacingPresets = listOf(0.5f, 0.7f, 0.9f, 1.0f, 1.2f, 1.4f, 1.7f, 2.0f)

    private data class Theme(val name: String, val bg: Int, val text: Int)
    private data class SourceLine(val text: String, val hasLeadingIndent: Boolean)

    /** 章节：标题 + 所在页号 */
    private data class Chapter(val title: String, val pageIndex: Int)
    private data class CharsetInfo(val charset: Charset)
    private data class RenderedText(val content: CharSequence, val chapterOffsets: List<Pair<String, Int>>)
    private data class ScanResult(
        val totalChars: Long,
        val pageStarts: List<Long>,
        val chapters: List<Chapter>
    )

    /** 分页源：持有缓存文件、编码、总字符数、每页起始偏移（章节对齐分页）、章节目录 */
    private data class ReaderSource(
        val file: File,
        val charset: Charset,
        val totalChars: Long,
        val pageStarts: List<Long>,
        val chapters: List<Chapter>
    )

    private sealed class LoadResult {
        data class Success(val source: ReaderSource) : LoadResult()
        data class Failure(val message: String) : LoadResult()
    }

    private class TextTooLargeException(val limitBytes: Long) : Exception()

    private val themes = listOf(
        Theme("白色", Color.WHITE, 0xFF1E293B.toInt()),
        Theme("米黄", 0xFFF5E9D0.toInt(), 0xFF4A3728.toInt()),
        Theme("护眼绿", 0xFFCCE8CF.toInt(), 0xFF2F4F2F.toInt()),
        Theme("夜间", 0xFF1A1A1A.toInt(), 0xFFB0B0B0.toInt()),
        Theme("浅灰", 0xFFE8E8E8.toInt(), 0xFF333333.toInt())
    )

    private val chapterRegex = Regex(
        "(?m)^[ \\t\\u3000]*(第[ \\t\\u3000]*[0-9０-９零〇一二三四五六七八九十百千万两]+[ \\t\\u3000]*[章卷回部节集话篇轮幕折更](?:[ \\t\\u3000]+[^\\n]{0,70})?|Chapter[ \\t\\u3000]*[0-9０-９]+[^\\n]{0,70}|(?:序章|楔子|番外篇?|尾声|后记|前言|引子|终章|大结局)[^\\n]{0,40})[ \\t\\u3000]*$",
        RegexOption.IGNORE_CASE
    )

    private lateinit var root: View
    private lateinit var scrollView: ScrollView
    private lateinit var tvContent: TextView
    private lateinit var bottomBar: View
    private lateinit var btnTheme: Button
    private lateinit var btnFontMinus: Button
    private lateinit var btnFontPlus: Button
    private lateinit var btnLineSpacing: Button
    private lateinit var btnCatalog: Button
    private lateinit var btnPrevChapter: Button
    private lateinit var btnNextChapter: Button

    private val chapters = mutableListOf<Chapter>()
    private var readerSource: ReaderSource? = null
    private var pageCount = 0
    private var currentPageIndex = 0
    private var currentPageRawText = ""
    private var currentPageChapterOffsets: List<Pair<String, Int>> = emptyList()
    private var loadThread: Thread? = null
    @Volatile private var pageLoading = false
    @Volatile private var pageRequestId = 0L
    @Volatile private var destroyed = false
    private val scrollHandler = Handler(Looper.getMainLooper())
    private var lastPageTime = 0L

    private var currentThemeIndex = 0
    private var textSize = 18f
    private var lineSpacing = 1.4f
    private var bottomBarVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra(EXTRA_NAME) ?: "阅读器"

        root = findViewById(R.id.readerRoot)
        scrollView = findViewById(R.id.readerScroll)
        tvContent = findViewById(R.id.tvContent)
        bottomBar = findViewById(R.id.bottomBar)
        btnTheme = findViewById(R.id.btnTheme)
        btnFontMinus = findViewById(R.id.btnFontMinus)
        btnFontPlus = findViewById(R.id.btnFontPlus)
        btnLineSpacing = findViewById(R.id.btnLineSpacing)
        btnCatalog = findViewById(R.id.btnCatalog)
        btnPrevChapter = findViewById(R.id.btnPrevChapter)
        btnNextChapter = findViewById(R.id.btnNextChapter)

        currentThemeIndex = Prefs.getReaderTheme(this).coerceIn(0, themes.size - 1)
        textSize = Prefs.getReaderFontSize(this).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        lineSpacing = Prefs.getReaderLineSpacing(this).coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
        applyTheme()
        applyTextStyle()
        cleanupReaderCache()

        btnTheme.setOnClickListener { showThemeDialog() }
        btnFontMinus.setOnClickListener { adjustTextSize(-TEXT_SIZE_STEP) }
        btnFontPlus.setOnClickListener { adjustTextSize(TEXT_SIZE_STEP) }
        btnLineSpacing.setOnClickListener { showLineSpacingDialog() }
        btnCatalog.setOnClickListener { showCatalogDialog() }
        btnPrevChapter.setOnClickListener { gotoPrevChapter() }
        btnNextChapter.setOnClickListener { gotoNextChapter() }

        // 单击呼出/隐藏工具栏：手动检测 down-up，避免 GestureDetector 与 textIsSelectable 长按选择冲突
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        tvContent.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    val slop = 24f // 点击滑动容差（px），小于此视为单击而非滑动
                    if (dx < slop && dy < slop && System.currentTimeMillis() - downTime < 300) {
                        toggleBottomBar()
                    }
                }
            }
            false // 不消费事件，交回滚动 / 长按选择
        }

        // 长按选中文字复制（系统默认选择模式）
        setupTextSelection()

        // 上下滑动翻页：滚到顶部/底部停止后自动翻页（防抖 + 回跳保护）
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            scrollHandler.removeCallbacksAndMessages(null)
            val atTop = scrollY <= 8
            val atBottom = scrollY + scrollView.height >= tvContent.height - 8
            if (atTop || atBottom) {
                scrollHandler.postDelayed({
                    if (pageLoading) return@postDelayed
                    if (System.currentTimeMillis() - lastPageTime < 800) return@postDelayed
                    if (scrollView.scrollY <= 8) {
                        if (currentPageIndex > 0) requestPage(currentPageIndex - 1, scrollToBottom = true)
                    } else {
                        if (currentPageIndex + 1 < pageCount) requestPage(currentPageIndex + 1, scrollToBottom = false)
                    }
                }, 150)
            }
        }

        val uri = intent.getStringExtra(EXTRA_URI)
        val filePath = intent.getStringExtra(EXTRA_FILE)
        loadThread = Thread {
            val result = prepareSource(uri, filePath)
            runOnUiThread {
                if (isActivityDead()) return@runOnUiThread
                when (result) {
                    is LoadResult.Failure -> showReaderError(result.message)
                    is LoadResult.Success -> {
                        readerSource = result.source
                        pageCount = result.source.pageStarts.size - 1
                        chapters.clear()
                        chapters.addAll(result.source.chapters)
                        val saved = Prefs.getReaderPosition(
                            this, intent.getStringExtra(EXTRA_NAME) ?: ""
                        )
                        val startPage = if (saved in 0 until pageCount) saved else 0
                        currentPageIndex = startPage
                        requestPage(startPage, scrollToBottom = false)
                    }
                }
            }
        }.also { it.start() }
    }

    private fun toggleBottomBar() {
        bottomBarVisible = !bottomBarVisible
        bottomBar.visibility = if (bottomBarVisible) View.VISIBLE else View.GONE
    }

    /** 启用长按选中文字 + 复制（系统默认选择模式）。 */
    private fun setupTextSelection() {
        tvContent.setTextIsSelectable(true)
    }

    private fun isActivityDead(): Boolean {
        return destroyed || isFinishing ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)
    }

    private fun cleanupReaderCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("reader_") && file.name.endsWith(".cache")) file.delete()
            }
        } catch (_: Throwable) {
        }
    }

    // ---------------- 读取：缓存 + 编码检测 + 扫描 ----------------

    private fun prepareSource(uriString: String?, filePath: String?): LoadResult {
        val temp = File(cacheDir, "reader_${System.nanoTime()}.cache")
        var keep = false
        try {
            val input = when {
                !uriString.isNullOrBlank() -> {
                    val parsed = try {
                        Uri.parse(uriString)
                    } catch (_: Throwable) {
                        return LoadResult.Failure("文件地址无效")
                    }
                    contentResolver.openInputStream(parsed)
                        ?: return LoadResult.Failure("文件不存在或无法访问")
                }
                !filePath.isNullOrBlank() -> {
                    val source = File(filePath)
                    if (!source.isFile || !source.canRead()) {
                        return LoadResult.Failure("文件不存在或无法访问")
                    }
                    if (source.length() > MAX_SOURCE_BYTES) {
                        throw TextTooLargeException(MAX_SOURCE_BYTES)
                    }
                    FileInputStream(source)
                }
                else -> return LoadResult.Failure("文件地址无效")
            }

            input.use { copyToCache(it, temp) }
            if (!temp.isFile || temp.length() == 0L) {
                return LoadResult.Failure("文件为空，无法打开")
            }
            if (temp.length() > MAX_SOURCE_BYTES) {
                return LoadResult.Failure(
                    "文件过大（${formatLimit(temp.length())}），当前最多支持约 ${formatLimit(MAX_SOURCE_BYTES)}"
                )
            }

            val charset = detectCharset(temp).charset
            val scan = scanSource(temp, charset)

            keep = true
            val source = ReaderSource(temp, charset, scan.totalChars, scan.pageStarts, scan.chapters)
            return LoadResult.Success(source)
        } catch (e: TextTooLargeException) {
            return LoadResult.Failure(
                "文件过大，当前最多支持约 ${formatLimit(e.limitBytes)}，请分卷后再打开"
            )
        } catch (_: SecurityException) {
            return LoadResult.Failure("没有读取该文件的权限")
        } catch (_: OutOfMemoryError) {
            return LoadResult.Failure("文件过大，手机内存不足，无法打开")
        } catch (_: Throwable) {
            return LoadResult.Failure("无法读取文件，请确认文件没有损坏")
        } finally {
            if (!keep) temp.delete()
        }
    }

    private fun copyToCache(input: InputStream, target: File): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        java.io.FileOutputStream(target).use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > MAX_SOURCE_BYTES) throw TextTooLargeException(MAX_SOURCE_BYTES)
                output.write(buffer, 0, count)
            }
            output.flush()
        }
        return total
    }

    /**
     * 流式逐字符扫描：统计总字符数、识别章节标题，并建立「章节对齐」的页边界。
     * 内存恒定（单行缓冲限 4K 字符），不整本读入。
     */
    private fun scanSource(file: File, charset: Charset): ScanResult {
        val chapterStarts = mutableListOf<Long>()
        val chapterTitles = mutableListOf<String>()
        val lineBuf = StringBuilder()
        var total = 0L
        var lineStart = 0L
        var sawCR = false

        fun processLine() {
            if (lineBuf.isEmpty()) return
            val title = lineBuf.toString()
            if (chapterTitles.size < MAX_CHAPTERS && isChapterTitle(title)) {
                chapterStarts.add(lineStart)
                // 用与渲染完全一致的处理（\u00A0→空格 + trim 全角空格），保证跳转时文本能精确匹配
                chapterTitles.add(title.replace('\u00A0', ' ').trim { isIndentChar(it) })
            }
            lineBuf.setLength(0)
        }

        InputStreamReader(FileInputStream(file), charset).use { reader ->
            val cbuf = CharArray(8192)
            while (true) {
                val n = reader.read(cbuf)
                if (n < 0) break
                for (i in 0 until n) {
                    val ch = cbuf[i]
                    total++
                    when (ch) {
                        '\r' -> {
                            processLine()
                            sawCR = true
                        }
                        '\n' -> {
                            if (!sawCR) processLine()
                            lineStart = total
                        }
                        else -> {
                            // BOM 计入字符偏移（与 readPage 的 skip 对齐），但不进行缓冲污染标题
                            if (ch != '\uFEFF' && lineBuf.length < MAX_LINE_BUF) lineBuf.append(ch)
                        }
                    }
                    sawCR = false
                }
            }
        }
        if (lineBuf.isNotEmpty()) processLine()

        // 跳过目录：文件开头连续密集的章节标题（间隔 < MIN_CHAPTER_GAP）是目录，不是正文章节
        var bodyStarts = chapterStarts
        var bodyTitles = chapterTitles
        if (chapterStarts.size >= 3 &&
            chapterStarts[1] - chapterStarts[0] < MIN_CHAPTER_GAP &&
            chapterStarts[2] - chapterStarts[1] < MIN_CHAPTER_GAP
        ) {
            var bodyStartIndex = chapterStarts.size
            for (j in 2 until chapterStarts.size) {
                if (chapterStarts[j] - chapterStarts[j - 1] >= MIN_CHAPTER_GAP) {
                    bodyStartIndex = j
                    break
                }
            }
            bodyStarts = chapterStarts.subList(bodyStartIndex, chapterStarts.size)
            bodyTitles = chapterTitles.subList(bodyStartIndex, chapterTitles.size)
        }

        // 剔除「卷/部」级空壳容器标题：某标题与下一个标题相距极短（说明它是容器，正文在其后一章才开始），
        // 则它不应单独占一页。反复剔除直到无过近相邻标题，避免连续多级容器（卷→部→章）层层留壳。
        var bs = bodyStarts
        var bt = bodyTitles
        if (bs.size >= 2) {
            var changed: Boolean
            do {
                changed = false
                val keptS = mutableListOf<Long>()
                val keptT = mutableListOf<String>()
                var i = 0
                while (i < bs.size) {
                    // 当前标题与下一个标题过近 → 当前是空壳容器，剔除
                    if (i < bs.size - 1 && bs[i + 1] - bs[i] < CONTAINER_MIN_GAP) {
                        i++
                        changed = true
                        continue
                    }
                    keptS.add(bs[i]); keptT.add(bt[i])
                    i++
                }
                bs = keptS; bt = keptT
            } while (changed)
        }
        bodyStarts = bs
        bodyTitles = bt

        // 章节对齐分页：跳过过短的前言（广告/书名等），让章节标题落在页首、打开直接进正文
        val pageStarts = mutableListOf<Long>()
        val chapters = mutableListOf<Chapter>()
        val firstStart = bodyStarts.firstOrNull()

        if (firstStart == null) {
            // 无章节：整个文件一页
            pageStarts.add(0L)
            pageStarts.add(total)
        } else if (firstStart >= PREFACE_MIN_CHARS) {
            // 前言足够长：前言是页 0，第一章是页 1
            pageStarts.add(0L)
            pageStarts.addAll(bodyStarts)
            pageStarts.add(total)
            bodyTitles.forEachIndexed { j, t -> chapters.add(Chapter(t, j + 1)) }
        } else {
            // 前言很短或第一个章节在文件开头：跳过前言，第一章是页 0
            pageStarts.addAll(bodyStarts)
            pageStarts.add(total)
            bodyTitles.forEachIndexed { j, t -> chapters.add(Chapter(t, j)) }
        }
        return ScanResult(total, pageStarts, chapters)
    }

    private fun detectCharset(file: File): CharsetInfo {
        val bytes = readPrefix(file, PREFIX_BYTES)
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return CharsetInfo(Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return CharsetInfo(Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return CharsetInfo(Charsets.UTF_16BE)
        }
        val sampleLength = completeUtf8PrefixLength(bytes)
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, 0, sampleLength))
            CharsetInfo(Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            CharsetInfo(Charset.forName("GBK"))
        }
    }

    private fun readPrefix(file: File, maxBytes: Int): ByteArray {
        val result = ByteArray(maxBytes)
        var size = 0
        FileInputStream(file).use { input ->
            while (size < result.size) {
                val count = input.read(result, size, result.size - size)
                if (count < 0) break
                if (count == 0) continue
                size += count
            }
        }
        return if (size == result.size) result else result.copyOf(size)
    }

    private fun completeUtf8PrefixLength(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val maxCut = minOf(4, bytes.size - 1)
        for (cut in 0..maxCut) {
            val length = bytes.size - cut
            try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes, 0, length))
                return length
            } catch (_: CharacterCodingException) {
            }
        }
        return bytes.size
    }

    /** 读取第 pageIndex 页的字符块（章节对齐分页，长度 = pageStarts 相邻差）。 */
    private fun readPage(source: ReaderSource, pageIndex: Int): String {
        if (pageIndex < 0 || pageIndex >= source.pageStarts.size - 1) return ""
        val startChar = source.pageStarts[pageIndex]
        val endChar = source.pageStarts[pageIndex + 1]
        val length = (endChar - startChar).toInt()
        if (length <= 0) return ""
        val cbuf = CharArray(length)
        val reader = InputStreamReader(FileInputStream(source.file), source.charset)
        try {
            skipChars(reader, startChar)
            var filled = 0
            while (filled < length) {
                val n = reader.read(cbuf, filled, length - filled)
                if (n < 0) break
                if (n == 0) {
                    if (reader.read() < 0) break
                    continue
                }
                filled += n
            }
            if (filled == 0) return ""
            return String(cbuf, 0, filled).removePrefix("\uFEFF")
        } finally {
            reader.close()
        }
    }

    /** 跳过 count 个字符：优先 Reader.skip，回退逐字符读取。 */
    private fun skipChars(reader: Reader, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = reader.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (reader.read() < 0) break
                remaining--
            }
        }
    }

    // ---------------- 翻页 ----------------

    private fun requestPage(index: Int, scrollToBottom: Boolean, chapterTitle: String? = null) {
        val source = readerSource ?: return
        if (pageLoading) return
        val target = index.coerceIn(0, pageCount.coerceAtLeast(0))

        // 目标页 == 当前页且有内容：直接滚动，不重新读
        if (target == currentPageIndex && currentPageRawText.isNotEmpty()) {
            if (chapterTitle != null) {
                scrollView.post { scrollToChapterTitle(chapterTitle) }
            } else if (scrollToBottom) {
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            } else {
                scrollView.post { scrollView.scrollTo(0, 0) }
            }
            return
        }

        pageLoading = true
        currentPageIndex = target
        val requestId = ++pageRequestId
        updatePageLabel(target)

        Thread {
            val text = readPage(source, target)
            runOnUiThread {
                pageLoading = false
                if (isActivityDead()) return@runOnUiThread
                if (requestId != pageRequestId) return@runOnUiThread // 过期请求丢弃
                currentPageRawText = text
                renderText()
                lastPageTime = System.currentTimeMillis()
                if (chapterTitle != null) {
                    scrollView.post { scrollToChapterTitle(chapterTitle) }
                } else if (scrollToBottom) {
                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                } else {
                    scrollView.post { scrollView.scrollTo(0, 0) }
                }
                savePosition()
            }
        }.start()
    }

    /** 滚动到指定章节标题所在行，让章节名显示在屏幕顶部。 */
    private fun scrollToChapterTitle(title: String) {
        scrollToChapterTitleInternal(title, 0)
    }

    private fun scrollToChapterTitleInternal(title: String, retry: Int) {
        val layout = tvContent.layout
        if (layout == null) {
            // 布局尚未完成（首次跳转的时序问题），等下一轮再试
            if (retry < 10) {
                tvContent.post { scrollToChapterTitleInternal(title, retry + 1) }
            }
            return
        }
        // 优先用渲染时记录的精确偏移，避免 indexOf 匹配到正文中的重复文本
        val recorded = currentPageChapterOffsets.firstOrNull { it.first == title }?.second
        val index = recorded ?: tvContent.text.indexOf(title)
        if (index < 0) {
            scrollView.scrollTo(0, 0)
            return
        }
        val line = layout.getLineForOffset(index)
        // getLineTop 是相对 TextView 内容区（padding 内）的偏移，实际滚动需加上 paddingTop
        val y = layout.getLineTop(line) + tvContent.paddingTop
        scrollView.scrollTo(0, y.coerceAtLeast(0))
    }

    private fun updatePageLabel(page: Int) {
        supportActionBar?.subtitle = if (pageCount > 1) "第 ${page + 1} / $pageCount 页" else null
    }

    private fun gotoPrevChapter() {
        if (currentPageIndex > 0) {
            requestPage(currentPageIndex - 1, scrollToBottom = false)
        } else {
            Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show()
        }
    }

    private fun gotoNextChapter() {
        if (currentPageIndex + 1 < pageCount) {
            requestPage(currentPageIndex + 1, scrollToBottom = false)
        } else {
            Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 渲染 ----------------

    private fun renderText() {
        if (currentPageRawText.isEmpty()) {
            tvContent.text = if (pageCount == 0) "文件为空" else ""
            return
        }
        val rendered = addParagraphIndent(currentPageRawText)
        currentPageChapterOffsets = rendered.chapterOffsets
        tvContent.setText(rendered.content, TextView.BufferType.SPANNABLE)
    }

    /** 段落处理 + 正文缩进 + 记录章节标题在渲染文本中的偏移，返回渲染内容。 */
    private fun addParagraphIndent(text: String): RenderedText {
        val paragraphs = splitIntoParagraphs(text)
        val sb = SpannableStringBuilder()
        val offsets = mutableListOf<Pair<String, Int>>()
        for (para in paragraphs) {
            if (para.isEmpty()) {
                // 段落分隔标记（原始空行）：保留空行，让段落/场景清晰
                sb.append('\n')
                continue
            }
            val isTitle = isChapterTitle(para)
            val display = if (isTitle) para else "　　$para"
            if (sb.isNotEmpty()) {
                sb.append('\n')
            }
            if (isTitle) {
                offsets.add(para to sb.length)
            }
            sb.append(display)
        }
        return RenderedText(sb, offsets)
    }

    /** 把文本按物理行/空行预处理成自然段；空行作为段落分隔标记（空字符串）保留。 */
    private fun splitIntoParagraphs(text: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\n' || ch == '\r') {
                lines.add(text.substring(start, index))
                if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                start = index + 1
            }
            index++
        }
        if (start < text.length) lines.add(text.substring(start))

        val block = mutableListOf<String>()
        fun flushBlock() {
            if (block.isEmpty()) return
            val sourceLines = block.map { s ->
                SourceLine(
                    s.replace('\u00A0', ' ').replace('\u3000', '　').trim { isIndentChar(it) },
                    s.replace('\u00A0', ' ').replace('\u3000', '　').firstOrNull()?.let { isIndentChar(it) } == true
                )
            }
            paragraphs.addAll(splitBlock(sourceLines))
            block.clear()
        }
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flushBlock()
                // 原始空行：作为段落分隔标记保留
                if (paragraphs.isNotEmpty() && paragraphs.last().isNotEmpty()) {
                    paragraphs.add("")
                }
            } else {
                block.add(line)
            }
        }
        flushBlock()
        return paragraphs
    }

    // ---------------- 段落处理 ----------------

    private fun splitBlock(lines: List<SourceLine>): List<String> {
        if (lines.size == 1) return listOf(lines.first().text)
        val mergeWrappedLines = shouldMergeHardWrappedLines(lines)
        val preservePhysicalLines = shouldPreservePhysicalLines(lines)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var previous: SourceLine? = null

        fun flush() {
            val value = current.toString().trim()
            if (value.isNotEmpty()) result.add(value)
            current.setLength(0)
        }
        for (line in lines) {
            val value = line.text
            val forceNewParagraph = current.isNotEmpty() && (
                preservePhysicalLines || !mergeWrappedLines ||
                    isChapterTitle(value) || isChapterTitle(previous?.text.orEmpty()) ||
                    isStandaloneLine(value) || isStandaloneLine(previous?.text.orEmpty()) ||
                    line.hasLeadingIndent ||
                    // 对话/新句边界：仅在非硬换行合并时生效（硬换行里以引号开头的行是段落延续，不应断开）
                    (!mergeWrappedLines && (startsDialogue(value) ||
                        (endsSentence(previous?.text) && looksLikeNewSentence(value)) ||
                        shouldKeepLineBreak(previous?.text, value)))
                )
            if (forceNewParagraph) flush()
            appendWrappedLine(current, value)
            previous = line
        }
        flush()
        return result.flatMap { splitLongParagraph(it) }
    }

    private fun shouldMergeHardWrappedLines(lines: List<SourceLine>): Boolean {
        val content = lines.filter {
            it.text.isNotBlank() && !it.hasLeadingIndent &&
                !isChapterTitle(it.text) && !isStandaloneLine(it.text)
        }
        if (content.size < 3) return false
        val originalLengths = content.map { it.text.length }
        val sortedLengths = originalLengths.sorted()
        val median = sortedLengths[sortedLengths.size / 2]
        val tolerance = maxOf(3, median / 10)
        val similarLengthRatio = originalLengths.count { kotlin.math.abs(it - median) <= tolerance }
            .toDouble() / originalLengths.size
        val unfinishedLineRatio = content.count { !endsSentence(it.text) }.toDouble() / content.size
        val hasShortTail = content.last().text.length <= median * 0.78
        val hasHardWrapLength = median >= 18
        return hasHardWrapLength && similarLengthRatio >= 0.55 &&
            unfinishedLineRatio >= 0.45 && (hasShortTail || unfinishedLineRatio >= 0.65)
    }

    private fun splitLongParagraph(paragraph: String): List<String> {
        val maxLength = 520
        if (paragraph.length <= maxLength || isChapterTitle(paragraph)) return listOf(paragraph)
        val result = mutableListOf<String>()
        var start = 0
        while (start < paragraph.length) {
            val end = (start + maxLength).coerceAtMost(paragraph.length)
            if (end == paragraph.length) {
                result += paragraph.substring(start).trim()
                break
            }
            val window = paragraph.substring(start, end)
            val punctuationCut = window.lastIndexOfAny(
                charArrayOf('。', '！', '？', '!', '?', '…', '；', ';')
            ).takeIf { it >= maxLength / 2 }
            val commaCut = window.lastIndexOf('，').takeIf { it >= maxLength / 2 }
            val cut = punctuationCut ?: commaCut
            val pieceEnd = if (cut != null) start + cut + 1 else end
            result += paragraph.substring(start, pieceEnd).trim()
            start = pieceEnd
        }
        return result.filter { it.isNotEmpty() }
    }

    private fun shouldPreservePhysicalLines(lines: List<SourceLine>): Boolean {
        if (lines.size < 3) return false
        val content = lines.filter { it.text.isNotBlank() }
        if (content.isEmpty()) return false
        val hasIndent = content.any { it.hasLeadingIndent }
        val standaloneCount = content.count { isStandaloneLine(it.text) }
        val sentenceEndRatio = content.count { endsSentence(it.text) }.toDouble() / content.size
        val similarShortLines = content.count { it.text.length in 1..24 }.toDouble() / content.size
        return hasIndent || standaloneCount >= 2 ||
            (sentenceEndRatio >= 0.75 && similarShortLines >= 0.6)
    }

    private fun appendWrappedLine(target: StringBuilder, line: String) {
        if (target.isEmpty()) {
            target.append(line)
            return
        }
        val left = target.lastOrNull() ?: return
        val right = line.firstOrNull() ?: return
        val brokenEnglishWord = left == '-' && right.isLetter()
        if (brokenEnglishWord) {
            target.deleteCharAt(target.length - 1)
        } else if (needsJoinSpace(left, right)) {
            target.append(' ')
        }
        target.append(line)
    }

    private fun isIndentChar(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '　'
    private fun isChapterTitle(value: String): Boolean = chapterRegex.matches(value.trim())
    private fun isStandaloneLine(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.matches(Regex("(?:[-*•●▪]|\\d+[.)、]|[一二三四五六七八九十]+、)\\s*.+"))
    }
    private fun startsDialogue(value: String): Boolean =
        value.firstOrNull() in setOf('“', '"', '‘', '\'', '「', '『', '【', '〈', '《')
    private fun endsSentence(value: String?): Boolean =
        value?.trimEnd()?.lastOrNull() in setOf(
            '。', '！', '？', '!', '?', '…', '：', ':', '”', '"', '’', '\'', '」', '』'
        )
    private fun looksLikeNewSentence(value: String): Boolean =
        value.firstOrNull()?.let {
            it in setOf('“', '"', '‘', '\'', '「', '『') || it.isUpperCase()
        } == true
    private fun shouldKeepLineBreak(previous: String?, current: String): Boolean {
        if (previous.isNullOrBlank() || current.isBlank()) return false
        val previousLast = previous.last()
        val currentFirst = current.first()
        return previousLast in setOf('。', '！', '？', '!', '?', '…') &&
            (currentFirst in setOf('“', '"', '‘', '\'', '「', '『') || currentFirst.isUpperCase())
    }
    private fun needsJoinSpace(left: Char, right: Char): Boolean =
        left.isLetterOrDigit() && right.isLetterOrDigit() && (left.code < 128 || right.code < 128)

    // ---------------- 设置 ----------------

    private fun showThemeDialog() {
        val labels = themes.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("背景颜色")
            .setSingleChoiceItems(labels, currentThemeIndex) { dialog, which ->
                currentThemeIndex = which
                applyTheme()
                Prefs.setReaderTheme(this, which)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyTheme() {
        val theme = themes[currentThemeIndex]
        root.setBackgroundColor(theme.bg)
        scrollView.setBackgroundColor(theme.bg)
        tvContent.setTextColor(theme.text)
    }

    private fun applyTextStyle() {
        tvContent.textSize = textSize
        tvContent.setLineSpacing(0f, lineSpacing)
    }

    private fun adjustTextSize(delta: Float) {
        textSize = (textSize + delta).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        Prefs.setReaderFontSize(this, textSize)
        applyTextStyle()
    }

    private fun showLineSpacingDialog() {
        val labels = spacingPresets.map { it.toString() }.toTypedArray()
        val checked = spacingPresets.indexOfFirst {
            kotlin.math.abs(it - lineSpacing) < 0.05f
        }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("行间距（倍数）")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                lineSpacing = spacingPresets[which].coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
                Prefs.setReaderLineSpacing(this, lineSpacing)
                applyTextStyle()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 目录 ----------------

    private fun showCatalogDialog() {
        if (chapters.isEmpty()) {
            Toast.makeText(this, "未检测到章节目录", Toast.LENGTH_SHORT).show()
            return
        }
        val currentChapterIndex = chapters.indexOfLast { it.pageIndex <= currentPageIndex }
            .coerceAtLeast(0)

        val dialog = AlertDialog.Builder(this)
            .setTitle("目录（${chapters.size} 章）")
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            // 目录显示时截断过长标题（内部匹配仍用完整标题）
            val labels = chapters.map { it.title.take(50) }.toTypedArray()
            val listView = ListView(this).apply {
                adapter = object : ArrayAdapter<String>(
                    this@ReaderActivity, android.R.layout.simple_list_item_1, labels
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getView(position, convertView, parent) as TextView
                        if (position == currentChapterIndex) {
                            v.setBackgroundColor(0x33000000.toInt())
                            v.setTextColor(0xFF000000.toInt())
                            v.typeface = Typeface.DEFAULT_BOLD
                            v.text = "▶ ${labels[position]}"
                        } else {
                            v.setBackgroundColor(0x00000000)
                            v.setTextColor(0xFF333333.toInt())
                            v.typeface = Typeface.DEFAULT
                            v.text = labels[position]
                        }
                        return v
                    }
                }
                setOnItemClickListener { _, _, position, _ ->
                    dialog.dismiss()
                    requestPage(
                        chapters[position].pageIndex,
                        scrollToBottom = false,
                        chapterTitle = chapters[position].title
                    )
                }
                post {
                    setSelectionFromTop(currentChapterIndex.coerceAtLeast(0), height / 3)
                }
            }
            dialog.setContentView(listView)
        }
        dialog.show()
    }

    // ---------------- 生命周期 ----------------

    private fun showReaderError(message: String) {
        chapters.clear()
        supportActionBar?.subtitle = null
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        tvContent.text = message
        tvContent.gravity = Gravity.CENTER
    }

    private fun formatLimit(bytes: Long): String {
        val mb = bytes / (1024L * 1024L)
        return if (mb >= 1) "$mb MB" else "${bytes / 1024L} KB"
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        saveReaderSettings()
    }

    private fun savePosition() {
        if (pageCount <= 0 || currentPageIndex < 0) return
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        Prefs.setReaderPosition(this, name, currentPageIndex)
    }

    /** 关闭/切后台时兜底保存字号、行距、背景色，下次打开自动恢复。 */
    private fun saveReaderSettings() {
        Prefs.setReaderFontSize(this, textSize)
        Prefs.setReaderLineSpacing(this, lineSpacing)
        Prefs.setReaderTheme(this, currentThemeIndex)
    }

    override fun onDestroy() {
        destroyed = true
        loadThread?.interrupt()
        try {
            readerSource?.file?.delete()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
