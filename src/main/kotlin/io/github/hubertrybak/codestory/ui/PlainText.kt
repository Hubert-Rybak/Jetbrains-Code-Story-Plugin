package io.github.hubertrybak.codestory.ui

import com.intellij.ide.ui.UISettings
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Graphics
import java.awt.Graphics2D

/**
 * Wrapped plain text in the label font, also after a theme change. A text area never interprets
 * HTML, so story and comment text from files and readers stays text.
 */
open class PlainText(value: String = "", editable: Boolean = false, private val bold: Boolean = false) : JBTextArea(value) {
    /** Shown while the text is empty, at the place and in the font of the text. */
    var placeholder: String? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        isEditable = editable
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty()
        updateFont()
    }

    // The superclass constructor calls this before [bold] is set; init sets the font again.
    override fun updateUI() {
        super.updateUI()
        updateFont()
    }

    private fun updateFont() {
        font = if (bold) JBFont.label().asBold() else JBFont.label()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val hint = placeholder?.takeIf { document.length == 0 } ?: return
        val g2 = g.create() as Graphics2D
        try {
            UISettings.setupAntialiasing(g2)
            g2.font = font
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(hint, insets.left, insets.top + g2.fontMetrics.ascent)
        } finally {
            g2.dispose()
        }
    }
}
