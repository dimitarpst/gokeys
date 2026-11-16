@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.input

import kotlinx.cinterop.*
import platform.windows.*

/**
 * Watches for the sender's hotkeys with a WH_KEYBOARD_LL hook that lets every key through, so a hotkey keeps doing
 * whatever it does on this PC as well (RegisterHotKey would take it away from every other app). Any key works,
 * a bare letter included. Main thread.
 */
object Hotkeys {
    private const val LLKHF_EXTENDED = 0x01u
    private const val LLKHF_INJECTED = 0x10u
    private var hook: HHOOK? = null
    private var bindings = emptyList<Pair<Shortcut, () -> Unit>>()
    private val down = mutableSetOf<Int>() // vks held right now, to ignore auto-repeat and know the modifiers

    /** Each shortcut runs its action when pressed anywhere. An empty list removes the hook. */
    fun set(bindings: List<Pair<Shortcut, () -> Unit>>) {
        if (bindings.isEmpty()) return clear()
        this.bindings = bindings
        if (hook == null) hook = SetWindowsHookExW(WH_KEYBOARD_LL, staticCFunction(::hotkeyProc), GetModuleHandleW(null), 0u)
    }

    fun clear() {
        hook?.let { UnhookWindowsHookEx(it) }
        hook = null
        bindings = emptyList()
        down.clear()
    }

    internal fun onKey(wParam: WPARAM, info: KBDLLHOOKSTRUCT) {
        if (info.flags and LLKHF_INJECTED != 0u) return // our own replays, and other tools'
        val vk = info.vkCode.toInt()
        if (wParam != WM_KEYDOWN.toULong() && wParam != WM_SYSKEYDOWN.toULong()) {
            down.remove(vk)
            return
        }
        if (!down.add(vk)) return // auto-repeat
        val key = Key(info.scanCode.toInt(), vk, info.flags and LLKHF_EXTENDED != 0u)
        if (key.modifier != 0) return
        val modifiers = down.fold(0) { flags, held -> flags or Key(0, held, false).modifier }
        bindings.find { (s, _) -> s.modifiers == modifiers && s.main.scan == key.scan && s.main.extended == key.extended }
            ?.second?.invoke()
    }
}

private fun hotkeyProc(nCode: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
    if (nCode >= 0) Hotkeys.onKey(wParam, lParam.toCPointer<KBDLLHOOKSTRUCT>()!!.pointed)
    return CallNextHookEx(null, nCode, wParam, lParam)
}
