package com.desqueeze.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job

enum class Screen { Main, Settings, Limits }

/** Lives above the screens so navigating never loses clips or settings. */
class AppState(settings: Settings, luts: LutManager) {
    var screen by mutableStateOf(Screen.Main)
    var videos by mutableStateOf(listOf<VideoInfo>())
    var selected by mutableIntStateOf(0)
    var squeeze by mutableFloatStateOf(settings.defaultSqueeze)
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
    var mode by mutableStateOf(settings.mode)
    var crashLog by mutableStateOf<String?>(null)
}
