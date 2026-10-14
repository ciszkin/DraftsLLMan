package by.ciszkin.draftsllman.scene.svg

object SvgSanitizer {
    private val ID_ALLOW = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")

    fun sanitizeId(raw: String): String {
        val trimmed = raw.trim()
        return if (ID_ALLOW.matches(trimmed)) trimmed
        else "_" + trimmed.map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }.joinToString("")
    }

    fun escapeText(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun escapeAttr(s: String): String = escapeText(s).replace("\"", "&quot;").replace("'", "&#39;")
}
