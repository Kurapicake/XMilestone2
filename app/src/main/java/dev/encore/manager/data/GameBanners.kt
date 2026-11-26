package dev.encore.manager.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Per-game custom banner images for the GameSpace selected-game panel (Master Prompt §6D).
 *
 * The picked image is decoded, center-cropped to a 16:9 banner shape, downscaled (so a huge
 * photo never balloons app storage) and saved as JPEG under the app's own private files dir —
 * not just a remembered content:// Uri, which would need a persisted permission grant that can
 * still be revoked by the system. Saving a local copy means the banner survives reboots and a
 * revoked grant with no extra bookkeeping. Package names are validated by the caller (they come
 * from [dev.encore.manager.GameEntry], not from user text input) before being used as file names.
 */
object GameBanners {

    private const val TARGET_WIDTH = 1280
    private const val ASPECT_W = 16f
    private const val ASPECT_H = 9f
    private const val JPEG_QUALITY = 88

    private fun dir(context: Context): File = File(context.filesDir, "game_banners").apply { mkdirs() }

    private fun fileFor(context: Context, pkg: String): File = File(dir(context), "$pkg.jpg")

    /** Absolute path of the saved banner for [pkg], or null if none has been set. */
    fun bannerPath(context: Context, pkg: String): String? {
        val f = fileFor(context, pkg)
        return if (f.exists() && f.length() > 0L) f.absolutePath else null
    }

    /**
     * Decodes [source] (from a photo-picker result), center-crops it to 16:9, downscales to at
     * most [TARGET_WIDTH] wide and saves it as this game's banner. Returns false (and leaves any
     * previous banner untouched) if the image could not be read or decoded — a picker failure or
     * an inaccessible Uri never crashes the app or corrupts the existing banner.
     */
    suspend fun saveBanner(context: Context, pkg: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = context.contentResolver.openInputStream(source)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return@withContext false

            val cropped = centerCropToAspect(decoded, ASPECT_W, ASPECT_H)
            val scaled = if (cropped.width > TARGET_WIDTH) {
                val targetHeight = (TARGET_WIDTH * ASPECT_H / ASPECT_W).toInt()
                Bitmap.createScaledBitmap(cropped, TARGET_WIDTH, targetHeight, true)
            } else {
                cropped
            }

            val target = fileFor(context, pkg)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tmp).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            tmp.renameTo(target)

            if (scaled !== cropped) scaled.recycle()
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()
            true
        }.getOrDefault(false)
    }

    /** Removes a game's custom banner so the panel falls back to its default artwork again. */
    fun clearBanner(context: Context, pkg: String) {
        runCatching { fileFor(context, pkg).delete() }
    }

    private fun centerCropToAspect(src: Bitmap, aspectW: Float, aspectH: Float): Bitmap {
        val targetRatio = aspectW / aspectH
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        return if (srcRatio > targetRatio) {
            // Source is wider than the target: crop left/right.
            val newWidth = (src.height * targetRatio).toInt().coerceAtMost(src.width)
            val x = (src.width - newWidth) / 2
            Bitmap.createBitmap(src, x, 0, newWidth, src.height)
        } else {
            // Source is taller than the target: crop top/bottom.
            val newHeight = (src.width / targetRatio).toInt().coerceAtMost(src.height)
            val y = (src.height - newHeight) / 2
            Bitmap.createBitmap(src, 0, y, src.width, newHeight)
        }
    }
}
