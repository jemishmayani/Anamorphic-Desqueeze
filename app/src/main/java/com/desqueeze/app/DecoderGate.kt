package com.desqueeze.app

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * Phones have only a few hardware decoders that can handle 4K 10-bit HEVC, and a decoder is only
 * returned when a player is released. Moving between steps (the slide animation briefly shows both
 * screens), switching clips or changing effects used to start the new preview before the old one
 * had let go, so the new one sometimes got no decoder. Previews now take turns: a player only
 * prepares (which is when it claims a decoder) once every other preview player has been released.
 * All calls happen on the main thread.
 */
object DecoderGate {
    private val active = LinkedHashSet<ExoPlayer>()

    /** Waits (up to [maxWaitMs]) for other preview players to be released, then registers [p]. */
    suspend fun acquire(p: ExoPlayer, maxWaitMs: Long = 2500) {
        var waited = 0L
        while (active.any { it !== p } && waited < maxWaitMs) { delay(40); waited += 40 }
        if (waited >= maxWaitMs) Diag.step("DecoderGate: waited ${waited} ms, ${active.size} preview player(s) still active")
        active += p
    }

    fun release(p: ExoPlayer) { active -= p }
}
