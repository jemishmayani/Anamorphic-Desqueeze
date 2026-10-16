package com.desqueeze.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import kotlin.math.roundToInt

/** One photo picked for de-squeezing: size as shown (EXIF orientation applied). */
data class PhotoItem(val uri: Uri, val name: String, val width: Int, val height: Int)

/** Settings shared by every photo in the batch (photos from one adapter usually match). */
data class PhotoLook(
    val squeeze: Float, val vertical: Boolean, val lutId: String?, val lutStrength: Float,
    val color: ColorAdjust, val crop: Float,
)

/**
 * De-squeezes photos. Unlike video, JPEG has no pixel-aspect tag that viewers respect, so the pixels are always
 * re-rendered: edge crop, then stretched by the squeeze (more pixels, nothing thrown away), then LUT + colour.
 * EXIF (camera, exposure, and date/location when "Keep recording date & location" is on) is copied.
 */
class PhotoExporter(private val ctx: Context, private val settings: Settings, private val luts: LutManager) {

    companion object {
        /** Largest output we'll make; bigger results are scaled down evenly (keeps memory sane on 50 MP+ sensors). */
        const val MAX_OUTPUT_PIXELS = 100_000_000L

        fun probe(ctx: Context, uri: Uri): PhotoItem? = try {
            var w = 0; var h = 0
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            // Only the header is read: the listener cancels the decode once the size is known.
            runCatching { ImageDecoder.decodeBitmap(src) { _, info, _ -> w = info.size.width; h = info.size.height; throw StopDecode() } }
            if (w <= 0 || h <= 0) null else PhotoItem(uri, displayName(ctx, uri), w, h)
        } catch (_: Exception) { null }

        private class StopDecode : RuntimeException()

        /**
         * Decodes upright, in software memory. When the result would exceed [MAX_OUTPUT_PIXELS] it is decoded smaller
         * (by pixel count, which doesn't depend on orientation). [maxSide] limits it further, e.g. for the preview.
         */
        fun decode(ctx: Context, uri: Uri, look: PhotoLook, maxSide: Int = Int.MAX_VALUE): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width.toDouble(); val h = info.size.height.toDouble()
                val k = 1.0 - 2.0 * look.crop.coerceIn(0f, 0.4f)
                val outPx = w * h * k * k * look.squeeze
                var s = minOf(1.0, Math.sqrt(MAX_OUTPUT_PIXELS / outPx), maxSide / maxOf(w, h))
                if (s < 0.999) d.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            }

        fun displayName(ctx: Context, uri: Uri): String = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "photo.jpg"

        /** Output size for a [w]×[h] photo: crop, then stretch along the squeeze axis, then cap. */
        fun outputSize(w: Int, h: Int, look: PhotoLook): Pair<Int, Int> {
            val k = 1f - 2f * look.crop.coerceIn(0f, 0.4f)
            var ow = (w * k * (if (look.vertical) 1f else look.squeeze)).toDouble()
            var oh = (h * k * (if (look.vertical) look.squeeze else 1f)).toDouble()
            val px = ow * oh
            if (px > MAX_OUTPUT_PIXELS) { val f = Math.sqrt(MAX_OUTPUT_PIXELS / px); ow *= f; oh *= f }
            return ow.roundToInt().coerceAtLeast(1) to oh.roundToInt().coerceAtLeast(1)
        }

        /** Crop, stretch and look on an already-decoded bitmap (used for the preview and for export). */
        fun render(src: Bitmap, look: PhotoLook, cube: LutManager.CubeLut?, outW: Int, outH: Int): Bitmap {
            val k = 1f - 2f * look.crop.coerceIn(0f, 0.4f)
            val cw = (src.width * k).roundToInt().coerceAtLeast(1); val ch = (src.height * k).roundToInt().coerceAtLeast(1)
            val cropped = if (cw == src.width && ch == src.height) src
                else Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
            val out = Bitmap.createScaledBitmap(cropped, outW, outH, true).let {
                if (it.isMutable && it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true).also { c -> if (it !== src) it.recycle() }
            }
            if (cropped !== src && cropped !== out) cropped.recycle()
            if (cube != null) {
                // In strips, so a 100 MP photo never needs a 400 MB pixel array at once.
                val band = maxOf(1, 1_000_000 / outW)
                val px = IntArray(outW * band)
                var y = 0
                while (y < outH) {
                    val rows = minOf(band, outH - y)
                    out.getPixels(px, 0, outW, 0, y, outW, rows)
                    val done = cube.applyTo(if (rows == band) px else px.copyOf(outW * rows), 1f)
                    out.setPixels(done, 0, outW, 0, y, outW, rows)
                    y += rows
                }
            }
            return out
        }
    }

    data class Result(val name: String, val width: Int, val height: Int)

    /** De-squeezes one photo into Pictures/<folder>. Blocking; call from a background thread. */
    fun export(item: PhotoItem, look: PhotoLook): Result {
        val cube = LookBuilder.build(look.lutId?.let { luts.load(it) }, look.lutStrength, look.color)
        val src = decode(ctx, item.uri, look)
        // Output from the decoded (upright) size, so EXIF rotation can't swap the axes.
        val (ow, oh) = outputSize(src.width, src.height, look)
        val out = render(src, look, cube, ow, oh)
        if (out !== src) src.recycle()

        val name = "${item.name.substringBeforeLast('.')}_DESQUEEZED_${"%.2f".format(look.squeeze).trimEnd('0').trimEnd('.')}X.jpg"
        val tmp = File(ctx.cacheDir, "photo_${System.nanoTime()}.jpg")
        try {
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            out.recycle()
            val taken = copyExif(item.uri, tmp, ow, oh)
            save(tmp, name, taken)
        } finally { tmp.delete() }
        return Result(name, ow, oh)
    }

    /** Copies the source's EXIF into [dest]; returns the date taken (ms) for the gallery, if known and kept. */
    private fun copyExif(src: Uri, dest: File, w: Int, h: Int): Long? {
        val inExif = runCatching { ctx.contentResolver.openInputStream(src)?.use { ExifInterface(it) } }.getOrNull() ?: return null
        val out = ExifInterface(dest.absolutePath)
        val camera = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            "LensMake", "LensModel", ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        )
        val dates = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        )
        val gps = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        )
        val keep = settings.preserveMeta
        for (t in camera + (if (keep) dates + gps else emptyList())) inExif.getAttribute(t)?.let { out.setAttribute(t, it) }
        // Pixels are already upright and stretched.
        out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        out.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString()); out.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
        out.setAttribute(ExifInterface.TAG_SOFTWARE, "Anamorphic De-Squeeze")
        runCatching { out.saveAttributes() }
        if (!keep) return null
        return runCatching {
            val d = inExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: inExif.getAttribute(ExifInterface.TAG_DATETIME) ?: return@runCatching null
            val off = inExif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            val f = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            if (off != null) f.timeZone = java.util.TimeZone.getTimeZone("GMT$off")
            f.parse(d)?.time
        }.getOrNull()
    }

    private fun save(tmp: File, name: String, takenMs: Long?) {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + settings.folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            takenMs?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw Exception("Couldn't create the output file. Is storage full?")
        try {
            r.openOutputStream(uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 20) } }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); r.update(uri, values, null, null)
        } catch (e: Throwable) { r.delete(uri, null, null); throw e }
    }
}
