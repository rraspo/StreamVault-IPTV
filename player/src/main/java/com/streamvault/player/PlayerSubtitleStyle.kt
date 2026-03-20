package com.streamvault.player

data class PlayerSubtitleStyle(
    val textScale: Float = 1f,
    val foregroundColorArgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundColorArgb: Int = 0x80000000.toInt()
)