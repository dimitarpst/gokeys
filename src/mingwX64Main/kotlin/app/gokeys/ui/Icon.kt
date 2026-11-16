@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import kotlinx.cinterop.*
import platform.windows.*
import app.gokeys.Win

/** The icons inside gokeys.exe (branding/make-logo.mjs, linked via gokeys.rc). Mint = connected. */
enum class Icon(private val id: Int) {
    APP(1), OFFLINE(2), PAUSED(3);

    /** Tray and title-bar size. */
    val small: HICON? by lazy { load(SM_CXSMICON) }

    /** Alt+Tab and taskbar size. */
    val large: HICON? by lazy { load(SM_CXICON) }

    private fun load(metric: Int): HICON? {
        val size = GetSystemMetrics(metric)
        return LoadImageW(GetModuleHandleW(null), id.toLong().toCPointer(), IMAGE_ICON.convert(), size, size, 0u)?.reinterpret()
    }
}
