package io.github.hubertrybak.codestory

/** Loaded from this plugin's jar, so the guide and the installed implementation travel together. */
object CodeStoryGuide {
    fun markdown(): String {
        // The build writes the plugin version next to the guide.
        val version = resource("version.txt", "plugin version").trim()
        val guide = resource("guide.md", "writing guide")
        return "# Code Story writing guide\n\nInstalled plugin version: $version\n\n$guide"
    }

    private fun resource(name: String, description: String): String =
        checkNotNull(CodeStoryGuide::class.java.getResourceAsStream("/code-story/$name")) {
            "The Code Story $description is missing from the plugin."
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
