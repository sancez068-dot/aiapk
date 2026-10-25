package ai.orbit.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WorkspaceTools(private val context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "orbit_workspace").canonicalFile
    private val cacheTmp = File(context.cacheDir, "orbit_tmp").apply { mkdirs() }
    private val externalPrefs = context.getSharedPreferences("orbit_external_access", Context.MODE_PRIVATE)
    @Volatile private var activeProjectRel: String = "workflow/project-session"

    init {
        root.mkdirs()
        File(root, "workflow").mkdirs()
        File(root, activeProjectRel).mkdirs()
    }

    fun beginProject(task: String): String {
        val workflow = File(root, "workflow").apply { mkdirs() }
        val slug = NativeCore.projectSlug(task.take(240))
        val baseName = "project-$slug"
        var target = File(workflow, baseName)
        var suffix = 2
        while (target.exists()) {
            target = File(workflow, "$baseName-$suffix")
            suffix++
        }
        require(target.mkdirs()) { "Не удалось создать отдельную папку проекта: ${target.name}" }
        activeProjectRel = "workflow/${target.name}"
        return activeProjectRel
    }

    fun currentProjectPath(): String = activeProjectRel

    fun hasExternalFolder(): Boolean {
        val folder = externalRoot() ?: return false
        return runCatching { folder.exists() && folder.canRead() && folder.canWrite() }.getOrDefault(false)
    }

    fun selectedExternalFolderName(): String {
        val rootDoc = externalRoot()?.takeIf { runCatching { it.exists() && it.canRead() && it.canWrite() }.getOrDefault(false) }
            ?: return "Папка не выбрана или доступ отозван"
        return rootDoc.name ?: "Выбранная папка"
    }

    fun setExternalFolder(uri: Uri) {
        externalPrefs.edit().putString("tree_uri", uri.toString()).apply()
    }

    private fun selectedTreeUri(): Uri? = externalPrefs.getString("tree_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun externalRoot(): DocumentFile? = selectedTreeUri()?.let { DocumentFile.fromTreeUri(context, it) }

    fun execute(name: String, args: JSONObject, allowCommands: Boolean, allowShizukuCommands: Boolean = false): String = try {
        when (name) {
            "workspace_info" -> workspaceInfo()
            "workspace_list" -> listFiles(args.optString("path", ""))
            "workspace_read" -> readFile(args.getString("path"), args.optInt("max_chars", 12000))
            "workspace_write" -> writeFile(args.getString("path"), args.getString("content"), args.optBoolean("append", false))
            "workspace_mkdir" -> makeDirectory(args.getString("path"))
            "workspace_delete" -> deletePath(args.getString("path"))
            "workspace_zip" -> zipPath(args.getString("path"), args.optString("output", ""))
            "workspace_unzip" -> unzipPath(args.getString("archive"), args.optString("destination", ""))
            "external_folder_info" -> externalFolderInfo()
            "external_list" -> externalList(args.optString("path", ""))
            "external_read" -> externalRead(args.getString("path"), args.optInt("max_chars", 12000))
            "external_write" -> externalWrite(args.getString("path"), args.getString("content"), args.optBoolean("append", false))
            "external_mkdir" -> externalMkdir(args.getString("path"))
            "external_delete" -> externalDelete(args.getString("path"))
            "shizuku_command" -> if (allowShizukuCommands) shizukuCommand(args.getString("command")) else
                "Отклонено: команды через Shizuku выключены в настройках ORBIT."
            "web_search" -> webSearch(args.getString("query"))
            "web_fetch" -> webFetch(args.getString("url"), args.optInt("max_chars", 10000))
            "run_command" -> if (allowCommands) runCommand(args.getString("command")) else
                "Отклонено: выполнение команд выключено в Настройках. Включи его отдельно."
            "open_url" -> openUrl(args.getString("url"))
            "launch_app" -> launchApp(args.getString("app"))
            "screen_read" -> AgentAccessibilityService.active?.readVisibleScreen()
                ?: "Accessibility не включён. Открой Настройки агента и разреши ORBIT доступ к экрану."
            "ui_click" -> AgentAccessibilityService.active?.clickText(args.getString("text"))
                ?: "Accessibility не включён. Разреши ORBIT доступ к экрану в системных настройках."
            "ui_type" -> AgentAccessibilityService.active?.typeIntoFocusedField(args.getString("text"))
                ?: "Accessibility не включён. Разреши ORBIT доступ к экрану в системных настройках."
            "ui_scroll" -> AgentAccessibilityService.active?.scroll(args.optString("direction", "down"))
                ?: "Accessibility не включён. Разреши ORBIT доступ к экрану в системных настройках."
            "ui_back" -> AgentAccessibilityService.active?.goBack()
                ?: "Accessibility не включён. Разреши ORBIT доступ к экрану в системных настройках."
            "ui_tap" -> AgentAccessibilityService.active?.tapCoordinates(args.getInt("x"), args.getInt("y"))
                ?: "Accessibility не включён. Разреши ORBIT доступ к экрану в системных настройках."
            else -> "Неизвестный инструмент: $name"
        }
    } catch (e: Exception) {
        "Ошибка инструмента $name: ${(e.message ?: e.javaClass.simpleName).take(700)}"
    }

    fun workspaceBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun createWorkspaceArchive(): File {
        val dest = File(cacheTmp, "orbit-workspace-${System.currentTimeMillis()}.zip")
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return dest
    }

    fun createItemArchive(relativePath: String): File {
        val source = safePath(relativePath)
        require(source.exists()) { "Объект не найден: $relativePath" }
        val dest = File(cacheTmp, "orbit-item-${System.currentTimeMillis()}.zip")
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            if (source.isFile) {
                zip.putNextEntry(ZipEntry(source.name))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            } else {
                source.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = source.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry("${source.name}/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return dest
    }

    private fun workspaceInfo(): String = JSONObject()
        .put("workspace_path", root.absolutePath)
        .put("current_project", activeProjectRel)
        .put("native_library_loaded", NativeCore.isLoaded())
        .put("external_folder", selectedExternalFolderName())
        .put("bytes_used", workspaceBytes())
        .put("human_size", humanBytes(workspaceBytes()))
        .put("files", root.walkTopDown().count { it.isFile })
        .put("storage_policy", "dynamic; no fixed size quota")
        .toString(2)

    private fun listFiles(path: String): String {
        val directory = safePath(taskPath(path))
        if (!directory.exists()) return "Папка не существует: ${rel(directory)}"
        if (!directory.isDirectory) return "Это не папка: ${rel(directory)}"
        val entries = directory.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })).orEmpty()
        val array = JSONArray()
        entries.take(200).forEach { file ->
            array.put(JSONObject()
                .put("name", file.name)
                .put("path", rel(file))
                .put("type", if (file.isDirectory) "directory" else "file")
                .put("bytes", if (file.isFile) file.length() else 0L))
        }
        return JSONObject().put("path", if (path.isBlank()) "." else path).put("entries", array)
            .put("truncated", entries.size > 200).toString(2)
    }

    private fun readFile(path: String, maxChars: Int): String {
        val file = safePath(taskPath(path))
        require(file.exists()) { "Файл не найден: $path" }
        require(file.isFile) { "Путь не является файлом: $path" }
        require(file.length() <= 2_000_000L) { "Файл больше 2 МБ; прочитай его частями или уменьши файл." }
        val text = file.readText(Charsets.UTF_8)
        val cap = maxChars.coerceIn(500, 20000)
        return JSONObject().put("path", rel(file)).put("bytes", file.length())
            .put("content", text.take(cap)).put("truncated", text.length > cap).toString(2)
    }

    private fun writeFile(path: String, content: String, append: Boolean): String {
        require(path.isNotBlank()) { "path не может быть пустым" }
        require(content.length <= 1_000_000) { "Один вызов записи ограничен 1 000 000 символов." }
        val file = safePath(taskPath(path))
        file.parentFile?.mkdirs()
        if (append) file.appendText(content, Charsets.UTF_8) else file.writeText(content, Charsets.UTF_8)
        return JSONObject().put("ok", true).put("path", rel(file)).put("bytes", file.length())
            .put("mode", if (append) "append" else "overwrite").toString()
    }

    private fun makeDirectory(path: String): String {
        val file = safePath(taskPath(path))
        require(file.mkdirs() || file.isDirectory) { "Не удалось создать папку $path" }
        return "Папка готова: ${rel(file)}"
    }

    private fun deletePath(path: String): String {
        require(path.isNotBlank() && path != "." && path != "/") { "Удаление корня workspace запрещено." }
        val file = safePath(taskPath(path))
        require(file != root && file.absolutePath != File(root, "workflow").canonicalPath) { "Удаление корня workspace/workflow запрещено." }
        require(file.exists()) { "Объект не найден: $path" }
        val count = file.walkBottomUp().count()
        require(file.deleteRecursively()) { "Android не смог удалить ${rel(file)}" }
        return "Удалено: ${rel(file)} (объектов: $count)"
    }

    private fun zipPath(path: String, output: String): String {
        val source = safePath(taskPath(path))
        require(source.exists()) { "Источник не найден: $path" }
        val destRel = output.ifBlank {
            if (source.isDirectory) "${source.name.ifBlank { "workspace" }}.zip" else "${source.name}.zip"
        }
        val destination = safePath(taskPath(destRel))
        require(destination != source) { "Архив не может перезаписать исходный файл." }
        require(!(source.isDirectory && destination.canonicalPath.startsWith(source.canonicalPath + File.separator))) {
            "Архив нельзя создавать внутри архивируемой папки. Сохрани ZIP рядом с ней."
        }
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            if (source.isFile) {
                zip.putNextEntry(ZipEntry(source.name))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            } else {
                source.walkTopDown().filter { it.isFile }.forEach { file ->
                    if (file.canonicalFile == destination.canonicalFile) return@forEach
                    val relative = source.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry("${source.name}/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return JSONObject().put("ok", true).put("archive", rel(destination)).put("bytes", destination.length()).toString()
    }

    private fun unzipPath(archivePath: String, destinationPath: String): String {
        val archive = safePath(taskPath(archivePath))
        require(archive.isFile) { "Архив не найден: $archivePath" }
        val destination = safePath(taskPath(destinationPath.ifBlank { archive.nameWithoutExtension }))
        destination.mkdirs()
        var extracted = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(destination, entry.name).canonicalFile
                require(out.path.startsWith(destination.canonicalPath + File.separator) || out == destination) {
                    "Архив содержит путь за пределами целевой папки: ${entry.name}"
                }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { zip.copyTo(it) }
                    extracted++
                }
                zip.closeEntry()
            }
        }
        return JSONObject().put("ok", true).put("destination", rel(destination)).put("files_extracted", extracted).toString()
    }

    private fun externalFolderInfo(): String {
        val folder = externalRoot()?.takeIf { runCatching { it.exists() && it.canRead() }.getOrDefault(false) }
            ?: return JSONObject().put("selected", false)
                .put("message", "Папка общего хранилища не выбрана или доступ отозван. В ORBIT открой Файлы → Выбрать папку; Android покажет системное окно разрешения.").toString(2)
        return JSONObject().put("selected", true).put("name", folder.name ?: "Выбранная папка")
            .put("uri", selectedTreeUri().toString()).put("read", folder.canRead()).put("write", folder.canWrite()).toString(2)
    }

    private fun externalList(path: String): String {
        val directory = externalResolve(path, createParents = false)
            ?: return "Путь не найден внутри выбранной папки: $path"
        require(directory.isDirectory) { "Это не папка: $path" }
        val entries = directory.listFiles().sortedBy { it.name?.lowercase(Locale.ROOT).orEmpty() }
        val array = JSONArray()
        entries.take(200).forEach { item ->
            array.put(JSONObject().put("name", item.name ?: "unnamed")
                .put("type", if (item.isDirectory) "directory" else "file")
                .put("bytes", if (item.isFile) item.length() else 0L))
        }
        return JSONObject().put("folder", path).put("entries", array).put("truncated", entries.size > 200).toString(2)
    }

    private fun externalRead(path: String, maxChars: Int): String {
        val file = externalResolve(path, createParents = false)
            ?: throw IllegalArgumentException("Файл не найден внутри выбранной папки: $path")
        require(file.isFile) { "Это не файл: $path" }
        require(file.length() <= 2_000_000L) { "Файл больше 2 МБ; используйте меньший файл." }
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 2_000_000) { "Файл больше 2 МБ; выбери меньший текстовый файл." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalStateException("Android не открыл файл для чтения.")
        val text = bytes.toString(Charsets.UTF_8)
        val cap = maxChars.coerceIn(500, 20_000)
        return JSONObject().put("path", path).put("bytes", bytes.size).put("content", text.take(cap))
            .put("truncated", text.length > cap).toString(2)
    }

    private fun externalWrite(path: String, content: String, append: Boolean): String {
        require(path.isNotBlank()) { "path не может быть пустым." }
        require(content.length <= 1_000_000) { "Одна запись ограничена 1 000 000 символов." }
        val parts = externalParts(path)
        require(parts.isNotEmpty()) { "Нельзя перезаписать выбранную корневую папку." }
        val rootDoc = externalRoot() ?: return noExternalAccess()
        var parent = rootDoc
        for (part in parts.dropLast(1)) {
            parent = parent.findFile(part) ?: parent.createDirectory(part)
                ?: throw IllegalStateException("Не удалось создать папку: $part")
            require(parent.isDirectory) { "Путь содержит файл вместо папки: $part" }
        }
        val name = parts.last()
        var target = parent.findFile(name)
        if (target?.isDirectory == true) throw IllegalArgumentException("По указанному пути уже существует папка: $path")
        if (target == null) {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            target = parent.createFile(mime, name) ?: throw IllegalStateException("Не удалось создать файл: $path")
        }
        val mode = if (append) "wa" else "wt"
        context.contentResolver.openOutputStream(target.uri, mode)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
            ?: throw IllegalStateException("Android не открыл файл для записи.")
        return JSONObject().put("ok", true).put("path", path).put("bytes", target.length())
            .put("mode", if (append) "append" else "overwrite").toString(2)
    }

    private fun externalMkdir(path: String): String {
        val parts = externalParts(path)
        require(parts.isNotEmpty()) { "Путь к папке пустой." }
        val rootDoc = externalRoot() ?: return noExternalAccess()
        var parent = rootDoc
        for (part in parts) {
            parent = parent.findFile(part) ?: parent.createDirectory(part)
                ?: throw IllegalStateException("Не удалось создать папку: $part")
            require(parent.isDirectory) { "Путь содержит файл вместо папки: $part" }
        }
        return "Папка создана/уже существует: $path"
    }

    private fun externalDelete(path: String): String {
        val parts = externalParts(path)
        require(parts.isNotEmpty()) { "Удалять выбранную корневую папку запрещено." }
        val rootDoc = externalRoot() ?: return noExternalAccess()
        var parent = rootDoc
        for (part in parts.dropLast(1)) {
            parent = parent.findFile(part) ?: throw IllegalArgumentException("Папка не найдена: $part")
            require(parent.isDirectory) { "Это не папка: $part" }
        }
        val target = parent.findFile(parts.last()) ?: throw IllegalArgumentException("Объект не найден: $path")
        require(target.uri != rootDoc.uri) { "Удаление выбранной корневой папки запрещено." }
        require(target.delete()) { "Android не смог удалить: $path" }
        return "Удалено из выбранной папки: $path"
    }

    private fun externalResolve(path: String, createParents: Boolean): DocumentFile? {
        val parts = externalParts(path)
        var current = externalRoot() ?: return null
        for (part in parts) {
            val next = current.findFile(part)
            if (next == null) {
                if (!createParents) return null
                current = current.createDirectory(part) ?: return null
            } else current = next
        }
        return current
    }

    private fun externalParts(path: String): List<String> {
        val normalized = path.trim().replace('\\', '/')
        require(NativeCore.isRelativePathSafe(normalized) && !normalized.startsWith('/')) {
            "Ожидается относительный безопасный путь внутри выбранной папки."
        }
        return normalized.split('/').filter { it.isNotBlank() && it != "." }
    }

    private fun noExternalAccess(): String = "Доступ не выдан. В ORBIT открой вкладку «Файлы» и нажми «Выбрать папку для файлов агента», затем выбери нужную папку в системном окне Android."

    private fun shizukuCommand(command: String): String {
        require(command.isNotBlank()) { "Команда пустая." }
        require(command.length <= 8_000) { "Команда слишком длинная." }
        return ShizukuSupport.execute(context, command, File(root, activeProjectRel).absolutePath)
    }

    private fun webSearch(query: String): String {
        require(query.isNotBlank()) { "Поисковый запрос пустой" }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = fetchText("https://html.duckduckgo.com/html/?q=$encoded", 1_200_000)
        val resultPattern = Pattern.compile("(?is)<a\\b([^>]*class=[\\\"'][^\\\"']*result__a[^\\\"']*[\\\"'][^>]*)>(.*?)</a>")
        val attrPattern = Pattern.compile("(?is)href=[\\\"']([^\\\"']+)[\\\"']")
        val matcher = resultPattern.matcher(html)
        val results = JSONArray()
        var count = 0
        while (matcher.find() && count < 8) {
            val attrs = matcher.group(1).orEmpty()
            val href = attrPattern.matcher(attrs).let { if (it.find()) it.group(1) else "" }
            var title = stripHtml(matcher.group(2).orEmpty())
            var link = decodeHtml(href)
            if (link.contains("uddg=")) {
                val redirect = try { URI(link).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("uddg=") }?.substringAfter("uddg=") } catch (_: Exception) { null }
                if (redirect != null) link = URLDecoder.decode(redirect, "UTF-8")
            }
            if (link.startsWith("http://")) link = "https://" + link.removePrefix("http://")
            if (!link.startsWith("https://")) continue
            if (title.isBlank()) title = link
            results.put(JSONObject().put("title", title.take(240)).put("url", link.take(1400)))
            count++
        }
        if (results.length() == 0) {
            return "Поиск не вернул распознанных результатов. Попробуй другой запрос или используй web_fetch для известного URL."
        }
        return JSONObject().put("query", query).put("results", results).toString(2)
    }

    private fun webFetch(rawUrl: String, maxChars: Int): String {
        val safeUrl = validatePublicUrl(rawUrl)
        val html = fetchText(safeUrl, 1_500_000)
        val text = stripHtml(html).take(maxChars.coerceIn(1000, 20000))
        return JSONObject().put("url", safeUrl).put("content", text).put("truncated", stripHtml(html).length > text.length).toString(2)
    }

    private fun fetchText(rawUrl: String, maxBytes: Int): String {
        var current = validatePublicUrl(rawUrl)
        for (redirectCount in 0..5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 25_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; ORBIT-Agent/1.0) AppleWebKit/537.36 Chrome/122 Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    require(redirectCount < 5) { "Слишком много перенаправлений при запросе $current" }
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("HTTP $code без заголовка Location")
                    current = validatePublicUrl(URL(URL(current), location).toString())
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code при запросе $current")
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IllegalStateException("Ответ сайта больше лимита ${humanBytes(maxBytes.toLong())}")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val type = connection.contentType.orEmpty()
                val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
                    ?: "UTF-8"
                return String(bytes, Charset.forName(charset))
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Не удалось загрузить URL после перенаправлений")
    }

    private fun openUrl(rawUrl: String): String {
        val safeUrl = validatePublicUrl(rawUrl)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Передан Android запрос на открытие: $safeUrl"
    }

    private fun launchApp(query: String): String {
        require(query.isNotBlank()) { "Укажи имя приложения или package name" }
        val pm = context.packageManager
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        val normalized = query.trim().lowercase(Locale.ROOT)
        val chosen = installed.firstOrNull { it.packageName.equals(query, true) }
            ?: installed.firstOrNull { pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT) == normalized }
            ?: installed.firstOrNull { pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(normalized) }
            ?: throw IllegalStateException("Не найдено установленное приложение по запросу: $query")
        val launch = pm.getLaunchIntentForPackage(chosen.packageName)
            ?: throw IllegalStateException("У приложения нет доступного запуска: ${chosen.packageName}")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "Запрошен запуск ${pm.getApplicationLabel(chosen)} (${chosen.packageName})."
    }

    private fun runCommand(command: String): String {
        require(command.isNotBlank()) { "Команда пустая" }
        require(command.length <= 5000) { "Команда слишком длинная" }
        val folded = command.lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")
        val deny = listOf(
            "rm -rf /", "rm -rf /*", "mkfs", "reboot", "shutdown", "setenforce", "mount -o", "pm install",
            "pm uninstall", "pm clear", "settings put", "svc power", "am force-stop", ":(){"
        )
        val attemptsRootEscalation = Regex("(?:^|[\\s;&|/])su(?:$|[\\s;&|])").containsMatchIn(folded)
        require(!attemptsRootEscalation && deny.none { folded.contains(it) }) {
            "Команда заблокирована защитным фильтром. Системные изменения из агента запрещены."
        }
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(File(root, activeProjectRel).takeIf { it.isDirectory } ?: root)
            .redirectErrorStream(true)
            .apply {
                val project = File(root, activeProjectRel).takeIf { it.isDirectory } ?: root
                environment()["HOME"] = project.absolutePath
                environment()["TMPDIR"] = cacheTmp.absolutePath
                environment()["ORBIT_WORKSPACE"] = root.absolutePath
                environment()["ORBIT_PROJECT"] = project.absolutePath
            }
            .start()
        val output = StringBuilder()
        val reader = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line -> if (output.length < 24000) synchronized(output) { output.append(line).append('\n') } }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            return JSONObject().put("timeout", true).put("exit_code", -1).put("output", synchronized(output) { output.toString().take(16000) }).toString(2)
        }
        reader.join(2000)
        val captured = synchronized(output) { output.toString().take(16000) }
        return JSONObject().put("exit_code", process.exitValue()).put("output", captured).put("cwd", File(root, activeProjectRel).absolutePath).toString(2)
    }

    private fun taskPath(relative: String): String {
        val cleaned = relative.trim().removePrefix("./")
        if (cleaned == "workflow" || cleaned.startsWith("workflow/")) return cleaned
        if (!NativeCore.isRelativePathSafe(cleaned)) throw IllegalArgumentException("Небезопасный относительный путь: $relative")
        return if (cleaned.isBlank() || cleaned == ".") activeProjectRel else "$activeProjectRel/$cleaned"
    }

    private fun safePath(relative: String): File {
        require(NativeCore.isRelativePathSafe(relative)) { "Путь содержит запрещённый сегмент: $relative" }
        val cleaned = relative.trim().removePrefix("./").trimStart('/')
        val target = File(root, cleaned.ifBlank { "." }).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) {
            "Путь выходит за пределы рабочей папки: $relative"
        }
        return target
    }

    private fun validatePublicUrl(raw: String): String {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", true)) { "Для сетевых запросов разрешён только HTTPS" }
        val host = (uri.host ?: "").lowercase(Locale.ROOT).trimEnd('.')
        require(host.isNotBlank()) { "В URL отсутствует имя хоста" }
        require(host != "localhost" && !host.endsWith(".localhost") && host != "::1") { "Локальные адреса запрещены" }
        require(!host.startsWith("127.") && !host.startsWith("10.") && !host.startsWith("192.168.")) { "Частные IP-адреса запрещены" }
        require(!Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)) { "Частные IP-адреса запрещены" }
        require(!host.startsWith("169.254.") && !host.equals("0.0.0.0")) { "Локальные адреса запрещены" }
        return uri.toString()
    }

    private fun stripHtml(input: String): String = decodeHtml(
        input.replace(Regex("(?is)<script\\b.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b.*?</style>"), " ")
            .replace(Regex("(?is)<noscript\\b.*?</noscript>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("[\\t\\r\\n ]+"), " ")
    ).trim()

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)
        .replace("&nbsp;", " ", true)
        .replace("&apos;", "'", true)

    private fun rel(file: File): String = try {
        root.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/').ifBlank { "." }
    } catch (_: Exception) { file.name }

    private fun humanBytes(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KiB".format(Locale.US, size / 1024.0)
        size < 1024L * 1024L * 1024L -> "%.1f MiB".format(Locale.US, size / (1024.0 * 1024.0))
        else -> "%.2f GiB".format(Locale.US, size / (1024.0 * 1024.0 * 1024.0))
    }
}
