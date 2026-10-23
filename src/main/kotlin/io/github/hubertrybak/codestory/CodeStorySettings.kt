package io.github.hubertrybak.codestory

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

fun interface StorySettingsListener {
    fun settingsChanged()

    companion object {
        val TOPIC = Topic(StorySettingsListener::class.java)
    }
}

/** How the reader follows a story: a step card beside the code, or the timeline of all steps next to the editor. */
enum class StoryView { CARD, TIMELINE }

@Service(Service.Level.APP)
@State(name = "CodeStorySettings", storages = [Storage("codeStory.xml")])
class CodeStorySettings : PersistentStateComponent<CodeStorySettings.Options> {
    class Options {
        var bringToFront = true
        var highlightColor: Int? = null
        var optionalStyleHints = false
        var projectSkillInstallation = false
        var setupNotificationShown = false
        @Volatile var automaticFeedback = true
        /** The step card size that the reader chose by dragging, in unscaled pixels; 0 means the default size. */
        var cardWidth = 0
        var cardHeight = 0
        /** The view that the reader used last. New stories open in it. */
        var storyView = StoryView.CARD
    }

    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) { options = state }

    val rangeBackground: Color
        get() = options.highlightColor?.let { Color(it, true) }
            ?: JBColor.namedColor("CodeStory.stepRangeBackground", JBColor(0xE6EEFC, 0x2D3A4E))

    companion object {
        fun getInstance(): CodeStorySettings = service()
    }
}

class CodeStoryConfigurable : Configurable {
    private var panel: JPanel? = null
    private var bringToFront: JCheckBox? = null
    private var optionalStyleHints: JCheckBox? = null
    private var projectSkillInstallation: JCheckBox? = null
    private var automaticFeedback: JCheckBox? = null
    private var themeDefault: JCheckBox? = null
    private var color: ColorPanel? = null
    private var cardView: JRadioButton? = null
    private var timelineView: JRadioButton? = null

    override fun getDisplayName() = "Code Story"

    override fun createComponent(): JComponent {
        bringToFront = JCheckBox("Bring the IDE to the front on a new story")
        cardView = JRadioButton("Step card beside the code")
        timelineView = JRadioButton("Timeline of all steps")
        ButtonGroup().apply { add(cardView); add(timelineView) }
        optionalStyleHints = JCheckBox("Include passive voice and -ing hints in agent reports")
        automaticFeedback = JCheckBox("Let connected agents listen for submitted feedback automatically")
        projectSkillInstallation = JCheckBox("Install the Code Story skill in the current project instead of the user folder")
        color = ColorPanel().apply { setSupportTransparency(true) }
        themeDefault = JCheckBox("Use theme default").apply {
            addActionListener { color!!.isEnabled = !isSelected }
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            add(bringToFront)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Show new stories in:"))
                add(cardView)
                add(timelineView)
            })
            add(JLabel("The IDE remembers the view that you use last."))
            add(optionalStyleHints)
            add(automaticFeedback)
            add(projectSkillInstallation)
            add(JLabel("Skill installation writes to both .agents/skills/code-story and .claude/skills/code-story."))
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Highlight color:"))
                add(color)
                add(themeDefault)
            })
            panel = this
            reset()
        }
    }

    override fun isModified(): Boolean = panel != null && CodeStorySettings.getInstance().state.let {
        bringToFront!!.isSelected != it.bringToFront || selectedColor() != it.highlightColor ||
            selectedView() != it.storyView ||
            optionalStyleHints!!.isSelected != it.optionalStyleHints ||
            automaticFeedback!!.isSelected != it.automaticFeedback ||
            projectSkillInstallation!!.isSelected != it.projectSkillInstallation
    }

    override fun apply() {
        CodeStorySettings.getInstance().state.apply {
            bringToFront = this@CodeStoryConfigurable.bringToFront!!.isSelected
            storyView = selectedView()
            highlightColor = selectedColor()
            optionalStyleHints = this@CodeStoryConfigurable.optionalStyleHints!!.isSelected
            automaticFeedback = this@CodeStoryConfigurable.automaticFeedback!!.isSelected
            projectSkillInstallation = this@CodeStoryConfigurable.projectSkillInstallation!!.isSelected
        }
        ApplicationManager.getApplication().messageBus.syncPublisher(StorySettingsListener.TOPIC).settingsChanged()
    }

    override fun reset() {
        if (panel == null) return
        val settings = CodeStorySettings.getInstance()
        bringToFront!!.isSelected = settings.state.bringToFront
        cardView!!.isSelected = settings.state.storyView == StoryView.CARD
        timelineView!!.isSelected = settings.state.storyView == StoryView.TIMELINE
        optionalStyleHints!!.isSelected = settings.state.optionalStyleHints
        automaticFeedback!!.isSelected = settings.state.automaticFeedback
        projectSkillInstallation!!.isSelected = settings.state.projectSkillInstallation
        themeDefault!!.isSelected = settings.state.highlightColor == null
        color!!.selectedColor = settings.rangeBackground
        color!!.isEnabled = !themeDefault!!.isSelected
    }

    private fun selectedColor() = if (themeDefault!!.isSelected) null else color!!.selectedColor?.rgb

    private fun selectedView() = if (timelineView!!.isSelected) StoryView.TIMELINE else StoryView.CARD

    override fun disposeUIResources() {
        panel = null
        bringToFront = null
        cardView = null
        timelineView = null
        optionalStyleHints = null
        automaticFeedback = null
        projectSkillInstallation = null
        themeDefault = null
        color = null
    }
}
