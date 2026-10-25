package ai.orbit.agent

import org.json.JSONArray
import org.json.JSONObject

class AgentEngine(
    private val settings: AgentSettings,
    private val apiKey: String,
    private val memoryContext: String = "",
    private val approveAndExecute: (String, JSONObject) -> String,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var stopped = false

    fun stop() { stopped = true }

    fun runTask(task: String): String {
        require(task.isNotBlank()) { "Задача пустая." }
        val endpoint = ApiClient.normalizeEndpoint(settings.endpoint)
        require(settings.model.isNotBlank()) { "Укажи модель в настройках API." }
        val api = ApiClient(endpoint, apiKey, settings.model)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", buildSystemPrompt()))
        messages.put(JSONObject().put("role", "user").put("content", task.trim()))
        val toolDefinitions = buildTools(settings.allowCommands)
        var toolCount = 0

        for (step in 1..settings.maxSteps.coerceIn(2, 20)) {
            checkNotStopped()
            onStatus("Шаг $step/${settings.maxSteps}: запрос к модели")
            val response = api.complete(messages, toolDefinitions, "auto")
            val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("API не вернул choices[0].message. Проверь формат endpoint и модель.")
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val content = extractContent(message)
                return content.ifBlank { "Модель завершила ответ без текста. Посмотри журнал ответа API и доступность инструментов." }
            }

            messages.put(message)
            for (index in 0 until calls.length()) {
                checkNotStopped()
                val call = calls.optJSONObject(index) ?: continue
                val id = call.optString("id", "call_${step}_$index")
                val function = call.optJSONObject("function")
                val name = function?.optString("name").orEmpty()
                val rawArgs = function?.optString("arguments", "{}") ?: "{}"
                if (name.isBlank()) {
                    messages.put(toolResult(id, "Ошибка: модель вернула вызов без имени инструмента."))
                    continue
                }
                val args = try { JSONObject(rawArgs) } catch (_: Exception) {
                    JSONObject().put("_parse_error", true).put("raw_arguments", rawArgs.take(1000))
                }
                onStatus("Инструмент: $name")
                val result = try {
                    if (args.optBoolean("_parse_error")) "Неверный JSON аргументов инструмента: ${args.optString("raw_arguments")}"
                    else approveAndExecute(name, args)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Выполнение остановлено пользователем.")
                } catch (e: Exception) {
                    "Ошибка выполнения $name: ${(e.message ?: e.javaClass.simpleName).take(700)}"
                }
                messages.put(toolResult(id, result.take(18000)))
                toolCount++
            }
        }

        checkNotStopped()
        onStatus("Лимит шагов достигнут: формирую промежуточный отчёт")
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                "Достигнут лимит инструментальных шагов. Не вызывай новые инструменты. Подведи итог: что сделано, что проверено, какие файлы созданы и что осталось. Количество вызовов инструментов: $toolCount."
            )
        )
        val finalResponse = api.complete(messages)
        val finalMessage = finalResponse.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        return extractContent(finalMessage ?: JSONObject()).ifBlank {
            "Работа остановлена после достижения лимита шагов. Вызовов инструментов: $toolCount. Проверь файлы в рабочей папке."
        }
    }

    private fun checkNotStopped() {
        if (stopped || Thread.currentThread().isInterrupted) throw InterruptedException("Stopped")
    }

    private fun buildSystemPrompt(): String {
        val base = """
            Ты ORBIT — агент, который выполняет задачи с помощью предоставленных инструментов, а не просто советует.
            Рабочая папка доступна через инструменты workspace_*. Все переданные пути относительны к ней.
            Сначала уточни цель внутри себя и составь краткий план, затем выполняй работу инструментами.
            Для фактов из интернета используй web_search/web_fetch; для файлов — workspace_list/read/write.
            После изменения файлов перечитай или проверь их подходящим инструментом. Не утверждай, что сделал то, чего инструмент не подтвердил.
            Для сборки проектов используй run_command, если инструмент доступен, но учитывай, что Android может не содержать компиляторы и SDK.
            Инструменты screen_read/ui_* работают только после того, как пользователь вручную включил Accessibility для ORBIT.
            Никогда не проси пользователя отправлять API-ключ в текст задачи. Не выводи секреты и не копируй их в файлы.
            Если нужен рискованный шаг или удаление — объясни его кратко; пользовательские настройки согласия определяют, нужно ли отдельное подтверждение.
            Если чего-то не можешь сделать в текущей среде, назови ограничение и предложи реально выполнимый следующий шаг.
            Отвечай на языке пользователя. Финальный ответ должен сообщать результат, созданные файлы и проверки.
        """.trimIndent()
        val withInstructions = if (settings.systemPrompt.isBlank()) base else "$base\n\nДополнительные инструкции пользователя:\n${settings.systemPrompt.take(6000)}"
        return if (memoryContext.isBlank()) withInstructions else "$withInstructions\n\nЛокальный контекст из кэша прошлых задач (используй только если релевантно):\n${memoryContext.take(6000)}"
    }

    private fun toolResult(id: String, content: String): JSONObject = JSONObject()
        .put("role", "tool")
        .put("tool_call_id", id)
        .put("content", content)

    private fun extractContent(message: JSONObject): String {
        val content = message.opt("content")
        return when (content) {
            is String -> content.trim()
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val text = part.optString("text")
                    if (text.isNotBlank()) append(text).append('\n')
                }
            }.trim()
            else -> ""
        }
    }

    private fun buildTools(includeCommand: Boolean): JSONArray {
        val array = JSONArray()
        fun add(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()) {
            val parameters = JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)
            if (required.isNotEmpty()) parameters.put("required", JSONArray(required))
            array.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", name).put("description", description).put("parameters", parameters)))
        }
        fun stringProp(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)
        fun intProp(description: String): JSONObject = JSONObject().put("type", "integer").put("description", description)
        fun boolProp(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)

        add("workspace_info", "Показывает путь, объём и количество файлов рабочего хранилища.", JSONObject())
        add("workspace_list", "Показывает файлы и папки в рабочей папке или её подпапке.", JSONObject().put("path", stringProp("Относительный путь к папке; пустая строка — корень.")))
        add("workspace_read", "Читает текстовый файл из рабочей папки.", JSONObject()
            .put("path", stringProp("Относительный путь к файлу."))
            .put("max_chars", intProp("Максимум символов, 500–20000.")), listOf("path"))
        add("workspace_write", "Создаёт или перезаписывает UTF-8 файл; родительские папки создаются автоматически.", JSONObject()
            .put("path", stringProp("Относительный путь к файлу."))
            .put("content", stringProp("Полное содержимое файла."))
            .put("append", boolProp("true — добавить в конец, false — перезаписать.")), listOf("path", "content"))
        add("workspace_mkdir", "Создаёт папку в рабочем хранилище.", JSONObject().put("path", stringProp("Относительный путь к папке.")), listOf("path"))
        add("workspace_delete", "Удаляет файл или подпапку внутри рабочей папки. Не используй для корня.", JSONObject().put("path", stringProp("Относительный путь удаляемого файла/каталога.")), listOf("path"))
        add("workspace_zip", "Создаёт ZIP из файла или папки. Архив должен находиться рядом с исходной папкой, не внутри неё.", JSONObject()
            .put("path", stringProp("Относительный путь к файлу или папке."))
            .put("output", stringProp("Необязательный относительный путь выходного ZIP.")), listOf("path"))
        add("workspace_unzip", "Распаковывает ZIP в рабочую папку с защитой от zip-slip.", JSONObject()
            .put("archive", stringProp("Относительный путь к ZIP."))
            .put("destination", stringProp("Необязательная целевая папка.")), listOf("archive"))
        add("web_search", "Ищет публичные веб-страницы через DuckDuckGo HTML и возвращает заголовки/URL.", JSONObject().put("query", stringProp("Поисковый запрос.")), listOf("query"))
        add("web_fetch", "Загружает публичный HTTPS URL и возвращает текст страницы; localhost и частные IP блокируются.", JSONObject()
            .put("url", stringProp("Полный URL http(s)."))
            .put("max_chars", intProp("Максимум текста: 1000–20000.")), listOf("url"))
        if (includeCommand) {
            add("run_command", "Запускает команду через /system/bin/sh с рабочей папкой ORBIT как cwd; таймаут 60 секунд. Это НЕ полноценная VM.",
                JSONObject().put("command", stringProp("Команда shell. Используй относительные пути и не пытайся менять систему.")), listOf("command"))
        }
        add("open_url", "Открывает публичный HTTPS URL системным обработчиком Android.", JSONObject().put("url", stringProp("URL для открытия.")), listOf("url"))
        add("launch_app", "Запускает установленное приложение по названию или package name.", JSONObject().put("app", stringProp("Название приложения или пакет, например org.telegram.messenger.")), listOf("app"))
        add("screen_read", "Читает текст активного окна Android. Передача содержимого экрана модели может раскрыть личные данные.", JSONObject())
        add("ui_click", "Нажимает видимый элемент по точному/частичному тексту.", JSONObject().put("text", stringProp("Текст элемента на экране.")), listOf("text"))
        add("ui_type", "Вводит текст в сфокусированное или найденное редактируемое поле.", JSONObject().put("text", stringProp("Текст для ввода.")), listOf("text"))
        add("ui_scroll", "Прокручивает текущий экран; direction=down/up.", JSONObject().put("direction", stringProp("down или up.")))
        add("ui_back", "Выполняет системное действие Android «Назад».", JSONObject())
        add("ui_tap", "Отправляет одиночное касание в координаты экрана; используй только после screen_read.", JSONObject()
            .put("x", intProp("X в экранных пикселях."))
            .put("y", intProp("Y в экранных пикселях.")), listOf("x", "y"))
        return array
    }
}
