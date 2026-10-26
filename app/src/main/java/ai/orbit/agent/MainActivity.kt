package ai.orbit.agent

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.speech.RecognizerIntent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity() {
    private enum class Screen { HOME, FILES, SETTINGS }
    private enum class Approval { ALLOW, ALLOW_ALL, DENY }

    private val ink = Color.rgb(27, 35, 48)
    private val muted = Color.rgb(99, 109, 125)
    private val bgColor = Color.rgb(246, 247, 249)
    private val surface = Color.WHITE
    private val surfaceRaised = Color.rgb(239, 242, 247)
    private val accent = Color.rgb(37, 99, 235)
    private val violet = Color.rgb(83, 93, 176)
    private val warning = Color.rgb(166, 102, 18)
    private val danger = Color.rgb(190, 45, 58)

    private lateinit var settingsStore: SettingsStore
    private lateinit var secrets: SecureSecretStore
    private lateinit var workspace: WorkspaceTools
    private val worker = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var screen = Screen.HOME
    private var taskDraft = ""
    private var statusText = "Готов к работе"
    private var lastResult = ""
    private var activeTaskName = ""
    private var currentTask: Future<*>? = null
    private var currentDirectoryRel = ""
    private var pendingExternalFolderLatch: CountDownLatch? = null
    private data class PendingAgentWrite(val fileName: String, val content: String, val latch: CountDownLatch, val result: AtomicReference<String>)
    private data class PendingAgentDelete(val requestedPath: String, val latch: CountDownLatch, val result: AtomicReference<String>)
    private var pendingAgentWrite: PendingAgentWrite? = null
    private var pendingAgentDelete: PendingAgentDelete? = null
    private var pendingExportIsTemp = false
    @Volatile private var activeEngine: AgentEngine? = null
    @Volatile private var approveAllForTask = false
    @Volatile private var taskRunning = false
    private val history = ArrayList<JSONObject>()
    private val memoryFile by lazy { File(File(cacheDir, "orbit_memory"), "recent_tasks.json") }

    private var taskInputView: EditText? = null
    private var statusValueView: TextView? = null
    private var resultValueView: TextView? = null
    private var settingsFeedbackView: TextView? = null
    private var endpointField: EditText? = null
    private var modelField: EditText? = null
    private var apiKeyField: EditText? = null
    private var promptField: EditText? = null
    private var maxStepsField: EditText? = null
    private var autoApproveCheck: CheckBox? = null
    private var allowCommandsCheck: CheckBox? = null
    private var rememberChoicesCheck: CheckBox? = null
    private var allowShizukuCheck: CheckBox? = null

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuSupport.REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            if (granted) runCatching { ShizukuSupport.bindIfAllowed(this) }
            Toast.makeText(this, if (granted) "Доступ Shizuku разрешён" else "Доступ Shizuku отклонён", Toast.LENGTH_SHORT).show()
            if (screen == Screen.SETTINGS) render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        settingsStore = SettingsStore(this)
        secrets = SecureSecretStore(this)
        workspace = WorkspaceTools(this)
        try { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) } catch (_: Exception) { }
        var launchVoiceForAssistant = false
        if (intent?.action == Intent.ACTION_ASSIST) {
            taskDraft = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra("android.intent.extra.ASSIST_INPUT_HINT_KEY")?.toString().orEmpty()
            launchVoiceForAssistant = taskDraft.isBlank()
            if (taskDraft.isBlank()) taskDraft = ""
            screen = Screen.HOME
        }
        loadMemory()
        render()
        if (launchVoiceForAssistant) mainHandler.post { startVoiceInput() }
    }

    override fun onResume() {
        super.onResume()
        if (ShizukuSupport.hasPermission()) runCatching { ShizukuSupport.bindIfAllowed(this) }
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) } catch (_: Exception) { }
        super.onDestroy()
        if (isFinishing) {
            activeEngine?.stop()
            pendingExternalFolderLatch?.countDown()
            pendingAgentWrite?.let { it.result.set("Операция прервана: ORBIT закрыт."); it.latch.countDown() }
            pendingAgentDelete?.let { it.result.set("Операция прервана: ORBIT закрыт."); it.latch.countDown() }
        }
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            clipToPadding = false
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(68)))
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(22))
        }
        when (screen) {
            Screen.HOME -> buildHome(content)
            Screen.FILES -> buildFiles(content)
            Screen.SETTINGS -> buildSettings(content)
        }
        scroller.addView(content)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildBottomNav(), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_orbit) }
        bar.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        texts.addView(label("ORBIT", 20f, ink, true))
        texts.addView(label("Рабочее пространство", 11f, muted, false))
        bar.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val state = TextView(this).apply {
            text = if (taskRunning) "Работает" else "Готов"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (taskRunning) Color.rgb(137, 87, 9) else Color.rgb(34, 113, 65))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(if (taskRunning) Color.rgb(255, 244, 220) else Color.rgb(229, 246, 235), 10f, Color.TRANSPARENT, 0f)
        }
        bar.addView(state)
        return bar
    }

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.WHITE, 14f, Color.rgb(226, 230, 236), 1f)
        }
        nav.addView(navItem("•", "Задачи", Screen.HOME), LinearLayout.LayoutParams(0, -1, 1f))
        nav.addView(navItem("▤", "Файлы", Screen.FILES), LinearLayout.LayoutParams(0, -1, 1f))
        nav.addView(navItem("⚙", "Настройки", Screen.SETTINGS), LinearLayout.LayoutParams(0, -1, 1f))
        val wrap = LinearLayout(this).apply {
            setPadding(dp(18), dp(2), dp(18), dp(10))
            setBackgroundColor(bgColor)
            addView(nav, LinearLayout.LayoutParams(-1, -1))
        }
        return wrap
    }

    private fun navItem(symbol: String, title: String, target: Screen): View {
        val selected = screen == target
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = if (selected) rounded(Color.rgb(232, 239, 255), 10f, Color.TRANSPARENT, 0f) else null
            isClickable = true
            isFocusable = true
            addView(label(symbol, 18f, if (selected) accent else muted, true))
            addView(label(title, 10f, if (selected) ink else muted, selected))
            setOnClickListener {
                if (screen == Screen.SETTINGS) saveSettings(false)
                screen = target
                render()
            }
        }
    }

    private fun buildHome(parent: LinearLayout) {
        parent.addView(sectionLabel("Задачи", if (taskRunning) "Выполняется" else "Готов к запуску"))
        val hero = card()
        hero.addView(label("Что нужно сделать?", 24f, ink, true))
        hero.addView(spacer(9))
        hero.addView(label("ORBIT работает с файлами, вебом и доступными действиями Android. Результаты проверяются и сохраняются в проекте.", 13f, muted, false))
        hero.addView(spacer(14))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stats.addView(metric("WORKSPACE", humanBytes(workspace.workspaceBytes()), accent), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(metric("TOOL CALLS", "API + device", violet), LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(stats)
        parent.addView(hero, withTop(12))

        parent.addView(label("Новая задача", 19f, ink, true), withTop(22))
        parent.addView(label("Опиши результат, который нужен. Агент выполнит действия доступными инструментами.", 12f, muted, false), withTop(4))
        val taskCard = card().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val taskInput = editField("Например: найди документацию, создай структуру проекта и упакуй её в ZIP…", multiline = true).apply {
            minLines = 4
            maxLines = 8
            setText(taskDraft)
            setSelection(text.length)
            setOnTextChanged { taskDraft = it }
        }
        taskInputView = taskInput
        taskCard.addView(taskInput, LinearLayout.LayoutParams(-1, -2))
        taskCard.addView(actionButton("Голосовой ввод", false) { startVoiceInput() }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        taskCard.addView(spacer(10))
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actionRow.addView(actionButton(if (taskRunning) "АГЕНТ РАБОТАЕТ" else "Запустить агента", true) {
            taskDraft = taskInputView?.text?.toString().orEmpty()
            startTask()
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actionRow.addView(spacerWidth(9))
        actionRow.addView(actionButton("Стоп", false) { stopTask() }, LinearLayout.LayoutParams(dp(78), dp(50)))
        taskCard.addView(actionRow)
        parent.addView(taskCard, withTop(10))

        val statusCard = card()
        statusCard.addView(label("ТЕКУЩИЙ СТАТУС", 10f, accent, true).apply { letterSpacing = 0.10f })
        val statusValue = label(statusText, 14f, ink, true)
        statusValueView = statusValue
        statusCard.addView(statusValue, withTop(8))
        if (taskRunning) {
            statusCard.addView(spacer(8))
            statusCard.addView(label("Не закрывай ORBIT, пока выполняется операция. Длительные задачи работают через уведомление.", 11f, muted, false))
        }
        parent.addView(statusCard, withTop(12))

        if (lastResult.isNotBlank()) {
            val result = card()
            result.addView(label("РЕЗУЛЬТАТ", 10f, accent, true).apply { letterSpacing = 0.10f })
            val resultValue = label(lastResult, 13f, ink, false)
            resultValue.setTextIsSelectable(true)
            resultValueView = resultValue
            result.addView(resultValue, withTop(8))
            val copy = actionButton("Скопировать результат", false) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ORBIT result", lastResult))
                Toast.makeText(this, "Результат скопирован", Toast.LENGTH_SHORT).show()
            }
            result.addView(copy, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
            parent.addView(result, withTop(12))
        } else {
            val hints = card()
            hints.addView(label("ЧТО УМЕЕТ", 10f, violet, true).apply { letterSpacing = 0.1f })
            hints.addView(label("Поиск в сети • создание файлов • ZIP-архивы • команды в среде приложения • запуск приложений • действия на экране после включения Accessibility.", 12f, muted, false), withTop(8))
            parent.addView(hints, withTop(12))
        }

        if (history.isNotEmpty()) {
            parent.addView(label("Недавние задачи", 17f, ink, true), withTop(22))
            history.take(3).forEach { item ->
                val past = card()
                past.addView(label(item.optString("task", "Задача").take(90), 13f, ink, true))
                past.addView(label(item.optString("status", "Завершено"), 10f, muted, false), withTop(4))
                past.setOnClickListener {
                    taskDraft = item.optString("task", "")
                    lastResult = item.optString("result", "")
                    render()
                }
                past.isClickable = true
                parent.addView(past, withTop(8))
            }
        }
        parent.addView(label("Автосогласие ${if (settingsStore.load().autoApprove) "включено" else "выключено"} · команды ${if (settingsStore.load().allowCommands) "разрешены" else "запрещены"}", 10f, muted, false), withTop(18))
    }

    private fun buildFiles(parent: LinearLayout) {
        parent.addView(sectionLabel("Файлы", "Проекты и результаты"))
        parent.addView(label("Рабочие проекты", 23f, ink, true), withTop(12))
        parent.addView(label("Каждая задача получает отдельную папку в workflow/project-…", 12f, muted, false), withTop(4))

        val importCard = card()
        importCard.addView(label("Входные файлы", 16f, ink, true))
        importCard.addView(label("Выбери файл, несколько файлов или ZIP. ORBIT скопирует их в workflow/inbox, чтобы агент мог прочитать текст, распаковать архив и использовать исходники при выполнении задачи.", 12f, muted, false), withTop(5))
        importCard.addView(actionButton("Загрузить файлы / ZIP…", true) { selectInputFiles() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        parent.addView(importCard, withTop(12))

        val info = card()
        info.addView(label("ЗАНЯТО", 10f, muted, true))
        info.addView(label(humanBytes(workspace.workspaceBytes()), 28f, ink, true), withTop(4))
        info.addView(label("${workspace.root.walkTopDown().count { it.isFile }} файлов · динамический размер", 12f, muted, false), withTop(2))
        info.addView(actionButton("Сохранить всё в ZIP…", true) {
            try { saveFileAs(workspace.createWorkspaceArchive(), "orbit-workspace.zip", true, "application/zip") }
            catch (e: Exception) { Toast.makeText(this, "Не удалось создать ZIP: ${e.message}", Toast.LENGTH_LONG).show() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        parent.addView(info, withTop(12))

        val access = card()
        access.addView(label("Доступ к файлам телефона", 16f, ink, true))
        access.addView(label("Выбери папку, к которой нужен доступ. На Android 11+ корень Download нельзя выдать через выбор папки; для отдельного файла ORBIT откроет системный диалог сохранения или выбора файла.", 12f, muted, false), withTop(5))
        access.addView(label("Выбрано: ${workspace.selectedExternalFolderName()}", 12f, if (workspace.hasExternalFolder()) Color.rgb(34, 113, 65) else muted, true), withTop(8))
        access.addView(actionButton("Выбрать папку для файлов агента…", false) { selectExternalFolder() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        parent.addView(access, withTop(12))

        var directory = runCatching {
            if (currentDirectoryRel.isBlank()) workspace.root.canonicalFile else File(workspace.root, currentDirectoryRel).canonicalFile
        }.getOrDefault(workspace.root)
        if (directory != workspace.root && !directory.path.startsWith(workspace.root.canonicalPath + File.separator)) {
            currentDirectoryRel = ""
            directory = workspace.root.canonicalFile
        }
        if (currentDirectoryRel.isNotBlank()) {
            parent.addView(actionButton("←  Назад", false) {
                currentDirectoryRel = currentDirectoryRel.substringBeforeLast('/', "")
                render()
            }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(12) })
        }
        parent.addView(label(if (currentDirectoryRel.isBlank()) "Рабочая папка" else currentDirectoryRel, 14f, ink, true), withTop(14))
        parent.addView(label(directory.absolutePath, 10f, muted, false), withTop(3))

        val files = directory.listFiles()
            ?.filter { child ->
                val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@filter false
                canonical == workspace.root.canonicalFile || canonical.path.startsWith(workspace.root.canonicalPath + File.separator)
            }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        if (files.isEmpty()) {
            val empty = card()
            empty.addView(label("Папка пуста", 14f, ink, true))
            empty.addView(label("Создай проект новой задачей или попроси агента создать файл.", 12f, muted, false), withTop(5))
            parent.addView(empty, withTop(8))
        } else {
            files.take(100).forEach { file ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(8), dp(10))
                    background = rounded(Color.WHITE, 11f, Color.rgb(229, 232, 238), 1f)
                    isClickable = true
                    isFocusable = true
                }
                row.addView(label(if (file.isDirectory) "▰" else "▤", 19f, if (file.isDirectory) violet else accent, true), LinearLayout.LayoutParams(dp(30), -2))
                val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                column.addView(label(file.name, 13f, ink, true))
                column.addView(label(if (file.isDirectory) "Папка" else humanBytes(file.length()), 11f, muted, false), withTop(2))
                row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener {
                    if (file.isDirectory) {
                        currentDirectoryRel = workspace.root.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
                        render()
                    } else showFileActions(file)
                }
                row.addView(actionButton("⋯", false) { showFileActions(file) }, LinearLayout.LayoutParams(dp(46), dp(38)))
                parent.addView(row, withTop(6))
            }
            if (files.size > 100) parent.addView(label("Показаны первые 100 элементов.", 11f, muted, false), withTop(6))
        }
    }

    private fun showFileActions(file: File) {
        val relative = workspace.root.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
        val choices = if (file.isDirectory) arrayOf("Открыть папку", "Сохранить папку в ZIP…", "Удалить папку")
            else arrayOf("Сохранить копию на устройстве…", "Предпросмотр текста", "Удалить файл")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(choices) { _, which ->
                if (file.isDirectory) when (which) {
                    0 -> { currentDirectoryRel = relative; render() }
                    1 -> try { saveFileAs(workspace.createItemArchive(relative), "${file.name}.zip", true, "application/zip") }
                        catch (e: Exception) { Toast.makeText(this, "Ошибка ZIP: ${e.message}", Toast.LENGTH_LONG).show() }
                    2 -> confirmDeleteWorkspaceItem(file)
                } else when (which) {
                    0 -> saveFileAs(file, file.name, false, mimeFor(file.name))
                    1 -> previewWorkspaceFile(file)
                    2 -> confirmDeleteWorkspaceItem(file)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteWorkspaceItem(file: File) {
        val canonical = runCatching { file.canonicalFile }.getOrNull()
        if (canonical == null || canonical == workspace.root.canonicalFile || canonical.path == File(workspace.root, "workflow").canonicalPath || !canonical.path.startsWith(workspace.root.canonicalPath + File.separator)) {
            Toast.makeText(this, "Нельзя удалить корень рабочего пространства или workflow.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (file.isDirectory) "Удалить папку?" else "Удалить файл?")
            .setMessage("Объект будет удалён из рабочей папки ORBIT. Это действие нельзя отменить.\n\n${file.name}")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
                Toast.makeText(this, if (removed) "Удалено" else "Не удалось удалить объект", Toast.LENGTH_SHORT).show()
                render()
            }.show()
    }

    private fun previewWorkspaceFile(file: File) {
        if (file.length() > 200_000L) {
            Toast.makeText(this, "Файл больше 200 КБ. Сохрани его отдельно для просмотра.", Toast.LENGTH_LONG).show()
            return
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { "Не удалось прочитать как UTF-8: ${it.message}" }
        val view = TextView(this).apply {
            this.text = text.take(12_000)
            setTextColor(ink)
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this).setTitle(file.name).setView(scroll).setPositiveButton("Готово", null).show()
    }

    private fun mimeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun saveFileAs(source: File, displayName: String, temporary: Boolean, mime: String) {
        pendingExport = source
        pendingExportIsTemp = temporary
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, displayName)
        }
        try { startActivityForResult(intent, EXPORT_REQUEST) }
        catch (e: Exception) {
            pendingExport = null
            pendingExportIsTemp = false
            if (temporary) source.delete()
            Toast.makeText(this, "Не удалось открыть окно сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectExternalFolder(waiter: CountDownLatch? = null) {
        pendingExternalFolderLatch = waiter
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, EXTERNAL_TREE_REQUEST) }
        catch (e: Exception) {
            pendingExternalFolderLatch?.countDown()
            pendingExternalFolderLatch = null
            Toast.makeText(this, "Окно выбора папки недоступно: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestExternalWriteFromAgent(args: JSONObject): String {
        val requestedPath = args.optString("path", "orbit-output.txt")
        val fileName = requestedPath.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\u0000-\\u001f]"), "_").take(120).ifBlank { "orbit-output.txt" }
        val content = args.optString("content", "")
        if (args.optBoolean("append", false)) return "Для добавления в существующий файл выбери постоянную SAF-папку в ORBIT и повтори операцию."
        if (content.length > 1_000_000) return "Отклонено: один файл ограничен 1 000 000 символов."
        val pending = PendingAgentWrite(fileName, content, CountDownLatch(1), AtomicReference("Сохранение отменено."))
        mainHandler.post {
            if (isFinishing || isDestroyed) {
                pending.result.set("Отклонено: экран ORBIT уже закрыт.")
                pending.latch.countDown()
                return@post
            }
            pendingAgentWrite = pending
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeFor(fileName)
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            try { startActivityForResult(intent, AGENT_SAVE_REQUEST) }
            catch (e: Exception) {
                pendingAgentWrite = null
                pending.result.set("Не удалось открыть системный диалог сохранения: ${e.message}")
                pending.latch.countDown()
            }
        }
        if (!pending.latch.await(5, TimeUnit.MINUTES)) {
            mainHandler.post { if (pendingAgentWrite === pending) pendingAgentWrite = null }
            return "Сохранение не завершено: системный диалог не вернул результат за 5 минут."
        }
        return pending.result.get()
    }

    private fun requestExternalDeleteFromAgent(requestedPath: String): String {
        val pending = PendingAgentDelete(requestedPath, CountDownLatch(1), AtomicReference("Удаление отменено."))
        mainHandler.post {
            if (isFinishing || isDestroyed) {
                pending.result.set("Отклонено: экран ORBIT уже закрыт.")
                pending.latch.countDown()
                return@post
            }
            pendingAgentDelete = pending
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            try { startActivityForResult(intent, AGENT_DELETE_REQUEST) }
            catch (e: Exception) {
                pendingAgentDelete = null
                pending.result.set("Не удалось открыть выбор файла: ${e.message}")
                pending.latch.countDown()
            }
        }
        if (!pending.latch.await(5, TimeUnit.MINUTES)) {
            mainHandler.post { if (pendingAgentDelete === pending) pendingAgentDelete = null }
            return "Удаление не завершено: системный выбор файла не вернул результат за 5 минут."
        }
        return pending.result.get()
    }

    private fun requestExternalFolderFromAgent(): String? {
        val latch = CountDownLatch(1)
        mainHandler.post {
            if (isFinishing || isDestroyed) { latch.countDown(); return@post }
            AlertDialog.Builder(this)
                .setTitle("ORBIT просит доступ к файлам")
                .setMessage("Агент хочет работать с файлами вне своей рабочей папки. Выбери разрешённую папку в системном окне Android. ORBIT получит доступ только к выбранной папке и её содержимому.")
                .setPositiveButton("Выбрать папку") { _, _ -> selectExternalFolder(latch) }
                .setNegativeButton("Отказать") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        if (!latch.await(5, TimeUnit.MINUTES)) return "Доступ к внешним файлам не был предоставлен вовремя."
        return if (workspace.hasExternalFolder()) null else "Пользователь не выбрал папку общего хранилища. Не повторяй операцию; сообщи, что доступ не предоставлен."
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите, что должен сделать ORBIT")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            startActivityForResult(intent, VOICE_INPUT_REQUEST)
        } catch (_: Exception) {
            Toast.makeText(this, "Распознавание речи недоступно. Проверь Google app / Speech Services by Google.", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectInputFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(intent, INPUT_FILES_REQUEST) }
        catch (e: Exception) { Toast.makeText(this, "Не удалось открыть выбор файлов: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importSelectedUris(data: Intent?): String {
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data?.data?.let(uris::add)
        if (uris.isEmpty()) return "Файлы не выбраны."
        val inbox = File(workspace.root, "workflow/inbox").canonicalFile
        if (!inbox.path.startsWith(workspace.root.canonicalPath + File.separator)) throw IllegalArgumentException("Некорректная папка импорта.")
        inbox.mkdirs()
        val imported = ArrayList<String>()
        for (uri in uris.take(30)) {
            val rawName = runCatching { DocumentFile.fromSingleUri(this, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "uploaded-file"
            val safeName = rawName.replace(Regex("[^A-Za-z0-9А-Яа-я._() -]"), "_").trim().take(120).ifBlank { "uploaded-file" }
            var target = File(inbox, safeName)
            var suffix = 2
            while (target.exists()) {
                val ext = target.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
                val base = target.name.removeSuffix(ext)
                target = File(inbox, "$base-$suffix$ext")
                suffix++
            }
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().buffered().use { output -> input.copyTo(output) } }
                ?: throw IllegalStateException("Android не открыл файл: $safeName")
            imported.add("${target.name} (${humanBytes(target.length())})")
        }
        return "Импортировано ${imported.size} файл(ов) в workflow/inbox/:\n" + imported.joinToString("\n") + if (uris.size > 30) "\nОстальные файлы не импортированы: лимит 30 за раз." else ""
    }

    private fun requestShizukuAccess() {
        if (!ShizukuSupport.binderAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku не запущен")
                .setMessage("Установи Shizuku, запусти его через Wireless Debugging/ADB, затем вернись сюда и выдай ORBIT разрешение.")
                .setPositiveButton("Открыть загрузку") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        try {
            if (ShizukuSupport.hasPermission()) {
                ShizukuSupport.bindIfAllowed(this)
                Toast.makeText(this, ShizukuSupport.status(), Toast.LENGTH_LONG).show()
            } else {
                Shizuku.requestPermission(ShizukuSupport.REQUEST_CODE)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAssistantRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching { getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false)
    }

    private fun requestAssistantRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val manager = getSystemService(RoleManager::class.java)
                if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), ASSISTANT_ROLE_REQUEST)
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть выбор ассистента: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildSettings(parent: LinearLayout) {
        parent.addView(sectionLabel("Настройки", "API и доступы"))
        parent.addView(label("Настройка агента", 25f, ink, true), withTop(14))
        parent.addView(label("API, автономность, память и локальные инструменты.", 12f, muted, false), withTop(4))
        val apiCard = card()
        apiCard.addView(label("ПОДКЛЮЧЕНИЕ К МОДЕЛИ", 10f, accent, true).apply { letterSpacing = 0.1f })
        apiCard.addView(label("Работает с OpenAI-совместимым Chat Completions API. Для провайдера укажи его endpoint, ключ и точный ID модели.", 12f, muted, false), withTop(7))
        endpointField = editField("https://provider.example/v1/chat/completions").apply {
            setText(settingsStore.load().endpoint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        apiCard.addView(fieldLabel("API endpoint URL"), withTop(15))
        apiCard.addView(endpointField, withTop(6))
        modelField = editField("model-id", false).apply { setText(settingsStore.load().model) }
        apiCard.addView(fieldLabel("Модель / Model ID"), withTop(13))
        apiCard.addView(modelField, withTop(6))
        apiKeyField = editField("API key (оставь пустым, чтобы сохранить прежний)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        apiCard.addView(fieldLabel("API-ключ"), withTop(13))
        apiCard.addView(apiKeyField, withTop(6))
        apiCard.addView(label(if (secrets.hasApiKey()) "Ключ сохранён в зашифрованном виде через Android Keystore." else "Ключ пока не сохранён. Локальные API могут работать без авторизации.", 10f, muted, false), withTop(6))
        promptField = editField("Дополнительные правила для агента (необязательно)", multiline = true).apply {
            minLines = 3
            maxLines = 7
            setText(settingsStore.load().systemPrompt)
        }
        apiCard.addView(fieldLabel("Дополнительные инструкции"), withTop(13))
        apiCard.addView(promptField, withTop(6))
        maxStepsField = editField("10", false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settingsStore.load().maxSteps.toString())
        }
        apiCard.addView(fieldLabel("Лимит шагов агента (2–20)"), withTop(13))
        apiCard.addView(maxStepsField, withTop(6))
        settingsFeedbackView = label("", 11f, accent, false)
        apiCard.addView(settingsFeedbackView, withTop(10))
        apiCard.addView(actionButton("Сохранить настройки", true) { saveSettings(true) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        apiCard.addView(actionButton("Проверить API", false) { testApi() }, LinearLayout.LayoutParams(-1, dp(45)).apply { topMargin = dp(8) })
        apiCard.addView(actionButton("Удалить сохранённый API-ключ", false) {
            AlertDialog.Builder(this)
                .setTitle("Удалить API-ключ?")
                .setMessage("Ключ исчезнет из защищённого хранилища этого приложения.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ -> secrets.clearApiKey(); apiKeyField?.setText(""); settingsFeedbackView?.text = "API-ключ удалён."; renderSettingsRefreshStatus() }
                .show()
        }, LinearLayout.LayoutParams(-1, dp(45)).apply { topMargin = dp(8) })
        parent.addView(apiCard, withTop(12))

        val autonomy = card()
        autonomy.addView(label("АВТОНОМНОСТЬ И ПАМЯТЬ", 10f, violet, true).apply { letterSpacing = 0.1f })
        autoApproveCheck = CheckBox(this).apply {
            text = "Автосогласие действий"
            isChecked = settingsStore.load().autoApprove
            setTextColor(ink)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        autonomy.addView(autoApproveCheck, withTop(8))
        autonomy.addView(label("Включено: агент самостоятельно выполняет вызовы инструментов без диалогов подтверждения. Он может изменять файлы своей рабочей папки и выполнять действия на экране, если Accessibility включён.", 11f, muted, false), withTop(2))
        allowCommandsCheck = CheckBox(this).apply {
            text = "Разрешить выполнение shell-команд"
            isChecked = settingsStore.load().allowCommands
            setTextColor(ink)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(warning)
        }
        autonomy.addView(allowCommandsCheck, withTop(12))
        autonomy.addView(label("Команды выполняются через /system/bin/sh под UID приложения, без root. Это не полноценная виртуальная машина и не жёсткая файловая песочница. Android не гарантирует наличие компиляторов.", 11f, muted, false), withTop(2))
        allowShizukuCheck = CheckBox(this).apply {
            text = "Разрешить команды через Shizuku"
            isChecked = settingsStore.load().allowShizukuCommands
            setTextColor(ink)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(warning)
        }
        autonomy.addView(allowShizukuCheck, withTop(12))
        autonomy.addView(label("Запускает явно выбранные агентом команды под UID shell, если Shizuku запущен и разрешение выдано. Это расширяет доступ к системе; включай только если понимаешь последствия. Root при запуске Shizuku через Wireless Debugging не появляется.", 11f, muted, false), withTop(2))
        rememberChoicesCheck = CheckBox(this).apply {
            text = "Запоминать выбор и недавние задачи"
            isChecked = settingsStore.load().rememberChoices
            setTextColor(ink)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        autonomy.addView(rememberChoicesCheck, withTop(12))
        autonomy.addView(label("Контекст последних задач хранится в очищаемом кэше и может отправляться выбранному API при следующих задачах. API-ключ хранится отдельно, зашифрован Android Keystore.", 11f, muted, false), withTop(2))
        parent.addView(autonomy, withTop(12))

        val device = card()
        device.addView(label("Доступ к Android", 16f, ink, true))
        device.addView(label(if (AgentAccessibilityService.active != null) "Accessibility подключён" else "Accessibility пока выключен", 14f, if (AgentAccessibilityService.active != null) accent else warning, true), withTop(8))
        device.addView(label("Чтобы агент мог читать текущий экран, нажимать элементы и вводить текст, включи ORBIT в системных настройках специальных возможностей. Это действие требует ручного разрешения Android. Текст видимого экрана может быть отправлен выбранному API.", 11f, muted, false), withTop(6))
        device.addView(actionButton("Открыть настройки Accessibility", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        device.addView(label("Рабочая папка", 11f, muted, true), withTop(16))
        device.addView(label(workspace.root.absolutePath, 10f, ink, false), withTop(4))
        device.addView(label(ShizukuSupport.status(), 12f, if (ShizukuSupport.hasPermission()) Color.rgb(34, 113, 65) else muted, false), withTop(16))
        device.addView(actionButton("Подключить / разрешить Shizuku", false) { requestShizukuAccess() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        device.addView(actionButton("Открыть настройки ассистента по умолчанию", false) { requestAssistantRole() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        device.addView(label(if (isAssistantRoleHeld()) "ORBIT выбран ассистентом по умолчанию." else "ORBIT можно выбрать системным ассистентом. На некоторых оболочках список ролей может отличаться.", 11f, muted, false), withTop(6))
        parent.addView(device, withTop(12))
        parent.addView(label("Модель и история работают на выбранном API. Нативная библиотека используется для валидации путей и имён проектов; Kotlin-код остаётся внутри DEX.", 11f, muted, false), withTop(18))
    }

    private fun sectionLabel(title: String, right: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 10f, accent, true).apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(right, 9f, muted, true))
        return row
    }

    private fun metric(title: String, value: String, color: Int): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(title, 9f, muted, true).apply { letterSpacing = 0.08f })
        column.addView(label(value, 14f, color, true), withTop(4))
        return column
    }

    private fun fieldLabel(text: String): TextView = label(text, 11f, muted, true)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(surface, 14f, Color.rgb(229, 232, 238), 1f)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.START
        includeFontPadding = true
        setLineSpacing(dp(2).toFloat(), 1.0f)
        linksClickable = true
    }

    private fun editField(hint: String, multiline: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 13f
        setTextColor(ink)
        setHintTextColor(Color.rgb(137, 145, 158))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.WHITE, 10f, Color.rgb(215, 220, 229), 1f)
        minHeight = dp(if (multiline) 108 else 46)
        gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
        } else {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
    }

    private fun EditText.setOnTextChanged(callback: (String) -> Unit) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { callback(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun actionButton(text: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else ink)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = if (primary) rounded(accent, 10f, accent, 1f) else rounded(surfaceRaised, 10f, Color.rgb(221, 226, 235), 1f)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int, strokeWidth: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpF(radiusDp)
        if (strokeWidth > 0f) setStroke(dp(strokeWidth.toInt().coerceAtLeast(1)), stroke)
    }

    private fun spacer(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spacerWidth(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun withTop(top: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    private fun startTask() {
        if (taskRunning) {
            Toast.makeText(this, "Агент уже выполняет задачу.", Toast.LENGTH_SHORT).show()
            return
        }
        val task = taskDraft.trim()
        if (task.equals("/help", ignoreCase = true)) {
            lastResult = "Команды: /goosearch123 запрос — веб-поиск; /creagile333 описание — создать проект/файлы; /zip333 путь — упаковать в ZIP; /openapp333 имя — запустить приложение; /screen333 — прочитать активный экран (нужен Accessibility). Можно также писать обычным языком."
            statusText = "Справка по командам"
            render()
            return
        }
        if (task.isBlank()) {
            Toast.makeText(this, "Сначала опиши задачу.", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = settingsStore.load()
        if (settings.endpoint.isBlank() || settings.model.isBlank()) {
            Toast.makeText(this, "Заполни API endpoint и модель в настройках.", Toast.LENGTH_LONG).show()
            screen = Screen.SETTINGS
            render()
            return
        }
        approveAllForTask = false
        val projectPath = try { workspace.beginProject(task) } catch (e: Exception) {
            Toast.makeText(this, "Не удалось создать проект: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        currentDirectoryRel = projectPath
        taskRunning = true
        statusText = "Проект $projectPath · подготовка…"
        activeTaskName = task
        val taskForAgent = expandTaskAlias(task)
        lastResult = ""
        render()
        try {
            startService(Intent(this, AgentKeepAliveService::class.java).putExtra("message", task.take(100)))
        } catch (_: Exception) { }

        val engine = AgentEngine(
            settings = settings,
            apiKey = secrets.readApiKey(),
            memoryContext = buildMemoryContext(settings.rememberChoices),
            approveAndExecute = { name, args -> executeToolWithApproval(name, args, settings) },
            onStatus = { newStatus ->
                statusText = newStatus
                mainHandler.post { statusValueView?.text = newStatus }
                try {
                    startService(Intent(this, AgentKeepAliveService::class.java).putExtra("message", newStatus))
                } catch (_: Exception) { }
            }
        )
        activeEngine = engine
        currentTask = worker.submit {
            var finalText = ""
            var finalStatus = "Готово"
            try {
                finalText = engine.runTask(taskForAgent)
                finalStatus = "Задача завершена"
            } catch (e: InterruptedException) {
                finalText = "Выполнение остановлено.\n\n${e.message ?: "Пользователь остановил задачу."}"
                finalStatus = "Остановлено"
            } catch (e: Exception) {
                finalText = "Ошибка выполнения: ${e.message ?: e.javaClass.simpleName}"
                finalStatus = "Ошибка"
            } finally {
                val result = finalText
                val state = finalStatus
                mainHandler.post {
                    lastResult = result
                    statusText = state
                    taskRunning = false
                    activeEngine = null
                    currentTask = null
                    stopService(Intent(this, AgentKeepAliveService::class.java))
                    saveTaskMemory(task, result, state)
                    if (screen == Screen.HOME) render() else Toast.makeText(this, state, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopTask() {
        if (!taskRunning) {
            Toast.makeText(this, "Активной задачи нет.", Toast.LENGTH_SHORT).show()
            return
        }
        statusText = "Запрошена остановка…"
        activeEngine?.stop()
        currentTask?.cancel(true)
        statusValueView?.text = statusText
    }

    private fun executeToolWithApproval(name: String, args: JSONObject, settings: AgentSettings): String {
        if (!settings.autoApprove && !approveAllForTask) {
            val latch = CountDownLatch(1)
            val answer = AtomicReference(Approval.DENY)
            mainHandler.post {
                if (isFinishing || isDestroyed) {
                    answer.set(Approval.DENY)
                    latch.countDown()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Разрешить действие агента?")
                        .setMessage("Инструмент: $name\n\nПараметры:\n${args.toString(2).take(1200)}\n\nВыполнение происходит без root, в пределах доступных приложению прав.")
                        .setPositiveButton("Разрешить") { _, _ -> answer.set(Approval.ALLOW); latch.countDown() }
                        .setNeutralButton("Разрешить все до конца задачи") { _, _ -> answer.set(Approval.ALLOW_ALL); latch.countDown() }
                        .setNegativeButton("Отклонить") { _, _ -> answer.set(Approval.DENY); latch.countDown() }
                        .setOnCancelListener { answer.set(Approval.DENY); latch.countDown() }
                        .show()
                }
            }
            if (!latch.await(10, TimeUnit.MINUTES)) return "Отклонено: подтверждение не получено за 10 минут."
            when (answer.get()) {
                Approval.ALLOW -> Unit
                Approval.ALLOW_ALL -> approveAllForTask = true
                Approval.DENY -> return "Пользователь отклонил действие $name. Продолжай другим безопасным способом или объясни ограничение."
            }
        }
        if (name == "external_save_as") return requestExternalWriteFromAgent(args)
        if (name == "external_delete_selected") return requestExternalDeleteFromAgent(args.optString("path", "не указан"))
        if (name.startsWith("external_") && !workspace.hasExternalFolder()) {
            when (name) {
                "external_write" -> return requestExternalWriteFromAgent(args)
                "external_delete" -> return requestExternalDeleteFromAgent(args.optString("path", "не указан"))
                "external_folder_info" -> return toolsExecute(name, args, settings)
            }
            val accessResult = requestExternalFolderFromAgent()
            if (accessResult != null) return accessResult
        }
        return toolsExecute(name, args, settings)
    }

    private fun toolsExecute(name: String, args: JSONObject, settings: AgentSettings): String {
        return workspace.execute(name, args, settings.allowCommands, settings.allowShizukuCommands)
    }

    private fun saveSettings(showToast: Boolean) {
        val current = settingsStore.load()
        val endpoint = endpointField?.text?.toString()?.trim() ?: current.endpoint
        val model = modelField?.text?.toString()?.trim() ?: current.model
        val key = apiKeyField?.text?.toString().orEmpty().trim()
        val prompt = promptField?.text?.toString()?.trim() ?: current.systemPrompt
        val stepCount = maxStepsField?.text?.toString()?.toIntOrNull()?.coerceIn(2, 20) ?: current.maxSteps
        val next = AgentSettings(
            endpoint = endpoint,
            model = model,
            systemPrompt = prompt,
            autoApprove = autoApproveCheck?.isChecked ?: current.autoApprove,
            allowCommands = allowCommandsCheck?.isChecked ?: current.allowCommands,
            rememberChoices = rememberChoicesCheck?.isChecked ?: current.rememberChoices,
            allowShizukuCommands = allowShizukuCheck?.isChecked ?: current.allowShizukuCommands,
            maxSteps = stepCount
        )
        try {
            ApiClient.normalizeEndpoint(next.endpoint)
            if (next.model.isBlank()) throw IllegalArgumentException("Укажи ID модели.")
            settingsStore.save(next)
            if (key.isNotBlank()) secrets.saveApiKey(key)
            apiKeyField?.setText("")
            settingsFeedbackView?.text = "Настройки сохранены. ${if (key.isNotBlank()) "API-ключ обновлён." else "API-ключ не менялся."}"
            if (!next.rememberChoices) {
                history.clear()
                memoryFile.delete()
            }
            if (showToast) Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            settingsFeedbackView?.setTextColor(danger)
            settingsFeedbackView?.text = "Ошибка: ${e.message}"
            if (showToast) Toast.makeText(this, "Не удалось сохранить: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun testApi() {
        saveSettings(false)
        val settings = settingsStore.load()
        if (endpointField?.text.isNullOrBlank() || modelField?.text.isNullOrBlank()) {
            settingsFeedbackView?.text = "Сначала укажи endpoint и модель."
            return
        }
        settingsFeedbackView?.setTextColor(violet)
        settingsFeedbackView?.text = "Проверяю подключение…"
        val keyNow = secrets.readApiKey()
        worker.submit {
            val result = try {
                ApiClient(settings.endpoint, keyNow, settings.model).testConnection()
            } catch (e: Exception) {
                "Ошибка подключения: ${e.message ?: e.javaClass.simpleName}"
            }
            mainHandler.post {
                settingsFeedbackView?.setTextColor(if (result.startsWith("Подключение работает")) accent else danger)
                settingsFeedbackView?.text = result
            }
        }
    }



    private fun expandTaskAlias(input: String): String {
        val firstSpace = input.indexOf(' ')
        val command = if (firstSpace < 0) input.lowercase() else input.substring(0, firstSpace).lowercase()
        val argument = if (firstSpace < 0) "" else input.substring(firstSpace + 1).trim()
        return when (command) {
            "/goosearch123" -> "Выполни веб-поиск по запросу: $argument. Используй web_search, при необходимости web_fetch, сверяй несколько результатов и подведи итог со ссылками."
            "/creagile333" -> "Создай рабочий проект или файлы по этому описанию: $argument. Сначала составь структуру, создай файлы, затем перечитай и проверь результат."
            "/zip333" -> "Создай ZIP-архив для пути в рабочем хранилище: $argument. Посмотри содержимое, выбери правильный путь и проверь готовый архив."
            "/openapp333" -> "Запусти установленное Android-приложение: $argument. Используй launch_app и сообщи результат."
            "/screen333" -> "Прочитай активный экран Android с помощью screen_read и кратко объясни, что на нём видно. Не нажимай ничего без необходимости."
            else -> input
        }
    }

    private fun buildMemoryContext(enabled: Boolean): String {
        if (!enabled || history.isEmpty()) return ""
        return history.take(4).joinToString("\n\n") { item ->
            "Задача: ${item.optString("task", "").take(500)}\nРезультат: ${item.optString("result", "").take(900)}"
        }.take(6000)
    }

    private fun loadMemory() {
        if (!settingsStore.load().rememberChoices || !memoryFile.exists()) return
        try {
            val array = JSONArray(memoryFile.readText())
            for (i in 0 until minOf(array.length(), 12)) {
                array.optJSONObject(i)?.let { history.add(it) }
            }
            if (history.isNotEmpty()) {
                val latest = history.first()
                lastResult = latest.optString("result", "")
            }
        } catch (_: Exception) { }
    }

    private fun saveTaskMemory(task: String, result: String, state: String) {
        if (!settingsStore.load().rememberChoices) return
        val record = JSONObject()
            .put("task", task)
            .put("result", result)
            .put("status", state)
            .put("time", System.currentTimeMillis())
        history.add(0, record)
        while (history.size > 12) history.removeAt(history.lastIndex)
        try {
            memoryFile.parentFile?.mkdirs()
            memoryFile.writeText(JSONArray(history).toString(), Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    private var pendingExport: File? = null

    @Deprecated("Legacy document-picker callback is used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_INPUT_REQUEST) {
            if (resultCode == RESULT_OK) {
                val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) {
                    taskDraft = if (taskDraft.isBlank()) spoken else "$taskDraft\n$spoken"
                    screen = Screen.HOME
                    render()
                    taskInputView?.setSelection(taskInputView?.text?.length ?: 0)
                }
            }
            return
        }
        if (requestCode == INPUT_FILES_REQUEST) {
            if (resultCode == RESULT_OK) {
                try {
                    Toast.makeText(this, importSelectedUris(data), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            if (screen == Screen.FILES) render()
            return
        }
        if (requestCode == AGENT_SAVE_REQUEST) {
            val pending = pendingAgentWrite
            pendingAgentWrite = null
            try {
                val uri = if (resultCode == RESULT_OK) data?.data else null
                if (pending == null) return
                if (uri == null) {
                    pending.result.set("Пользователь отменил сохранение файла.")
                } else {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pending.content) }
                        ?: throw IllegalStateException("Android не открыл файл для записи.")
                    val savedName = runCatching { DocumentFile.fromSingleUri(this, uri)?.name }.getOrNull() ?: pending.fileName
                    pending.result.set("Файл сохранён через системный диалог: $savedName")
                }
            } catch (e: Exception) {
                pending?.result?.set("Ошибка сохранения файла: ${e.message}")
            } finally {
                pending?.latch?.countDown()
            }
            return
        }
        if (requestCode == AGENT_DELETE_REQUEST) {
            val pending = pendingAgentDelete
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (pending == null) return
            if (uri == null) {
                pendingAgentDelete = null
                pending.result.set("Пользователь отменил выбор файла для удаления.")
                pending.latch.countDown()
                return
            }
            val selectedName = runCatching { DocumentFile.fromSingleUri(this, uri)?.name }.getOrNull() ?: uri.lastPathSegment ?: "выбранный файл"
            AlertDialog.Builder(this)
                .setTitle("Подтвердить удаление файла?")
                .setMessage("Запрошено: ${pending.requestedPath}\nВыбрано в системном окне: $selectedName\n\nУдалить именно выбранный файл? Отменить удаление нельзя.")
                .setNegativeButton("Отмена") { _, _ ->
                    pending.result.set("Удаление отменено пользователем.")
                    pendingAgentDelete = null
                    pending.latch.countDown()
                }
                .setPositiveButton("Удалить выбранный файл") { _, _ ->
                    try {
                        val deleted = DocumentsContract.deleteDocument(contentResolver, uri)
                        pending.result.set(if (deleted) "Удалён выбранный файл: $selectedName" else "Поставщик документов не смог удалить: $selectedName")
                    } catch (e: Exception) {
                        pending.result.set("Не удалось удалить $selectedName: ${e.message}")
                    } finally {
                        pendingAgentDelete = null
                        pending.latch.countDown()
                    }
                }
                .setOnCancelListener {
                    pending.result.set("Удаление отменено пользователем.")
                    pendingAgentDelete = null
                    pending.latch.countDown()
                }
                .show()
            return
        }
        if (requestCode == EXTERNAL_TREE_REQUEST) {
            try {
                val treeUri = if (resultCode == RESULT_OK) data?.data else null
                if (treeUri != null) {
                    val grantedFlags = (data?.flags ?: 0) and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(treeUri, grantedFlags)
                    workspace.setExternalFolder(treeUri)
                    Toast.makeText(this, "Доступ к папке сохранён: ${workspace.selectedExternalFolderName()}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Папка не выбрана", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось сохранить доступ: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                pendingExternalFolderLatch?.countDown()
                pendingExternalFolderLatch = null
                if (!isFinishing && screen == Screen.FILES) render()
            }
            return
        }
        if (requestCode == ASSISTANT_ROLE_REQUEST) {
            Toast.makeText(this, if (isAssistantRoleHeld()) "ORBIT выбран ассистентом" else "Роль ассистента не назначена", Toast.LENGTH_LONG).show()
            if (screen == Screen.SETTINGS) render()
            return
        }
        if (requestCode != EXPORT_REQUEST) return
        val source = pendingExport
        val deleteTemp = pendingExportIsTemp
        pendingExport = null
        pendingExportIsTemp = false
        val target = data?.data
        try {
            if (resultCode == RESULT_OK && target != null && source != null) {
                contentResolver.openOutputStream(target, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("Android не открыл файл для записи.")
                Toast.makeText(this, "Файл сохранён", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            if (deleteTemp) source?.delete()
        }
    }

    private fun renderSettingsRefreshStatus() {
        // Re-check the cached status without rebuilding every field (which would erase unsaved edits).
        settingsFeedbackView?.text = if (secrets.hasApiKey()) "API-ключ удалён/обновлён. Проверь статус выше." else "API-ключ не сохранён."
    }

    private fun humanBytes(size: Long): String = when {
        size < 1024L -> "$size B"
        size < 1024L * 1024 -> "%.1f KiB".format(size / 1024.0)
        size < 1024L * 1024L * 1024L -> "%.1f MiB".format(size / (1024.0 * 1024.0))
        else -> "%.2f GiB".format(size / (1024.0 * 1024.0 * 1024.0))
    }

    companion object {
        private const val EXPORT_REQUEST = 4201
        private const val EXTERNAL_TREE_REQUEST = 4202
        private const val ASSISTANT_ROLE_REQUEST = 4203
        private const val AGENT_SAVE_REQUEST = 4204
        private const val AGENT_DELETE_REQUEST = 4205
        private const val VOICE_INPUT_REQUEST = 4206
        private const val INPUT_FILES_REQUEST = 4207
    }
}
