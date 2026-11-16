@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.App
import app.gokeys.OwnKey
import app.gokeys.input.Key
import app.gokeys.input.KeyRecorder
import app.gokeys.input.Shortcut
import app.gokeys.net.KeyInfo
import app.gokeys.net.PcInfo
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.*

/**
 * Adds or edits one key, or sets this PC's hotkey for another PC's key. The keys are the point, so they're drawn
 * big on a stage: grey while held, mint once complete and usable. Recorded like PowerToys: a new combination
 * replaces the last one, problems show inline and block saving, Enter saves and Esc cancels.
 */
object KeyWindow : PaintedWindow("gokeys-key", widthDp = 440, maxHeightDp = 480), KeyRecorder.Listener {
    /** What's being edited. */
    sealed interface Target {
        /** A key on this PC (null: a new one). */
        data class Own(val key: OwnKey?) : Target
        /** A key on another PC, recorded here (same physical keys) and sent to it. */
        data class Remote(val pc: PcInfo, val key: KeyInfo?) : Target
        /** This PC's hotkey for another PC's key. */
        data class Binding(val pc: PcInfo, val key: KeyInfo) : Target
    }

    private var target: Target = Target.Own(null)
    private var held = emptyList<Key>()
    private var captured: Shortcut? = null
    private var problem: String? = null
    private var nameField: Field? = null

    fun addOwn() = open(Target.Own(null))
    fun editOwn(key: OwnKey) = open(Target.Own(key))
    fun addRemote(pc: PcInfo) = open(Target.Remote(pc, null))
    fun editRemote(pc: PcInfo, key: KeyInfo) = open(Target.Remote(pc, key))
    fun bind(pc: PcInfo, key: KeyInfo) = open(Target.Binding(pc, key))

    /** Debug previews. */
    internal fun preview(target: Target, held: List<Key>, captured: Shortcut?) {
        this.target = target
        this.held = held
        this.captured = captured
        problem = captured?.let(::problemWith)
    }

    private fun open(target: Target) {
        this.target = target
        held = emptyList()
        captured = null
        problem = null
        show("gokeys")
        val name = when (target) {
            is Target.Own -> target.key?.name
            is Target.Remote -> target.key?.name
            is Target.Binding -> null
        }
        nameField?.let { field ->
            ShowWindow(field.hwnd, if (target is Target.Binding) SW_HIDE else SW_SHOW)
            field.text = name.orEmpty()
            if (target !is Target.Binding && name == null) field.focus() else SetFocus(hwnd)
        }
        KeyRecorder.start(this)
        refresh()
    }

    override fun onCreate() {
        nameField = Field(hwnd, "Name, e.g. Volume down", 60, onEnter = ::fromName, onEscape = ::close, onTab = ::arm)
    }

    override fun close() {
        KeyRecorder.stop()
        nameField?.destroy()
        nameField = null
        super.close()
    }

    /** Recording happens while the window itself has focus, not the name field. */
    private val armed get() = isOpen && GetFocus() == hwnd

    private fun arm() {
        SetFocus(hwnd)
        refresh()
    }

    private fun fromName() = if (canSave) save() else arm()

    /** The shortcut that's there now, before any change. */
    private val existing: Shortcut?
        get() = when (val t = target) {
            is Target.Own -> t.key?.shortcut
            is Target.Remote -> null // only the owner has it; its label is shown instead
            is Target.Binding -> App.config.hotkey(t.pc.id, t.key.id)?.shortcut
        }

    private val name get() = nameField?.text?.trim().orEmpty()

    private val canSave: Boolean
        get() = held.isEmpty() && problem == null && when (target) {
            is Target.Binding -> captured != null
            else -> name.isNotEmpty()
        }

    private fun save() {
        if (!canSave) return
        when (val t = target) {
            is Target.Own -> App.saveKey(OwnKey(t.key?.id ?: OwnKey.newId(), name, captured ?: t.key?.shortcut))
            is Target.Remote -> App.editRemote(t.pc.id, t.key?.id ?: OwnKey.newId(), name, captured)
            is Target.Binding -> App.setHotkey(t.pc.id, t.key.id, captured)
        }
        close()
    }

    private fun remove() {
        when (val t = target) {
            is Target.Own -> t.key?.let { App.removeKey(it.id) }
            is Target.Remote -> t.key?.let { App.removeRemote(t.pc.id, it.id) }
            is Target.Binding -> App.setHotkey(t.pc.id, t.key.id, null)
        }
        close()
    }

    /** Why a shortcut won't work, in a few words; null if it's fine. */
    private fun problemWith(shortcut: Shortcut): String? = when (val t = target) {
        is Target.Own -> App.config.keys.find { it.id != t.key?.id && it.shortcut?.sameAs(shortcut) == true }?.let { "Already used for ${it.name}" }
        is Target.Binding -> App.config.hotkeys.find { !(it.pc == t.pc.id && it.key == t.key.id) && it.shortcut.sameAs(shortcut) }?.let { h ->
            val key = App.others.find { it.id == h.pc }?.keys?.find { it.id == h.key }
            "Already your hotkey for ${key?.name ?: "another key"}"
        }
        is Target.Remote -> null // only the owner knows its other shortcuts
    }

