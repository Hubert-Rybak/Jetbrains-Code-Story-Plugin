package io.github.hubertrybak.codestory.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.StoryDraft
import io.github.hubertrybak.codestory.core.DiffLine
import io.github.hubertrybak.codestory.core.ProposalDiff
import io.github.hubertrybak.codestory.core.StepChange
import io.github.hubertrybak.codestory.core.StepKind
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.ThreadKind
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** The colors of the timeline. Steps that explain code use the accent of the gutter step badges. */
object TimelineColors {
    val ACCENT: Color = JBColor.namedColor("CodeStory.stepBadgeBackground", JBColor(0x3574F0, 0x3574F0))
    val PROPOSAL: Color = JBColor.namedColor("CodeStory.proposalAccent", JBColor(0x208A3C, 0x5FAD65))
    val CONTEXT: Color = JBColor.namedColor("CodeStory.contextAccent", JBColor(0x818594, 0x868A91))
    val RAIL: Color = JBColor.namedColor("CodeStory.timelineRail", JBColor(0xD3D5DB, 0x43454A))

    fun of(kind: StepKind): Color = when (kind) {
        StepKind.EXPLANATION -> ACCENT
        StepKind.CONTEXT -> CONTEXT
        StepKind.PROPOSAL -> PROPOSAL
    }
}

fun stepKindLabel(kind: StepKind): String = when (kind) {
    StepKind.EXPLANATION -> "Explanation"
    StepKind.CONTEXT -> "Context"
    StepKind.PROPOSAL -> "Proposal"
}

/** The code of a step as the IDE shows it now: [lines] start at file line [firstLine]. [file] is `null` when the file cannot open. */
class StepCode(val lines: List<String>, val firstLine: Int, val file: VirtualFile?, val fileName: String)

/** One step in the timeline, at index [index] of [stored]. */
class TimelineItem(
    val stored: StoredStory,
    val index: Int,
    val code: StepCode?,
    val change: StepChange?,
    val canShowDiff: Boolean,
    val first: Boolean,
    val last: Boolean,
)

/** What the reader can do with a step in the timeline. */
interface TimelineActions {
    /** Plays step [index] in the timeline view. [line] is the file line for the caret; [focus] moves the keyboard focus to the editor. */
    fun navigate(index: Int, line: Int?, focus: Boolean)
    fun showChangeDiff(index: Int)
    fun previewProposal(index: Int)
    fun askToApply(index: Int)
}

/**
 * A step in the timeline: a node on the rail and a card with the title, the explanation of the
 * agent, the code of the step and the comment threads. Context steps hang from the rail under the
 * step that they give background for, and proposals show the proposed code as a change.
 * Story text is untrusted, so it shows as plain text or through [StoryTextPane], never as HTML.
 */
