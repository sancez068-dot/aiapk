package ai.orbit.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

    private val ink = Color.rgb(242, 246, 255)
    private val muted = Color.rgb(151, 163, 187)
    private val bgColor = Color.rgb(11, 16, 32)
    private val surface = Color.rgb(20, 28, 48)
    private val surfaceRaised = Color.rgb(26, 36, 60)
    private val accent = Color.rgb(118, 231, 215)
    private val violet = Color.rgb(140, 155, 255)
    private val warning = Color.rgb(255, 190, 105)
    private val danger = Color.rgb(255, 125, 140)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        settingsStore = SettingsStore(this)
        secrets = SecureSecretStore(this)
        workspace = WorkspaceTools(this)
        loadMemory()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            activeEngine?.stop()
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
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(76)))
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
        texts.addView(label("PERSONAL AI AGENT", 9f, muted, true).apply { letterSpacing = 0.12f })
        bar.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val state = TextView(this).apply {
            text = if (taskRunning) "● WORKING" else "● LOCAL CORE"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (taskRunning) warning else accent)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(surfaceRaised, 24f, if (taskRunning) warning else accent, 1f)
        }
        bar.addView(state)
        return bar
    }

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(14, 21, 39), 18f, Color.rgb(33, 44, 70), 1f)
        }
        nav.addView(navItem("⌂", "Задачи", Screen.HOME), LinearLayout.LayoutParams(0, -1, 1f))
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
            background = if (selected) rounded(Color.rgb(32, 53, 68), 13f, Color.TRANSPARENT, 0f) else null
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
        parent.addView(sectionLabel("AGENT CONSOLE", "01 / RUN"))
        val hero = card()
        hero.addView(label("Передай задачу.\nЗабери результат.", 27f, ink, true))
        hero.addView(spacer(9))
        hero.addView(label("ORBIT планирует действия, вызывает инструменты и проверяет, что получилось.", 13f, muted, false))
        hero.addView(spacer(14))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stats.addView(metric("WORKSPACE", humanBytes(workspace.workspaceBytes()), accent), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(metric("TOOL CALLS", "API + device", violet), LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(stats)
        parent.addView(hero, withTop(12))

        parent.addView(label("Твоя задача", 19f, ink, true), withTop(22))
        parent.addView(label("Напиши обычным языком. Агент сам выберет инструменты.", 12f, muted, false), withTop(4))
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
        parent.addView(sectionLabel("WORKSPACE", "02 / FILES"))
        parent.addView(label("Рабочее хранилище", 25f, ink, true), withTop(14))
        parent.addView(label("Размер меняется по задаче. Проекты хранятся отдельно от кэша истории и настроек.", 12f, muted, false), withTop(4))
        val info = card().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        info.addView(label("РАЗМЕР РАБОЧИХ ФАЙЛОВ", 10f, accent, true))
        info.addView(label(humanBytes(workspace.workspaceBytes()), 30f, ink, true), withTop(6))
        info.addView(label("${workspace.root.walkTopDown().count { it.isFile }} файлов", 12f, muted, false), withTop(2))
        info.addView(label(workspace.root.absolutePath, 10f, muted, false), withTop(8))
        info.addView(actionButton("Экспортировать всё в ZIP", true) {
            try {
                pendingExport = workspace.createWorkspaceArchive()
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, "orbit-workspace.zip")
                }
                startActivityForResult(intent, EXPORT_REQUEST)
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось создать ZIP: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        parent.addView(info, withTop(12))

        parent.addView(label("Содержимое корня", 17f, ink, true), withTop(22))
        val files = workspace.root.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        if (files.isEmpty()) {
            val empty = card()
            empty.addView(label("Здесь пока пусто", 14f, ink, true))
            empty.addView(label("Попроси агента создать файл или проект — результат появится здесь.", 12f, muted, false), withTop(6))
            parent.addView(empty, withTop(8))
        } else {
            files.take(60).forEach { file ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    background = rounded(surface, 13f, Color.rgb(37, 49, 75), 1f)
                }
                val symbol = label(if (file.isDirectory) "▰" else "▤", 20f, if (file.isDirectory) violet else accent, true)
                row.addView(symbol, LinearLayout.LayoutParams(dp(32), -2))
                val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                column.addView(label(file.name, 13f, ink, true))
                column.addView(label(if (file.isDirectory) "Папка" else humanBytes(file.length()), 10f, muted, false), withTop(3))
                row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
                parent.addView(row, withTop(7))
            }
            if (files.size > 60) parent.addView(label("Показаны первые 60 элементов.", 10f, muted, false), withTop(6))
        }
        parent.addView(label("Примечание: рабочая папка находится в app-specific storage Android. Для переноса файлов используй экспорт ZIP.", 11f, muted, false), withTop(18))
    }

    private fun buildSettings(parent: LinearLayout) {
        parent.addView(sectionLabel("CONFIGURATION", "03 / SETTINGS"))
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
        device.addView(label("УПРАВЛЕНИЕ ANDROID · БЕЗ ROOT", 10f, accent, true).apply { letterSpacing = 0.1f })
        device.addView(label(if (AgentAccessibilityService.active != null) "Accessibility подключён" else "Accessibility пока выключен", 14f, if (AgentAccessibilityService.active != null) accent else warning, true), withTop(8))
        device.addView(label("Чтобы агент мог читать текущий экран, нажимать элементы и вводить текст, включи ORBIT в системных настройках специальных возможностей. Это действие требует ручного разрешения Android. Текст видимого экрана может быть отправлен выбранному API.", 11f, muted, false), withTop(6))
        device.addView(actionButton("Открыть настройки Accessibility", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        device.addView(label("Рабочая папка", 11f, muted, true), withTop(16))
        device.addView(label(workspace.root.absolutePath, 10f, ink, false), withTop(4))
        parent.addView(device, withTop(12))
        parent.addView(label("Вся работа с моделью выполняется напрямую из APK. ORBIT не использует встроенный сервер или общий API-ключ разработчика.", 11f, muted, false), withTop(18))
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
        background = rounded(surface, 18f, Color.rgb(38, 49, 74), 1f)
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
        setHintTextColor(Color.rgb(112, 125, 151))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = rounded(Color.rgb(12, 19, 36), 12f, Color.rgb(47, 60, 88), 1f)
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
        setTextColor(if (primary) Color.rgb(8, 25, 31) else ink)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = if (primary) rounded(accent, 13f, accent, 1f) else rounded(surfaceRaised, 13f, Color.rgb(65, 79, 111), 1f)
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
        taskRunning = true
        statusText = "Подготовка к задаче…"
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
        return toolsExecute(name, args, settings.allowCommands)
    }

    private fun toolsExecute(name: String, args: JSONObject, allowCommands: Boolean): String {
        return workspace.execute(name, args, allowCommands)
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
        if (requestCode != EXPORT_REQUEST) return
        val source = pendingExport
        pendingExport = null
        val target = data?.data
        if (resultCode != RESULT_OK || target == null || source == null) return
        try {
            contentResolver.openOutputStream(target, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
            Toast.makeText(this, "ZIP сохранён", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            source.delete()
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
    }
}
