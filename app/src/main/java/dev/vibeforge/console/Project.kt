package dev.vibeforge.console

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Turns a folder the user picked into the map of files a commit needs.
 *
 * Skipping matters more than it sounds. A project folder on a phone collects
 * build output, editor state and the odd 40 MB model cache, and pushing those
 * is slow, noisy and occasionally rejected. The rules below are the ones that
 * would otherwise bite on every single push.
 */
object Project {

    private val SKIP_DIRS = setOf(
        ".git", "build", ".gradle", "node_modules", ".idea", ".vscode",
        "captures", ".externalNativeBuild", ".cxx", "data", "backups"
    )

    private val SKIP_FILES = setOf(".DS_Store", "local.properties", "Thumbs.db")

    private val SKIP_EXT = setOf("apk", "aab", "keystore", "jks", "iml", "log", "zip")

    /** GitHub's blob API takes anything, but a phone upload should stay sane. */
    private const val MAX_FILE_BYTES = 3 * 1024 * 1024

    data class Scan(
        val files: Map<String, ByteArray>,
        val skipped: List<String>,
        val totalBytes: Long
    )

    fun read(context: Context, treeUri: Uri): Scan {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Could not open that folder")
        val files = LinkedHashMap<String, ByteArray>()
        val skipped = mutableListOf<String>()
        var total = 0L

        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                if (name.startsWith(".") && name != ".github" && name != ".gitignore") {
                    continue
                }
                if (child.isDirectory) {
                    if (name in SKIP_DIRS) { skipped.add("$prefix$name/ (ignored)"); continue }
                    walk(child, "$prefix$name/")
                    continue
                }
                if (name in SKIP_FILES) { skipped.add("$prefix$name"); continue }
                if (name.substringAfterLast('.', "").lowercase() in SKIP_EXT) {
                    skipped.add("$prefix$name"); continue
                }
                if (child.length() > MAX_FILE_BYTES) {
                    skipped.add("$prefix$name (${child.length() / 1024} KB — too large)")
                    continue
                }
                val bytes = try {
                    context.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                } catch (e: Exception) { null }
                if (bytes == null) { skipped.add("$prefix$name (unreadable)"); continue }
                files["$prefix$name"] = bytes
                total += bytes.size
            }
        }

        walk(root, "")
        return Scan(files, skipped, total)
    }

    fun folderName(context: Context, treeUri: Uri): String =
        DocumentFile.fromTreeUri(context, treeUri)?.name ?: "project"
}
