package ai.orbit.agent

/** Thin JNI helper. The app remains functional if a native library cannot be loaded. */
object NativeCore {
    private val available = try {
        System.loadLibrary("orbitcore")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    private external fun nativeSanitizeSlug(input: String): String
    private external fun nativeIsRelativePathSafe(path: String): Boolean

    fun projectSlug(input: String): String {
        val transliterated = input.lowercase().map { c ->
            when (c) {
                'а' -> "a"; 'б' -> "b"; 'в' -> "v"; 'г' -> "g"; 'д' -> "d"; 'е', 'ё' -> "e";
                'ж' -> "zh"; 'з' -> "z"; 'и', 'й' -> "i"; 'к' -> "k"; 'л' -> "l"; 'м' -> "m";
                'н' -> "n"; 'о' -> "o"; 'п' -> "p"; 'р' -> "r"; 'с' -> "s"; 'т' -> "t";
                'у' -> "u"; 'ф' -> "f"; 'х' -> "kh"; 'ц' -> "ts"; 'ч' -> "ch"; 'ш' -> "sh";
                'щ' -> "shch"; 'ъ', 'ь' -> ""; 'ы' -> "y"; 'э' -> "e"; 'ю' -> "yu"; 'я' -> "ya";
                else -> c.toString()
            }
        }.joinToString("")
        if (available) runCatching { nativeSanitizeSlug(transliterated) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        val fallback = transliterated.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(36)
        return fallback.ifBlank { "task" }
    }

    fun isRelativePathSafe(path: String): Boolean {
        if ('\u0000' in path) return false
        if (available) return runCatching { nativeIsRelativePathSafe(path) }.getOrDefault(false)
        val normalized = path.replace('\\', '/')
        return normalized.split('/').none { it == ".." }
    }

    fun isLoaded(): Boolean = available
}
