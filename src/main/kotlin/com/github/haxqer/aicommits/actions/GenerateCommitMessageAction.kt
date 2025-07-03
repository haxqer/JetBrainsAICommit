package com.github.haxqer.aicommits.actions

import com.github.haxqer.aicommits.services.AICommitsService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.vcs.commit.CommitWorkflowUi
import git4idea.GitUtil
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import kotlinx.coroutines.runBlocking
import git4idea.commands.GitCommandResult
import git4idea.commands.Git

class GenerateCommitMessageAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(GenerateCommitMessageAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitWorkflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) as? CommitWorkflowUi
        
        if (commitWorkflowUi == null) {
            showError(project, "Unable to access commit dialog. Please use this action from the commit dialog.")
            return
        }

        val gitRepository = GitUtil.getRepositoryManager(project).repositories.firstOrNull()
        if (gitRepository == null) {
            showError(project, "No Git repository found in this project.")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating AI Commit Message", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Collecting changes..."
                    indicator.fraction = 0.1
                    
                    val selectedChanges = commitWorkflowUi.getIncludedChanges()
                    if (selectedChanges.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            showError(project, "No changes selected for commit.")
                        }
                        return
                    }

                    indicator.text = "Generating diff..."
                    indicator.fraction = 0.3
                    
                    val diff = generateDiff(gitRepository, selectedChanges)
                    val files = selectedChanges.map { it.virtualFile?.name ?: "unknown" }
                    val branch = gitRepository.currentBranch?.name ?: "main"

                    indicator.text = "Calling AI service..."
                    indicator.fraction = 0.6

                    val aiService = AICommitsService.getInstance()
                    val settings = com.github.haxqer.aicommits.settings.AICommitsSettings.getInstance()
                    
                    val result = if (settings.enableStreaming) {
                        // Streaming mode
                        ApplicationManager.getApplication().invokeLater {
                            setCommitMessage(commitWorkflowUi, "Generating...")
                        }
                        
                        runBlocking {
                            aiService.generateCommitMessageStreaming(diff, files, branch) { streamedContent ->
                                // Update UI in real-time on the EDT thread
                                ApplicationManager.getApplication().invokeLater {
                                    if (streamedContent.isNotEmpty()) {
                                        setCommitMessage(commitWorkflowUi, streamedContent)
                                        // Update progress text to show streaming
                                        indicator.text = "Streaming response... ${streamedContent.take(30)}..."
                                    }
                                }
                            }
                        }
                    } else {
                        // Non-streaming mode (original behavior)
                        runBlocking {
                            aiService.generateCommitMessage(diff, files, branch)
                        }
                    }

                    indicator.fraction = 1.0

                    ApplicationManager.getApplication().invokeLater {
                        result.fold(
                            onSuccess = { finalMessage ->
                                // Final update with the complete message
                                setCommitMessage(commitWorkflowUi, finalMessage)
                                indicator.text = "Completed"
                            },
                            onFailure = { error ->
                                showError(project, "Failed to generate commit message: ${error.message}")
                            }
                        )
                    }
                } catch (e: Exception) {
                    LOG.warn("Error generating commit message", e)
                    ApplicationManager.getApplication().invokeLater {
                        showError(project, "Failed to generate commit message: ${e.message}")
                    }
                }
            }
        })
    }

    private fun generateDiff(repository: GitRepository, changes: Collection<Change>): String {
        try {
            // Get file paths
            val paths = changes.mapNotNull { change ->
                change.virtualFile?.path?.removePrefix(repository.root.path + "/")
            }
            
            // Try staged diff first
            val git = Git.getInstance()
            val handler = GitLineHandler(repository.project, repository.root, GitCommand.DIFF)
            handler.addParameters("--cached")
            
            if (paths.isNotEmpty()) {
                handler.addParameters("--")
                handler.addParameters(paths)
            }
            
            val result = git.runCommand(handler)
            return if (result.success() && result.output.isNotEmpty()) {
                result.output.joinToString("\n")
            } else {
                // Fallback to unstaged diff
                val unstagedHandler = GitLineHandler(repository.project, repository.root, GitCommand.DIFF)
                if (paths.isNotEmpty()) {
                    unstagedHandler.addParameters("--")
                    unstagedHandler.addParameters(paths)
                }
                val unstagedResult = git.runCommand(unstagedHandler)
                unstagedResult.output.joinToString("\n").ifEmpty { "No diff available" }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to generate diff", e)
            return "Error generating diff: ${e.message}"
        }
    }

    private fun setCommitMessage(commitWorkflowUi: CommitWorkflowUi, message: String) {
        try {
            LOG.info("AI Commits: Setting commit message with ${message.lines().size} lines: '${message.take(100)}...'")
            
            // Try to access the commit message component
            val commitMessageComponent = commitWorkflowUi.commitMessageUi
            
            // Method 1: Try CommitMessage interface
            if (commitMessageComponent is CommitMessage) {
                LOG.info("AI Commits: Using CommitMessage.setCommitMessage() method")
                commitMessageComponent.setCommitMessage(message)
                
                // Verify the message was set correctly
                val currentText = commitMessageComponent.comment
                LOG.info("AI Commits: After setting, current text: '${currentText.take(100)}...'")
                
                if (currentText != message) {
                    LOG.warn("AI Commits: CommitMessage.setCommitMessage() didn't preserve full text, trying alternative method")
                    // Try setting the comment property directly
                    try {
                        commitMessageComponent.comment = message
                        LOG.info("AI Commits: Successfully set via comment property")
                    } catch (e: Exception) {
                        LOG.warn("AI Commits: Failed to set via comment property", e)
                    }
                }
            } else {
                LOG.info("AI Commits: Using commitMessageUi.text property")
                // Method 2: Try text property
                commitWorkflowUi.commitMessageUi.text = message
                
                // Verify the message was set
                val currentText = commitWorkflowUi.commitMessageUi.text
                LOG.info("AI Commits: After setting text property, current text: '${currentText.take(100)}...'")
            }
            
            // Method 3: Try to access the underlying text component directly
            try {
                val textComponent = commitMessageComponent
                
                // Try to find and access text area/editor component
                val componentClass = textComponent.javaClass
                LOG.info("AI Commits: CommitMessageUi component class: ${componentClass.name}")
                
                // Look for common text component methods
                try {
                    val textField = componentClass.getDeclaredField("textField")
                    textField.isAccessible = true
                    val textFieldComponent = textField.get(textComponent)
                    
                    if (textFieldComponent != null) {
                        val setTextMethod = textFieldComponent.javaClass.getMethod("setText", String::class.java)
                        setTextMethod.invoke(textFieldComponent, message)
                        LOG.info("AI Commits: Successfully set text via textField reflection")
                    }
                } catch (e: Exception) {
                    LOG.debug("AI Commits: textField reflection failed", e)
                    
                    // Try alternative field names
                    try {
                        val editorField = componentClass.getDeclaredField("editor")
                        editorField.isAccessible = true
                        val editorComponent = editorField.get(textComponent)
                        
                        if (editorComponent != null) {
                            val documentMethod = editorComponent.javaClass.getMethod("getDocument")
                            val document = documentMethod.invoke(editorComponent)
                            val insertStringMethod = document.javaClass.getMethod("insertString", Int::class.java, String::class.java, javax.swing.text.AttributeSet::class.java)
                            
                            // Clear existing text first
                            val removeMethod = document.javaClass.getMethod("remove", Int::class.java, Int::class.java)
                            val lengthMethod = document.javaClass.getMethod("getLength")
                            val length = lengthMethod.invoke(document) as Int
                            removeMethod.invoke(document, 0, length)
                            
                            // Insert new text
                            insertStringMethod.invoke(document, 0, message, null)
                            LOG.info("AI Commits: Successfully set text via editor document")
                        }
                    } catch (e2: Exception) {
                        LOG.debug("AI Commits: editor reflection failed", e2)
                    }
                }
            } catch (e: Exception) {
                LOG.debug("AI Commits: Direct component access failed", e)
            }
            
        } catch (e: Exception) {
            LOG.warn("AI Commits: Failed to set commit message in UI", e)
            
            // Final fallback: show the message in a dialog
            try {
                // Try to get project from commitWorkflowUi
                val project = when {
                    commitWorkflowUi.javaClass.methods.any { it.name == "getProject" } -> {
                        commitWorkflowUi.javaClass.getMethod("getProject").invoke(commitWorkflowUi) as? Project
                    }
                    else -> null
                }
                
                if (project != null) {
                    // Show dialog with option to copy to clipboard
                    val action = Messages.showYesNoDialog(
                        project,
                        "Generated commit message:\n\n$message\n\nWould you like to copy this to clipboard?",
                        "AI Generated Commit Message",
                        "Copy to Clipboard",
                        "OK",
                        Messages.getInformationIcon()
                    )
                    
                    if (action == Messages.YES) {
                        // Copy to clipboard
                        try {
                            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            val stringSelection = java.awt.datatransfer.StringSelection(message)
                            clipboard.setContents(stringSelection, null)
                            LOG.info("AI Commits: Copied message to clipboard")
                        } catch (clipboardError: Exception) {
                            LOG.warn("AI Commits: Failed to copy to clipboard", clipboardError)
                        }
                    }
                } else {
                    LOG.error("AI Commits: Could not get project reference for fallback dialog")
                }
            } catch (dialogError: Exception) {
                LOG.error("AI Commits: Failed to show fallback dialog", dialogError)
            }
        }
    }

    private fun showError(project: Project, message: String) {
        Messages.showErrorDialog(project, message, "AI Commits Error")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val commitWorkflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        
        e.presentation.isEnabled = project != null && commitWorkflowUi != null
        e.presentation.isVisible = commitWorkflowUi != null
    }
} 