@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.App
import app.gokeys.net.KeyInfo
import app.gokeys.net.PcInfo
import kotlinx.cinterop.*
import platform.windows.*

/**
 * The gokeys window: this PC's keys, every paired PC's keys with this PC's hotkey for each, and pairing.
 * Opens from the tray, or by starting gokeys.exe again.
 */
object MainWindow : PaintedWindow("gokeys-main", widthDp = 460, maxHeightDp = 720) {
    private enum class Pairing { IDLE, CODE, ENTER, BUSY }

    private var pairing = Pairing.IDLE
    private var pairOpen = false // "Pair another PC" was clicked
    private var code = ""
    private var pairError: String? = null
    private var codeField: Field? = null

    fun open() = show("gokeys")

    /** Debug previews: shows the pairing card in a given state. */
    internal fun previewPairing(showCode: String?) {
        pairing = if (showCode != null) Pairing.CODE else Pairing.IDLE
        code = showCode.orEmpty()
    }

    override fun onCreate() {
        codeField = Field(hwnd, "Code, e.g. K7Q-4MX", 7, upperCase = true, onEnter = ::join, onEscape = ::idle)
        ShowWindow(codeField?.hwnd, SW_HIDE)
    }

    override fun close() {
        codeField?.destroy()
        codeField = null
        super.close()
    }

    override fun onClosed() {
        pairing = Pairing.IDLE
        pairOpen = false
        pairError = null
    }

    override fun draw(ui: Ui): Int {
        val pad = ui.px(28)
        val w = ui.width
        var y = ui.px(20) - scroll

        // Only when something's off (or the relay has news): a line above everything. Online needs no saying.
        val line = App.note ?: when {
            App.config.paused -> "Paused · other PCs can't press keys here"
            !App.online -> App.status
            else -> null
        }
        line?.let {
            ui.text(it, Fonts.body, if (App.note != null) Theme.ACCENT else Theme.MUTED, pad, y, w - pad, y + ui.px(20))
            y += ui.px(32)
        }

        // This PC.
        section(ui, App.config.name, "This PC", y)
        ui.pill("add:me", "Add key", w - pad, y, primary = false, height = ui.px(32)) { KeyWindow.addOwn() }
        y += ui.px(44)
        if (App.config.keys.isEmpty()) {
            y = emptyTile(ui, y, "No keys yet. Add the shortcuts other PCs may press here.")
        }
        for (key in App.config.keys) {
            y = tile(ui, "me:${key.id}", y, ui.px(56), onRight = {
                menu(listOf("Edit" to { KeyWindow.editOwn(key) }, "Remove" to { App.removeKey(key.id) }))
            }, onClick = { KeyWindow.editOwn(key) }) { top, bottom ->
                ui.text(key.name, Fonts.body, Theme.TEXT, pad + ui.px(20), top, w / 2, bottom)
                val names = key.shortcut?.keys?.map { it.name }
                if (names == null) {
                    ui.text("Not recorded", Fonts.body, Theme.MUTED, w / 2, top, w - pad - ui.px(20), bottom, DT_RIGHT or DT_VCENTER)
                } else {
                    val cw = ui.capsWidth(names, Cap.SMALL)
                    ui.caps(names, w - pad - ui.px(16) - cw, (top + bottom - ui.px(31)) / 2, Cap.SMALL, Look.HELD)
                }
            }
        }

        // Every paired PC.
        for (pc in App.others) {
            y += ui.px(28)
            section(ui, pc.name, if (pc.online) "Online" else "Offline", y, dot = if (pc.online) Theme.ACCENT else Theme.DISABLED_TEXT)
            val more = ui.pill("more:${pc.id}", "···", w - pad, y, primary = false, height = ui.px(32), round = true) {
                menu(listOf("Unpair ${pc.name}" to { App.unpair(pc.id) }))
            }
            ui.pill("add:${pc.id}", "Add key", more - ui.px(8), y, primary = false, height = ui.px(32)) { KeyWindow.addRemote(pc) }
            y += ui.px(44)
            if (pc.keys.isEmpty()) y = emptyTile(ui, y, "No keys yet. Add one here, or on ${pc.name}.")
            for (key in pc.keys) y = remoteTile(ui, pc, key, y)
        }

        // Pairing.
        y += ui.px(28)
        y = if (App.others.isEmpty() || pairOpen || pairing != Pairing.IDLE) pairCard(ui, y) else {
            ui.pill("pair", "Pair another PC", w - pad, y, primary = false) { pairOpen = true; refresh() }
            y + ui.px(40)
        }
        if (pairing != Pairing.ENTER) codeField?.hwnd?.let { ShowWindow(it, SW_HIDE) }
        return y + ui.px(28) + scroll
    }

