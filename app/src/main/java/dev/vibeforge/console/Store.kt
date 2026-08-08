package dev.vibeforge.console

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Settings live in EncryptedSharedPreferences because one of them is a GitHub
 * token with write access to your repositories. Plain preferences would be
 * readable by anything with root or a backup extraction.
 */
object Store {

    private const val FILE = "vibeforge-secure"

    private fun prefs(context: Context) = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore failures happen on a few devices and after some restores.
        // Falling back keeps the app usable; the token is the price.
        context.getSharedPreferences("vibeforge-plain", Context.MODE_PRIVATE)
    }

    fun get(context: Context, key: String, fallback: String = "") =
        prefs(context).getString(key, fallback) ?: fallback

    fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    const val TOKEN = "token"
    const val OWNER = "owner"
    const val REPO = "repo"
    const val BRANCH = "branch"
    const val PROJECT_URI = "projectUri"
    const val LAST_COMMIT = "lastCommit"
}

object Installer {

    /**
     * Download an APK and hand it to the package installer.
     *
     * The file must live somewhere FileProvider can serve from, and the intent
     * needs a content:// URI with a read grant — a file:// URI throws
     * FileUriExposedException on anything modern.
     */
    fun download(
        context: Context,
        url: String,
        token: String?,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        // One file, always overwritten: keeping old APKs in the cache is how a
        // phone quietly loses a gigabyte.
        val out = File(dir, "download.apk")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VibeForge")
            // Only needed for private repos; harmless on public ones.
            if (!token.isNullOrEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/octet-stream")
            }
            connectTimeout = 20000
            readTimeout = 120000
        }

        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            out.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
        conn.disconnect()
        if (out.length() < 1024) throw IllegalStateException("Downloaded file is too small to be an APK")
        return out
    }

    /**
     * Copy a downloaded APK into shared Downloads so it survives cache
     * eviction and can be found in a file manager — which is the whole point
     * of downloading without installing.
     */
    fun keep(context: Context, apk: File, name: String): String {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
            return "Downloads/$name"
        }
        // Older devices without the Downloads collection: keep the cache copy.
        val fallback = File(context.getExternalFilesDir(null), name)
        apk.copyTo(fallback, overwrite = true)
        return fallback.absolutePath
    }

    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.files", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
