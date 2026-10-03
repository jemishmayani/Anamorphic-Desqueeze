package com.desqueeze.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever

/** Still frames for thumbnails, the filmstrip and the LUT before/after compare. */
object Frames {
    /**
     * Returns the frame in display orientation. Some Android versions rotate retriever frames
     * by the file's metadata and some don't, so we compare the frame's shape with the stored shape.
     */
    fun orient(b: Bitmap, storedW: Int, storedH: Int, metaRot: Int, extraRot: Int): Bitmap {
        // For 90°/270° files, a frame that still has the stored shape hasn't been rotated yet.
        // (180° can't be told apart by shape; the platform handles it.)
        val stillStoredShape = storedW != storedH && (b.width > b.height) == (storedW > storedH)
        val deg = (((extraRot + if (metaRot % 180 != 0 && stillStoredShape) metaRot else 0) % 360) + 360) % 360
        if (deg == 0) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    fun frameAt(ctx: Context, v: VideoInfo, timeMs: Long, maxSide: Int, extraRot: Int, exact: Boolean): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, v.uri)
            val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val b = mmr.getScaledFrameAtTime(timeMs * 1000L, opt, maxSide, maxSide) ?: return null
            val argb = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
            orient(argb, v.width, v.height, v.rotation, extraRot)
        } catch (_: Throwable) { null } finally { mmr.release() }
    }
}
