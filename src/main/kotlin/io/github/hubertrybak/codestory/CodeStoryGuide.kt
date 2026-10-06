package io.github.hubertrybak.codestory

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/** Loaded from this plugin's jar, so the guide and the installed implementation travel together. */
object CodeStoryGuide {
    fun markdown(): String {
        val version = checkNotNull(PluginManagerCore.getPlugin(PluginId.getId("io.github.hubertrybak.codestory"))).version
        val guide = checkNotNull(CodeStoryGuide::class.java.getResourceAsStream("/code-story/guide.md")) {
            "The Code Story writing guide is missing from the plugin."
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return "# Code Story writing guide\n\nInstalled plugin version: $version\n\n$guide"
    }
}
