package org.autojs.autojs.util

object FileUtils {
    object TYPE {
        val JAVASCRIPT = FileType("js", ".js")
    }

    data class FileType(val extension: String, val extensionWithDot: String)
}
