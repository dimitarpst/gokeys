@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.Win
import kotlinx.cinterop.*
import platform.windows.*

/**
 * A native text box (caret, selection, clipboard and IME for free) without a border, so the window can draw the
 * go*-style rounded field behind it. Enter, Esc and Tab go to the callbacks instead of beeping.
 */
class Field(
    parent: HWND?,
    placeholder: String,
    maxLength: Int,
    upperCase: Boolean = false,
    private val onEnter: () -> Unit,
    private val onEscape: () -> Unit,
    private val onTab: () -> Unit = {},
) {
    val hwnd: HWND? = CreateWindowExW(
        0u, "EDIT", "", (WS_CHILD or WS_VISIBLE or WS_TABSTOP or ES_AUTOHSCROLL or (if (upperCase) ES_UPPERCASE else 0)).convert(),
        0, 0, 0, 0, parent, null, GetModuleHandleW(null), null,
    )
    private val original: Long

    init {
        SendMessageW(hwnd, WM_SETFONT.convert(), Fonts.body.toLong().toULong(), 1)
        SendMessageW(hwnd, EM_LIMITTEXT.convert(), maxLength.convert(), 0)
        memScoped { SendMessageW(hwnd, EM_SETCUEBANNER.convert(), 1u, placeholder.wcstr.ptr.toLong()) }
        original = SetWindowLongPtrW(hwnd, GWLP_WNDPROC, staticCFunction(::fieldProc).toLong())
        fields[hwnd.toLong()] = this
    }

    var text: String
        get() = memScoped {
            val buf = allocArray<WCHARVar>(256)
            GetWindowTextW(hwnd, buf, 256)
            buf.toKString()
        }
        set(value) {
            SetWindowTextW(hwnd, value)
            SendMessageW(hwnd, EM_SETSEL.convert(), value.length.convert(), value.length.toLong())
        }

    val focused get() = GetFocus() == hwnd

    fun focus() {
        SetFocus(hwnd)
    }

    /** Fits the text box into the rounded field [left]..[right] × [top]..[bottom] the window draws. */
    fun place(left: Int, top: Int, right: Int, bottom: Int) {
        val h = Win.px(20)
        SetWindowPos(hwnd, null, left + Win.px(16), (top + bottom - h) / 2, right - left - Win.px(32), h, (SWP_NOZORDER or SWP_NOACTIVATE).convert())
    }

    fun destroy() {
        fields.remove(hwnd.toLong())
        DestroyWindow(hwnd)
    }

    internal fun handle(hwnd: HWND?, msg: UInt, wParam: WPARAM, lParam: LPARAM): LRESULT {
        val key = wParam.toInt()
        when (msg.toInt()) {
            WM_KEYDOWN -> when (key) {
                VK_RETURN -> return 0L.also { onEnter() }
                VK_ESCAPE -> return 0L.also { onEscape() }
                VK_TAB -> return 0L.also { onTab() }
            }
            WM_CHAR -> if (key == '\r'.code || key == 27 || key == '\t'.code) return 0 // no beep
        }
        return CallWindowProcW(original.toCPointer(), hwnd, msg, wParam, lParam)
    }

    companion object {
        private const val EM_SETCUEBANNER = 0x1501
        private val fields = mutableMapOf<Long, Field>()

        internal fun of(hwnd: HWND?) = fields[hwnd.toLong()]
    }
}

private fun fieldProc(hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT =
    Field.of(hwnd)?.handle(hwnd, msg, wParam, lParam) ?: DefWindowProcW(hwnd, msg, wParam, lParam)
