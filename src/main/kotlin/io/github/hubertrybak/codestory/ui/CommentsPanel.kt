package io.github.hubertrybak.codestory.ui

import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.CodeStorySettings
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
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
 * The comment threads of a story or of one step, and the reader's unsent comments. [composer] posts
 * a new comment or a reply at once; the host shows it where it never scrolls away. A [compact]
 * panel, in the step card, hides itself while the step has no comments.
 * Comments come from story files and from readers, so they show as plain text, never as HTML.
 */
class CommentsPanel(
    private val comments: StoryComments,
    private val compact: Boolean = false,
    private val layoutChanged: () -> Unit = {},
    /** Escape in the composer; `null` leaves Escape to the host, such as a tool window. */
    private val escape: (() -> Unit)? = null,
) : JPanel(VerticalLayout(10)) {
    private var stored: StoredStory? = null
    private var stepIndex: Int? = null

    /**
     * Unposted input, by the story or step that it belongs to. It hides while the reader looks at
     * another story or step and shows again on return, so a reload or an agent's revision never loses it.
     */
    private val inputs = mutableMapOf<String, Input>()

    /** Holds the input of the shown story or step. */
    val composer: JPanel = JPanel(BorderLayout()).apply { isOpaque = false; isVisible = false }

    init {
        addHierarchyListener { if (isShowing) markShownRead() }
    }

    /** Resolved threads stay hidden until the reader shows them. */
    val showResolved = JBCheckBox("Show resolved").apply {
        isOpaque = false
        addActionListener { rebuild() }
    }

    /**
     * Posts a new comment on [StoryDraft.step], a reply in [StoryDraft.thread], or an unsent comment
     * that the reader edits; posting removes that unsent comment. [title] names the input, and
     * [context] shows above the text for a reply or an edit.
     */
    inner class Input(val target: String, val draft: StoryDraft, val title: String, private val context: String? = null) {
        val text: PlainText = PlainText(draft.text, editable = true).apply {
            placeholder = when {
                draft.thread != null -> "Write a reply"
                draft.step != null -> "Ask about this step, or request a change"
                else -> "Ask about the story, or request a change"
            }
            accessibleContext.accessibleName = title
            for (modifier in listOf(InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK)) {
                registerKeyboardAction({ post() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifier), JComponent.WHEN_FOCUSED)
            }
            escape?.let { registerKeyboardAction({ it() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED) }
        }
        val question = JBRadioButton("Question", draft.kind != ThreadKind.CHANGE).apply { isOpaque = false }
        val change = JBRadioButton("Change request", draft.kind == ThreadKind.CHANGE).apply { isOpaque = false }
        val kind: ThreadKind get() = if (draft.kind == ThreadKind.REFRESH) ThreadKind.REFRESH else if (change.isSelected) ThreadKind.CHANGE else ThreadKind.QUESTION
        private val kindGroup = ButtonGroup().apply { add(question); add(change) }
        val post = JButton("Post").apply {
            toolTipText = "Post now (${KeymapUtil.getKeystrokeText(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK))})"
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            isOpaque = false
            addActionListener { post() }
        }
        /** Discards the text, and leaves a reply or an edit. */
        val cancel = ActionLink("Cancel") { cancel() }

        /** A new comment without text is the resting state of the composer. */
        val isBlank: Boolean get() = text.text.isBlank() && draft.thread == null && context == null

        /** The height of the text, to follow a new wrapped line rather than every key. */
        private var textHeight = -1

        val component: JComponent = InputBox(text).apply {
            context?.let { add(JBLabel(it).apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.NORTH) }
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                if (draft.kind != ThreadKind.REFRESH) add(row(question, change), BorderLayout.WEST)
                add(row(cancel, post), BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }

        init {
            text.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = textChanged()
                override fun removeUpdate(e: DocumentEvent) = textChanged()
                override fun changedUpdate(e: DocumentEvent) = textChanged()
            })
            textChanged()
        }

        private fun textChanged() {
            post.isEnabled = text.text.isNotBlank()
            cancel.isVisible = !isBlank
            // The text view updates after this listener. A floating card must resize its balloon itself.
            SwingUtilities.invokeLater {
                val height = text.preferredSize.height
                if (textHeight >= 0 && height != textHeight) layoutChanged()
                textHeight = height
            }
        }

        /** Posts the text now. An empty input posts nothing. */
        fun post() {
            val value = text.text.trim()
            val stored = stored ?: return
            if (value.isEmpty()) return
            val focused = text.isFocusOwner
            inputs.remove(target)
            comments.post(stored, draft.copy(text = value, kind = kind))
            rebuild()
            if (focused) shownInput()?.text?.requestFocusInWindow()
        }

        fun cancel() {
            val focused = text.isFocusOwner
            inputs.remove(target)
            rebuild()
            if (focused) shownInput()?.text?.requestFocusInWindow()
        }

        /** The same input in another panel, or for the same story at its new file. */
        fun copyTo(panel: CommentsPanel, target: String = this.target, story: String = draft.story) =
            panel.Input(target, draft.copy(story = story, text = text.text, kind = kind), title, context)
    }

    fun show(stored: StoredStory?, stepIndex: Int?) {
        val previous = this.stored?.let(::target)
        this.stored = stored
        this.stepIndex = stepIndex
        // An untouched input needs no place in memory while the reader looks elsewhere. The same story
        // or step shows again after any reload, so its input, and its keyboard focus, stay.
        if (previous != null && previous != stored?.let(::target)) {
            inputs[previous]?.takeIf { it.isBlank && !it.text.isFocusOwner }?.let { inputs.remove(previous) }
        }
        rebuild()
    }

    /** Move unsaved input with the story when Share changes its file identity. */
    fun relocated(previous: StoredStory, current: StoredStory) {
        val moved = inputs.values.filter { it.draft.story == previous.key }
        val shown = stored?.key == previous.key
        if (!shown && moved.isEmpty()) return
        val focused = moved.any { it.text.isFocusOwner }
        if (shown) stored = current
        for (old in moved) {
            inputs.remove(old.target)
            val target = current.key + old.target.removePrefix(previous.key)
            inputs[target] = old.copyTo(this, target, current.key)
        }
        rebuild()
        if (focused) shownInput()?.text?.requestFocusInWindow()
    }

    /** The input of the shown story or step. */
    fun shownInput(): Input? = stored?.let { inputs[target(it)] }

    /** The reader writes or edits a comment, or is about to, so the input must stay in view. */
    fun isWriting(): Boolean = shownInput()?.let { !it.isBlank || it.text.isFocusOwner } == true

    /** Moves the keyboard focus to the input for a new comment about the shown story or step. */
    fun startComment() {
        val stored = stored ?: return
        input(stored).text.requestFocusInWindow()
    }

    private fun input(stored: StoredStory): Input = inputs.getOrPut(target(stored)) {
        val step = stepIndex?.let { stored.story.steps[it] }
        Input(target(stored), StoryDraft(story = stored.key, step = step?.id), if (step == null) "New comment about the story" else "New comment about this step")
    }

    /** Opens [draft] in the input, which may be a withdrawn comment, an unsent comment or a reply. */
    private fun open(draft: StoryDraft, title: String, context: String?) {
        val stored = stored ?: return
        inputs[target(stored)] = Input(target(stored), draft, title, context)
        rebuild()
        shownInput()?.text?.requestFocusInWindow()
    }

    private fun edit(draft: StoryDraft) {
        val reply = draft.thread != null
        open(draft, if (reply) "Edit unsent reply" else "Edit unsent comment", if (reply) "Edit your unsent reply" else "Edit your unsent comment")
    }

    /** A withdrawn comment opens for editing only where it belongs; elsewhere it waits as an unsent comment. */
    private fun belongsHere(draft: StoryDraft) = stored?.let { target(it) == draft.story + "#" + draft.step.orEmpty() } == true

    private fun reply(stored: StoredStory, stepId: String?, thread: StoryThread) {
        // Typed text moves into the reply; an edited unsent comment stays unsent.
        val typed = shownInput()?.takeIf { it.draft.id !in comments.drafts(stored).map { draft -> draft.id } }
        val excerpt = thread.messages.firstOrNull()?.text?.lineSequence()?.first()?.let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
        open(
            StoryDraft(story = stored.key, step = stepId, thread = thread.id, text = typed?.text?.text.orEmpty(), kind = typed?.kind ?: ThreadKind.QUESTION),
            "Reply in this thread", excerpt?.let { "Reply to “$it”" } ?: "Reply in this thread",
        )
    }

    private fun target(stored: StoredStory) = stored.key + "#" + stepIndex?.let { stored.story.steps[it].id }.orEmpty()

    private fun rebuild() {
        val focused = shownInput()?.text?.isFocusOwner == true
        removeAll()
        val stored = stored
        isVisible = stored != null && build(stored)
        showComposer(stored?.let(::input))
        revalidate()
        repaint()
        if (focused) shownInput()?.text?.requestFocusInWindow()
        if (isShowing) markShownRead()
        layoutChanged()
    }

    private fun showComposer(input: Input?) {
        val component = input?.component
        if (composer.componentCount == 1 && composer.getComponent(0) === component) return
        composer.removeAll()
        component?.let { composer.add(it, BorderLayout.CENTER) }
        composer.isVisible = component != null
        composer.revalidate()
        composer.repaint()
    }

    fun markShownRead() {
        val stored = stored ?: return
        val threads = stepIndex?.let { stored.story.steps[it].threads } ?: stored.story.threads
        comments.read(stored, threads.filter { showResolved.isSelected || it.state != ThreadState.RESOLVED })
    }

    /** Keeps partially typed card input when an agent replaces the same step. */
    fun restoreInput(previous: CommentsPanel) {
        val old = previous.shownInput()?.takeUnless { it.isBlank } ?: return
        val stored = stored ?: return
        if (old.target != target(stored)) return
        inputs[old.target] = old.copyTo(this)
        rebuild()
    }

    /** Returns `false` when a compact panel has nothing to show. */
    private fun build(stored: StoredStory): Boolean {
        val story = stored.story
        val step = stepIndex?.let { story.steps[it] }
        val threads = step?.threads ?: story.threads
        // An unsent comment shows where posting puts it: in its thread, on its step, or on the story when its step is gone.
        val drafts = comments.drafts(stored)
        val threadIds = StoryThreads.all(story).map { it.second.id }.toSet()
        val stepIds = story.steps.mapNotNull { it.id }.toSet()
        val editing = shownInput()?.draft?.id
        val replies = drafts.filter { it.thread in threadIds && it.id != editing }.groupBy { it.thread }
        val unsent = drafts.filter { it.thread !in threadIds && it.id != editing && if (step != null) it.step == step.id else it.step !in stepIds }
        if (compact && threads.isEmpty() && unsent.isEmpty()) return false
        val resolved = threads.count { it.state == ThreadState.RESOLVED }
        val shown = threads.filter { showResolved.isSelected || it.state != ThreadState.RESOLVED }

        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Comments").apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
            if (resolved > 0) add(showResolved, BorderLayout.EAST)
        })
        for (thread in shown) add(threadView(stored, step?.id, thread, replies[thread.id].orEmpty()))
        for (draft in unsent) add(draftView(stored, draft))
        if (shown.isEmpty() && unsent.isEmpty()) {
            add(note(if (resolved > 0) "Resolved threads are hidden." else "No comments yet. Post a question or a change request below. Your listening agent answers here."))
        }
        if (StoryThreads.all(story).any { it.second.state == ThreadState.SUBMITTED }) {
            add(JPanel(VerticalLayout(2)).apply {
                isOpaque = false
                add(note(if (CodeStorySettings.getInstance().state.automaticFeedback)
                    "Listening agents pick up posted comments at once. If your agent is stopped, tell it $FEEDBACK_COMMAND."
                else "Automatic listening is off. Tell your agent $FEEDBACK_COMMAND."))
                add(row(ActionLink("Copy") { comments.copy(FEEDBACK_COMMAND) }, ActionLink("Copy for Codex") { comments.copy(CODEX_FEEDBACK_COMMAND) }))
            })
        }
        return true
    }

    private fun threadView(stored: StoredStory, stepId: String?, thread: StoryThread, replies: List<StoryDraft>) = block(JBColor.border()).apply {
        add(JBLabel("${threadKindLabel(thread.kind)} · ${threadStateLabel(thread.state)}").apply { font = JBFont.label().asBold() })
        thread.removedStep?.let { add(note("step removed: $it")) }
        for (message in thread.messages) {
            add(JBLabel(header(message)).apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() })
            add(text(message.text))
            message.storyChanges?.let { add(note("Story changes: $it")) }
            if (message.changedFiles.isNotEmpty()) add(note("Changed files: ${message.changedFiles.joinToString()}"))
        }
        for (reply in replies) add(draftView(stored, reply))
        val replying = shownInput()?.draft?.thread == thread.id
        val last = thread.messages.lastOrNull()
        add(row(
            ActionLink("Withdraw") { comments.withdraw(stored, thread) { draft -> if (belongsHere(draft)) edit(draft) } }
                .apply { isVisible = thread.state == ThreadState.SUBMITTED && last?.author == MessageAuthor.READER },
            ActionLink("Reply") { reply(stored, stepId, thread) }
                .apply { isVisible = !thread.state.isPending && replies.isEmpty() && !replying },
            ActionLink("Resolve") { comments.resolve(stored, thread) }
                .apply { isVisible = !thread.state.isPending && thread.state != ThreadState.RESOLVED },
        ))
    }

    /** A comment that is not in the story file: being posted, or unsent after a withdrawal or a failed write. */
    private fun draftView(stored: StoredStory, draft: StoryDraft) = block(JBUI.CurrentTheme.Link.Foreground.ENABLED).apply {
        val kind = if (draft.thread != null) "reply" else threadKindLabel(draft.kind).lowercase()
        val sending = comments.isSending(draft)
        add(JBLabel(if (sending) "Posting your $kind…" else "Unsent $kind").apply { font = JBFont.label().asBold() })
        add(text(draft.text))
        if (!sending) add(row(
            ActionLink("Post") { comments.post(stored, draft); rebuild() },
            ActionLink("Edit") { edit(draft) },
            ActionLink("Delete") { comments.delete(draft); rebuild() },
        ))
    }

    private fun header(message: ThreadMessage): String =
        if (message.author == MessageAuthor.READER) "You"
        else "Agent${message.outcome?.let { " · ${it.value}" }.orEmpty()}"

    private fun block(line: Color) = JPanel(VerticalLayout(4)).apply {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLine(line, 0, 2, 0, 0), JBUI.Borders.emptyLeft(8))
    }

    private fun row(vararg components: JComponent) = JPanel(HorizontalLayout(12, SwingConstants.CENTER)).apply {
        isOpaque = false
        components.forEach(::add)
    }

    private fun note(value: String) = text(value).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun text(value: String) = PlainText(value)
}

/**
 * A rounded input box around [text], like a text field. The text grows with its lines, up to
 * [MAX_ROWS], then scrolls.
 */
private class InputBox(private val text: PlainText) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 8)
        add(object : JBScrollPane(text) {
            override fun getPreferredSize(): Dimension {
                val row = text.getFontMetrics(text.font).height
                val wanted = text.preferredSize.height.coerceIn(row * MIN_ROWS, row * MAX_ROWS)
                return Dimension(super.getPreferredSize().width, wanted + insets.top + insets.bottom)
            }

            // A new line of text must resize the panel around the input, not only this scroll pane.
            override fun isValidateRoot() = false
        }.apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        text.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(10).toFloat()
            val line = JBUI.scale(1).toFloat()
            g2.color = UIUtil.getTextFieldBackground()
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            g2.color = if (text.isFocusOwner) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()
            g2.stroke = java.awt.BasicStroke(line)
            g2.draw(RoundRectangle2D.Float(line / 2, line / 2, width - line, height - line, arc, arc))
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val MIN_ROWS = 2
        const val MAX_ROWS = 6
    }
}
