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

/** One photo picked for de-squeezing: size as shown (orientation applied); for DNG, the full RAW image's size. */
data class PhotoItem(val uri: Uri, val name: String, val width: Int, val height: Int, val isDng: Boolean = false)

/** File type for photos that have to be rendered. */
enum class PhotoFormat(val label: String, val ext: String, val mime: String) {
    JPEG("JPEG (max quality)", "jpg", "image/jpeg"), PNG("PNG (lossless)", "png", "image/png")
}

/** Settings shared by every photo in the batch (photos from one adapter usually match). */
data class PhotoLook(
    val squeeze: Float, val vertical: Boolean, val lutId: String?, val lutStrength: Float,
    val color: ColorAdjust, val crop: Float,
) {
    /** LUT, colour or crop: these need new pixels, so a DNG can't stay RAW. */
    val hasEdits get() = (lutId != null && lutStrength > 0f) || !color.isNeutral || crop > 0f
}

/**
 * De-squeezes photos. DNG (RAW) files without edits are kept as RAW and tagged (see [DngTagger]). Everything else
 * has no pixel-aspect tag that viewers respect, so it is rendered: edge crop, then stretched by the squeeze (more
 * pixels, nothing thrown away), then LUT + colour, saved as JPEG at quality 100 or lossless PNG.
 * EXIF (camera, exposure, and date/location when "Keep recording date & location" is on) is copied.
 */
class PhotoExporter(private val ctx: Context, private val settings: Settings, private val luts: LutManager) {

    companion object {
        /** Largest output we'll make; bigger results are scaled down evenly (keeps memory sane on 50 MP+ sensors). */
        const val MAX_OUTPUT_PIXELS = 100_000_000L

        fun isDng(ctx: Context, uri: Uri, name: String) =
            ctx.contentResolver.getType(uri)?.contains("dng", ignoreCase = true) == true || name.endsWith(".dng", ignoreCase = true)

        /** Reads a DNG's header straight from its file descriptor (no copy). */
        fun dngInfo(ctx: Context, uri: Uri): DngTagger.Info? = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> DngTagger.info(File("/proc/self/fd/${pfd.fd}")) }
        }.getOrNull()

        fun probe(ctx: Context, uri: Uri): PhotoItem? = try {
            val name = displayName(ctx, uri)
            val dng = if (isDng(ctx, uri, name)) dngInfo(ctx, uri) else null
            if (dng != null) PhotoItem(uri, name, if (dng.turned) dng.rawHeight else dng.rawWidth, if (dng.turned) dng.rawWidth else dng.rawHeight, isDng = true)
            else probeDecoded(ctx, uri, name)
        } catch (_: Exception) { null }

        private fun probeDecoded(ctx: Context, uri: Uri, name: String): PhotoItem? {
            var w = 0; var h = 0
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            // Only the header is read: the listener cancels the decode once the size is known.
            runCatching { ImageDecoder.decodeBitmap(src) { _, info, _ -> w = info.size.width; h = info.size.height; throw StopDecode() } }
            return if (w <= 0 || h <= 0) null else PhotoItem(uri, name, w, h)
        }

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

    data class Result(val name: String, val width: Int, val height: Int, val note: String? = null)

    private fun baseName(item: PhotoItem, look: PhotoLook) =
        "${item.name.substringBeforeLast('.')}_DESQUEEZED_${"%.2f".format(look.squeeze).trimEnd('0').trimEnd('.')}X"

    /**
     * De-squeezes one photo into Pictures/<folder>. Blocking; call from a background thread.
     * A DNG without edits stays a DNG (RAW data untouched, DefaultScale tag added); everything else is rendered.
     */
    fun export(item: PhotoItem, look: PhotoLook, format: PhotoFormat = PhotoFormat.JPEG): Result {
        if (item.isDng && !look.hasEdits) return exportDng(item, look)
        val cube = LookBuilder.build(look.lutId?.let { luts.load(it) }, look.lutStrength, look.color)
        val src = decode(ctx, item.uri, look)
        // Some phones decode only the small preview embedded in a RAW file; say so instead of quietly shrinking it.
        val srcW = src.width; val srcH = src.height
        val previewOnly = item.isDng && maxOf(srcW, srcH) < 0.7f * maxOf(item.width, item.height)
        // Output from the decoded (upright) size, so EXIF rotation can't swap the axes.
        val (ow, oh) = outputSize(src.width, src.height, look)
        val out = render(src, look, cube, ow, oh)
        if (out !== src) src.recycle()

        val name = baseName(item, look) + "." + format.ext
        val tmp = File(ctx.cacheDir, "photo_${System.nanoTime()}.${format.ext}")
        try {
            tmp.outputStream().use {
                if (format == PhotoFormat.PNG) out.compress(Bitmap.CompressFormat.PNG, 100, it) else out.compress(Bitmap.CompressFormat.JPEG, 100, it)
            }
            out.recycle()
            val taken = copyExif(item.uri, tmp, ow, oh)
            save(tmp, name, format.mime, taken)
        } finally { tmp.delete() }
        return Result(name, ow, oh, listOfNotNull(
            if (item.isDng) "Rendered from RAW (the RAW data isn't kept)." else null,
            if (previewOnly) "This phone could only decode a ${srcW}×${srcH} preview from the RAW file, not its full " +
                "${item.width}×${item.height}. Remove the edits to keep the DNG as RAW at full size." else null,
        ).joinToString(" ").ifEmpty { null })
    }

    /** Lossless DNG: copy byte for byte, add DefaultScale to the RAW image (see [DngTagger]). */
    private fun exportDng(item: PhotoItem, look: PhotoLook): Result {
        val tmp = File(ctx.cacheDir, "dng_${System.nanoTime()}.dng")
        try {
            ctx.contentResolver.openInputStream(item.uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            val info = DngTagger.info(tmp) ?: throw IllegalArgumentException("Not a DNG this app can tag.")
            // "Horizontal" is as the photo is shown; a 90° Orientation swaps that onto the RAW image's other axis.
            val rawHorizontal = (!look.vertical) != info.turned
            DngTagger.tag(tmp, look.squeeze, rawHorizontal)
            val taken = if (settings.preserveMeta) runCatching {
                val e = ExifInterface(tmp.absolutePath)
                val d = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME)
                d?.let { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(it)?.time }
            }.getOrNull() else null
            save(tmp, baseName(item, look) + ".dng", "image/x-adobe-dng", taken)
        } finally { tmp.delete() }
        val (p, q) = PaspWriter.ratio(look.squeeze)
        val (w, h) = if (look.vertical) item.width to (item.height * look.squeeze).roundToInt() else (item.width * look.squeeze).roundToInt() to item.height
        return Result(baseName(item, look) + ".dng", w, h,
            "RAW kept, tagged $p:$q (DefaultScale): Lightroom, Camera Raw and Photoshop open it de-squeezed. Galleries show the built-in preview, still squeezed.")
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

    private fun save(tmp: File, name: String, mime: String, takenMs: Long?) {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
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
