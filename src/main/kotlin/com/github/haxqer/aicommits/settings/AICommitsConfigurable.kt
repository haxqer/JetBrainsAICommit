package com.github.haxqer.aicommits.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.JTextField
import javax.swing.JPasswordField
import javax.swing.JTextArea
import javax.swing.JSpinner

class AICommitsConfigurable : Configurable {
    
    private val settings = AICommitsSettings.getInstance()
    private lateinit var panel: DialogPanel
    
    // UI Components
    private lateinit var apiHostField: JTextField
    private lateinit var apiKeyField: JPasswordField
    private lateinit var modelField: JTextField
    private lateinit var maxTokensField: JTextField
    private lateinit var temperatureSpinner: JSpinner
    private lateinit var customPromptArea: JTextArea
    private lateinit var useCustomPromptCheckBox: JCheckBox
    private lateinit var enableStreamingCheckBox: JCheckBox
    private lateinit var enableEmojiCheckBox: JCheckBox

    override fun getDisplayName(): String = "AI Commits"

    override fun createComponent(): JComponent {
        panel = panel {
            group("API Configuration") {
                row("API Host:") {
                    apiHostField = textField()
                        .columns(COLUMNS_LARGE)
                        .comment("OpenAI compatible API endpoint")
                        .component
                }
                row("API Key:") {
                    apiKeyField = passwordField()
                        .columns(COLUMNS_LARGE)
                        .comment("Your API key for the LLM service")
                        .component
                }
                row("Model:") {
                    modelField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("Model name (e.g., gpt-4o, gpt-3.5-turbo)")
                        .component
                }
                row("Max Tokens:") {
                    maxTokensField = intTextField()
                        .columns(COLUMNS_TINY)
                        .comment("Maximum tokens for the response")
                        .component
                }
                row("Temperature:") {
                    temperatureSpinner = spinner(0.0..2.0, 0.1)
                        .comment("Randomness of the output (0.0 = deterministic, 2.0 = very random)")
                        .component
                }
                row {
                    enableStreamingCheckBox = checkBox("Enable streaming")
                        .comment("Show real-time generation of commit messages (recommended)")
                        .component
                }
                row {
                    enableEmojiCheckBox = checkBox("Enable emoji 😊")
                        .comment("Add expressive emojis to make commit messages more human-friendly")
                        .component
                }
            }
            
            group("Prompt Configuration") {
                row {
                    useCustomPromptCheckBox = checkBox("Use custom prompt")
                        .comment("Enable to use your own prompt template")
                        .component
                }
                row {
                    customPromptArea = textArea()
                        .rows(15)
                        .columns(COLUMNS_LARGE)
                        .comment("""
                            Available variables:
                            {{diff}} - The git diff of the changes
                            {{files}} - List of changed files  
                            {{branch}} - Current git branch name
                            {{emoji}} - Emoji instructions (automatic based on settings)
                        """.trimIndent())
                        .component
                }
                
                row {
                    button("Reset to Default") {
                        customPromptArea.text = AICommitsSettings.DEFAULT_PROMPT
                    }
                }
            }
        }
        
        // Add listener to enable/disable custom prompt area
        useCustomPromptCheckBox.addActionListener {
            updateCustomPromptAreaState()
        }
        
        // Load current settings
        reset()
        
        return panel
    }

    override fun isModified(): Boolean {
        val currentApiHost = apiHostField.text
        val currentApiKey = String(apiKeyField.password)
        val currentModel = modelField.text
        val currentMaxTokens = maxTokensField.text.toIntOrNull() ?: 0
        val currentTemperature = temperatureSpinner.value as Double
        val currentCustomPrompt = customPromptArea.text
        val currentUseCustomPrompt = useCustomPromptCheckBox.isSelected
        val currentEnableStreaming = enableStreamingCheckBox.isSelected
        val currentEnableEmoji = enableEmojiCheckBox.isSelected
        
        return currentApiHost != settings.apiHost ||
                currentApiKey != settings.apiKey ||
                currentModel != settings.model ||
                currentMaxTokens != settings.maxTokens ||
                currentTemperature != settings.temperature ||
                currentCustomPrompt != settings.customPrompt ||
                currentUseCustomPrompt != settings.useCustomPrompt ||
                currentEnableStreaming != settings.enableStreaming ||
                currentEnableEmoji != settings.enableEmoji
    }

    override fun apply() {
        // Read values from UI components
        settings.apiHost = apiHostField.text
        settings.apiKey = String(apiKeyField.password)
        settings.model = modelField.text
        settings.maxTokens = maxTokensField.text.toIntOrNull() ?: 1024
        settings.temperature = temperatureSpinner.value as Double
        settings.customPrompt = customPromptArea.text
        settings.useCustomPrompt = useCustomPromptCheckBox.isSelected
        settings.enableStreaming = enableStreamingCheckBox.isSelected
        settings.enableEmoji = enableEmojiCheckBox.isSelected
        
        // Ensure custom prompt is not empty
        if (settings.customPrompt.isEmpty()) {
            settings.customPrompt = AICommitsSettings.DEFAULT_PROMPT
        }
        
        // Force save the application settings
        com.intellij.openapi.application.ApplicationManager.getApplication().saveSettings()
        
        println("AI Commits: Configuration saved successfully") // Debug log
    }

    override fun reset() {
        // Load values from settings to UI components
        apiHostField.text = settings.apiHost
        apiKeyField.text = settings.apiKey
        modelField.text = settings.model
        maxTokensField.text = settings.maxTokens.toString()
        temperatureSpinner.value = settings.temperature
        customPromptArea.text = if (settings.customPrompt.isEmpty()) AICommitsSettings.DEFAULT_PROMPT else settings.customPrompt
        useCustomPromptCheckBox.isSelected = settings.useCustomPrompt
        enableStreamingCheckBox.isSelected = settings.enableStreaming
        enableEmojiCheckBox.isSelected = settings.enableEmoji
        
        // Update UI state
        updateCustomPromptAreaState()
        
        println("AI Commits: Configuration reset to current settings") // Debug log
    }
    
    private fun updateCustomPromptAreaState() {
        customPromptArea.isEnabled = useCustomPromptCheckBox.isSelected
    }
    
    override fun disposeUIResources() {
        // Clean up resources if needed
    }
} 