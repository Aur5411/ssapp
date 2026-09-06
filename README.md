一个基于 **Android WebView** 的 Discuz! X3.4 小说论坛壳应用。当前版本 **v1.9.78**。

## 功能总览

- ✅ **可配置网址**：首次打开进入设置页填写论坛地址，之后启动直达论坛
- ✅ **白色启动背景**：启动/加载页固定白底（手机深色模式下同样生效）
- ✅ **电脑版网页**：默认桌面 UA，进入网站即为 PC 版完整页面（设置页可关）
- ✅ **v1.9.6 双请求下载**：不再依赖系统 `setDownloadListener` 回调。WebView 加载附件/文件地址时由 App 自己发一次请求；HTML 跳转页交回 WebView 原生加载，只有服务器返回文件才直接保存，**不弹窗**，仅 toast 提示 + 结果页；同时修复浏览器通道正则语法错误并过滤误识别的 `search.php`
- ✅ **附件点击走内置浏览器导航**：跳转页中的 JS 在浏览器内真实执行（与 Via 浏览器行为一致）；`window.open`/`target=_blank` 经 onCreateWindow 接管转发，倒计时自动下载也能触发
- ✅ **双通道下载器**：原生通道（浏览器导航头 + 手动重定向 + 每跳带 Cookie + 帖子页 Referer）失败时自动切换**浏览器通道**（页面内 fetch 分块回传保存）
- ✅ **设置页版本号显示**：标题栏显示"设置 · vX.X.X"，便于确认手机上安装的版本
- ✅ **下载文件管理**：支持多选删除、全选、长按重命名和左滑删除；普通点击仍用于打开文件
- ✅ **文件名自动处理**：自动删除 `.com` 等网站标记；乱码文件名自动修复为中文；响应头文件名始终优先
- ✅ **自定义下载位置**：设置页配置目录，保存于系统 `Download/自定义目录`
- ✅ **App 内下载管理**：菜单「下载文件」查看全部下载，普通点击打开文件，长按重命名
- ✅ **自定义脚本**：设置页可粘贴任意 JS 脚本（油猴脚本 @grant none 版本可直接使用），每个网页加载完成后自动执行
- ✅ **去广告预设**：菜单/设置页均可开关，与 Discuz 常见广告位匹配移除
- ✅ 下拉刷新、加载进度条、返回键退网页历史、站外链接走系统浏器

---

## 一、快速使用

1. 安装 APK 后打开，首次进入设置页填写论坛地址（自动补 `https://`），保存后直达论坛
2. 网页内登录论坛账号（下载器会自动携带登录 Cookie）
3. 进帖子页点击附件链接 → 自动下载（toast 提示 + "已下载完成"结果页，无弹窗），右上角菜单「下载文件」查看结果

## 二、界面与菜单

主界面为简洁布局：标题栏 + 网页 + 顶部进度条。所有功能在右上角菜单：

| 菜单项 | 作用 |
|--------|------|
| 后退 / 前进 | 网页历史导航 |
| 刷新 | 重新加载当前页 |
| 返回首页 | 回到设置的论坛首页 |
| 去广告（可勾选） | 开关去广告脚本，与设置页状态同步 |
| 下载文件 | 打开 App 内下载管理页 |
| 设置 | 打开设置页（标题栏显示当前版本号） |

## 三、下载系统

### 下载流程（v1.9.6）
**主通道：附件直连下载（不渲染错误跳转页）**
1. WebView 的导航回调识别 `mod=attachment` / `attachment.php` 后按站点双请求流程处理：第一次请求只放行不保存，第二次（“点击这里重新下载”）才交给自研下载器并返回 `true`
2. 下载器携带登录 Cookie、帖子页 Referer、桌面 UA，手动跟随服务器重定向
3. `DownloadHelper.probe()` 自己发一次请求并判定响应类型：
   - HTML 跳转页交给浏览器通道继续查找下一层地址，最多连续跟随 5 层（location、window.open、meta refresh、form/iframe、data-url、下载链接等）
   - **文件**（`Content-Disposition` 含 attachment，或 Content-Type 非 html/xhtml）→ 直接保存，toast 提示，并返回"已下载完成"结果页给 WebView
   - **网页**（跳转页/提示页）→ 不重放探测到的 HTML，而是返回 `null` 放行 WebView 原生加载；页面 JS（`meta refresh` / `location.href` / `window.open`）在原始上下文中照常执行，其后到文件即被保存
4. 文件响应直接保存；如果服务器仍返回网页，交由备用浏览器通道继续最多 5 层跳转

> 此前日志记录显示附件页加载后报 `Cannot read properties of null`，停在“点击这里重新下载”；v1.9.2 增加多层跳转解析，浏览器通道会继续寻找下一层真实下载地址，不再因第一层 HTML 立即失败。

**备用通道（拦截网未覆盖时兜底）**
5. `setDownloadListener` 仍保留：系统判定为下载时走原有的原生通道（HTML 跳转页解析 + 浏览器通道 fetch 分块回传）
6. `onCreateWindow` 接管 `window.open` / `target=_blank`，保留独立弹出 WebView 的 JavaScript/Cookie 上下文；弹出页和二次弹出页均挂载下载监听器

