@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.input

import kotlinx.cinterop.*
import platform.windows.*

/** Presses a shortcut as if someone typed it: SendInput by scan code, so this PC's layout gives the keys their meaning. */
object KeyPresser {
    private const val KEYEVENTF_EXTENDEDKEY = 0x1
    private const val KEYEVENTF_KEYUP = 0x2
    private const val KEYEVENTF_SCANCODE = 0x8

    /** Downs in order, a short hold, ups in reverse. Blocks ~40 ms (call it off the main thread). */
    fun press(shortcut: Shortcut) {
        send(shortcut.keys, up = false)
        Sleep(40u)
        send(shortcut.keys.reversed(), up = true)
    }

    private fun send(keys: List<Key>, up: Boolean) = memScoped {
        val inputs = allocArray<INPUT>(keys.size)
        keys.forEachIndexed { i, key ->
            inputs[i].type = INPUT_KEYBOARD.convert()
            inputs[i].ki.wScan = key.scan.convert()
            inputs[i].ki.dwFlags =
                (KEYEVENTF_SCANCODE or (if (key.extended) KEYEVENTF_EXTENDEDKEY else 0) or (if (up) KEYEVENTF_KEYUP else 0)).convert()
        }
        SendInput(keys.size.convert(), inputs, sizeOf<INPUT>().convert())
    }
}
