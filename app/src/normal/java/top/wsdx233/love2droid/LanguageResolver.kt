package top.wsdx233.love2droid

import java.io.File

object LanguageResolver {
    private val scopes = mapOf(
        "lua" to "source.lua",
        "java" to "source.java",
        "kt" to "source.kotlin",
        "kts" to "source.kotlin",
        "py" to "source.python",
        "js" to "source.js",
        "jsx" to "source.js",
        "ts" to "source.js",
        "tsx" to "source.js",
        "html" to "text.html.basic",
        "htm" to "text.html.basic",
        "xml" to "text.xml",
        "md" to "text.html.markdown",
        "markdown" to "text.html.markdown",
        "json" to "source.json",
        "css" to "source.css",
        "sh" to "source.shell",
        "bash" to "source.shell",
    )

    fun scopeFor(file: File): String? {
        val extension = file.extension.lowercase()
        return scopes[extension]
    }

    fun displayName(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "lua" -> "Lua"
            "java" -> "Java"
            "kt", "kts" -> "Kotlin"
            "js", "jsx" -> "JavaScript"
            "ts", "tsx" -> "TypeScript"
            "py" -> "Python"
            "html", "htm" -> "HTML"
            "xml" -> "XML"
            "md", "markdown" -> "Markdown"
            "json" -> "JSON"
            "css" -> "CSS"
            "sh", "bash" -> "Shell"
            else -> if (extension.isBlank()) "Text" else extension.uppercase()
        }
    }
}
