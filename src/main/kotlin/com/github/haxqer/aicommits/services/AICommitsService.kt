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
        
        // Smart diff truncation to ensure all files are represented
        val processedDiff = intelligentDiffTruncation(diff, settings.maxDiffSize, files)
        
        return template
            .replace("{{diff}}", processedDiff)
            .replace("{{files}}", files.joinToString("\n"))
            .replace("{{branch}}", branch)
            .replace("{{emoji}}", emojiInstructions)
    }

    /**
     * Intelligently truncate diff to ensure all files are represented when possible
     */
    private fun intelligentDiffTruncation(diff: String, maxSize: Int, files: List<String>): String {
        if (diff.length <= maxSize) {
            return diff
        }

        // Split diff by files (look for "diff --git" markers)
        val fileDiffs = mutableMapOf<String, String>()
        val lines = diff.split('\n')
        var currentFile = ""
        var currentDiffLines = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("diff --git")) {
                // Save previous file's diff
                if (currentFile.isNotEmpty()) {
                    fileDiffs[currentFile] = currentDiffLines.joinToString("\n")
                }
                
                // Extract filename from diff --git a/file b/file
                currentFile = line.substringAfter("b/").takeIf { it.isNotEmpty() } 
                    ?: line.substringAfter("a/").takeIf { it.isNotEmpty() }
                    ?: "unknown"
                currentDiffLines = mutableListOf(line)
            } else {
                currentDiffLines.add(line)
            }
        }
        
        // Don't forget the last file
        if (currentFile.isNotEmpty()) {
            fileDiffs[currentFile] = currentDiffLines.joinToString("\n")
        }

        // If we couldn't parse by files, fall back to simple truncation
        if (fileDiffs.isEmpty()) {
            return diff.take(maxSize) + if (diff.length > maxSize) "\n... (truncated)" else ""
        }

        // Calculate how much space each file should get
        val availableSpace = maxSize - 100 // Reserve space for truncation messages
        val spacePerFile = availableSpace / fileDiffs.size
        val minSpacePerFile = 200 // Minimum meaningful diff size per file

        val result = StringBuilder()
        var remainingSpace = availableSpace
        var processedFiles = 0

        for ((filename, fileDiff) in fileDiffs) {
            // Adjust space allocation for remaining files
            val filesLeft = fileDiffs.size - processedFiles
            val allocatedSpace = if (filesLeft == 1) {
                remainingSpace
            } else {
                maxOf(minSpacePerFile, remainingSpace / filesLeft)
            }

            if (remainingSpace < minSpacePerFile) {
                result.append("\n... (${filesLeft} more files truncated)")
                break
            }

            if (fileDiff.length <= allocatedSpace) {
                result.append(fileDiff)
                remainingSpace -= fileDiff.length
            } else {
                // Truncate this file's diff intelligently
                val truncatedDiff = truncateFileDiff(fileDiff, allocatedSpace)
                result.append(truncatedDiff)
                remainingSpace -= truncatedDiff.length
            }
            
            if (processedFiles < fileDiffs.size - 1) {
                result.append("\n")
            }
            
            processedFiles++
        }

        return result.toString()
    }

    /**
     * Truncate a single file's diff while preserving important information
     */
    private fun truncateFileDiff(fileDiff: String, maxSize: Int): String {
        val lines = fileDiff.split('\n')
        val result = mutableListOf<String>()
        var currentSize = 0
        
        // Always include the header lines (diff --git, index, +++, ---)
        var i = 0
        while (i < lines.size && (lines[i].startsWith("diff --git") || 
                                   lines[i].startsWith("index") || 
                                   lines[i].startsWith("+++") || 
                                   lines[i].startsWith("---") ||
                                   lines[i].startsWith("@@"))) {
            result.add(lines[i])
            currentSize += lines[i].length + 1
            i++
            
            if (currentSize >= maxSize * 0.3) break // Don't use more than 30% for headers
        }
        
        // Add content lines until we hit the limit
        var addedLines = 0
        var removedLines = 0
        while (i < lines.size && currentSize < maxSize - 50) { // Reserve space for summary
            val line = lines[i]
            result.add(line)
            currentSize += line.length + 1
            
            if (line.startsWith("+") && !line.startsWith("+++")) addedLines++
            if (line.startsWith("-") && !line.startsWith("---")) removedLines++
            
            i++
        }
        
        // Add summary if we truncated
        if (i < lines.size) {
            result.add("... (truncated, ~${addedLines} additions, ~${removedLines} deletions)")
        }
        
        return result.joinToString("\n")
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