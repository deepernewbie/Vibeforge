package dev.vibeforge.console

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Turns one picture into the icon set Android expects.
 *
 * Every project built here launches with the default grey Android icon unless
 * someone produces five PNGs at five densities plus an adaptive foreground,
 * which is not something anyone is going to do by hand on a phone. Given a
 * cropped square, this writes the lot straight into the project so the next
 * build ships with a real icon.
 *
 * Adaptive icons matter more than they look: without one, modern launchers
 * draw a white plate behind the legacy icon and the result looks broken. The
 * foreground layer is padded to the safe zone so nothing important is clipped
 * when the launcher masks it to a circle or a squircle.
 */
object IconMaker {

    /** mipmap density buckets and their launcher icon size in pixels. */
    private val DENSITIES = listOf(
        "mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192
    )

    /**
     * The adaptive foreground is 108dp but only the middle 72dp is guaranteed
     * visible, so the artwork is drawn at two thirds scale and centred.
     */
    private const val ADAPTIVE_SAFE_FRACTION = 0.66f

    data class Generated(val files: Map<String, ByteArray>, val note: String)

    fun load(context: Context, uri: Uri, maxEdge: Int = 1024): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = if (longest > maxEdge) Integer.highestOneBit(longest / maxEdge) else 1
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    } catch (e: Exception) {
        null
    }

    fun crop(source: Bitmap, region: Rect): Bitmap {
        val safe = Rect(
            region.left.coerceIn(0, source.width - 1),
            region.top.coerceIn(0, source.height - 1),
            region.right.coerceIn(1, source.width),
            region.bottom.coerceIn(1, source.height)
        )
        val side = minOf(safe.width(), safe.height()).coerceAtLeast(1)
        return Bitmap.createBitmap(source, safe.left, safe.top, side, side)
    }

    private fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun square(source: Bitmap, size: Int, round: Boolean, background: Int?): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

        background?.let {
            if (round) canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it })
            else canvas.drawColor(it)
        }

        val scaled = Bitmap.createScaledBitmap(source, size, size, true)
        if (round) {
            // Draw through a circular mask rather than cropping, so edges stay smooth.
            val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(mask).drawCircle(size / 2f, size / 2f, size / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            canvas.drawBitmap(scaled, 0f, 0f, null)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(mask, 0f, 0f, paint)
            paint.xfermode = null
        } else {
            canvas.drawBitmap(scaled, 0f, 0f, paint)
        }
        return out
    }

    private fun adaptiveForeground(source: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val inner = (size * ADAPTIVE_SAFE_FRACTION).toInt()
        val offset = (size - inner) / 2f
        val scaled = Bitmap.createScaledBitmap(source, inner, inner, true)
        canvas.drawBitmap(scaled, offset, offset, Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true })
        return out
    }

    /** Average the edge pixels — a plausible background for the adaptive layer. */
    fun edgeColour(source: Bitmap): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val stride = maxOf(1, source.width / 32)
        for (x in 0 until source.width step stride) {
            for (y in listOf(0, source.height - 1)) {
                val p = source.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
            }
        }
        for (y in 0 until source.height step stride) {
            for (x in listOf(0, source.width - 1)) {
                val p = source.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
            }
        }
        if (n == 0) return Color.WHITE
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * Produce every file the project needs, keyed by path relative to the repo
     * root. Written alongside the source so the next push carries them.
     */
    fun generate(source: Bitmap, backgroundColour: Int): Generated {
        val files = LinkedHashMap<String, ByteArray>()

        for ((bucket, size) in DENSITIES) {
            files["app/src/main/res/mipmap-$bucket/ic_launcher.png"] =
                png(square(source, size, round = false, background = null))
            files["app/src/main/res/mipmap-$bucket/ic_launcher_round.png"] =
                png(square(source, size, round = true, background = null))
            // Adaptive foregrounds are 108dp; at each bucket that is 2.25× the
            // launcher size.
            files["app/src/main/res/mipmap-$bucket/ic_launcher_foreground.png"] =
                png(adaptiveForeground(source, (size * 2.25f).toInt()))
        }

        val hex = String.format("#%06X", 0xFFFFFF and backgroundColour)
        files["app/src/main/res/values/ic_launcher_background.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="ic_launcher_background">$hex</color>
            </resources>
        """.trimIndent().toByteArray()

        val adaptive = """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@color/ic_launcher_background" />
                <foreground android:drawable="@mipmap/ic_launcher_foreground" />
            </adaptive-icon>
        """.trimIndent().toByteArray()
        files["app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"] = adaptive
        files["app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"] = adaptive

        return Generated(
            files,
            "${files.size} icon files across ${DENSITIES.size} densities, " +
            "plus an adaptive icon on $hex."
        )
    }

    /**
     * The manifest has to point at the icon or none of this shows up. Returns
     * the edited manifest, or null when it already refers to one.
     */
    fun patchManifest(manifestXml: String): String? {
        if (manifestXml.contains("android:icon=")) return null
        val anchor = Regex("<application[^>]*").find(manifestXml) ?: return null
        val insertion = anchor.value +
            "\n        android:icon=\"@mipmap/ic_launcher\"" +
            "\n        android:roundIcon=\"@mipmap/ic_launcher_round\""
        return manifestXml.replaceRange(anchor.range, insertion)
    }
}
