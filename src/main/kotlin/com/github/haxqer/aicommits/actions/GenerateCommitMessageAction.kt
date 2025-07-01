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
            // Try to access the commit message component
            val commitMessageComponent = commitWorkflowUi.commitMessageUi
            if (commitMessageComponent is CommitMessage) {
                commitMessageComponent.setCommitMessage(message)
            } else {
                // Fallback: try to set via the UI interface
                commitWorkflowUi.commitMessageUi.text = message
            }
        } catch (e: Exception) {
            LOG.warn("Failed to set commit message in UI", e)
            // Show the message in a dialog as fallback
            val project = commitWorkflowUi.javaClass.getMethod("getProject").invoke(commitWorkflowUi) as Project
            Messages.showInfoMessage(project, "Generated commit message:\n\n$message", "AI Generated Commit Message")
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