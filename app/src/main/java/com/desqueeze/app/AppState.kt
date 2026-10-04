package com.desqueeze.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job

enum class Screen { Main, Settings, Limits, Luts }

/** Double Desqueeze Protection: what to do with a clip that is already tagged as anamorphic. */
enum class TagPolicy(val label: String, val detail: String) {
    KEEP("Keep existing", "Use the file's own factor and ignore the one picked here."),
    REPLACE("Replace tag", "Use the factor picked here instead of the file's."),
    FORCE("Force anyway", "Apply the factor picked here on top of the existing one."),
}

/** Existing non-square pixel-aspect tag of a clip, as a squeeze factor (e.g. 1.33), or null. */
fun existingTag(v: VideoInfo): Float? = v.footage.pixelAspect?.let { (h, w) ->
    if (h <= 0 || w <= 0 || h == w) null else maxOf(h, w).toFloat() / minOf(h, w)
}

/** The sequential flow on the main screen. */
enum class Step(val label: String) { Clips("Clips"), Frame("Frame"), Look("Look"), Export("Export") }

/** Lives above the screens so navigating never loses clips or settings. */
class AppState(settings: Settings, luts: LutManager) {
    var screen by mutableStateOf(Screen.Main)
    var videos by mutableStateOf(listOf<VideoInfo>())
    var selected by mutableIntStateOf(0)
    /** Squeeze given to clips added from now on (the default, or the last factor picked). */
    var newClipSqueeze by mutableFloatStateOf(settings.defaultSqueeze)
    /** Each clip's own squeeze factor, keyed by uri: different adapters can be mixed in one batch. */
    val clipSqueeze = mutableStateMapOf<String, Float>()
    /** What to do when a clip already carries a pixel-aspect tag (keyed by uri). Missing = not decided yet. */
    val tagPolicy = mutableStateMapOf<String, TagPolicy>()
    var guides by mutableStateOf(settings.guides)
    var keepHdrSetting by mutableStateOf(settings.keepHdr)
    /** Asks the preview to open the full-quality before/after still. */
    var compareRequest by mutableIntStateOf(0)
    /** Issues found right before export, awaiting "Export anyway" / "Review". */
    var preflight by mutableStateOf<List<Pair<String, CompatReport>>?>(null)

    fun keyOf(v: VideoInfo) = v.uri.toString()
    fun squeezeFor(v: VideoInfo) = clipSqueeze[keyOf(v)] ?: newClipSqueeze

    /** Squeeze for the selected clip. Setting it changes only that clip. */
    var squeeze: Float
        get() = videos.getOrNull(selected)?.let { squeezeFor(it) } ?: newClipSqueeze
        set(value) {
            videos.getOrNull(selected)?.let { clipSqueeze[keyOf(it)] = value }
            newClipSqueeze = value
        }

    /** The factor actually applied, after deciding what to do with an existing tag. */
    fun effectiveSqueeze(v: VideoInfo): Float {
        val chosen = squeezeFor(v)
        val existing = existingTag(v) ?: return chosen
        return when (tagPolicy[keyOf(v)]) {
            TagPolicy.KEEP -> existing
            TagPolicy.FORCE -> existing * chosen
            else -> chosen // REPLACE, or not decided yet (warned about before export)
        }
    }
    /** True once the user picks a factor; imports then keep it instead of applying the default. */
    var squeezeChosen by mutableStateOf(false)
    var customSqueeze by mutableStateOf(false)
    var desqueezed by mutableStateOf(true)
    var lutList by mutableStateOf(luts.list())
    var lutId by mutableStateOf<String?>(null)
    var strength by mutableFloatStateOf(1f)
    var status by mutableStateOf("")
    var results by mutableStateOf(listOf<String>())
    var busy by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var job: Job? = null
    var theme by mutableStateOf(settings.theme)
    var accent by mutableStateOf(settings.accent)
    var mode by mutableStateOf(settings.mode)
    var step by mutableStateOf(Step.Clips)
    var orientation by mutableStateOf(Orientation.AUTO)
    var direction by mutableStateOf(Direction.AUTO)
    /** LUT applied in the live preview (proxy) when a LUT is selected. */
    var lutPreview by mutableStateOf(true)
    /** Per-clip export method chosen by the user (keyed by uri). Missing = follow the default. */
    val clipModes = mutableStateMapOf<String, ExportMode>()
    var followRecommendation by mutableStateOf(settings.followRecommendation)
    var quality by mutableStateOf(settings.quality)
    var codec by mutableStateOf(settings.codec)
    var crashLog by mutableStateOf<String?>(null)
    /** Playback position shared by the Frame and Look previews. */
    val memory = PlayheadMemory()
    /** Per-clip trim (start ms to end ms), keyed by uri. Missing = whole clip. */
    val clipTrim = mutableStateMapOf<String, Pair<Long, Long>>()
    fun trimFor(v: VideoInfo): Pair<Long, Long>? = clipTrim[keyOf(v)]?.takeIf { (a, b) -> a > 0 || b < v.durationMs }
    /** Length that will actually be exported. */
    fun lengthMs(v: VideoInfo): Long = trimFor(v)?.let { (a, b) -> b - a } ?: v.durationMs
    /** Social-ready output frame for Re-encode, and whether to crop to fill it (else black bars). */
    var format by mutableStateOf(settings.format)
    var formatFill by mutableStateOf(settings.formatFill)
    /** Exposure tool shown with the preview. */
    var scope by mutableStateOf(Scope.OFF)
    /** Scope overlay drawn large (tap it to toggle), and its latest readings for the Exposure panel. */
    var scopeLarge by mutableStateOf(false)
    /** Clip import in progress: (done, total), or null when idle. */
    var importing by mutableStateOf<Pair<Int, Int>?>(null)
    /** A LUT file is being read and checked. */
    var lutLoading by mutableStateOf(false)
    /** Where the scope overlay sits inside the video: x, y as 0..1 of the free space (1, 0 = top-right). */
    var scopePos by mutableStateOf(androidx.compose.ui.geometry.Offset(1f, 0f))
    var scopeStats by mutableStateOf<ScopeMath.Stats?>(null)
    /** Selected tool tab in the Frame / Look steps (preview stays pinned above). */
    var frameTool by mutableIntStateOf(0)
    var lookTool by mutableIntStateOf(0)

    fun jobFor(v: VideoInfo) = ExportJob(v, effectiveSqueeze(v), lutId, strength, orientation, direction,
        trim = trimFor(v), format = format, fill = formatFill)
}
