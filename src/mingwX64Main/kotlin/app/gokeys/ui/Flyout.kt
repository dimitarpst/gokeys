@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.App
import app.gokeys.Install
import app.gokeys.Win
import kotlinx.cinterop.*
import platform.windows.*

/**
 * The tray menu, drawn like the rest of gokeys instead of a plain Win32 menu: rounded, opening upward from the tray.
 * Open gokeys, up to five of the other PCs' keys (click to press now, with your hotkey shown), then the switches.
 */
object Flyout : PaintedWindow("gokeys-flyout", widthDp = 288, maxHeightDp = 600, popup = true) {
    private const val MAX_KEYS = 5 // the rest are in the window
    private var closedAt = 0uL

    /** Right-click on the tray icon. A click that just closed it (by taking focus away) doesn't reopen it. */
    fun toggle() {
        if (isOpen || GetTickCount64() - closedAt < 300u) return close()
        show("gokeys")
    }

    override fun onClosed() {
        closedAt = GetTickCount64()
    }

    override fun anchor(width: Int, height: Int): Pair<Int, Int> = memScoped {
        val cursor = alloc<POINT>().also { GetCursorPos(it.ptr) }
        val work = alloc<RECT>().also { SystemParametersInfoW(SPI_GETWORKAREA.convert(), 0u, it.ptr, 0u) }
        val gap = Win.px(12)
        val left = (cursor.x - width / 2).coerceIn(work.left + gap, work.right - width - gap)
        val bottom = minOf(cursor.y, work.bottom).minus(gap).coerceAtLeast(work.top + height + gap)
        left to bottom
    }

    override fun draw(ui: Ui): Int {
        val pad = ui.px(6)
        var y = pad

        fun row(id: String, onClick: () -> Unit, content: (Int, Int) -> Unit) {
            val bottom = y + ui.px(40)
            val fill = when {
                ui.isPressed(id) -> Theme.PRESSED
                ui.isHovered(id) -> Theme.HOVER
                else -> Theme.SURFACE
            }
            ui.c.roundRect(pad, y, ui.width - pad, bottom, ui.px(8), fill)
            content(y, bottom)
            ui.hit(id, pad, y, ui.width - pad, bottom, onClick = onClick)
            y = bottom
        }
        fun label(text: String, top: Int, bottom: Int, color: Int = Theme.TEXT, right: Int = ui.width - ui.px(20)) =
            ui.text(text, Fonts.body, color, ui.px(20), top, right, bottom)
        fun separator() {
            y += ui.px(4)
            ui.c.rect(ui.px(18), y, ui.width - ui.px(18), y + maxOf(1, ui.px(1)), Theme.LINE)
            y += ui.px(5)
        }

        row("open", { close(); MainWindow.open() }) { top, bottom ->
            label("Open gokeys", top, bottom)
            val (status, dot) = when {
                App.config.paused -> "Paused" to Theme.DISABLED_TEXT
                App.online -> "Online" to Theme.ACCENT
                else -> "Offline" to Theme.DISABLED_TEXT
            }
            val sw = ui.c.textWidth(status, Fonts.small)
            val right = ui.width - ui.px(20)
            ui.text(status, Fonts.small, Theme.MUTED, right - sw, top, right, bottom)
            val mid = (top + bottom) / 2
            ui.c.roundRect(right - sw - ui.px(14), mid - ui.px(4), right - sw - ui.px(6), mid + ui.px(4), ui.px(4), dot)
        }

        App.update?.let { release ->
            row("update", { App.installUpdate() }) { top, bottom ->
                label(if (App.updating) "Updating…" else "Update to ${release.version}", top, bottom, Theme.ACCENT)
            }
        }

        // Other PCs' keys: click to press now.
        val keys = App.others.flatMap { pc -> pc.keys.map { pc to it } }
        if (keys.isNotEmpty()) separator()
        for ((pc, key) in keys.take(MAX_KEYS)) {
            row("key:${pc.id}:${key.id}", { App.press(pc.id, key.id) }) { top, bottom ->
                val names = App.config.hotkey(pc.id, key.id)?.shortcut?.keys?.map { it.name }
                val capsW = names?.let { ui.capsWidth(it, Cap.SMALL) } ?: 0
                val capsLeft = ui.width - ui.px(16) - capsW
                val name = if (App.others.size > 1) "${key.name} · ${pc.name}" else key.name
                label(name, top, bottom, right = capsLeft - ui.px(8))
                names?.let { ui.caps(it, capsLeft, (top + bottom - ui.px(31)) / 2, Cap.SMALL, Look.READY) }
            }
        }
        if (keys.size > MAX_KEYS) row("all", { close(); MainWindow.open() }) { top, bottom ->
            label("All ${keys.size} keys…", top, bottom, Theme.MUTED)
        }

        separator()
        row("pause", { App.togglePause() }) { top, bottom ->
            label("Pause", top, bottom)
            switch(ui, App.config.paused, top, bottom)
        }
        row("autostart", { Install.autostart = !Install.autostart; refresh() }) { top, bottom ->
            label("Start with Windows", top, bottom)
            switch(ui, Install.autostart, top, bottom)
        }
        separator()
        row("quit", { close(); App.quit() }) { top, bottom -> label("Quit", top, bottom) }
        return y + pad
    }

    /** A small on/off switch at the row's right end. */
    private fun switch(ui: Ui, on: Boolean, top: Int, bottom: Int) {
        val right = ui.width - ui.px(18)
        val left = right - ui.px(36)
        val t = (top + bottom) / 2 - ui.px(10)
        val b = t + ui.px(20)
        val r = ui.px(10)
        if (on) {
            ui.c.roundRect(left, t, right, b, r, Theme.ACCENT)
            ui.c.roundRect(right - ui.px(17), t + ui.px(3), right - ui.px(3), b - ui.px(3), ui.px(7), Theme.ON_ACCENT)
        } else {
            ui.c.roundRect(left, t, right, b, r, Theme.LINE)
            ui.c.roundRect(left + ui.px(1), t + ui.px(1), right - ui.px(1), b - ui.px(1), r - ui.px(1), Theme.SURFACE)
            ui.c.roundRect(left + ui.px(4), t + ui.px(4), left + ui.px(16), b - ui.px(4), ui.px(6), Theme.MUTED)
        }
    }
}
