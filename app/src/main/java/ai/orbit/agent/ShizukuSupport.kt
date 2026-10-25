package ai.orbit.agent

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

object ShizukuSupport {
    const val REQUEST_CODE = 8321
    private val lock = Any()
    @Volatile private var remote: IOrbitShizukuService? = null
    @Volatile private var binding = false
    @Volatile private var boundArgs: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = IOrbitShizukuService.Stub.asInterface(service)
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            binding = false
        }
    }

    fun binderAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        binderAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(): String = when {
        !binderAvailable() -> "Shizuku не запущен или недоступен."
        hasPermission() && remote != null -> "Shizuku подключён, доступ разрешён."
        hasPermission() -> "Shizuku доступ разрешён; сервис запускается при первом вызове."
        else -> "Shizuku найден, но разрешение ORBIT ещё не выдано."
    }

    fun bindIfAllowed(context: Context) {
        if (!hasPermission() || remote != null || binding) return
        synchronized(lock) {
            if (remote != null || binding || !hasPermission()) return
            val args = Shizuku.UserServiceArgs(ComponentName(context, OrbitShizukuUserService::class.java))
                .daemon(false)
                .tag("${context.packageName}.orbit-shizuku")
                .version(1)
            boundArgs = args
            binding = true
            try {
                Shizuku.bindUserService(args, connection)
            } catch (e: Exception) {
                binding = false
                remote = null
                throw e
            }
        }
    }

    fun execute(context: Context, command: String, workingDirectory: String): String {
        if (!binderAvailable()) return "Shizuku не запущен. Запусти его и выдай ORBIT разрешение в настройках."
        if (!hasPermission()) return "ORBIT не получил разрешение Shizuku. Открой Настройки → Shizuku → Разрешённые приложения."
        bindIfAllowed(context)
        val deadline = System.currentTimeMillis() + 8_000
        while (remote == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val service = remote ?: return "Не удалось запустить Shizuku UserService. Проверь версию Shizuku и перезапусти его."
        return service.execute(command, workingDirectory, 60_000)
    }
}
