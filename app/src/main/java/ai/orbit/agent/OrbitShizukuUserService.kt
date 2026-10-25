package ai.orbit.agent

import java.io.File
import java.util.concurrent.TimeUnit

/** Runs in the Shizuku user-service process (shell UID when Shizuku was started by ADB). */
class OrbitShizukuUserService : IOrbitShizukuService.Stub() {
    override fun execute(command: String, workingDirectory: String, timeoutMs: Int): String {
        require(command.isNotBlank()) { "Команда пустая." }
        require(command.length <= 8_000) { "Команда слишком длинная (лимит 8000 символов)." }
        val cwd = runCatching { File(workingDirectory).canonicalFile }.getOrNull()
            ?.takeIf { it.isDirectory } ?: File("/sdcard")
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> synchronized(output) { if (output.length < 20_000) output.append(line).append('\n') } }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs.coerceIn(1_000, 60_000).toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000)
            return "timeout=true\n${synchronized(output) { output.toString().take(16_000) }}"
        }
        reader.join(1_500)
        return "exit_code=${process.exitValue()}\ncwd=${cwd.absolutePath}\n${synchronized(output) { output.toString().take(16_000) }}"
    }
}
