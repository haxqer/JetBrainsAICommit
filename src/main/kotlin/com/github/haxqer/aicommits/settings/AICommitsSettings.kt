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
    var maxDiffSize: Int = 12000

    override fun getState(): AICommitsSettings = this

    override fun loadState(state: AICommitsSettings) {
        apiHost = state.apiHost
        apiKey = state.apiKey
        model = state.model
        maxTokens = state.maxTokens
        temperature = state.temperature
        customPrompt = state.customPrompt.ifEmpty { DEFAULT_PROMPT }
        useCustomPrompt = state.useCustomPrompt
        enableStreaming = state.enableStreaming
        enableEmoji = state.enableEmoji
        maxDiffSize = if (state.maxDiffSize > 0) state.maxDiffSize else 12000
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
You are an expert programmer analyzing code changes for a git commit message.
Analyze ALL the changes across ALL files and create a comprehensive commit message.

Variables available:
- {{diff}}: The git diff of all changes
- {{files}}: List of all changed files
- {{branch}}: Current git branch name
- {{emoji}}: Whether to include emojis in the commit message

IMPORTANT RULES:
1. Use conventional commit format: type(scope): description
2. ANALYZE ALL FILES and changes - do not focus on just one file
3. Create a title that summarizes the OVERALL impact across all files
4. Keep the title under 72 characters
5. Use present tense ("add feature" not "added feature")
6. If changes span multiple logical areas, mention the main ones
7. Focus on the business/functional impact, not implementation details
{{emoji}}

All file changes to analyze:
{{files}}

Complete diff of all changes:
{{diff}}

Current branch: {{branch}}

Generate a comprehensive commit message that covers all the changes:
        """.trimIndent()

        val EMOJI_GUIDE = """
8. Add appropriate emojis to make the commit message more expressive and human-friendly:
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