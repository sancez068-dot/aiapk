package ai.orbit.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class ApiClient(
    private val endpointInput: String,
    private val apiKey: String,
    private val model: String
) {
    val endpoint: String = normalizeEndpoint(endpointInput)

    fun complete(messages: JSONArray, tools: JSONArray? = null, toolChoice: String? = null): JSONObject {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
        if (tools != null && tools.length() > 0) {
            payload.put("tools", tools)
            payload.put("tool_choice", toolChoice ?: "auto")
        }
        return post(payload)
    }

    fun testConnection(): String {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", "Reply with exactly: ORBIT_OK")
        )
        val response = complete(messages)
        val content = response.optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")?.trim()
        return if (content.isNullOrBlank()) {
            "API ответил, но не вернул текст. Проверь модель и совместимость Chat Completions."
        } else {
            "Подключение работает. Ответ модели: ${content.take(200)}"
        }
    }

    private fun post(payload: JSONObject): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 75_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ORBIT-Agent-Android/1.0")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            if (endpoint.contains("openrouter.ai", ignoreCase = true)) {
                setRequestProperty("X-Title", "ORBIT Agent")
            }
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(BufferedInputStream(input), Charset.forName("UTF-8"))).use { it.readText() }
            }.orEmpty()
            if (code !in 200..299) {
                val detail = try {
                    val json = JSONObject(body)
                    json.optJSONObject("error")?.optString("message")
                        ?: json.optString("message", body)
                } catch (_: Exception) { body }
                throw IllegalStateException("API HTTP $code: ${detail.ifBlank { "пустой ответ" }.take(900)}")
            }
            val parsed = JSONObject(body)
            if (parsed.has("error")) throw IllegalStateException("API error: ${parsed.opt("error")}")
            return parsed
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun normalizeEndpoint(raw: String): String {
            val clean = raw.trim().trimEnd('/')
            require(clean.startsWith("https://")) {
                "Для API нужен полный HTTPS URL, начинающийся с https://"
            }
            return when {
                clean.endsWith("/chat/completions") -> clean
                clean.endsWith("/v1") -> "$clean/chat/completions"
                else -> clean
            }
        }
    }
}
