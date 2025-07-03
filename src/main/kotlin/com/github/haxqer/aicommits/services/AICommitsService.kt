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
            "8. Do not use emojis in the commit message"
        }
        
        // Smart diff truncation to ensure all files are represented
        val processedDiff = intelligentDiffTruncation(diff, settings.maxDiffSize, files)
        
        // Generate file change summary
        val filesSummary = generateFileChangesSummary(processedDiff, files)
        
        return template
            .replace("{{diff}}", processedDiff)
            .replace("{{files}}", filesSummary)
            .replace("{{branch}}", branch)
            .replace("{{emoji}}", emojiInstructions)
    }

    /**
     * Generate a summary of file changes to help AI understand the scope
     */
    private fun generateFileChangesSummary(diff: String, files: List<String>): String {
        val summary = StringBuilder()
        
        // Add basic file list
        summary.append("Modified files (${files.size}):\n")
        files.forEach { file ->
            summary.append("- $file")
            
            // Try to extract change statistics from diff
            val filePattern = "diff --git.*?b/$file"
            val additionsPattern = "\\+[^+]".toRegex()
            val deletionsPattern = "^-[^-]".toRegex(RegexOption.MULTILINE)
            
            // Simple statistics extraction
            val fileDiffSection = diff.lines().takeWhile { !it.startsWith("diff --git") || it.contains(file) }
                .dropWhile { !it.contains(file) }
                .takeWhile { line -> !line.startsWith("diff --git") || line.contains(file) }
                .joinToString("\n")
            
            if (fileDiffSection.isNotEmpty()) {
                val additions = additionsPattern.findAll(fileDiffSection).count()
                val deletions = deletionsPattern.findAll(fileDiffSection).count()
                
                if (additions > 0 || deletions > 0) {
                    summary.append(" (+$additions -$deletions)")
                }
            }
            summary.append("\n")
        }
        
        return summary.toString()
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

        // Smart allocation: prioritize smaller files and ensure minimum representation
        val result = StringBuilder()
        val reserveSpace = 200 // Reserve space for summary
        var availableSpace = maxSize - reserveSpace
        
        // Sort files by diff size to process smaller ones first
        val sortedFileDiffs = fileDiffs.toList().sortedBy { it.second.length }
        val processedFiles = mutableSetOf<String>()
        
        // First pass: include smaller files completely
        for ((filename, fileDiff) in sortedFileDiffs) {
            if (fileDiff.length <= availableSpace / (fileDiffs.size - processedFiles.size) * 1.5) {
                if (result.isNotEmpty()) result.append("\n")
                result.append(fileDiff)
                availableSpace -= fileDiff.length
                processedFiles.add(filename)
            }
        }
        
        // Second pass: truncate remaining larger files
        val remainingFiles = sortedFileDiffs.filter { it.first !in processedFiles }
        if (remainingFiles.isNotEmpty()) {
            val spacePerRemainingFile = availableSpace / remainingFiles.size
            val minSpacePerFile = 300 // Minimum meaningful diff size per file
            
            for ((filename, fileDiff) in remainingFiles) {
                val allocatedSpace = maxOf(minSpacePerFile, spacePerRemainingFile)
                
                if (availableSpace >= minSpacePerFile) {
                    if (result.isNotEmpty()) result.append("\n")
                    
                    if (fileDiff.length <= allocatedSpace) {
                        result.append(fileDiff)
                        availableSpace -= fileDiff.length
                    } else {
                        val truncatedDiff = truncateFileDiff(fileDiff, allocatedSpace)
                        result.append(truncatedDiff)
                        availableSpace -= truncatedDiff.length
                    }
                    processedFiles.add(filename)
                } else {
                    break
                }
            }
        }
        
        // Add summary for unprocessed files
        val unprocessedCount = fileDiffs.size - processedFiles.size
        if (unprocessedCount > 0) {
            result.append("\n... ($unprocessedCount more files truncated: ")
            result.append(fileDiffs.keys.filter { it !in processedFiles }.joinToString(", "))
            result.append(")")
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
        cleaned = cleaned.replace(Regex("\\n\\s*\\n+"), "\n")
        cleaned = cleaned.trim()
        
        // Split into lines and find the best commit message line
        val lines = cleaned.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        
        if (lines.isEmpty()) {
            LOG.warn("AI Commits: No meaningful content after cleaning: '$content'")
            return "chore: update multiple files"
        }
        
        // Strategy 1: Look for conventional commit format lines
        val conventionalCommitPattern = Regex("^(feat|fix|docs|style|refactor|test|chore|perf|ci|build|revert)(\\(.+\\))?:\\s*.+")
        val conventionalCommits = lines.filter { conventionalCommitPattern.matches(it) && it.length <= 100 }
        
        if (conventionalCommits.isNotEmpty()) {
            // Return the most comprehensive one (longest within reasonable limits)
            val bestConventional = conventionalCommits.maxByOrNull { 
                // Prefer longer messages that mention multiple files/areas
                val score = it.length + 
                    (if (it.contains("and")) 10 else 0) +
                    (if (it.contains("multiple")) 15 else 0) +
                    (if (it.contains("across")) 15 else 0) +
                    (if (it.contains("files")) 10 else 0)
                score
            }
            if (bestConventional != null && bestConventional.length >= 20) {
                LOG.info("AI Commits: Selected conventional commit: '$bestConventional'")
                return bestConventional
            }
        }
        
        // Strategy 2: Look for any line with colon (type: description format)
        val colonLines = lines.filter { 
            it.contains(":") && 
            !it.endsWith(":") && 
            it.length >= 15 && 
            it.length <= 100 &&
            !it.startsWith("#") &&
            !it.startsWith("//")
        }
        
        if (colonLines.isNotEmpty()) {
            // Score lines based on how comprehensive they seem
            val scoredLines = colonLines.map { line ->
                val score = line.length +
                    (if (line.lowercase().contains("multiple")) 20 else 0) +
                    (if (line.lowercase().contains("across")) 15 else 0) +
                    (if (line.lowercase().contains("and")) 10 else 0) +
                    (if (line.lowercase().contains("files")) 10 else 0) +
                    (if (line.lowercase().contains("update")) 5 else 0) +
                    (if (line.lowercase().contains("improve")) 8 else 0) +
                    (if (line.lowercase().contains("enhance")) 8 else 0) +
                    (if (line.lowercase().contains("implement")) 8 else 0) +
                    (if (line.lowercase().contains("add")) 5 else 0)
                line to score
            }
            
            val bestLine = scoredLines.maxByOrNull { it.second }?.first
            if (bestLine != null) {
                LOG.info("AI Commits: Selected scored line: '$bestLine'")
                return bestLine
            }
        }
        
        // Strategy 3: Find the most substantial line that looks like a commit message
        val substantialLines = lines.filter { it.length >= 15 && it.length <= 100 }
        if (substantialLines.isNotEmpty()) {
            // Prefer lines that seem to describe multiple changes
            val multiFileLines = substantialLines.filter { line ->
                val lower = line.lowercase()
                lower.contains("multiple") || lower.contains("several") || 
                lower.contains("across") || lower.contains("and") ||
                lower.contains("files") || lower.contains("components")
            }
            
            if (multiFileLines.isNotEmpty()) {
                val selected = multiFileLines.maxByOrNull { it.length }!!
                LOG.info("AI Commits: Selected multi-file line: '$selected'")
                return selected
            }
            
            // Fallback to longest substantial line
            val selected = substantialLines.maxByOrNull { it.length }!!
            LOG.info("AI Commits: Selected substantial line: '$selected'")
            return selected
        }
        
        // Strategy 4: Last resort - take the first meaningful line and enhance it
        val firstLine = lines.firstOrNull { it.length > 10 } ?: "chore: update files"
        val enhanced = if (!firstLine.contains(":") && !firstLine.lowercase().contains("multiple")) {
            // Try to make it more comprehensive if it seems too narrow
            when {
                firstLine.lowercase().contains("update") -> "chore: update multiple files with enhancements"
                firstLine.lowercase().contains("fix") -> "fix: resolve issues across multiple components"
                firstLine.lowercase().contains("add") -> "feat: add features and improvements across files"
                else -> "chore: improve multiple components - $firstLine"
            }
        } else {
            firstLine
        }
        
        // Ensure it's not too long
        val final = if (enhanced.length > 72) {
            enhanced.take(69) + "..."
        } else {
            enhanced
        }
        
        LOG.info("AI Commits: Final enhanced commit message: '$final'")
        return final
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