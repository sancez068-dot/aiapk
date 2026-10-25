package ai.orbit.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional user-enabled bridge for interacting with the currently visible Android UI. */
class AgentAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    fun readVisibleScreen(): String = onServiceThread {
        val root = rootInActiveWindow ?: return@onServiceThread "Нет активного окна. Открой нужное приложение."
        val lines = ArrayList<String>()
        lines.add("package=${root.packageName ?: "unknown"}")
        appendNodeText(root, lines, 0, 0)
        lines.take(180).joinToString("\n").ifBlank { "На экране не найден читаемый текст." }
    }

    fun clickText(target: String): String = onServiceThread {
        val root = rootInActiveWindow ?: return@onServiceThread "Нет активного окна."
        val match = findTextNode(root, target)
            ?: return@onServiceThread "Не найден видимый элемент с текстом: ${target.take(120)}"
        var clickable: AccessibilityNodeInfo? = match
        var depth = 0
        while (clickable != null && depth < 6) {
            if (clickable.isClickable && clickable.isEnabled) {
                val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return@onServiceThread "Нажат элемент: ${target.take(120)}"
            }
            clickable = clickable.parent
            depth++
        }
        if (match.isEnabled && match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Нажат элемент: ${target.take(120)}"
        } else {
            "Элемент найден, но Android не разрешил нажатие: ${target.take(120)}"
        }
    }

    fun typeIntoFocusedField(text: String): String = onServiceThread {
        val root = rootInActiveWindow ?: return@onServiceThread "Нет активного окна."
        var node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) node = findEditableNode(root)
        if (node == null) return@onServiceThread "Не найдено поле ввода. Сначала коснись поля на экране."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "Текст введён (${text.length} символов)."
        } else {
            "Поле найдено, но не поддерживает ввод текста через Accessibility."
        }
    }

    fun scroll(direction: String): String = onServiceThread {
        val root = rootInActiveWindow ?: return@onServiceThread "Нет активного окна."
        val forward = direction.equals("down", true) || direction.equals("forward", true) || direction.equals("вниз", true)
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val node = findScrollableNode(root)
            ?: return@onServiceThread "Не найден прокручиваемый элемент на экране."
        if (node.performAction(action)) "Прокрутка выполнена." else "Прокрутка не поддерживается этим элементом."
    }

    fun goBack(): String = onServiceThread {
        if (performGlobalAction(GLOBAL_ACTION_BACK)) "Нажата системная кнопка «Назад»." else "Android не выполнил действие «Назад»."
    }

    fun tapCoordinates(x: Int, y: Int): String = onServiceThread {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()
        if (dispatchGesture(gesture, null, null)) "Касание отправлено в координаты ($x, $y)."
        else "Android не принял жест."
    }

    private fun appendNodeText(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int, visited: Int) {
        if (depth > 18 || visited > 500 || out.size > 180) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val label = text.ifBlank { description }
        if (label.isNotBlank()) {
            val suffix = if (node.isClickable) " [clickable]" else ""
            out.add("${"  ".repeat(depth.coerceAtMost(8))}${label.take(240)}$suffix")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            appendNodeText(child, out, depth + 1, visited + i + 1)
            if (out.size > 180) break
        }
    }

    private fun findTextNode(node: AccessibilityNodeInfo, target: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 24) return null
        val needle = target.trim().lowercase()
        val text = node.text?.toString().orEmpty().lowercase()
        val description = node.contentDescription?.toString().orEmpty().lowercase()
        if (needle.isNotBlank() && (text == needle || description == needle || text.contains(needle) || description.contains(needle))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findTextNode(child, target, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 24) return null
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 24) return null
        if (node.isScrollable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun <T> onServiceThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Any? = null
        var failure: Throwable? = null
        mainHandler.post {
            try { result = block() } catch (error: Throwable) { failure = error } finally { latch.countDown() }
        }
        if (!latch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("Accessibility bridge timed out")
        failure?.let { throw IllegalStateException(it.message ?: "Accessibility bridge failed", it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        @Volatile var active: AgentAccessibilityService? = null
            private set
    }
}
