package io.github.hubertrybak.codestory.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Icons of the plugin. Dark themes use the `_dark` variant of each file. */
object CodeStoryIcons {
    @JvmField
    val Timeline: Icon = IconLoader.getIcon("/icons/timeline.svg", CodeStoryIcons::class.java)
}
