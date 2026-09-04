package com.mightykatun.speedometer.app.domain.model

enum class PortraitDisplayMode {
    NORMAL,
    SPEED_FOCUS,
    REGATTA;

    fun next(): PortraitDisplayMode = when (this) {
        NORMAL -> SPEED_FOCUS
        SPEED_FOCUS -> REGATTA
        REGATTA -> NORMAL
    }
}
