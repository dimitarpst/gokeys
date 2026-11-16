@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.Win
import kotlinx.cinterop.*
import platform.windows.*

/**
 * A gokeys window: one colour (title bar included), drawn in one go each paint by [draw], with hover and press
 * states for whatever [draw] marked clickable. Its height follows the content up to [maxHeightDp]; past that the
 * mouse wheel scrolls. A [resizable] window opens that way but then takes whatever size the user drags it to
 * ([widthDp] is the narrowest), and remembers it. A [popup] has no title bar, sits above its anchor (the tray) and
 * closes when it loses focus. Main thread.
 */
abstract class PaintedWindow(
    private val className: String,
    private val widthDp: Int,
    private val maxHeightDp: Int,
    private val popup: Boolean = false,
    private val resizable: Boolean = false,
) {
    var hwnd: HWND? = null
        private set
    private var hits = emptyList<Hit>()
    private var hovered: String? = null
    private var pressed: String? = null
    private var widthPx = 0
    private var maximizeOnShow = false
    private var heightPx = 0
    private var contentPx = 0

    /** How far the content is scrolled up, in pixels. */
    protected var scroll = 0
        private set

    val isOpen get() = hwnd != null

    /** Paints the content (scrolled by [scroll]) and returns where it ends, in pixels from the top. */
    protected abstract fun draw(ui: Ui): Int

    /** After the window is created: add child controls here. */
    protected open fun onCreate() {}

    /** After the window is gone. */
    protected open fun onClosed() {}

    /** Popups only: where the window's bottom-left corner goes, given its size in pixels. */
    protected open fun anchor(width: Int, height: Int): Pair<Int, Int> = 0 to 0

    /** Resizable windows: the size and position saved last time ("left,top,right,bottom,maximized"), or null. */
    protected open fun savedPlacement(): String? = null

    protected open fun savePlacement(placement: String) {}

    private val minPx get() = if (popup) 0 else Win.px(120)
    private val style get() = when {
        popup -> WS_POPUP.toUInt()
        resizable -> STYLE or (WS_THICKFRAME or WS_MAXIMIZEBOX).toUInt()
        else -> STYLE
    }

    protected fun show(title: String) {
        if (hwnd == null) create(title) else SetWindowTextW(hwnd, title)
        val cmd = when {
            IsIconic(hwnd) != 0 -> SW_RESTORE
            maximizeOnShow -> SW_SHOWMAXIMIZED
            else -> SW_SHOW
        }
        maximizeOnShow = false
        ShowWindow(hwnd, cmd)
        SetForegroundWindow(hwnd)
        refresh()
    }

    open fun close() {
        val h = hwnd ?: return
        if (resizable) savePlacement(placement(h))
        hwnd = null
        windows.remove(h.toLong())
        DestroyWindow(h)
        hits = emptyList()
        hovered = null
        pressed = null
        scroll = 0
        onClosed()
    }

    fun refresh() {
        hwnd?.let { InvalidateRect(it, null, FALSE) }
    }

    /** Debug previews: paints into a bitmap file at [widthDp] wide, no window needed. */
    internal fun snapshot(path: String, widthDp: Int = this.widthDp) {
        val w = Win.px(widthDp)
        val probe = Canvas(1, 1)
        val height = draw(Ui(probe, w, null, null)).coerceIn(minPx, Win.px(maxHeightDp))
        probe.release()
        val c = Canvas(w, height)
        c.rect(0, 0, w, height, Theme.SURFACE)
        draw(Ui(c, w, null, null))
        c.saveBmp(path)
        c.release()
    }

    private fun create(title: String) = memScoped {
        if (className !in registered) {
            val wc = alloc<WNDCLASSEXW>()
            wc.cbSize = sizeOf<WNDCLASSEXW>().convert()
            wc.lpfnWndProc = staticCFunction(::paintedProc)
            wc.hInstance = GetModuleHandleW(null)
            wc.hIcon = Icon.APP.large
            wc.hIconSm = Icon.APP.small
            wc.hCursor = LoadCursorW(null, 32512L.toCPointer()) // IDC_ARROW
            wc.lpszClassName = className.wcstr.ptr
            RegisterClassExW(wc.ptr)
            registered += className
        }
        // Measure first, so the window opens at its final size.
        widthPx = Win.px(widthDp)
        val probe = Canvas(1, 1)
        heightPx = draw(Ui(probe, widthPx, null, null)).coerceIn(minPx, Win.px(maxHeightDp))
        probe.release()
        val (w, h) = frameSize(widthPx, heightPx)
        val (x, y) = if (popup) anchor(w, h).let { (left, bottom) -> left to bottom - h }
            else (GetSystemMetrics(SM_CXSCREEN) - w) / 2 to (GetSystemMetrics(SM_CYSCREEN) - h) / 2 - Win.px(40)
        val ex = if (popup) (WS_EX_TOOLWINDOW or WS_EX_TOPMOST).toUInt() else 0u
        hwnd = CreateWindowExW(ex, className, title, style, x, y, w, h, null, null, GetModuleHandleW(null), null)
        windows[hwnd.toLong()] = this@PaintedWindow
        if (popup) Win.roundCorners(hwnd, Theme.LINE) else Win.darkTitleBar(hwnd, Theme.SURFACE)
        if (resizable) savedPlacement()?.let { restore(it) }
        onCreate()
    }

    private fun frameSize(clientWidth: Int, clientHeight: Int): Pair<Int, Int> = memScoped {
        val frame = alloc<RECT>().apply { right = clientWidth; bottom = clientHeight }
        AdjustWindowRectEx(frame.ptr, style, FALSE, 0u)
        (frame.right - frame.left) to (frame.bottom - frame.top)
    }

    private fun paint(target: HDC?) {
        val w = widthPx
        val c = Canvas(w, heightPx)
        c.rect(0, 0, w, heightPx, Theme.SURFACE)
        val ui = Ui(c, w, hovered, pressed)
        contentPx = draw(ui)
        hits = ui.hits
        // A thin bar on the right edge when there's more to scroll to.
        val total = contentPx + scroll
        if (total > heightPx) {
            val barH = maxOf(heightPx * heightPx / total, Win.px(32))
            val barTop = scroll * (heightPx - barH) / maxOf(1, total - heightPx)
            c.roundRect(w - Win.px(7), barTop + Win.px(3), w - Win.px(3), barTop + barH - Win.px(3), Win.px(2), Theme.LINE)
        }
        c.copyTo(target)
        c.release()
        fit()
    }

    /** Grows or shrinks the window to the content, keeping its top edge (a popup: its bottom edge) where it is. */
    private fun fit() {
        if (resizable) {
            val max = maxOf(0, contentPx + scroll - heightPx)
            if (scroll > max) {
                scroll = max
                refresh()
            }
            return
        }
        val wanted = (contentPx + scroll).coerceIn(minPx, Win.px(maxHeightDp))
        scroll = scroll.coerceIn(0, maxOf(0, contentPx + scroll - wanted))
        if (wanted == heightPx) return
        heightPx = wanted
        val (w, h) = frameSize(widthPx, heightPx)
        if (popup) memScoped {
            val r = alloc<RECT>().also { GetWindowRect(hwnd, it.ptr) }
            SetWindowPos(hwnd, null, r.left, r.bottom - h, w, h, (SWP_NOZORDER or SWP_NOACTIVATE).convert())
        } else {
            SetWindowPos(hwnd, null, 0, 0, w, h, (SWP_NOMOVE or SWP_NOZORDER or SWP_NOACTIVATE).convert())
        }
        refresh()
    }

    /** "left,top,right,bottom,maximized" of the window's normal (restored) position. */
    private fun placement(h: HWND): String = memScoped {
        val p = alloc<WINDOWPLACEMENT>().apply { length = sizeOf<WINDOWPLACEMENT>().convert() }
        GetWindowPlacement(h, p.ptr)
        val r = p.rcNormalPosition
        "${r.left},${r.top},${r.right},${r.bottom},${if (p.showCmd.toInt() == SW_SHOWMAXIMIZED) 1 else 0}"
    }

    /** Puts the window back where [placement] says, if that's still on a screen. */
    private fun restore(placement: String) = memScoped {
        val n = placement.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (n.size != 5) return@memScoped
        val r = alloc<RECT>().apply { left = n[0]; top = n[1]; right = n[2]; bottom = n[3] }
        if (MonitorFromRect(r.ptr, MONITOR_DEFAULTTONULL.convert()) == null) return@memScoped // that monitor is gone
        val p = alloc<WINDOWPLACEMENT>().apply { length = sizeOf<WINDOWPLACEMENT>().convert() }
        GetWindowPlacement(hwnd, p.ptr)
        p.rcNormalPosition.left = r.left
        p.rcNormalPosition.top = r.top
        p.rcNormalPosition.right = r.right
        p.rcNormalPosition.bottom = r.bottom
        p.showCmd = SW_HIDE.convert() // shown by [show]
        SetWindowPlacement(hwnd, p.ptr)
        maximizeOnShow = n[4] == 1
    }

    internal fun handle(msg: UInt, wParam: WPARAM, lParam: LPARAM): LRESULT? {
        val x = (lParam and 0xFFFF).toShort().toInt()
        val y = (lParam shr 16 and 0xFFFF).toShort().toInt()
        when (msg.toInt()) {
            WM_PAINT -> memScoped {
                val ps = alloc<PAINTSTRUCT>()
                paint(BeginPaint(hwnd, ps.ptr))
                EndPaint(hwnd, ps.ptr)
            }
            WM_ERASEBKGND -> return 1 // WM_PAINT covers every pixel
            WM_SIZE -> if (wParam.toInt() != SIZE_MINIMIZED) {
                widthPx = x
                heightPx = y
                refresh()
            }
            WM_GETMINMAXINFO -> {
                if (!resizable) return null
                val (w, h) = frameSize(Win.px(widthDp), Win.px(360))
                lParam.toCPointer<MINMAXINFO>()!!.pointed.ptMinTrackSize.apply { this.x = w; this.y = h }
            }
            WM_MOUSEMOVE -> {
                val over = hits.find { it.contains(x, y) }?.id
                if (over != hovered) {
                    hovered = over
                    refresh()
                    memScoped {
                        val track = alloc<TRACKMOUSEEVENT>()
                        track.cbSize = sizeOf<TRACKMOUSEEVENT>().convert()
                        track.dwFlags = TME_LEAVE.convert()
                        track.hwndTrack = hwnd
                        TrackMouseEvent(track.ptr)
                    }
                }
            }
            WM_MOUSELEAVE -> {
                hovered = null
                refresh()
            }
            WM_LBUTTONDOWN -> {
                SetFocus(hwnd) // out of any text field
                hits.find { it.contains(x, y) }?.let {
                    pressed = it.id
                    SetCapture(hwnd)
                }
                refresh()
            }
            WM_LBUTTONUP -> {
                val clicked = hits.find { it.contains(x, y) && it.id == pressed }
                pressed = null
                ReleaseCapture()
                refresh()
                clicked?.onClick?.invoke()
            }
            WM_RBUTTONUP -> hits.find { it.contains(x, y) }?.onRight?.invoke()
            WM_MOUSEWHEEL -> {
                val delta = (wParam shr 16 and 0xFFFFu).toShort().toInt()
                val max = maxOf(0, contentPx + scroll - heightPx)
                val next = (scroll - delta * Win.px(48) / 120).coerceIn(0, max)
                if (next != scroll) {
                    scroll = next
                    refresh()
                }
            }
            WM_CTLCOLOREDIT -> { // text fields: our colours on our field background
                val dc: HDC? = wParam.toLong().toCPointer()
                SetTextColor(dc, Win.rgb(Theme.TEXT))
                SetBkColor(dc, Win.rgb(Theme.FIELD))
                return fieldBrush.toLong()
            }
            WM_COMMAND -> refresh() // a text field gained or lost focus: its outline changes
            WM_ACTIVATE -> {
                if (popup && (wParam and 0xFFFFu).toInt() == WA_INACTIVE) PostMessageW(hwnd, WM_CLOSE.convert(), 0u, 0)
                return null
            }
            WM_KEYDOWN -> if (popup && wParam.toInt() == VK_ESCAPE) close() else return null
            WM_CLOSE -> close()
            else -> return null
        }
        return 0
    }

    internal companion object {
        private val STYLE = (WS_CAPTION or WS_SYSMENU or WS_MINIMIZEBOX).toUInt()
        private val registered = mutableSetOf<String>()
        private val windows = mutableMapOf<Long, PaintedWindow>()
        private val fieldBrush by lazy { CreateSolidBrush(Win.rgb(Theme.FIELD)) }

        fun of(hwnd: HWND?) = windows[hwnd.toLong()]
    }
}

private fun paintedProc(hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT =
    PaintedWindow.of(hwnd)?.handle(msg, wParam, lParam) ?: DefWindowProcW(hwnd, msg, wParam, lParam)

/** Draws the rounded box behind a [Field]: mint outline while typing in it. */
fun Ui.fieldBox(field: Field?, left: Int, top: Int, right: Int, bottom: Int) {
    val r = (bottom - top) / 2
    c.roundRect(left, top, right, bottom, r, if (field?.focused == true) Theme.ACCENT else Theme.LINE)
    c.roundRect(left + px(1), top + px(1), right - px(1), bottom - px(1), r - px(1), Theme.FIELD)
    field?.place(left, top, right, bottom)
}
