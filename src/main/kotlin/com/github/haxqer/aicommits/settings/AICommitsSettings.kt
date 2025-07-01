package com.github.haxqer.aicommits.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

@State(
    name = "AICommitsSettings",
    storages = [Storage("AICommitsSettings.xml")]
)
@Service(Service.Level.APP)
class AICommitsSettings : PersistentStateComponent<AICommitsSettings> {

    var apiHost: String = "https://api.openai.com"
    var apiKey: String = ""
    var model: String = "gpt-4o"
    var maxTokens: Int = 1024
    var temperature: Double = 0.7
    var customPrompt: String = ""
    var useCustomPrompt: Boolean = false
    var enableStreaming: Boolean = true
    var enableEmoji: Boolean = true

    override fun getState(): AICommitsSettings = this

    override fun loadState(state: AICommitsSettings) {
        apiHost = state.apiHost
        apiKey = state.apiKey
        model = state.model
        maxTokens = state.maxTokens
        temperature = state.temperature
        customPrompt = state.customPrompt.ifEmpty { DEFAULT_PROMPT }
        useCustomPrompt = state.useCustomPrompt
    }

    override fun noStateLoaded() {
        // Initialize with default values if no state is loaded
        if (customPrompt.isEmpty()) {
            customPrompt = DEFAULT_PROMPT
        }
    }

    companion object {
        fun getInstance(): AICommitsSettings = ApplicationManager.getApplication().getService(AICommitsSettings::class.java)
        
        val DEFAULT_PROMPT = """
You are an expert programmer, and you are trying to write a commit message for this change.
Generate a concise git commit message written in present tense for the following code changes:

Variables available:
- {{diff}}: The git diff of the changes
- {{files}}: List of changed files
- {{branch}}: Current git branch name
- {{emoji}}: Whether to include emojis in the commit message

Rules:
1. Use conventional commit format: type(scope): description
2. Keep the message under 72 characters for the title
3. Use present tense ("add feature" not "added feature")
4. Focus on the why and what, not how
5. If there are multiple changes, focus on the most significant one
{{emoji}}

The diff:
{{diff}}

Files changed:
{{files}}

Current branch: {{branch}}

Generate a commit message:
        """.trimIndent()

        val EMOJI_GUIDE = """
6. Add appropriate emojis to make the commit message more expressive and human-friendly:
   - ✨ for new features
   - 🐛 for bug fixes
   - 🔧 for configuration changes
   - 📝 for documentation
   - 🎨 for code style/formatting
   - ⚡ for performance improvements
   - 🔒 for security fixes
   - 🚀 for deployments
   - 🧪 for tests
   - 📦 for dependencies
   - 🔥 for removing code/files
   - 💚 for CI/build fixes
   - 🔀 for merging branches
   - ⬆️ for upgrading dependencies
   - ⬇️ for downgrading dependencies
   - 🏷️ for version tags"""
    }
} 