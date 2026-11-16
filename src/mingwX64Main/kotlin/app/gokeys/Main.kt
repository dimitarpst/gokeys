@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package app.gokeys

import app.gokeys.ui.Tray
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.experimental.ExperimentalNativeApi

/**
 * gokeys: press saved shortcuts on another PC, from this PC's hotkeys or its tray menu.
 * One small native tray app; every PC can press and be pressed, paired through a relay both connect out to.
 */
fun main() {
    val openWindow = "--tray" !in GetCommandLineW()?.toKString().orEmpty()
    if (Install.handOff(openWindow)) return
    SetProcessDPIAware()
    Win.allowDarkMenus()
    if (Platform.isDebugBinary && Win.env("GOKEYS_PREVIEW").isNotEmpty()) return Preview.run(Win.env("GOKEYS_PREVIEW"))
    CreateMutexW(null, FALSE, "gokeys-${Config.profile}")
    if (GetLastError() == ERROR_ALREADY_EXISTS.toUInt()) return // starting at the same moment as another one
    Tray.create()
    App.start(openWindow)
    memScoped {
        val msg = alloc<MSG>()
        while (GetMessageW(msg.ptr, null, 0u, 0u) > 0) {
            TranslateMessage(msg.ptr)
            DispatchMessageW(msg.ptr)
        }
    }
    ExitProcess(0u) // don't wait for network threads
}
