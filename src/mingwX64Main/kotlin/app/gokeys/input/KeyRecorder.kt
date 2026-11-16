@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.input

import kotlinx.cinterop.*
import platform.windows.*

/**
 * Reads shortcuts while a gokeys window is in front, the way PowerToys' shortcut dialog does: held modifiers show up
 * live, the first other key completes the shortcut, a new combination replaces it, Enter confirms and Esc cancels.
 * A WH_KEYBOARD_LL hook that exists only while recording, so scan codes are exact on any layout (Ä included).
 * Keys are swallowed while our window is in front (Win doesn't open Start, the old shortcut doesn't fire);
 * typing in other apps passes through untouched.
 */
object KeyRecorder {
    interface Listener {
        /** False while typing in a text field of ours: then keys go to the field. */
        fun capturing(): Boolean = true

        /** Modifiers held right now, before the main key. */
        fun onHeld(keys: List<Key>)
        fun onShortcut(shortcut: Shortcut)
        fun onEnter()
        fun onEscape()
    }

    private const val LLKHF_EXTENDED = 0x01u
    private const val LLKHF_INJECTED = 0x10u
    private var hook: HHOOK? = null
    private var listener: Listener? = null
    private val held = LinkedHashMap<Int, Key>()
    private val swallowed = mutableSetOf<Int>() // keys whose release we must swallow too

    /** Main thread; the listener runs on it too. */
    fun start(listener: Listener) {
        stop()
        this.listener = listener
        hook = SetWindowsHookExW(WH_KEYBOARD_LL, staticCFunction(::hookProc), GetModuleHandleW(null), 0u)
    }

    fun stop() {
        hook?.let { UnhookWindowsHookEx(it) }
        hook = null
        held.clear()
        swallowed.clear()
    }

    internal fun onKey(wParam: WPARAM, info: KBDLLHOOKSTRUCT): Boolean {
        if (info.flags and LLKHF_INJECTED != 0u) return false
        val key = Key(info.scanCode.toInt(), info.vkCode.toInt(), info.flags and LLKHF_EXTENDED != 0u)
        val down = wParam == WM_KEYDOWN.toULong() || wParam == WM_SYSKEYDOWN.toULong()
        if (!down) return swallowed.remove(key.vk) || held.remove(key.vk)?.also { listener?.onHeld(held.values.toList()) } != null
        if (!ourWindowInFront() || key.vk == VK_SNAPSHOT) return false // PrintScreen: a screenshot, not a shortcut
        val l = listener?.takeIf { it.capturing() } ?: return false
        when {
            key.modifier != 0 -> if (key.vk !in held) {
                held[key.vk] = key
                l.onHeld(held.values.toList())
            }
            key.vk in swallowed -> {} // auto-repeat of a key already handled
            held.isEmpty() && key.vk == VK_RETURN -> l.onEnter()
            held.isEmpty() && key.vk == VK_ESCAPE -> l.onEscape()
            else -> l.onShortcut(Shortcut(held.values + key))
        }
        if (key.modifier == 0) swallowed += key.vk
        return true
    }

    private fun ourWindowInFront(): Boolean = memScoped {
        val pid = alloc<UIntVar>()
        GetWindowThreadProcessId(GetForegroundWindow() ?: return false, pid.ptr)
        pid.value == GetCurrentProcessId()
    }
}

private fun hookProc(nCode: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
    if (nCode >= 0 && KeyRecorder.onKey(wParam, lParam.toCPointer<KBDLLHOOKSTRUCT>()!!.pointed)) return 1
    return CallNextHookEx(null, nCode, wParam, lParam)
}
