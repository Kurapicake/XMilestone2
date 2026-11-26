package dev.encore.manager.ui

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.encore.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(256)

    fun peek(pkg: String): ImageBitmap? = cache.get(pkg)

    suspend fun load(pm: PackageManager, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
        if (bmp != null) cache.put(pkg, bmp)
        return bmp
    }
}

/**
 * The game/app icon chip used in the Game List Manager: the real launcher icon when it can be
 * loaded, otherwise the Encore Tweaks module's own `app_icon_fallback` artwork (from webroot/)
 * inside the same angular, neon-bordered frame so the grid never shows a blank placeholder.
 */
@Composable
fun AppIcon(pkg: String, modifier: Modifier = Modifier) {
    val pm = LocalContext.current.packageManager
    val bitmap by produceState<ImageBitmap?>(initialValue = IconCache.peek(pkg), key1 = pkg) {
        value = IconCache.load(pm, pkg)
    }
    val shape = CutCornerShape(6.dp)
    val frame = modifier
        .clip(shape)
        .background(EncoreColors.Obsidian)
        .border(1.dp, EncoreColors.NeonRed.copy(alpha = 0.35f), shape)

    val b = bitmap
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = frame, contentScale = ContentScale.Fit)
    } else {
        Image(
            painter = painterResource(R.drawable.app_icon_fallback),
            contentDescription = null,
            modifier = frame,
            contentScale = ContentScale.Fit,
        )
    }
}
