package com.duoopen.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import com.duoopen.settings.DuoSettings
import java.io.File
import kotlin.math.max

/**
 * The picture behind the glass: a user-picked image stored in app files, or a
 * generated default. One decoded copy is cached per [com.duoopen.settings.DuoConfig.imageVersion]
 * so the app preview and the wallpaper engine (same process) share it.
 */
object WallpaperImage {
    private const val FILE_NAME = "wallpaper.jpg"
    private const val MAX_DIM = 2800
    private const val DEFAULT_SIZE = 2048

    private val lock = Any()
    private var cached: Pair<Long, Bitmap>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Blocking; call off the main thread. */
    fun load(context: Context, version: Long): Bitmap = synchronized(lock) {
        cached?.let { (v, bmp) -> if (v == version) return bmp }
        val f = file(context)
        val bmp = if (f.exists()) {
            runCatching { decode(ImageDecoder.createSource(f)) }.getOrNull() ?: generateDefault()
        } else {
            generateDefault()
        }
        cached = version to bmp
        bmp
    }

    /** Blocking; copies the picked image into app storage and bumps the version. */
    fun import(context: Context, uri: Uri) {
        val bmp = decode(ImageDecoder.createSource(context.contentResolver, uri))
        val f = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        tmp.renameTo(f)
        DuoSettings.update { it.copy(imageVersion = it.imageVersion + 1) }
    }

    fun reset(context: Context) {
        file(context).delete()
        DuoSettings.update { it.copy(imageVersion = it.imageVersion + 1) }
    }

    /**
     * Center-crop scale+offset mapping a [bw]x[bh] image onto a [w]x[h] surface:
     * returns (scale, dx, dy).
     */
    fun centerCrop(bw: Int, bh: Int, w: Float, h: Float): Triple<Float, Float, Float> {
        val scale = max(w / bw, h / bh)
        return Triple(scale, (w - bw * scale) * 0.5f, (h - bh * scale) * 0.5f)
    }

    private fun decode(source: ImageDecoder.Source): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = max(info.size.width, info.size.height)
            if (longSide > MAX_DIM) {
                val s = MAX_DIM.toFloat() / longSide
                decoder.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1),
                )
            }
        }

    /**
     * Soft color fields over deep ink plus a fine dot grid — the gradients show
     * the darkening, the dots show the frost smearing.
     */
    private fun generateDefault(): Bitmap {
        val size = DEFAULT_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF0D0A1C.toInt())

        val blobs = listOf(
            Blob(0.18f, 0.22f, 0.55f, 0xFF7B5CFF.toInt()),
            Blob(0.86f, 0.20f, 0.50f, 0xFFF0564A.toInt()),
            Blob(0.50f, 0.52f, 0.42f, 0xFFC44EDD.toInt()),
            Blob(0.20f, 0.86f, 0.50f, 0xFF19C3B2.toInt()),
            Blob(0.84f, 0.84f, 0.48f, 0xFFFFB020.toInt()),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (b in blobs) {
            val cx = b.x * size
            val cy = b.y * size
            val r = b.radius * size
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(b.color, b.color and 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            paint.alpha = 220
            canvas.drawCircle(cx, cy, r, paint)
        }

        paint.shader = null
        paint.color = 0x38FFFFFF
        val step = size / 36f
        val dot = size / 620f
        var y = step * 0.5f
        while (y < size) {
            var x = step * 0.5f
            while (x < size) {
                canvas.drawCircle(x, y, dot, paint)
                x += step
            }
            y += step
        }
        return bmp
    }

    private data class Blob(val x: Float, val y: Float, val radius: Float, val color: Int)
}
