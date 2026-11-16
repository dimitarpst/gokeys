@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.App
import app.gokeys.net.KeyInfo
import app.gokeys.net.PcInfo
import kotlinx.cinterop.*
import platform.windows.*

/**
 * The gokeys window: this PC's keys, every paired PC's keys with this PC's hotkey for each, and pairing.
 * Opens from the tray, or by starting gokeys.exe again. Resizable: wider windows put the keys into a grid.
 */
object MainWindow : PaintedWindow("gokeys-main", widthDp = 460, maxHeightDp = 720, resizable = true) {
    private const val PAD = 28 // dp around the content
    private const val MAX_CONTENT = 1144 // dp: wider windows keep the content centred instead of stretching it
    private const val MIN_TILE = 280 // dp: key tiles go into as many columns as fit at this width
    private const val MAX_CARD = 560 // dp: the pairing and update cards don't stretch across a wide window

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

    override fun savedPlacement() = App.config.window

    override fun savePlacement(placement: String) = App.saveWindow(placement)

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

    // The content column: the window minus margins, at most MAX_CONTENT wide and centred. Set at the start of [draw].
    private var left = 0
    private var right = 0

    override fun draw(ui: Ui): Int {
        val inner = minOf(ui.width - 2 * ui.px(PAD), ui.px(MAX_CONTENT))
        left = (ui.width - inner) / 2
        right = left + inner
        var y = ui.px(20) - scroll

        // Only when something's off (or the relay has news): a line above everything. Online needs no saying.
        val line = App.note ?: when {
            App.config.paused -> "Paused · other PCs can't press keys here"
            !App.online -> App.status
            else -> null
        }
        line?.let {
            ui.text(it, Fonts.body, if (App.note != null) Theme.ACCENT else Theme.MUTED, left, y, right, y + ui.px(20))
            y += ui.px(32)
        }

        App.update?.let { release ->
            val r = minOf(right, left + ui.px(MAX_CARD))
            ui.c.roundRect(left, y, r, y + ui.px(64), ui.px(16), Theme.ACCENT_TINT)
            val mid = y + ui.px(32)
            ui.text("gokeys ${release.version} is ready", Fonts.label, Theme.TEXT, left + ui.px(20), mid - ui.px(20), r - ui.px(140), mid)
            val detail = App.updateError ?: if (App.updating) "Downloading…" else "You have ${app.gokeys.VERSION}"
            ui.text(detail, Fonts.small, if (App.updateError != null) Theme.ERROR else Theme.MUTED, left + ui.px(20), mid, r - ui.px(140), mid + ui.px(18))
            ui.pill("update", if (App.updating) "Updating…" else "Update", r - ui.px(14), mid - ui.px(16), primary = true,
                enabled = !App.updating, height = ui.px(32), on = Theme.ACCENT_TINT) { App.installUpdate() }
            y += ui.px(80)
        }

        // This PC.
        section(ui, App.config.name, "This PC", y)
        ui.pill("add:me", "Add key", right, y, primary = false, height = ui.px(32)) { KeyWindow.addOwn() }
        y += ui.px(44)
        y = if (App.config.keys.isEmpty()) emptyTile(ui, y, "No keys yet. Add the shortcuts other PCs may press here.")
        else grid(ui, y, App.config.keys, ui.px(56)) { key, l, t, r, b ->
            tile(ui, "me:${key.id}", l, t, r, b, onRight = {
                menu(listOf("Edit" to { KeyWindow.editOwn(key) }, "Remove" to { App.removeKey(key.id) }))
            }, onClick = { KeyWindow.editOwn(key) })
            val textRight = trailing(ui, key.shortcut?.keys?.map { it.name }, "Not recorded", Theme.MUTED, Look.HELD, l, t, r, b)
            ui.text(key.name, Fonts.body, Theme.TEXT, l + ui.px(20), t, textRight, b)
        }

        // Every paired PC.
        for (pc in App.others) {
            y += ui.px(28)
            section(ui, pc.name, if (pc.online) "Online" else "Offline", y, dot = if (pc.online) Theme.ACCENT else Theme.DISABLED_TEXT)
            val more = ui.pill("more:${pc.id}", "···", right, y, primary = false, height = ui.px(32), round = true) {
                menu(listOf("Unpair ${pc.name}" to { App.unpair(pc.id) }))
            }
            ui.pill("add:${pc.id}", "Add key", more - ui.px(8), y, primary = false, height = ui.px(32)) { KeyWindow.addRemote(pc) }
            y += ui.px(44)
            y = if (pc.keys.isEmpty()) emptyTile(ui, y, "No keys yet. Add one here, or on ${pc.name}.")
            else grid(ui, y, pc.keys, ui.px(64)) { key, l, t, r, b -> remoteTile(ui, pc, key, l, t, r, b) }
        }

        // Pairing.
        y += ui.px(28)
        y = if (App.others.isEmpty() || pairOpen || pairing != Pairing.IDLE) pairCard(ui, y) else {
            ui.pill("pair", "Pair another PC", right, y, primary = false) { pairOpen = true; refresh() }
            y + ui.px(40)
        }
        if (pairing != Pairing.ENTER) codeField?.hwnd?.let { ShowWindow(it, SW_HIDE) }
        return y + ui.px(PAD) + scroll
    }

