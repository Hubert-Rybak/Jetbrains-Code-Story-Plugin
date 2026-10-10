package io.github.hubertrybak.codestory.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

abstract class PlaybackAction(private val perform: (StoryPlayer) -> Unit) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { perform(StoryPlayer.getInstance(it)) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { StoryPlayer.getInstance(it).isPlaying } == true
    }
}

class NextStepAction : PlaybackAction(StoryPlayer::next) {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = if (e.project?.let { StoryPlayer.getInstance(it).isLastStep } == true) "Finish" else "Next Step"
    }
}

class PreviousStepAction : PlaybackAction(StoryPlayer::previous) {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.presentation.isEnabled && e.project?.let { StoryPlayer.getInstance(it).stepIndex > 0 } == true
    }
}

class StopStoryAction : PlaybackAction(StoryPlayer::stop)

class CommentOnCurrentStepAction : PlaybackAction(StoryPlayer::commentOnCurrentStep)