    private fun section(ui: Ui, name: String, detail: String, y: Int, dot: Int? = null) {
        val pad = ui.px(28)
        val nameW = minOf(ui.c.textWidth(name, Fonts.section), ui.px(220))
        ui.text(name, Fonts.section, Theme.TEXT, pad, y, pad + nameW, y + ui.px(32))
        var x = pad + nameW + ui.px(10)
        if (dot != null) {
            ui.c.roundRect(x, y + ui.px(12), x + ui.px(8), y + ui.px(20), ui.px(4), dot)
            x += ui.px(14)
        }
        ui.text(detail, Fonts.body, Theme.MUTED, x, y, x + ui.px(120), y + ui.px(32))
    }

    /** A rounded, clickable row; [content] draws inside it. Returns the y after it. */
    private fun tile(ui: Ui, id: String, y: Int, height: Int, onRight: () -> Unit, onClick: () -> Unit, content: (Int, Int) -> Unit): Int {
        val pad = ui.px(28)
        ui.c.roundRect(pad, y, ui.width - pad, y + height, ui.px(16), if (ui.isHovered(id)) Theme.CARD_HOVER else Theme.CARD)
        content(y, y + height)
        ui.hit(id, pad, y, ui.width - pad, y + height, onRight, onClick)
        return y + height + ui.px(8)
    }

    private fun emptyTile(ui: Ui, y: Int, text: String): Int {
        val pad = ui.px(28)
        ui.c.roundRect(pad, y, ui.width - pad, y + ui.px(56), ui.px(16), Theme.CARD)
        ui.text(text, Fonts.body, Theme.MUTED, pad + ui.px(20), y, ui.width - pad - ui.px(20), y + ui.px(56))
        return y + ui.px(64)
    }

    /** Another PC's key: its name and shortcut there, and this PC's hotkey for it (click to set). */
    private fun remoteTile(ui: Ui, pc: PcInfo, key: KeyInfo, y: Int): Int {
        val pad = ui.px(28)
        val w = ui.width
        val hotkey = App.config.hotkey(pc.id, key.id)
        return tile(ui, "${pc.id}:${key.id}", y, ui.px(64), onRight = {
            menu(listOf(
                "Press now" to { App.press(pc.id, key.id) },
                "Edit on ${pc.name}" to { KeyWindow.editRemote(pc, key) },
                "Remove from ${pc.name}" to { App.removeRemote(pc.id, key.id) },
            ))
        }, onClick = { KeyWindow.bind(pc, key) }) { top, bottom ->
            val mid = (top + bottom) / 2
            ui.text(key.name, Fonts.body, Theme.TEXT, pad + ui.px(20), mid - ui.px(20), w / 2 + ui.px(20), mid)
            val there = if (key.label.isEmpty()) "Not recorded there yet" else "Presses ${key.label}"
            ui.text(there, Fonts.small, Theme.MUTED, pad + ui.px(20), mid, w / 2 + ui.px(20), mid + ui.px(18))
            val names = hotkey?.shortcut?.keys?.map { it.name }
            if (names == null) {
                ui.text("Set hotkey", Fonts.label, Theme.ACCENT, w / 2, top, w - pad - ui.px(20), bottom, DT_RIGHT or DT_VCENTER)
            } else {
                val cw = ui.capsWidth(names, Cap.SMALL)
                ui.caps(names, w - pad - ui.px(16) - cw, (top + bottom - ui.px(31)) / 2, Cap.SMALL, Look.READY)
            }
        }
    }

