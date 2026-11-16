@file:OptIn(ExperimentalNativeApi::class)

package app.gokeys

import app.gokeys.input.Shortcut
import kotlin.experimental.ExperimentalNativeApi
import kotlin.random.Random

/** A key this PC presses when a paired PC asks: a name and a shortcut (null until recorded). */
data class OwnKey(val id: String, val name: String, val shortcut: Shortcut?) {
    companion object {
        fun newId(): String = (1..8).map { "abcdefghijkmnpqrstuvwxyz23456789"[Random.nextInt(32)] }.joinToString("")
    }
}

/** This PC's hotkey for key [key] of paired PC [pc]. */
data class Hotkey(val pc: String, val key: String, val shortcut: Shortcut)

/**
 * Settings in `%APPDATA%\gokeys\settings.properties`, one `name=value` per line; keys and hotkeys repeat:
 * `key=<id>|<shortcut>|<name>` and `hotkey=<pc>|<key>|<shortcut>`.
 */
data class Config(
    val token: String? = null, // <room>.<pc>.<secret> from the relay; null until registered
    val name: String = Win.env("COMPUTERNAME").ifEmpty { "PC" },
    val relayUrl: String = defaultRelay,
    val paused: Boolean = false,
    val keys: List<OwnKey> = emptyList(),
    val hotkeys: List<Hotkey> = emptyList(),
) {
    fun hotkey(pc: String, key: String) = hotkeys.find { it.pc == pc && it.key == key }

    fun save() {
        Win.mkdir(dir)
        Win.writeText(file, buildString {
            token?.let { appendLine("token=$it") }
            appendLine("name=$name")
            if (relayUrl != defaultRelay) appendLine("relayUrl=$relayUrl")
            appendLine("paused=$paused")
            keys.forEach { appendLine("key=${it.id}|${it.shortcut?.encode().orEmpty()}|${it.name}") }
            hotkeys.forEach { appendLine("hotkey=${it.pc}|${it.key}|${it.shortcut.encode()}") }
        })
    }

    companion object {
        /** GOKEYS_PROFILE=b gives a second instance on the same PC its own settings (local testing). */
        val profile = Win.env("GOKEYS_PROFILE")
        val dir = Win.env("APPDATA") + "\\gokeys"
        private val file = dir + if (profile.isEmpty()) "\\settings.properties" else "\\settings-$profile.properties"

        /** The public relay; debug builds talk to `npm run dev` unless GOKEYS_RELAY says otherwise. */
        private val defaultRelay = Win.env("GOKEYS_RELAY").ifEmpty {
            if (Platform.isDebugBinary) "http://localhost:8787" else "https://keys.govrse.app"
        }

        /** No settings saved yet: a fresh install. */
        val isFirstRun get() = Win.readText(file) == null

        fun load(): Config {
            val lines = Win.readText(file).orEmpty().lineSequence()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                .toList()
            val p = lines.toMap()
            fun all(name: String) = lines.filter { it.first == name }.map { it.second.split('|', limit = 3) }.filter { it.size == 3 }
            val defaults = Config()
            return Config(
                token = p["token"]?.takeIf { it.count { c -> c == '.' } == 2 }, // the old shared token had no dots
                name = p["name"] ?: defaults.name,
                relayUrl = p["relayUrl"]?.takeIf { "name" in p } ?: defaults.relayUrl, // the first version always saved one
                paused = p["paused"] == "true",
                keys = all("key").map { (id, s, name) -> OwnKey(id, name, Shortcut.decode(s)) } + legacyKeys(p),
                hotkeys = all("hotkey").mapNotNull { (pc, key, s) -> Shortcut.decode(s)?.let { Hotkey(pc, key, it) } },
            )
        }

        /** The first version had exactly three shortcuts; they become ordinary keys. */
        private fun legacyKeys(p: Map<String, String>) = listOf("down" to "Volume down", "up" to "Volume up", "mute" to "Mute")
            .mapNotNull { (id, name) -> Shortcut.decode(p["shortcut.$id"])?.let { OwnKey(id, name, it) } }
    }
}
