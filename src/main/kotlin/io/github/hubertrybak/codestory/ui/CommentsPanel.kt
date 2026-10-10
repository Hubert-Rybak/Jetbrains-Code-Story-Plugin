package io.github.hubertrybak.codestory.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.StoryDraft
import io.github.hubertrybak.codestory.core.MessageAuthor
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryThread
import io.github.hubertrybak.codestory.core.StoryThreads
import io.github.hubertrybak.codestory.core.ThreadKind
import io.github.hubertrybak.codestory.core.ThreadMessage
import io.github.hubertrybak.codestory.core.ThreadState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants

const val FEEDBACK_COMMAND = "/code-story feedback"
const val CODEX_FEEDBACK_COMMAND = "\$code-story feedback"

fun threadKindLabel(kind: ThreadKind): String = when (kind) {
    ThreadKind.QUESTION -> "Question"
    ThreadKind.CHANGE -> "Change request"
    ThreadKind.REFRESH -> "Refresh request"
}

/** What the reader sees about the state of a thread. */
fun threadStateLabel(state: ThreadState): String = when (state) {
    ThreadState.SUBMITTED -> "Waiting for agent"
    ThreadState.RECEIVED -> "Agent working"
    ThreadState.ANSWERED -> "Answered"
    ThreadState.DONE -> "Done"
    ThreadState.DECLINED -> "Declined"
    ThreadState.RESOLVED -> "Resolved"
}

/**
 * The comment threads of a story or of one step, the reader's drafts and the comment input.
 * Comments come from story files and from readers, so they show as plain text, never as HTML.
 */
class CommentsPanel(private val comments: StoryComments, private val compact: Boolean = false, private val layoutChanged: () -> Unit = {}) : JPanel(VerticalLayout(10)) {
    private var stored: StoredStory? = null
    private var stepIndex: Int? = null
    var expanded: Boolean = !compact
        set(value) { field = value; rebuild() }

    init {
        addHierarchyListener { if (isShowing) markShownRead() }
    }

    /**
     * The unsaved input. It belongs to one story or step: it hides while the reader looks at
     * another one and shows again on return, so a reload or an agent's revision never loses it.
     */
    var input: Input? = null
        private set

    /** Resolved threads stay hidden until the reader shows them. */
    val showResolved = JBCheckBox("Show resolved").apply {
        isOpaque = false
        addActionListener { rebuild() }
    }

    /** Shown while the story has drafts. Submit writes all drafts of the story as one round. */
    val submit = JButton("Submit Round").apply { addActionListener { stored?.let(comments::submit) } }

    /** Writes a new comment on [StoryDraft.step], a reply in [StoryDraft.thread], or changes a saved draft. */
    inner class Input(val target: String, val draft: StoryDraft, val title: String) {
        val text = JBTextArea(draft.text, 3, 0).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(4, 6))
            accessibleContext.accessibleName = title
            for (modifier in listOf(InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK)) {
                registerKeyboardAction({ save() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifier), JComponent.WHEN_FOCUSED)
            }
            registerKeyboardAction({ close() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)
        }
        val save = JButton("Save Draft").apply { addActionListener { save() } }
        val cancel = JButton("Cancel").apply { addActionListener { close() } }
        val component = JPanel(VerticalLayout(4)).apply {
            isOpaque = false
            add(JBLabel(title))
            add(text)
            add(row(save, cancel))
        }

        /** An empty input saves nothing; Delete removes a saved draft. */
        fun save() {
            val value = text.text.trim()
            if (value.isEmpty()) return close()
            input = null
            comments.save(draft.copy(text = value))
            rebuild()
        }

