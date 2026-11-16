package app.gokeys.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A key as its owner publishes it to the room. [label] is the shortcut in the owner's layout, "" if not recorded. */
@Serializable
data class KeyInfo(val id: String, val name: String, val label: String)

/** A PC in the room, as the relay reports it. */
@Serializable
data class PcInfo(val id: String, val name: String, val online: Boolean, val keys: List<KeyInfo>)

/** What travels over the room's WebSocket (see relay/src/index.ts), tagged by "t". */
@Serializable
sealed interface Message

/** First thing after connecting: who this PC is and which keys it offers. */
@Serializable @SerialName("hello")
data class Hello(val name: String, val keys: List<KeyInfo>) : Message

/** This PC's keys changed. */
@Serializable @SerialName("keys")
data class Keys(val keys: List<KeyInfo>) : Message

/** Press [key] on PC [to]; arrives at the owner with [from] set instead. */
@Serializable @SerialName("press")
data class Press(val key: String, val to: String? = null, val from: String? = null) : Message

/** Add, rename, re-record or delete a key on its owner. [shortcut] is [app.gokeys.input.Shortcut.encode]d scan codes. */
@Serializable @SerialName("edit")
data class Edit(
    val key: String,
    val to: String? = null,
    val from: String? = null,
    val name: String? = null,
    val shortcut: String? = null,
    val remove: Boolean = false,
) : Message

/** Unpair PC [pc]. */
@Serializable @SerialName("remove")
data class Remove(val pc: String) : Message

/** The whole room, sent to everyone whenever anything changes. */
@Serializable @SerialName("room")
data class Room(val you: String, val pcs: List<PcInfo>) : Message

/** Something to tell the user, e.g. "OFFICE-PC is offline". */
@Serializable @SerialName("note")
data class Note(val text: String) : Message

val wire = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    explicitNulls = false
}
