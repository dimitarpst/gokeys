package app.gokeys

import app.gokeys.input.Hotkeys
import app.gokeys.input.KeyPresser
import app.gokeys.input.Shortcut
import app.gokeys.net.Edit
import app.gokeys.net.Hello
import app.gokeys.net.KeyInfo
import app.gokeys.net.Keys
import app.gokeys.net.Link
import app.gokeys.net.Message
import app.gokeys.net.Note
import app.gokeys.net.PcInfo
import app.gokeys.net.Press
import app.gokeys.net.Remove
import app.gokeys.net.Release
import app.gokeys.net.Room
import app.gokeys.net.Updates
import app.gokeys.ui.Flyout
import app.gokeys.ui.MainWindow
import app.gokeys.ui.Tray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The app's state: settings, the room as the relay last described it, and the one live [Link].
 * Main thread only; the link's callbacks are posted here first.
 */
object App : Link.Listener {
    var config = Config.load()
        internal set // Preview sets it directly
    /** The other PCs in this PC's room. */
    var others = emptyList<PcInfo>()
        internal set
    var online = false
        private set
    var status = "Connecting…"
        private set
    /** A short message from the relay ("OFFICE-PC is offline"), shown for a few seconds. */
    var note: String? = null
        private set

    /** A newer gokeys on GitHub, once a check found one. */
    var update: Release? = null
        internal set
    /** Downloading [update], or why that failed. */
    var updating = false
        private set
    var updateError: String? = null
        private set

    private var link: Link? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A fresh install starts with Windows and opens the window, which starts with pairing. */
    fun start(openWindow: Boolean) {
        val firstRun = Config.isFirstRun
        if (firstRun) {
            config.save()
            if (Install.isInstalled) Install.autostart = true
        }
        connect()
        if (openWindow || firstRun) MainWindow.open()
        Install.cleanUp()
        if (Updates.enabled) background.launch {
            delay(5.seconds)
            while (true) {
                runCatching { Updates.latest() }.onSuccess { found -> Tray.post { update = found; refresh() } }
                delay(6.hours)
            }
        }
    }

    /** Downloads the update, checks it, and runs it; it replaces this copy and reopens the window if it was open. */
    fun installUpdate() {
        val release = update ?: return
        if (updating) return
        updating = true
        updateError = null
        refresh()
        background.launch {
            val result = runCatching { Updates.download(release) }
            Tray.post {
                result.onSuccess { Install.runUpdate(it, openWindow = MainWindow.isOpen) }
                result.onFailure {
                    updating = false
                    updateError = it.message ?: "The update didn't download. Try again."
                    refresh()
                }
            }
        }
    }

    // This PC's keys

    fun saveKey(key: OwnKey) {
        val keys = if (config.keys.any { it.id == key.id }) config.keys.map { if (it.id == key.id) key else it } else config.keys + key
        change { copy(keys = keys) }
        link?.send(Keys(published()))
    }

    fun removeKey(id: String) {
        change { copy(keys = keys.filterNot { it.id == id }) }
        link?.send(Keys(published()))
    }

    // Other PCs' keys

    fun press(pc: String, key: String) {
        if (link?.send(Press(key, to = pc)) != true) tell("Not connected")
    }

    /** Adds or changes key [key] on PC [pc]; it applies the edit and publishes its new list. */
    fun editRemote(pc: String, key: String, name: String, shortcut: Shortcut?) {
        if (link?.send(Edit(key, to = pc, name = name, shortcut = shortcut?.encode())) != true) tell("Not connected")
    }

    fun removeRemote(pc: String, key: String) {
        if (link?.send(Edit(key, to = pc, remove = true)) != true) tell("Not connected")
        setHotkey(pc, key, null)
    }

    fun setHotkey(pc: String, key: String, shortcut: Shortcut?) = change {
        copy(hotkeys = hotkeys.filterNot { it.pc == pc && it.key == key } + listOfNotNull(shortcut?.let { Hotkey(pc, key, it) }))
    }

    fun unpair(pc: String) {
        if (link?.send(Remove(pc)) != true) return tell("Not connected")
        change { copy(hotkeys = hotkeys.filterNot { it.pc == pc }) }
    }

    // Pairing

    /** A code for another PC to type; [done] runs on the main thread. */
    fun pairingCode(done: (Result<String>) -> Unit) {
        val l = link ?: return done(Result.failure(Exception("Not connected")))
        background.launch {
            val result = runCatching { l.code() }
            Tray.post { done(result) }
        }
    }

    /** Joins the PC that shows [code]. */
    fun join(code: String, done: (Result<Unit>) -> Unit) {
        val l = link ?: return done(Result.failure(Exception("Not connected")))
        background.launch {
            val result = runCatching { l.join(code, config.name) }
            Tray.post {
                result.onSuccess { token ->
                    change { copy(token = token) }
                    connect()
                }
                done(result.map { })
            }
        }
    }

    // Tray

    fun togglePause() {
        change { copy(paused = !paused) }
        connect()
    }

    fun quit() {
        link?.close()
        Hotkeys.clear()
        MainWindow.close()
        Tray.destroy()
    }

    /** Redraws everything that shows state and re-arms the hotkeys. */
    fun refresh() {
        Hotkeys.set(
            if (config.paused) emptyList()
            else config.hotkeys
                .filter { h -> others.any { pc -> pc.id == h.pc && pc.keys.any { it.id == h.key } } }
                .map { h -> h.shortcut to { press(h.pc, h.key) } },
        )
        Tray.update()
        MainWindow.refresh()
        Flyout.refresh()
    }

    private fun tell(text: String) {
        note = text
        Tray.clearNoteLater()
        refresh()
    }

    internal fun clearNote() {
        note = null
        refresh()
    }

    private fun change(block: Config.() -> Config) {
        config = config.block().also(Config::save)
        refresh()
    }

    private fun connect() {
        link?.close()
        link = null
        online = false
        others = others.map { it.copy(online = false) }
        status = if (config.paused) "Paused" else "Connecting…"
        if (!config.paused) link = Link(config.relayUrl, this)
        refresh()
    }

    private fun published() = config.keys.map { KeyInfo(it.id, it.name, it.shortcut?.label.orEmpty()) }

    private fun receive(message: Message) {
        val paired = { id: String? -> others.any { it.id == id } }
        when (message) {
            is Room -> others = message.pcs.filter { it.id != message.you }
            is Press -> if (paired(message.from)) {
                config.keys.find { it.id == message.key }?.shortcut?.let { s -> background.launch { KeyPresser.press(s) } }
            }
            is Edit -> if (paired(message.from)) {
                if (message.remove) return removeKey(message.key)
                val old = config.keys.find { it.id == message.key }
                val shortcut = message.shortcut?.let(Shortcut::decode) ?: old?.shortcut
                return saveKey(OwnKey(message.key, message.name ?: old?.name ?: "Key", shortcut))
            }
            is Note -> return tell(message.text)
            else -> {}
        }
        refresh()
    }

    // Link.Listener: background threads, so everything that touches state hops to the main thread.

    override fun token() = config.token

    override fun onToken(token: String) = Tray.post { change { copy(token = token) } }

    override fun hello() = Hello(config.name, published())

    override fun onMessage(message: Message) = Tray.post { receive(message) }

    override fun onStatus(online: Boolean, text: String) = Tray.post {
        this.online = online
        status = text
        if (!online) others = others.map { it.copy(online = false) }
        refresh()
    }
}
