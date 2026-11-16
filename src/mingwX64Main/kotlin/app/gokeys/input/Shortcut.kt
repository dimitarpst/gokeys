@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.input

import kotlinx.cinterop.*
import platform.windows.*

/** One physical key as Windows reports it. Replayed by scan code, so the receiver's own layout decides what it means. */
data class Key(val scan: Int, val vk: Int, val extended: Boolean) {
    /** RegisterHotKey's MOD_* flag for Ctrl/Shift/Alt/Win, 0 for any other key. */
    val modifier: Int
        get() = when (vk) {
            0x12, 0xA4, 0xA5 -> MOD_ALT
            0x11, 0xA2, 0xA3 -> MOD_CONTROL
            0x10, 0xA0, 0xA1 -> MOD_SHIFT
            0x5B, 0x5C -> MOD_WIN
            else -> 0
        }

    /** Name on this PC's layout, e.g. "Ctrl" or "Strg", "F7", "Ä". */
    val name: String
        get() = memScoped {
            val eitherSide = if (modifier != 0) 1 shl 25 else 0 // "Ctrl", not "Right Ctrl"
            val buf = allocArray<WCHARVar>(64)
            val n = GetKeyNameTextW((scan shl 16) or (if (extended) 1 shl 24 else 0) or eitherSide, buf, 64)
            when {
                n > 0 -> buf.toKString()
                vk in 0x70..0x87 -> "F${vk - 0x6F}" // F13–F24 have no name in most layouts
                else -> "0x" + vk.toString(16).uppercase()
            }
        }
}

/** Modifiers in the order they were pressed, then one main key. Stored as `scan:vk:ext` hex triples. */
data class Shortcut(val keys: List<Key>) {
    val main get() = keys.last()
    val modifiers get() = keys.fold(0) { flags, key -> flags or key.modifier }
    val label get() = keys.joinToString("+") { it.name }

    /** Same combination, whatever order the modifiers went down in. */
    fun sameAs(other: Shortcut) = modifiers == other.modifiers && main.scan == other.main.scan && main.extended == other.main.extended

    fun encode() = keys.joinToString(",") { "${it.scan.toString(16)}:${it.vk.toString(16)}:${if (it.extended) 1 else 0}" }

    companion object {
        fun decode(s: String?): Shortcut? = runCatching {
            Shortcut(s!!.split(",").map { key -> key.split(":").let { Key(it[0].toInt(16), it[1].toInt(16), it[2] == "1") } })
        }.getOrNull()
    }
}