    private fun pairCard(ui: Ui, y: Int): Int {
        val pad = ui.px(28)
        val w = ui.width
        val inner = pad + ui.px(24)
        val bottom = y + when (pairing) {
            Pairing.CODE -> ui.px(212)
            Pairing.ENTER -> ui.px(200)
            else -> ui.px(156)
        }
        ui.c.roundRect(pad, y, w - pad, bottom, ui.px(28), Theme.CARD)
        var cy = y + ui.px(24)
        val title = if (App.others.isEmpty()) "Pair with another PC" else "Pair another PC"
        ui.text(title, Fonts.section, Theme.TEXT, inner, cy, w - inner, cy + ui.px(24))
        cy += ui.px(28)
        val buttonsTop = bottom - ui.px(24) - ui.px(40)
        when (pairing) {
            Pairing.IDLE, Pairing.BUSY -> {
                val text = if (pairing == Pairing.BUSY) "One moment…" else "Show a code here and type it on the other PC."
                ui.text(text, Fonts.body, Theme.MUTED, inner, cy, w - inner, cy + ui.px(20))
                pairError?.let { ui.text(it, Fonts.body, Theme.ERROR, inner, cy + ui.px(22), w - inner, cy + ui.px(42)) }
                if (pairing == Pairing.IDLE) {
                    val left = ui.pill("code:show", "Show code", w - inner, buttonsTop, primary = true, on = Theme.CARD, onClick = ::showCode)
                    val enterLeft = ui.pill("code:enter", "Enter code", left - ui.px(8), buttonsTop, primary = false, on = Theme.CARD, onClick = ::enter)
                    if (App.others.isNotEmpty()) ui.pill("code:close", "Cancel", enterLeft - ui.px(8), buttonsTop, primary = false, on = Theme.CARD, onClick = ::idle)
                }
            }
            Pairing.CODE -> {
                ui.text("Type this on the other PC. It works once, for 10 minutes.", Fonts.body, Theme.MUTED, inner, cy, w - inner, cy + ui.px(20))
                cy += ui.px(36)
                val groups = code.split('-').map { g -> g.map { it.toString() } }
                val widths = groups.map { ui.capsWidth(it, Cap.MEDIUM) }
                val dash = ui.px(24)
                var x = (w - widths.sum() - dash) / 2
                groups.forEachIndexed { i, g ->
                    ui.caps(g, x, cy, Cap.MEDIUM, Look.READY)
                    x += widths[i] + dash
                }
                ui.pill("code:done", "Done", w - inner, buttonsTop, primary = false, on = Theme.CARD, onClick = ::idle)
            }
            Pairing.ENTER -> {
                ui.text("Type the code the other PC shows.", Fonts.body, Theme.MUTED, inner, cy, w - inner, cy + ui.px(20))
                cy += ui.px(32)
                ShowWindow(codeField?.hwnd, SW_SHOW)
                ui.fieldBox(codeField, inner, cy, w - inner, cy + ui.px(44))
                pairError?.let { ui.text(it, Fonts.body, Theme.ERROR, inner, buttonsTop, w - inner - ui.px(200), buttonsTop + ui.px(40)) }
                val left = ui.pill("code:join", "Pair", w - inner, buttonsTop, primary = true, onClick = ::join)
                ui.pill("code:cancel", "Cancel", left - ui.px(8), buttonsTop, primary = false, on = Theme.CARD, onClick = ::idle)
            }
        }
        return bottom
    }

    private fun idle() {
        pairing = Pairing.IDLE
        pairOpen = false
        pairError = null
        SetFocus(hwnd)
        refresh()
    }

    private fun showCode() {
        pairing = Pairing.BUSY
        pairError = null
        refresh()
        App.pairingCode { result ->
            result.onSuccess { code = it; pairing = Pairing.CODE }
            result.onFailure { pairing = Pairing.IDLE; pairError = it.message ?: "Couldn't get a code" }
            refresh()
        }
    }

    private fun enter() {
        pairing = Pairing.ENTER
        pairError = null
        codeField?.text = ""
        refresh()
        codeField?.focus()
    }

    private fun join() {
        val typed = codeField?.text?.trim().orEmpty()
        if (typed.length < 6) return
        pairing = Pairing.BUSY
        refresh()
        App.join(typed) { result ->
            result.onSuccess { pairing = Pairing.IDLE; pairOpen = false }
            result.onFailure { pairing = Pairing.ENTER; pairError = it.message ?: "That didn't work" }
            refresh()
        }
    }

    /** A small right-click menu at the cursor. */
    private fun menu(items: List<Pair<String, () -> Unit>>) {
        val m = CreatePopupMenu()
        items.forEachIndexed { i, (label, _) -> AppendMenuW(m, MF_STRING.convert(), (i + 1).convert(), label) }
        val chosen = memScoped {
            val pt = alloc<POINT>()
            GetCursorPos(pt.ptr)
            TrackPopupMenu(m, (TPM_RETURNCMD or TPM_NONOTIFY or TPM_RIGHTBUTTON).convert(), pt.x, pt.y, 0, hwnd, null)
        }
        DestroyMenu(m)
        items.getOrNull(chosen - 1)?.second?.invoke()
    }
}