    /**
     * Lays [items] out as tiles of [height], as many columns as fit at least [MIN_TILE] wide (one when narrow).
     * Returns the y below the last row.
     */
    private fun <T> grid(ui: Ui, y: Int, items: List<T>, height: Int, tile: (T, Int, Int, Int, Int) -> Unit): Int {
        val gap = ui.px(8)
        val columns = maxOf(1, (right - left + gap) / (ui.px(MIN_TILE) + gap))
        val width = (right - left - gap * (columns - 1)) / columns
        items.forEachIndexed { i, item ->
            val l = left + (i % columns) * (width + gap)
            val t = y + (i / columns) * (height + gap)
            tile(item, l, t, l + width, t + height)
        }
        return y + (items.size + columns - 1) / columns * (height + gap)
    }

    private fun section(ui: Ui, name: String, detail: String, y: Int, dot: Int? = null) {
        val nameW = minOf(ui.c.textWidth(name, Fonts.section), ui.px(220))
        ui.text(name, Fonts.section, Theme.TEXT, left, y, left + nameW, y + ui.px(32))
        var x = left + nameW + ui.px(10)
        if (dot != null) {
            ui.c.roundRect(x, y + ui.px(12), x + ui.px(8), y + ui.px(20), ui.px(4), dot)
            x += ui.px(14)
        }
        ui.text(detail, Fonts.body, Theme.MUTED, x, y, x + ui.px(120), y + ui.px(32))
    }

    /** A rounded, clickable tile background. */
    private fun tile(ui: Ui, id: String, l: Int, t: Int, r: Int, b: Int, onRight: () -> Unit, onClick: () -> Unit) {
        ui.c.roundRect(l, t, r, b, ui.px(16), if (ui.isHovered(id)) Theme.CARD_HOVER else Theme.CARD)
        ui.hit(id, l, t, r, b, onRight, onClick)
    }

    /**
     * The right end of a tile: keycaps when they fit, else [fallback] text. Returns where the tile's own text must
     * stop. Long shortcuts in a narrow tile fall back to their plain label rather than covering the name.
     */
    private fun trailing(ui: Ui, caps: List<String>?, fallback: String, fallbackColor: Int, look: Look, l: Int, t: Int, r: Int, b: Int): Int {
        if (caps != null) {
            val cw = ui.capsWidth(caps, Cap.SMALL)
            val capsLeft = r - ui.px(16) - cw
            if (capsLeft >= l + ui.px(110)) {
                ui.caps(caps, capsLeft, (t + b - ui.px(31)) / 2, Cap.SMALL, look)
                return capsLeft - ui.px(12)
            }
        }
        val text = if (caps != null) caps.joinToString("+") else fallback
        val color = if (caps != null) Theme.TEXT else fallbackColor
        val font = if (caps != null || fallbackColor == Theme.ACCENT) Fonts.label else Fonts.body
        val tw = minOf(ui.c.textWidth(text, font), (r - l) / 2)
        ui.text(text, font, color, r - ui.px(20) - tw, t, r - ui.px(20), b, DT_RIGHT or DT_VCENTER)
        return r - ui.px(20) - tw - ui.px(12)
    }

    private fun emptyTile(ui: Ui, y: Int, text: String): Int {
        ui.c.roundRect(left, y, right, y + ui.px(56), ui.px(16), Theme.CARD)
        ui.text(text, Fonts.body, Theme.MUTED, left + ui.px(20), y, right - ui.px(20), y + ui.px(56))
        return y + ui.px(64)
    }

