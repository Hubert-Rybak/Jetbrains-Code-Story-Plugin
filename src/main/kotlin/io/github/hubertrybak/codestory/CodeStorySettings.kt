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
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

fun interface StorySettingsListener {
    fun settingsChanged()

    companion object {
        val TOPIC = Topic(StorySettingsListener::class.java)
    }
}

@Service(Service.Level.APP)
@State(name = "CodeStorySettings", storages = [Storage("codeStory.xml")])
class CodeStorySettings : PersistentStateComponent<CodeStorySettings.Options> {
    class Options {
        var bringToFront = true
        var highlightColor: Int? = null
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
    private var themeDefault: JCheckBox? = null
    private var color: ColorPanel? = null

    override fun getDisplayName() = "Code Story"

    override fun createComponent(): JComponent {
        bringToFront = JCheckBox("Bring the IDE to the front on a new story")
        color = ColorPanel().apply { setSupportTransparency(true) }
        themeDefault = JCheckBox("Use theme default").apply {
            addActionListener { color!!.isEnabled = !isSelected }
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            add(bringToFront)
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
        bringToFront!!.isSelected != it.bringToFront || selectedColor() != it.highlightColor
    }

    override fun apply() {
        CodeStorySettings.getInstance().state.apply {
            bringToFront = this@CodeStoryConfigurable.bringToFront!!.isSelected
            highlightColor = selectedColor()
        }
        ApplicationManager.getApplication().messageBus.syncPublisher(StorySettingsListener.TOPIC).settingsChanged()
    }

    override fun reset() {
        if (panel == null) return
        val settings = CodeStorySettings.getInstance()
        bringToFront!!.isSelected = settings.state.bringToFront
        themeDefault!!.isSelected = settings.state.highlightColor == null
        color!!.selectedColor = settings.rangeBackground
        color!!.isEnabled = !themeDefault!!.isSelected
    }

    private fun selectedColor() = if (themeDefault!!.isSelected) null else color!!.selectedColor?.rgb

    override fun disposeUIResources() {
        panel = null
        bringToFront = null
        themeDefault = null
        color = null
    }
}
