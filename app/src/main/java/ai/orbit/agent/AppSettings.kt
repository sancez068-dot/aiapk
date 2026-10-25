package ai.orbit.agent

import android.content.Context

private const val SETTINGS_FILE = "orbit_settings"

 data class AgentSettings(
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val model: String = "openai/gpt-4o-mini",
    val systemPrompt: String = "",
    val autoApprove: Boolean = false,
    val allowCommands: Boolean = false,
    val rememberChoices: Boolean = true,
    val maxSteps: Int = 10
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

    fun load(): AgentSettings = AgentSettings(
        endpoint = prefs.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions")
            ?: "https://openrouter.ai/api/v1/chat/completions",
        model = prefs.getString("model", "openai/gpt-4o-mini") ?: "openai/gpt-4o-mini",
        systemPrompt = prefs.getString("system_prompt", "") ?: "",
        autoApprove = prefs.getBoolean("auto_approve", false),
        allowCommands = prefs.getBoolean("allow_commands", false),
        rememberChoices = prefs.getBoolean("remember_choices", true),
        maxSteps = prefs.getInt("max_steps", 10).coerceIn(2, 20)
    )

    fun save(settings: AgentSettings) {
        prefs.edit()
            .putString("endpoint", settings.endpoint.trim())
            .putString("model", settings.model.trim())
            .putString("system_prompt", settings.systemPrompt.trim())
            .putBoolean("auto_approve", settings.autoApprove)
            .putBoolean("allow_commands", settings.allowCommands)
            .putBoolean("remember_choices", settings.rememberChoices)
            .putInt("max_steps", settings.maxSteps.coerceIn(2, 20))
            .apply()
    }
}