    // KeyRecorder.Listener

    override fun capturing() = GetForegroundWindow() == hwnd && nameField?.focused != true

    override fun onHeld(keys: List<Key>) {
        held = keys
        refresh()
    }

    override fun onShortcut(shortcut: Shortcut) {
        held = emptyList()
        captured = shortcut
        problem = problemWith(shortcut)
        refresh()
    }

    override fun onEnter() = save()

    override fun onEscape() = close()

    override fun draw(ui: Ui): Int {
        val pad = ui.px(28)
        val w = ui.width
        val t = target
        var y = ui.px(22) - scroll

        val isNew = (t is Target.Own && t.key == null) || (t is Target.Remote && t.key == null)
        val title = when (t) {
            is Target.Binding -> t.key.name
            else -> if (isNew) "New key" else "Edit key"
        }
        val where = when (t) {
            is Target.Own -> "This PC"
            is Target.Remote -> t.pc.name
            is Target.Binding -> t.pc.name
        }
        val tagLeft = ui.tag(where, w - pad, y + ui.px(6), Theme.ACCENT_TINT, Theme.ACCENT)
        ui.text(title, Fonts.title, Theme.TEXT, pad, y, tagLeft - ui.px(12), y + ui.px(36))
        y += ui.px(38)
        val ask = when (t) {
            is Target.Own -> "Paired PCs can press this here."
            is Target.Remote -> "Press the keys here; ${t.pc.name} presses the same ones."
            is Target.Binding -> "Your hotkey for it. It keeps working on this PC too."
        }
        ui.text(ask, Fonts.body, Theme.MUTED, pad, y, w - pad, y + ui.px(20))
        y += ui.px(36)

        if (t !is Target.Binding) {
            ui.fieldBox(nameField, pad, y, w - pad, y + ui.px(44))
            y += ui.px(60)
        }

        // The stage.
        val stageTop = y
        val stageBottom = y + ui.px(108)
        val armed = armed
        if (armed) ui.c.roundRect(pad - ui.px(2), stageTop - ui.px(2), w - pad + ui.px(2), stageBottom + ui.px(2), ui.px(30), Theme.ACCENT_TINT)
        ui.c.roundRect(pad, stageTop, w - pad, stageBottom, ui.px(28), Theme.CARD)
        if (!armed) ui.hit("stage", pad, stageTop, w - pad, stageBottom) { arm() }
        val remoteLabel = (t as? Target.Remote)?.key?.label?.takeIf { it.isNotEmpty() }
        val shown: List<String>? = when {
            held.isNotEmpty() -> held.map { it.name } + ""
            captured != null -> captured!!.keys.map { it.name }
            existing != null -> existing!!.keys.map { it.name }
            remoteLabel != null -> remoteLabel.split('+')
            else -> null
        }
        if (shown == null) {
            val text = if (armed) "Press the keys" else "Click here, then press the keys"
            ui.text(text, Fonts.body, Theme.MUTED, pad, stageTop, w - pad, stageBottom, DT_CENTER or DT_VCENTER)
        } else {
            val look = if (held.isEmpty() && captured != null && problem == null) Look.READY else Look.HELD
            val cw = ui.capsWidth(shown, Cap.BIG)
            ui.caps(shown, (w - cw) / 2, (stageTop + stageBottom - ui.px(61)) / 2, Cap.BIG, look)
        }
        y = stageBottom + ui.px(10)
        val (line, color) = when {
            held.isNotEmpty() -> "Now the last key" to Theme.MUTED
            problem != null -> problem!! to Theme.ERROR
            captured != null -> "Enter to save" to Theme.MUTED
            shown != null && armed -> "Press new keys to change it" to Theme.MUTED
            shown != null -> "Current shortcut" to Theme.MUTED
            else -> "" to Theme.MUTED
        }
        ui.text(line, Fonts.small, color, pad, y, w - pad, y + ui.px(18), DT_CENTER or DT_VCENTER)
        y += ui.px(36)

        // Buttons: remove on the left when there's something to remove, cancel and save on the right.
        val saveLabel = if (t is Target.Binding) "Save" else if (isNew) "Add" else "Save"
        val left = ui.pill("save", saveLabel, w - pad, y, primary = true, enabled = canSave, onClick = ::save)
        ui.pill("cancel", "Cancel", left - ui.px(8), y, primary = false, onClick = ::close)
        val removable = when (t) {
            is Target.Binding -> existing != null
            else -> !isNew
        }
        if (removable) {
            val label = if (t is Target.Binding) "Clear" else "Remove"
            val rw = ui.c.textWidth(label, Fonts.label) + ui.px(32)
            ui.c.text(label, Fonts.label, if (ui.isHovered("remove")) Theme.ERROR else Theme.MUTED, pad, y, pad + rw, y + ui.px(40), DT_LEFT or DT_VCENTER)
            ui.hit("remove", pad, y, pad + rw, y + ui.px(40)) { remove() }
        }
        return y + ui.px(40) + ui.px(28) + scroll
    }
}