class TimelineStep(
    private val project: Project,
    private val readerComments: StoryComments,
    private val actions: TimelineActions,
) : JPanel(BorderLayout()), Disposable {
    var item: TimelineItem? = null
        private set
    val index: Int get() = item?.index ?: -1
    val stepId: String? get() = item?.let { it.stored.story.steps[it.index].id }
    private val step get() = item?.let { it.stored.story.steps[it.index] }
    val kind: StepKind get() = step?.kind ?: StepKind.EXPLANATION

    /** The step that plays now. Its card has the accent border and the comment input. */
    var isCurrent = false
        private set

    val rail = Rail()
    val chip = KindChip()
    /** Keeps the chip at the top when the title wraps. Hidden with the chip, so the title of an explanation starts at the edge of the card. */
    private val chipBox = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
        add(chip, BorderLayout.NORTH)
    }
    val title = PlainText(bold = true, keepCaret = true).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = "Step title"
    }
    /** Opens the code of the step in the editor, with the keyboard focus. Its text is a path from the story, so HTML is off. */
    val locationLink = ActionLink("") { navigate(null, focus = true) }.apply { putClientProperty("html.disable", true) }
    private val change = JBLabel().apply { font = JBFont.label().asBold() }
    private val status = JBLabel()
    private val noteIcon = JBLabel(AllIcons.General.Information).apply { verticalAlignment = SwingConstants.TOP }
    val note = PlainText(keepCaret = true).apply { isFocusable = false }
    private val noteRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(noteIcon, BorderLayout.WEST)
        add(note, BorderLayout.CENTER)
    }
    val text = StoryTextPane { link -> item?.let { openStoryLink(project, it.stored.root, link) } }
    /** Created for the first step with code, then reused while the step keeps code. */
    var snippet: CodeSnippet? = null
        private set
    val snippetCaption = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val snippetBox = JPanel(VerticalLayout(4)).apply { isOpaque = false }
    val showDiff = ActionLink("Show diff") { actions.showChangeDiff(index) }
    val preview = ActionLink("Preview change") { actions.previewProposal(index) }
    val askToApply = ActionLink("Ask agent to apply") { actions.askToApply(index) }
    private val links = JPanel(HorizontalLayout(12, SwingConstants.CENTER)).apply {
        isOpaque = false
        add(showDiff); add(preview); add(askToApply)
    }
    val comments = CommentsPanel(readerComments, compact = true, layoutChanged = ::commentsChanged).apply { isOpaque = false }
    /** Holds the comment input of the current step, and of a step where the reader writes a reply. */
    val composer = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
        add(comments.composer, BorderLayout.CENTER)
    }
    val card = Card()

    init {
        isOpaque = false
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(chipBox, BorderLayout.WEST)
            add(title, BorderLayout.CENTER)
        }
        val meta = JPanel(HorizontalLayout(8, SwingConstants.CENTER)).apply {
            isOpaque = false
            add(locationLink); add(change); add(status)
        }
        card.add(header)
        card.add(meta)
        card.add(noteRow)
        card.add(text)
        card.add(snippetBox)
        card.add(links)
        card.add(comments)
        card.add(composer)
        add(rail, BorderLayout.WEST)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, GAP, RIGHT_MARGIN)
            add(card, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        val open = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) navigate(null, focus = e.clickCount > 1)
            }
        }
        title.addMouseListener(open)
        rail.addMouseListener(open)
        rail.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    fun update(item: TimelineItem, current: Boolean) {
        this.item = item
        this.isCurrent = current
        val step = item.stored.story.steps[item.index]
        getAccessibleContext().accessibleName = "Step ${item.index + 1}" + (if (step.kind == StepKind.EXPLANATION) "" else ", ${stepKindLabel(step.kind).lowercase()}") + ": " + step.title
        chip.kind = step.kind
        chipBox.isVisible = chip.isVisible
        if (title.text != step.title) title.text = step.title
        title.toolTipText = "Open step ${item.index + 1} in the editor"
        val location = step.location
        locationLink.isVisible = location != null
        if (location != null) {
            locationLink.text = location.file.substringAfterLast('/') + ":" + lines(location.startLine, location.endLine)
            locationLink.toolTipText = "Open " + location.file + " at " + (if (location.startLine == location.endLine) "line ${location.startLine}" else "lines ${location.startLine}–${location.endLine}")
        }
        change.isVisible = item.change != null
        item.change?.let {
            change.text = it.badge
            change.foreground = if (it == StepChange.ADDED) FileStatus.ADDED.color else FileStatus.MODIFIED.color
            change.toolTipText = if (it == StepChange.ADDED) "All lines are new since the story's base" else "Some lines changed since the story's base"
        }
        val stepStatus = item.stored.status(item.index)
        status.isVisible = stepStatus == StepStatus.CODE_CHANGED || stepStatus == StepStatus.LOST
        status.text = if (stepStatus == StepStatus.LOST) "lost" else "code changed"
        status.foreground = WARNING
        val statusText = statusNote(stepStatus)
        noteRow.isVisible = statusText != null
        statusText?.let { note.text = it }
        noteIcon.icon = if (stepStatus == StepStatus.LOST) AllIcons.General.Warning else AllIcons.General.Information
        text.markdown = step.comment
        showCode(item)
        val proposal = step.kind == StepKind.PROPOSAL
        showDiff.isVisible = item.canShowDiff
        preview.isVisible = proposal && location != null && step.proposedCode != null && item.code?.file != null
        askToApply.isVisible = proposal && !changeRequested(item)
        links.isVisible = showDiff.isVisible || preview.isVisible || askToApply.isVisible
        comments.show(item.stored, item.index)
        rail.first = item.first
        rail.last = item.last
        updateComposer()
        revalidate()
        repaint()
    }

    /** Only the threads changed, for example a new answer or an unsent comment. */
    fun refreshComments(stored: StoredStory) {
        val item = item ?: return
        if (stored.story.steps.getOrNull(item.index)?.id != stepId) return
        this.item = TimelineItem(stored, item.index, item.code, item.change, item.canShowDiff, item.first, item.last)
        comments.show(stored, item.index)
        askToApply.isVisible = kind == StepKind.PROPOSAL && !changeRequested(this.item!!)
        links.isVisible = showDiff.isVisible || preview.isVisible || askToApply.isVisible
        updateComposer()
    }

    fun setCurrent(current: Boolean) {
        if (isCurrent == current) return
        isCurrent = current
        updateComposer()
        repaint()
    }

    /** Moves the keyboard focus to the comment input of this step. */
    fun startComment() {
        composer.isVisible = true
        comments.startComment()
    }

    private fun navigate(line: Int?, focus: Boolean) {
        if (item != null) actions.navigate(index, line, focus)
    }

    /** A change request for a proposal waits for an agent or for posting, so the reader cannot ask twice. */
    private fun changeRequested(item: TimelineItem): Boolean {
        val step = item.stored.story.steps[item.index]
        return step.threads.any { it.kind == ThreadKind.CHANGE && it.state.isPending } ||
            readerComments.drafts(item.stored).any { it.step == step.id && it.thread == null && it.kind == ThreadKind.CHANGE }
    }

    private fun showCode(item: TimelineItem) {
        val step = item.stored.story.steps[item.index]
        val code = item.code
        val proposed = step.proposedCode?.takeIf { step.kind == StepKind.PROPOSAL }?.let(ProposalDiff::proposedLines)
        val lines = when {
            proposed != null && code != null -> SnippetLines.proposal(ProposalDiff.lines(code.lines, proposed, code.firstLine), code.firstLine)
            // New code, or a proposal for code that the IDE cannot read.
            proposed != null -> SnippetLines.newCode(proposed)
            code != null -> SnippetLines.code(code.lines, code.firstLine)
            else -> null
        }
        snippetBox.isVisible = lines != null
        if (lines == null) return
        val snippet = snippet ?: CodeSnippet(project, this).also { created ->
            snippet = created
            created.onLineClick = { line, double -> navigate(line, double) }
            snippetBox.add(snippetCaption)
            snippetBox.add(created)
        }
        val changes = lines.changes
        snippetCaption.isVisible = changes != null
        if (changes != null) {
            val removed = changes.count { it == DiffLine.Change.REMOVED }
            val added = changes.count { it == DiffLine.Change.ADDED }
            snippetCaption.text = when {
                step.location == null -> "Proposed new code"
                code == null -> "Proposed code"
                else -> "Proposed change: ${count(removed, "line")} removed, ${count(added, "line")} added"
            }
        }
        snippet.show(lines, code?.file, code?.fileName ?: step.location?.file?.substringAfterLast('/'))
    }

    private fun commentsChanged() {
        updateComposer()
        revalidate()
        repaint()
    }

    private fun updateComposer() {
        composer.isVisible = isCurrent || comments.isWriting()
    }

    override fun dispose() {
        snippet = null
    }

    /** The node of the step on the vertical line of the timeline. Context hangs to the right of the line. */
    inner class Rail : JComponent() {
        var first = false
        var last = false

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(if (kind == StepKind.CONTEXT) CONTEXT_RAIL_WIDTH else RAIL_WIDTH), 0)

        /** The center of the node: level with the first line of the title. */
        val nodeY: Int get() {
            val titleTop = SwingUtilities.convertPoint(title.parent ?: return JBUI.scale(TOP), title.x, title.y, this).y
            return titleTop + title.getFontMetrics(title.font).height / 2
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                UISettings.setupAntialiasing(g2)
                val line = JBUIScale.scale(1.5f)
                val x = JBUI.scale(RAIL_WIDTH) / 2f
                val y = nodeY.toFloat()
                g2.color = TimelineColors.RAIL
                g2.stroke = BasicStroke(line)
                g2.draw(Line2D.Float(x, if (first) y else 0f, x, if (last) y else height.toFloat()))
                val number = (index + 1).toString()
                when (kind) {
                    StepKind.CONTEXT -> {
                        val branch = JBUI.scale(CONTEXT_RAIL_WIDTH - RAIL_WIDTH / 2).toFloat()
                        g2.draw(Line2D.Float(x, y, branch, y))
                        node(g2, branch, y, JBUI.scale(CONTEXT_NODE).toFloat(), number, TimelineColors.CONTEXT, round = true)
                    }
                    StepKind.EXPLANATION -> node(g2, x, y, JBUI.scale(NODE).toFloat(), number, TimelineColors.ACCENT, round = true)
                    StepKind.PROPOSAL -> node(g2, x, y, JBUI.scale(NODE).toFloat(), number, TimelineColors.PROPOSAL, round = false)
                }
            } finally {
                g2.dispose()
            }
        }

        /** The current step is filled with its color; other steps are outlined. A proposal is a square, so it does not depend on color alone. */
        private fun node(g2: Graphics2D, x: Float, y: Float, size: Float, label: String, color: Color, round: Boolean) {
            val shape = if (round) Ellipse2D.Float(x - size / 2, y - size / 2, size, size)
                else RoundRectangle2D.Float(x - size / 2, y - size / 2, size, size, size / 3, size / 3)
            g2.color = if (isCurrent) color else UIUtil.getPanelBackground()
            g2.fill(shape)
            if (!isCurrent) {
                g2.color = color
                g2.stroke = BasicStroke(JBUIScale.scale(1.5f))
                g2.draw(shape)
            }
            g2.font = JBFont.label().deriveFont(Font.BOLD, if (kind == StepKind.CONTEXT) JBUIScale.scale(8.5f) else JBUIScale.scale(10f))
            // Not JBColor.WHITE, which turns black in dark themes.
            g2.color = if (isCurrent) Color.WHITE else UIUtil.getLabelForeground()
            val glyphs = g2.font.createGlyphVector(g2.fontRenderContext, label).visualBounds
            g2.drawString(label, (x - glyphs.width / 2 - glyphs.x).toFloat(), (y - glyphs.height / 2 - glyphs.y).toFloat())
        }
    }

    /** The rounded card of the step. Proposals are tinted, context has a dashed border, and the current step has the accent border. */
    inner class Card : JPanel(VerticalLayout(8)) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(TOP - 2, 12, 10, 12)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                val width = if (isCurrent) JBUIScale.scale(2f) else JBUIScale.scale(1f)
                val arc = JBUIScale.scale(10f)
                val shape = RoundRectangle2D.Float(width / 2, width / 2, this.width - width, height - width, arc, arc)
                if (kind == StepKind.PROPOSAL) {
                    g2.color = ColorUtil.withAlpha(TimelineColors.PROPOSAL, 0.07)
                    g2.fill(shape)
                }
                g2.color = when {
                    isCurrent -> TimelineColors.ACCENT
                    kind == StepKind.PROPOSAL -> TimelineColors.PROPOSAL
                    else -> JBColor.border()
                }
                val dash = JBUIScale.scale(4f)
                g2.stroke = if (kind == StepKind.CONTEXT && !isCurrent) BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(dash, dash), 0f)
                    else BasicStroke(width)
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        const val RAIL_WIDTH = 40
        const val CONTEXT_RAIL_WIDTH = 68
        const val NODE = 22
        const val CONTEXT_NODE = 16
        /** The space above the title in a card. The first node of a step without a card layout yet sits here. */
        const val TOP = 12
        const val GAP = 12
        const val RIGHT_MARGIN = 12
        private val WARNING = JBColor.namedColor("Label.warningForeground", JBColor(0xA35F00, 0xD9A343))

        private fun lines(start: Int, end: Int) = if (start == end) "$start" else "$start–$end"
        private fun count(n: Int, noun: String) = "$n ${if (n == 1) noun else noun + "s"}"
    }
}

/** "Context" or "Proposal" in the color of its kind. An explanation has no chip. */
class KindChip : JBLabel() {
    var kind: StepKind = StepKind.EXPLANATION
        set(value) {
            field = value
            text = stepKindLabel(value)
            icon = if (value == StepKind.PROPOSAL) AllIcons.Actions.IntentionBulb else null
            isVisible = value != StepKind.EXPLANATION
            foreground = TimelineColors.of(value)
            repaint()
        }

    init {
        font = JBFont.small().asBold()
        border = JBUI.Borders.empty(1, 6)
        iconTextGap = JBUI.scale(3)
        isVisible = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.color = ColorUtil.withAlpha(TimelineColors.of(kind), 0.14)
            val arc = height.toFloat()
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** The change request that asks the agent to apply the proposal of the step [stepId]. */
fun applyProposalDraft(stored: StoredStory, stepId: String?) =
    StoryDraft(story = stored.key, step = stepId, kind = ThreadKind.CHANGE, text = APPLY_PROPOSAL_TEXT)

const val APPLY_PROPOSAL_TEXT = "Apply this proposal."