        fun close() {
            input = null
            rebuild()
        }
    }

    fun show(stored: StoredStory?, stepIndex: Int?) {
        this.stored = stored
        this.stepIndex = stepIndex
        rebuild()
    }

    /** The input of the shown story or step, if the reader started one. */
    fun shownInput(): Input? = stored?.let { stored -> input?.takeIf { it.target == target(stored) } }

    /** Opens the input for a new question about the shown story or step. */
    fun startComment() {
        shownInput()?.let { it.text.requestFocusInWindow(); return }
        val stored = stored ?: return
        val step = stepIndex?.let { stored.story.steps[it] }
        open(StoryDraft(story = stored.key, step = step?.id), if (step == null) "New question about the story" else "New question about this step")
    }

    private fun open(draft: StoryDraft, title: String) {
        val stored = stored ?: return
        input = Input(target(stored), draft, title)
        rebuild()
        input?.text?.requestFocusInWindow()
    }

    private fun target(stored: StoredStory) = stored.key + "#" + stepIndex?.let { stored.story.steps[it].id }.orEmpty()

    private fun rebuild() {
        val focused = input?.text?.isFocusOwner == true
        removeAll()
        val stored = stored
        isVisible = stored != null
        if (stored != null) build(stored)
        revalidate()
        repaint()
        if (focused) shownInput()?.text?.requestFocusInWindow()
        if (isShowing) markShownRead()
        layoutChanged()
    }

    fun markShownRead() {
        if (!expanded) return
        val stored = stored ?: return
        val threads = stepIndex?.let { stored.story.steps[it].threads } ?: stored.story.threads
        comments.read(stored, threads.filter { showResolved.isSelected || it.state != ThreadState.RESOLVED })
    }

    /** Keeps partially typed card input when an agent replaces the same step. */
    fun restoreInput(previous: CommentsPanel) {
        val old = previous.shownInput() ?: return
        val stored = stored ?: return
        if (old.target != target(stored)) return
        input = Input(old.target, old.draft.copy(text = old.text.text), old.title)
        expanded = previous.expanded
    }

    private fun build(stored: StoredStory) {
        val story = stored.story
        val step = stepIndex?.let { story.steps[it] }
        val threads = step?.threads ?: story.threads
        // A draft shows where Submit puts it: in its thread, on its step, or on the story when its step is gone.
        val drafts = comments.drafts(stored)
        val threadIds = StoryThreads.all(story).map { it.second.id }.toSet()
        val stepIds = story.steps.mapNotNull { it.id }.toSet()
        val replies = drafts.filter { it.thread in threadIds }.associateBy { it.thread }
        val newDrafts = drafts.filter { it.thread !in threadIds && if (step != null) it.step == step.id else it.step !in stepIds }
        val input = shownInput()
        if (compact && !expanded) {
            input?.let { add(it.component) }
            return
        }
        val editing = input?.draft?.id
        val resolved = threads.count { it.state == ThreadState.RESOLVED }
        val shown = threads.filter { showResolved.isSelected || it.state != ThreadState.RESOLVED }

        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Comments").apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
            if (resolved > 0) add(showResolved, BorderLayout.EAST)
        })
        for (thread in shown) add(threadView(stored, step?.id, thread, replies[thread.id]?.takeIf { it.id != editing }))
        for (draft in newDrafts.filter { it.id != editing }) add(draftView(draft, "Draft question"))
        if (shown.isEmpty() && newDrafts.isEmpty() && input == null) {
            add(note(if (resolved > 0) "Resolved threads are hidden." else "No comments yet. Ask a question, then submit it to your agent."))
        }
        input?.let { add(it.component) }
        submit.text = if (drafts.isEmpty()) "Submit Round" else "Submit Round (${drafts.size})"
        submit.isEnabled = drafts.isNotEmpty()
        add(row(ActionLink(if (step == null) "Comment on story" else "Comment on step") { startComment() }.apply { isVisible = input == null }, submit))
        if (StoryThreads.all(story).any { it.second.state == ThreadState.SUBMITTED }) {
            add(JPanel(VerticalLayout(2)).apply {
                isOpaque = false
                add(note("Waiting for your agent: tell it $FEEDBACK_COMMAND"))
                add(row(ActionLink("Copy") { comments.copy(FEEDBACK_COMMAND) }, ActionLink("Copy for Codex") { comments.copy(CODEX_FEEDBACK_COMMAND) }))
            })
        }
    }

    private fun threadView(stored: StoredStory, stepId: String?, thread: StoryThread, reply: StoryDraft?) = block(JBColor.border()).apply {
        add(JBLabel("${threadKindLabel(thread.kind)} · ${threadStateLabel(thread.state)}").apply { font = JBFont.label().asBold() })
        thread.removedStep?.let { add(note("step removed: $it")) }
        for (message in thread.messages) {
            add(JBLabel(header(message)).apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() })
            add(text(message.text))
            message.storyChanges?.let { add(note("Story changes: $it")) }
        }
        reply?.let { add(draftView(it, "Draft reply")) }
        val replying = input?.draft?.thread == thread.id
        val last = thread.messages.lastOrNull()
        add(row(
            ActionLink("Withdraw") { comments.withdraw(stored, thread) }
                .apply { isVisible = thread.state == ThreadState.SUBMITTED && last?.author == MessageAuthor.READER },
            ActionLink("Reply") { open(StoryDraft(story = stored.key, step = stepId, thread = thread.id, kind = thread.kind), "Reply in this thread") }
                .apply { isVisible = !thread.state.isPending && reply == null && !replying },
            ActionLink("Resolve") { comments.resolve(stored, thread) }
                .apply { isVisible = !thread.state.isPending && thread.state != ThreadState.RESOLVED },
        ))
    }

    private fun draftView(draft: StoryDraft, label: String) = block(JBUI.CurrentTheme.Link.Foreground.ENABLED).apply {
        add(JBLabel(label).apply { font = JBFont.label().asBold() })
        add(text(draft.text))
        add(row(
            ActionLink("Edit") { open(draft, "Edit draft") },
            ActionLink("Delete") { comments.delete(draft) },
        ))
    }

    private fun header(message: ThreadMessage): String =
        if (message.author == MessageAuthor.READER) "Reader · round ${message.round}"
        else "Agent · round ${message.round}${message.outcome?.let { " · ${it.value}" }.orEmpty()}"

    private fun block(line: Color) = JPanel(VerticalLayout(4)).apply {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLine(line, 0, 2, 0, 0), JBUI.Borders.emptyLeft(8))
    }

    private fun row(vararg components: JComponent) = JPanel(HorizontalLayout(12, SwingConstants.CENTER)).apply {
        isOpaque = false
        components.forEach(::add)
    }

    private fun note(value: String) = text(value).apply { foreground = UIUtil.getContextHelpForeground() }

    /** Story and comment text is plain: a text area never interprets HTML. */
    private fun text(value: String) = JBTextArea(value).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty()
    }
}
