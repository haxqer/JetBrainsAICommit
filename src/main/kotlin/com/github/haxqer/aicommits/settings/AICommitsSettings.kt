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
Analyze ALL the changes across ALL files and create ONE comprehensive commit message line.

Variables available:
- {{diff}}: The git diff of all changes
- {{files}}: List of all changed files
- {{branch}}: Current git branch name
- {{emoji}}: Whether to include emojis in the commit message

CRITICAL REQUIREMENTS:
1. Generate EXACTLY ONE LINE that summarizes ALL changes across ALL files
2. Use conventional commit format: type(scope): description
3. ANALYZE ALL FILES - do not focus on just one file or change
4. Create a title that captures the OVERALL impact of all modifications
5. Keep the message under 72 characters but make it comprehensive
6. Use present tense ("add feature" not "added feature")
7. If changes span multiple logical areas, mention the most significant impact
8. Focus on the business/functional outcome, not implementation details
{{emoji}}

All file changes to analyze:
{{files}}

Complete diff of all changes:
{{diff}}

Current branch: {{branch}}

Generate a SINGLE comprehensive commit message that covers all the changes:
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