**通用**
7. 文件名以响应头 `Content-Disposition` 为准，写入 `Download/自定义目录`，完成后 toast 提示保存位置
8. 若服务器返回的是提示页（未登录/需积分），页面直接在浏览器中显示原因

> v1.9.6 的附件策略：按网站实际流程，第一次附件请求只放行，第二次“重新下载”请求才接管保存；浏览器通道改用无正则字符串扫描，避免脚本解析错误，并过滤 `search.php` 搜索表单误跳转；整个过程无下载确认弹窗。

### 文件名自动处理（保存前执行）
- **去网站标记**：删除主体中的 `www.xxx.com`、`xxx.com`、`@xxx.com` 等（仅匹配常见域名后缀，不误伤 `.txt`/`.zip` 扩展名）
- **乱码修复**：HTTP 头中非 ASCII 文件名按 ISO-8859-1 原始字节分别尝试 UTF-8 / GBK 还原，按中文字符评分取最优，自动修复为正确中文
- 非法文件名字符替换为 `_`；同名文件覆盖更新，不堆积 `(1)(2)` 副本

### 存储与权限
- Android 10+：经 MediaStore 写入公共 Download 目录，**无需任何权限**
- Android 9 及以下：首次下载时动态申请存储权限

### App 内下载管理（菜单 → 下载文件）
- 列表显示：文件名、大小、修改时间（按时间倒序）
- 点击：调用系统应用打开（txt 阅读器、解压缩应用等）
- 点击文件：打开文件；点击复选框：选择文件
- 顶部“全选”：一键选择/取消全部文件
- “删除选中”：批量删除所选文件
- 长按文件：重命名
- 向左滑动文件：删除该文件

## 四、设置页

| 配置项 | 说明 |
|--------|------|
| 论坛网址 | 支持 http/https，省略协议自动补 https:// |
| 电脑版网页 | 默认开启（桌面 UA），关闭后用手机版 UA |
| 下载保存目录 | 默认 `DiscuzNovel`，位于系统 Download 文件夹内 |
| 去广告 | Discuz X3.4 去广告预设（菜单中也可开关） |
| 自定义脚本（JS） | 粘贴任意 JavaScript，网页加载完成后自动执行，留空不执行 |

---

## 五、编译打包

### 方式 A：Android Studio（推荐）
1. 用 Android Studio 打开本目录 `DiscuzForumApp`
2. 等 Gradle 同步完成（首次会自动下载 Gradle 8.7，已配置腾讯云镜像加速）
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`

### 方式 B：命令行（工程已内置 Gradle Wrapper，无需预装 Gradle）
```bash
# 前置：JDK 17 + Android SDK（Android Studio 自带，或单独下载）
# 1) 配置 SDK 路径：编辑 local.properties，把 sdk.dir 改为本机 Android SDK 路径
#    （Android Studio 打开工程会自动生成/覆盖，无需手动改）

# 2) 编译（Windows 用 gradlew.bat，macOS/Linux 用 ./gradlew）
cd DiscuzForumApp
gradlew assembleRelease   # 产物：app/build/outputs/apk/release/app-release.apk
```

> 首次运行 gradlew 会自动下载 Gradle 8.7（已配置腾讯云镜像），之后即可直接编译。
> 若命令行不想用 local.properties，也可设置环境变量 `ANDROID_HOME` 指向 SDK 目录。

### 签名
- 正式签名配置在工程根目录 `keystore.properties`（指向 `discuz-novel.keystore`）
- **请务必备份这两个文件**：密钥丢失后新版本将无法覆盖安装

---

## 六、技术栈

- 语言：Kotlin
- 最低系统：Android 5.0（API 21）
- 目标 SDK：34（JDK 17 + Gradle 8.7 + AGP 8.x）
- 依赖：AndroidX（AppCompat / Material / SwipeRefreshLayout / FileProvider）、系统 WebView
- 下载：HttpURLConnection + MediaStore（API 29+）/ File API（API ≤28）
- 持久化：SharedPreferences

## 七、目录结构

```
app/src/main/
├── java/com/discuz/novel/
│   ├── MainActivity.kt        # 主页：WebView、菜单、下载拦截网（shouldInterceptRequest）、JS 桥接
│   ├── SettingsActivity.kt    # 设置页（标题栏显示版本号）
│   ├── DownloadsActivity.kt   # App 内下载管理（列表/打开/删除）
│   ├── DownloadHelper.kt      # 自研下载器：拦截探测 + 手动重定向 + HTML 跳转解析 + 文件名处理
│   ├── ScriptManager.kt       # 脚本注入：去广告预设 + 自定义脚本（仅此两项）
│   └── Prefs.kt               # SharedPreferences 配置读写
└── res/
    ├── layout/activity_main.xml       # 主页
    ├── layout/activity_settings.xml   # 设置页
    ├── layout/activity_downloads.xml  # 下载管理页
    ├── menu/menu_main.xml             # 右上角菜单
    ├── xml/file_paths.xml             # FileProvider 路径（API ≤28 打开文件）
    └── drawable/ic_launcher.xml       # 应用图标（矢量）
```