    /** Another PC's key: its name and shortcut there, and this PC's hotkey for it (click to set). */
    private fun remoteTile(ui: Ui, pc: PcInfo, key: KeyInfo, l: Int, t: Int, r: Int, b: Int) {
        tile(ui, "${pc.id}:${key.id}", l, t, r, b, onRight = {
            menu(listOf(
                "Press now" to { App.press(pc.id, key.id) },
                "Edit on ${pc.name}" to { KeyWindow.editRemote(pc, key) },
                "Remove from ${pc.name}" to { App.removeRemote(pc.id, key.id) },
            ))
        }, onClick = { KeyWindow.bind(pc, key) })
        val hotkey = App.config.hotkey(pc.id, key.id)?.shortcut?.keys?.map { it.name }
        val textRight = trailing(ui, hotkey, "Set hotkey", Theme.ACCENT, Look.READY, l, t, r, b)
        val mid = (t + b) / 2
        ui.text(key.name, Fonts.body, Theme.TEXT, l + ui.px(20), mid - ui.px(20), textRight, mid)
        val there = if (key.label.isEmpty()) "Not recorded there yet" else "Presses ${key.label}"
        ui.text(there, Fonts.small, Theme.MUTED, l + ui.px(20), mid, textRight, mid + ui.px(18))
    }

    private fun pairCard(ui: Ui, y: Int): Int {
        val cardLeft = left
        val cardRight = minOf(right, left + ui.px(MAX_CARD))
        val inner = cardLeft + ui.px(24)
        val innerRight = cardRight - ui.px(24)
        val bottom = y + when (pairing) {
            Pairing.CODE -> ui.px(212)
            Pairing.ENTER -> ui.px(200)
            else -> ui.px(156)
        }
        ui.c.roundRect(cardLeft, y, cardRight, bottom, ui.px(28), Theme.CARD)
        var cy = y + ui.px(24)
        val title = if (App.others.isEmpty()) "Pair with another PC" else "Pair another PC"
        ui.text(title, Fonts.section, Theme.TEXT, inner, cy, innerRight, cy + ui.px(24))
        cy += ui.px(28)
        val buttonsTop = bottom - ui.px(24) - ui.px(40)
        when (pairing) {
            Pairing.IDLE, Pairing.BUSY -> {
                val text = if (pairing == Pairing.BUSY) "One moment…" else "Show a code here and type it on the other PC."
                ui.text(text, Fonts.body, Theme.MUTED, inner, cy, innerRight, cy + ui.px(20))
                pairError?.let { ui.text(it, Fonts.body, Theme.ERROR, inner, cy + ui.px(22), innerRight, cy + ui.px(42)) }
                if (pairing == Pairing.IDLE) {
                    val left = ui.pill("code:show", "Show code", innerRight, buttonsTop, primary = true, on = Theme.CARD, onClick = ::showCode)
                    val enterLeft = ui.pill("code:enter", "Enter code", left - ui.px(8), buttonsTop, primary = false, on = Theme.CARD, onClick = ::enter)
                    if (App.others.isNotEmpty()) ui.pill("code:close", "Cancel", enterLeft - ui.px(8), buttonsTop, primary = false, on = Theme.CARD, onClick = ::idle)
                }
            }
            Pairing.CODE -> {
                ui.text("Type this on the other PC. It works once, for 10 minutes.", Fonts.body, Theme.MUTED, inner, cy, innerRight, cy + ui.px(20))
                cy += ui.px(36)
                val groups = code.split('-').map { g -> g.map { it.toString() } }
                val widths = groups.map { ui.capsWidth(it, Cap.MEDIUM) }
                val dash = ui.px(24)
                var x = (cardLeft + cardRight - widths.sum() - dash) / 2
                groups.forEachIndexed { i, g ->
                    ui.caps(g, x, cy, Cap.MEDIUM, Look.READY)
                    x += widths[i] + dash
                }
                ui.pill("code:done", "Done", innerRight, buttonsTop, primary = false, on = Theme.CARD, onClick = ::idle)
            }
            Pairing.ENTER -> {
                ui.text("Type the code the other PC shows.", Fonts.body, Theme.MUTED, inner, cy, innerRight, cy + ui.px(20))
                cy += ui.px(32)
                ShowWindow(codeField?.hwnd, SW_SHOW)
                ui.fieldBox(codeField, inner, cy, innerRight, cy + ui.px(44))
                pairError?.let { ui.text(it, Fonts.body, Theme.ERROR, inner, buttonsTop, innerRight - ui.px(200), buttonsTop + ui.px(40)) }
                val left = ui.pill("code:join", "Pair", innerRight, buttonsTop, primary = true, onClick = ::join)
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
