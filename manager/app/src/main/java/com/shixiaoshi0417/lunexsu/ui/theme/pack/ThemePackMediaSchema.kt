package com.shixiaoshi0417.lunexsu.ui.theme.pack

internal data class PackMedia(
    val video: String?, val videoVolume: Float,
    val music: String?, val musicVolume: Float, val autoPlay: Boolean, val looping: Boolean,
) {
    val assets: Set<String> get() = listOfNotNull(video, music).toSet()
}

internal object ThemePackMediaSchema {
    private val audioExtensions = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
    private val videoExtensions = setOf("mp4", "webm", "mkv", "3gp")

    fun parse(r: PackFieldReader, names: Set<String>, warnings: MutableList<String>): PackMedia {
        fun select(name: String, extensions: Set<String>): String? {
            require(name.length <= 255 && '/' !in name && '\\' !in name) { "Invalid media filename" }
            if (name.isBlank() || name !in names) return null
            require(name.substringAfterLast('.').lowercase() in extensions) { "Invalid media format: $name" }
            return name
        }
        val videoEnabled = r.bool("isVideoBackgroundEnabled")
        val videos = names.filter { it.substringBefore('.') == "video_background" }
        require(videos.size <= 1) { "Ambiguous video background" }
        val video = if (videoEnabled) videos.singleOrNull()?.let { select(it, videoExtensions) } else null
        if (videoEnabled && video == null) warnings += "video_background: video missing"
        val musicEnabled = r.bool("isMusicEnabled")
        val musicName = r.text("musicFilename")
        val music = if (musicEnabled) select(musicName, audioExtensions) else null
        if (musicEnabled && music == null) warnings += "musicFilename: audio missing"
        // Disabled feedback defaults are harmless; active unsupported effects remain visible.
        val soundEnabled = r.bool("isSoundEffectEnabled")
        r.text("soundEffectFilename")
        r.text("soundEffectScope", "global")
        if (soundEnabled) warnings += "isSoundEffectEnabled: sound effects are not supported"
        return PackMedia(video, r.number("videoVolume", 0f), music, r.number("musicVolume", 1f),
            r.bool("isAutoPlayEnabled"), r.bool("isLoopingEnabled"))
    }
}
