package com.github.haxqer.aicommits.services

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.haxqer.aicommits.settings.AICommitsSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class AICommitsService {
    
    companion object {
        fun getInstance(): AICommitsService = ApplicationManager.getApplication().getService(AICommitsService::class.java)
        private val LOG = Logger.getInstance(AICommitsService::class.java)
    }

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateCommitMessage(
        diff: String,
        files: List<String>,
        branch: String = "main"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val settings = AICommitsSettings.getInstance()
            
            if (settings.apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API key is not configured. Please set it in Settings > Tools > AI Commits."))
            }

            val prompt = buildPrompt(diff, files, branch)
            val response = callLLMAPI(prompt)
            
            Result.success(response)
        } catch (e: Exception) {
            LOG.warn("Failed to generate commit message", e)
            Result.failure(e)
        }
    }

    suspend fun generateCommitMessageStreaming(
        diff: String,
        files: List<String>,
        branch: String = "main",
        onUpdate: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val settings = AICommitsSettings.getInstance()
            
            if (settings.apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API key is not configured. Please set it in Settings > Tools > AI Commits."))
            }

            val prompt = buildPrompt(diff, files, branch)
            val response = callLLMAPIStreaming(prompt, onUpdate)
            
            Result.success(response)
        } catch (e: Exception) {
            LOG.warn("Failed to generate commit message", e)
            Result.failure(e)
        }
    }

    private fun buildPrompt(diff: String, files: List<String>, branch: String): String {
        val settings = AICommitsSettings.getInstance()
        val template = if (settings.useCustomPrompt) {
            settings.customPrompt
        } else {
            AICommitsSettings.DEFAULT_PROMPT
        }
        
        val emojiInstructions = if (settings.enableEmoji) {
            AICommitsSettings.EMOJI_GUIDE
        } else {
            "6. Do not use emojis in the commit message"
        }
        
        return template
            .replace("{{diff}}", diff.take(4000)) // Limit diff size
            .replace("{{files}}", files.joinToString("\n"))
            .replace("{{branch}}", branch)
            .replace("{{emoji}}", emojiInstructions)
    }

    private suspend fun callLLMAPI(prompt: String): String = withContext(Dispatchers.IO) {
        val settings = AICommitsSettings.getInstance()
        
        val requestBody = ChatCompletionRequest(
            model = settings.model,
            messages = listOf(
                ChatMessage(role = "user", content = prompt)
            ),
            maxTokens = settings.maxTokens,
            temperature = settings.temperature
        )

        val json = objectMapper.writeValueAsString(requestBody)
        val body = json.toRequestBody("application/json".toMediaType())

        // Smart URL construction to avoid duplicate /v1
        val baseUrl = settings.apiHost.trimEnd('/')
        val endpoint = if (baseUrl.endsWith("/v1")) {
            "$baseUrl/chat/completions"
        } else {
            "$baseUrl/v1/chat/completions"
        }
        
        LOG.info("AI Commits: Making API call to: $endpoint")
        LOG.info("AI Commits: Using model: ${settings.model}")
        
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error details"
            LOG.warn("AI Commits: API call failed. URL: $endpoint, Status: ${response.code}, Body: $errorBody")
            throw IOException("API call failed: ${response.code} ${response.message}. Details: $errorBody")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response body")
        val chatResponse = objectMapper.readValue(responseBody, ChatCompletionResponse::class.java)
        
        val rawContent = chatResponse.choices.firstOrNull()?.message?.content?.trim()
            ?: throw IOException("No content in API response")
        
        // Clean the response content
        cleanResponseContent(rawContent)
    }

    private suspend fun callLLMAPIStreaming(prompt: String, onUpdate: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val settings = AICommitsSettings.getInstance()
        
        val requestBody = ChatCompletionRequest(
            model = settings.model,
            messages = listOf(
                ChatMessage(role = "user", content = prompt)
            ),
            maxTokens = settings.maxTokens,
            temperature = settings.temperature,
            stream = true
        )

        val json = objectMapper.writeValueAsString(requestBody)
        val body = json.toRequestBody("application/json".toMediaType())

        // Smart URL construction to avoid duplicate /v1
        val baseUrl = settings.apiHost.trimEnd('/')
        val endpoint = if (baseUrl.endsWith("/v1")) {
            "$baseUrl/chat/completions"
        } else {
            "$baseUrl/v1/chat/completions"
        }
        
        LOG.info("AI Commits: Making streaming API call to: $endpoint")
        LOG.info("AI Commits: Using model: ${settings.model}")
        
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error details"
            LOG.warn("AI Commits: Streaming API call failed. URL: $endpoint, Status: ${response.code}, Body: $errorBody")
            throw IOException("API call failed: ${response.code} ${response.message}. Details: $errorBody")
        }

        val responseBody = response.body ?: throw IOException("Empty response body")
        val fullContent = StringBuilder()
        var currentContent = ""

        responseBody.source().use { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                
                if (line.startsWith("data: ")) {
                    val data = line.substring(6)
                    
                    // Skip [DONE] marker
                    if (data == "[DONE]") break
                    
                    try {
                        val streamResponse = objectMapper.readValue(data, StreamingResponse::class.java)
                        val delta = streamResponse.choices.firstOrNull()?.delta?.content
                        
                        if (delta != null) {
                            fullContent.append(delta)
                            currentContent += delta
                            
                            // Clean and update incrementally
                            val cleaned = cleanStreamingContent(currentContent)
                            if (cleaned.isNotEmpty()) {
                                onUpdate(cleaned)
                            }
                        }
                    } catch (e: Exception) {
                        LOG.debug("Skipping malformed SSE data: $data", e)
                    }
                }
            }
        }

        val finalContent = fullContent.toString()
        val cleanedFinal = cleanResponseContent(finalContent)
        
        // Final update with cleaned content
        onUpdate(cleanedFinal)
        
        cleanedFinal
    }

    private fun cleanStreamingContent(content: String): String {
        // Lighter cleaning for streaming content to show progress
        var cleaned = content
        
        // Remove think tags as they appear
        cleaned = cleaned.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        
        // Remove markdown code blocks
        cleaned = cleaned.replace(Regex("```[a-zA-Z]*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*", RegexOption.MULTILINE), "")
        
        // Basic cleanup but keep partial content for streaming
        cleaned = cleaned.trim()
        
        return cleaned
    }

    private fun cleanResponseContent(content: String): String {
        var cleaned = content
        
        // Remove think tags for reasoning models
        cleaned = cleaned.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        
        // Remove markdown code blocks
        cleaned = cleaned.replace(Regex("```[a-zA-Z]*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*", RegexOption.MULTILINE), "")
        
        // Remove common prefixes that LLMs might add
        cleaned = cleaned.replace(Regex("^(Here's a|Here is a|Commit message:|Generated commit message:)\\s*", RegexOption.MULTILINE), "")
        
        // Remove extra whitespace and newlines
        cleaned = cleaned.replace(Regex("\\n\\s*\\n"), "\n")
        cleaned = cleaned.trim()
        
        // If the result is multiline, take only the first meaningful line as commit message
        val lines = cleaned.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty()) {
            // For commit messages, usually the first line is the most important
            val firstLine = lines[0]
            
            // If first line looks like a proper commit message, use it
            if (firstLine.length > 10 && !firstLine.endsWith(':')) {
                return firstLine
            }
            
            // Otherwise, look for the first substantial line
            for (line in lines) {
                if (line.length > 10 && !line.endsWith(':') && !line.startsWith("##")) {
                    return line
                }
            }
        }
        
        LOG.info("AI Commits: Cleaned response from '${content.take(100)}...' to '$cleaned'")
        return cleaned
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        @JsonProperty("max_tokens")
        val maxTokens: Int,
        val temperature: Double,
        val stream: Boolean = false
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatMessage(
        val role: String,
        val content: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatCompletionResponse(
        val choices: List<Choice>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Choice(
        val message: ChatMessage
    )

    // Streaming response data classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamingResponse(
        val choices: List<StreamingChoice>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamingChoice(
        val delta: ChatMessage?
    )
} 