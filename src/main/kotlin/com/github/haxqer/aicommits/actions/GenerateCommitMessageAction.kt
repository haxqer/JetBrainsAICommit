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
            LOG.info("AI Commits: Setting commit message: '${message.take(100)}...'")
            
            // Method 1: Try CommitMessage interface
            val commitMessageComponent = commitWorkflowUi.commitMessageUi
            if (commitMessageComponent is CommitMessage) {
                LOG.info("AI Commits: Using CommitMessage interface")
                commitMessageComponent.setCommitMessage(message)
                return
            }
            
            // Method 2: Try text property
            try {
                commitWorkflowUi.commitMessageUi.text = message
                LOG.info("AI Commits: Successfully set via text property")
                return
            } catch (e: Exception) {
                LOG.debug("AI Commits: Text property failed", e)
            }
            
            // Method 3: Reflection fallback
            try {
                val componentClass = commitMessageComponent.javaClass
                
                // Try textField
                try {
                    val textField = componentClass.getDeclaredField("textField")
                    textField.isAccessible = true
                    val textComponent = textField.get(commitMessageComponent)
                    if (textComponent != null) {
                        val setText = textComponent.javaClass.getMethod("setText", String::class.java)
                        setText.invoke(textComponent, message)
                        LOG.info("AI Commits: Set via textField reflection")
                        return
                    }
                } catch (e: Exception) {
                    LOG.debug("AI Commits: textField reflection failed", e)
                }
                
                // Try editor field
                try {
                    val editorField = componentClass.getDeclaredField("editor")
                    editorField.isAccessible = true
                    val editor = editorField.get(commitMessageComponent)
                    if (editor != null) {
                        val getDocument = editor.javaClass.getMethod("getDocument")
                        val document = getDocument.invoke(editor)
                        
                        val getLength = document.javaClass.getMethod("getLength")
                        val currentLength = getLength.invoke(document) as Int
                        
                        if (currentLength > 0) {
                            val remove = document.javaClass.getMethod("remove", Int::class.java, Int::class.java)
                            remove.invoke(document, 0, currentLength)
                        }
                        
                        val insertString = document.javaClass.getMethod("insertString", Int::class.java, String::class.java, javax.swing.text.AttributeSet::class.java)
                        insertString.invoke(document, 0, message, null)
                        LOG.info("AI Commits: Set via editor document")
                        return
                    }
                } catch (e: Exception) {
                    LOG.debug("AI Commits: editor reflection failed", e)
                }
            } catch (e: Exception) {
                LOG.debug("AI Commits: Reflection failed", e)
            }
            
            LOG.warn("AI Commits: All methods failed, showing dialog")
            showCommitMessageDialog(commitWorkflowUi, message)
            
        } catch (e: Exception) {
            LOG.error("AI Commits: Fatal error setting commit message", e)
            showCommitMessageDialog(commitWorkflowUi, message)
        }
    }
    
    private fun showCommitMessageDialog(commitWorkflowUi: CommitWorkflowUi, message: String) {
        try {
            // Try to get project
            var project: Project? = null
            try {
                val methods = commitWorkflowUi.javaClass.methods
                val getProjectMethod = methods.find { it.name == "getProject" }
                if (getProjectMethod != null) {
                    project = getProjectMethod.invoke(commitWorkflowUi) as? Project
                }
            } catch (e: Exception) {
                LOG.debug("AI Commits: Could not get project", e)
            }
            
            if (project != null) {
                val result = Messages.showYesNoDialog(
                    project,
                    "Generated commit message:\n\n$message\n\nCopy to clipboard?",
                    "AI Commit Message",
                    "Copy",
                    "Cancel",
                    Messages.getInformationIcon()
                )
                
                if (result == Messages.YES) {
                    try {
                        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        val selection = java.awt.datatransfer.StringSelection(message)
                        clipboard.setContents(selection, null)
                        LOG.info("AI Commits: Copied to clipboard")
                    } catch (e: Exception) {
                        LOG.warn("AI Commits: Failed to copy to clipboard", e)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("AI Commits: Dialog failed", e)
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