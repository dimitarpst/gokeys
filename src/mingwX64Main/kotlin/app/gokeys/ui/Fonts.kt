@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.Win
import kotlinx.cinterop.*
import platform.windows.*

/** Roboto, like the other go* apps: embedded in the exe (gokeys.rc) and installed for this process only. */
object Fonts {
    val title by lazy { create(22, FW_MEDIUM) }
    val section by lazy { create(16, FW_MEDIUM) }
    val body by lazy { create(14, FW_NORMAL) }
    val label by lazy { create(14, FW_MEDIUM) }
    val small by lazy { create(12, FW_NORMAL) }
    val tag by lazy { create(12, FW_MEDIUM) }
    val bigKey by lazy { create(22, FW_MEDIUM) }
    val mediumKey by lazy { create(18, FW_MEDIUM) }
    val smallKey by lazy { create(13, FW_MEDIUM) }

    private val loaded by lazy {
        val module = GetModuleHandleW(null)
        for (id in 10..11) memScoped {
            val res = FindResourceW(module, "#$id", "#10") ?: return@memScoped // "#10" = RT_RCDATA
            val count = alloc<UIntVar>()
            AddFontMemResourceEx(LockResource(LoadResource(module, res)), SizeofResource(module, res), null, count.ptr)
        }
    }

    private fun create(sizeDp: Int, weight: Int): HFONT? {
        loaded
        return CreateFontW(-Win.px(sizeDp), 0, 0, 0, weight, 0u, 0u, 0u, DEFAULT_CHARSET.convert(), 0u, 0u, CLEARTYPE_QUALITY.convert(), 0u, "Roboto")
    }
}
