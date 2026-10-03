package com.desqueeze.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts a clip without re-encoding: the compressed video and audio samples are copied as-is into a new
 * MP4. Video can only start on a keyframe, so the cut starts at the keyframe at or before [startMs]
 * (usually within a second); it ends shortly after [endMs] so the last frames still decode correctly.
 */
object LosslessTrim {
    data class Result(val file: File, val actualStartMs: Long, val actualEndMs: Long)

    fun trim(ctx: Context, src: Uri, rotation: Int, startMs: Long, endMs: Long, onProgress: (Int) -> Unit): Result {
        val out = File(ctx.cacheDir, "trim_${System.nanoTime()}.mp4")
        val video = MediaExtractor(); val audio = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            video.setDataSource(ctx, src, null); audio.setDataSource(ctx, src, null)
            var vTrack = -1; var aTrack = -1
            for (i in 0 until video.trackCount) {
                val m = video.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vTrack < 0 && m.startsWith("video/")) vTrack = i
                if (aTrack < 0 && m.startsWith("audio/")) aTrack = i
            }
            require(vTrack >= 0) { "No video track to trim." }
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) muxer.setOrientationHint(rotation)
            video.selectTrack(vTrack)
            val vFmt = video.getTrackFormat(vTrack)
            val outV = muxer.addTrack(vFmt)
            var outA = -1
            if (aTrack >= 0) {
                audio.selectTrack(aTrack)
                outA = try { muxer.addTrack(audio.getTrackFormat(aTrack)) } catch (_: Exception) { -1 } // e.g. PCM in MP4
            }
            muxer.start()

            val startUs = startMs * 1000; val endUs = endMs * 1000
            video.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val base = video.sampleTime.coerceAtLeast(0)          // the keyframe we really start on
            val tailUs = endUs + 200_000                           // keep references for B-frames near the end
            val size = maxOf(vFmt.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0), 8 shl 20)
            val buf = ByteBuffer.allocateDirect(size)
            val info = MediaCodec.BufferInfo()
            var lastV = base
            val span = (tailUs - base).coerceAtLeast(1)
            while (true) {
                val t = video.sampleTime
                if (t < 0 || t > tailUs) break
                info.offset = 0; info.size = video.readSampleData(buf, 0); if (info.size < 0) break
                info.presentationTimeUs = t - base
                info.flags = if (video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                if (info.presentationTimeUs >= 0) muxer.writeSampleData(outV, buf, info)
                lastV = maxOf(lastV, t)
                onProgress(((t - base) * 90 / span).toInt().coerceIn(0, 90))
                if (!video.advance()) break
            }
            if (outA >= 0) {
                audio.seekTo(base, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val aBuf = ByteBuffer.allocateDirect(maxOf(audio.getTrackFormat(aTrack).intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0), 1 shl 20))
                while (true) {
                    val t = audio.sampleTime
                    if (t < 0 || t > lastV) break
                    info.offset = 0; info.size = audio.readSampleData(aBuf, 0); if (info.size < 0) break
                    info.presentationTimeUs = t - base; info.flags = 0
                    if (info.presentationTimeUs >= 0) muxer.writeSampleData(outA, aBuf, info)
                    if (!audio.advance()) break
                }
            }
            muxer.stop()
            onProgress(100)
            return Result(out, base / 1000, lastV / 1000)
        } catch (e: Throwable) {
            out.delete(); throw e
        } finally {
            try { muxer?.release() } catch (_: Throwable) {}
            video.release(); audio.release()
        }
    }

    private fun MediaFormat.intOr(k: String, d: Int) = if (containsKey(k)) try { getInteger(k) } catch (_: Exception) { d } else d
}
