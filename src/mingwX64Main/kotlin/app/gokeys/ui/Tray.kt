@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.App
import app.gokeys.Config
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.concurrent.AtomicReference

/**
 * The tray icon and its menu, owned by a hidden window that also runs work posted from other threads ([post]) and
 * hears "open the window" from a second gokeys.exe. Click the icon to open gokeys; right-click for the [Flyout].
 */
object Tray {
    /** Window class, per profile so test instances don't find each other. */
    val CLASS = "gokeys" + Config.profile
    const val WM_OPEN = WM_APP + 3
    private const val WM_TRAY = WM_APP + 1
    private const val WM_RUN = WM_APP + 2
    private const val NOTE_TIMER = 1uL

    private var hwnd: HWND? = null
    private val queue = AtomicReference(emptyList<() -> Unit>())
    private val taskbarCreated by lazy { RegisterWindowMessageW("TaskbarCreated") }

    fun create() = memScoped {
        val wc = alloc<WNDCLASSEXW>()
        wc.cbSize = sizeOf<WNDCLASSEXW>().convert()
        wc.lpfnWndProc = staticCFunction(::trayProc)
        wc.hInstance = GetModuleHandleW(null)
        wc.lpszClassName = CLASS.wcstr.ptr
        RegisterClassExW(wc.ptr)
        hwnd = CreateWindowExW(0u, CLASS, "gokeys", 0u, 0, 0, 0, 0, null, null, GetModuleHandleW(null), null)
        icon(NIM_ADD)
    }

    fun destroy() {
        DestroyWindow(hwnd)
    }

    /** Any thread: runs [block] on the main thread. */
    fun post(block: () -> Unit) {
        while (true) {
            val list = queue.value
            if (queue.compareAndSet(list, list + block)) break
        }
        PostMessageW(hwnd, WM_RUN.convert(), 0u, 0)
    }

    /** The icon and tooltip follow the app's state. */
    fun update() {
        if (hwnd != null) icon(NIM_MODIFY)
    }

    /** Clears [App.note] in a few seconds. */
    fun clearNoteLater() {
        SetTimer(hwnd, NOTE_TIMER, 5000u, null)
    }

    private fun icon(message: Int) = memScoped {
        val data = alloc<NOTIFYICONDATAW>()
        data.cbSize = sizeOf<NOTIFYICONDATAW>().convert()
        data.hWnd = hwnd
        data.uID = 1u
        data.uFlags = (NIF_ICON or NIF_MESSAGE or NIF_TIP).convert()
        data.uCallbackMessage = WM_TRAY.convert()
        data.hIcon = when {
            App.config.paused -> Icon.PAUSED
            App.online -> Icon.APP
            else -> Icon.OFFLINE
        }.small
        val tip = "gokeys · " + if (App.config.paused) "Paused" else App.status
        tip.take(127).forEachIndexed { i, c -> data.szTip[i] = c.code.convert() }
        Shell_NotifyIconW(message.convert(), data.ptr)
    }

    internal fun handle(msg: UInt, wParam: WPARAM, lParam: LPARAM): LRESULT? {
        when (msg.toInt()) {
            WM_TRAY -> when ((lParam and 0xFFFF).toInt()) {
                WM_LBUTTONUP -> MainWindow.open()
                WM_RBUTTONUP -> Flyout.toggle()
            }
            WM_RUN -> queue.getAndSet(emptyList()).forEach { it() }
            WM_OPEN -> MainWindow.open()
            WM_TIMER -> {
                KillTimer(hwnd, NOTE_TIMER)
                App.clearNote()
            }
            WM_CLOSE -> App.quit() // also how a newer gokeys.exe asks this one to make way
            WM_DESTROY -> {
                icon(NIM_DELETE)
                PostQuitMessage(0)
            }
            else -> return if (msg == taskbarCreated) 0L.also { icon(NIM_ADD) } else null // Explorer restarted
        }
        return 0
    }
}

private fun trayProc(hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT =
    Tray.handle(msg, wParam, lParam) ?: DefWindowProcW(hwnd, msg, wParam, lParam)
