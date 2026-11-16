package app.gokeys.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * This PC's connection to its room on the relay: one WebSocket, kept open and reconnected, plus the few plain HTTP
 * calls for registering and pairing. Both PCs connect out, so nobody opens ports.
 * Every [Listener] call comes from a background thread.
 */
class Link(private val relayUrl: String, private val listener: Listener) {
    interface Listener {
        /** The token to connect with; null registers a new one. */
        fun token(): String?
        fun onToken(token: String)
        /** Sent right after connecting. */
        fun hello(): Hello
        fun onMessage(message: Message)
        fun onStatus(online: Boolean, text: String)
    }

    /** A relay answer meant for the user, e.g. "That code didn't work…". */
    class Refused(message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient(WinHttp) {
        install(WebSockets)
        install(HttpTimeout)
    }
    private var session: DefaultClientWebSocketSession? = null

    init {
        scope.launch { run() }
    }

    /** Sends [message] if connected; false otherwise (a press shouldn't fire minutes later). */
    fun send(message: Message): Boolean {
        val s = session ?: return false
        scope.launch { runCatching { s.send(Frame.Text(wire.encodeToString(Message.serializer(), message))) } }
        return true
    }

    /** A pairing code for another PC to join this room. */
    suspend fun code(): String = call("/code", JsonObject(emptyMap()), auth = true)["code"]!!.jsonPrimitive.content

    /** Joins the room of [code]; returns the new token (this PC leaves its old room). */
    suspend fun join(code: String, name: String): String =
        call("/join", JsonObject(mapOf("code" to JsonPrimitive(code), "name" to JsonPrimitive(name))), auth = true)["token"]!!.jsonPrimitive.content

    fun close() {
        scope.cancel()
        http.close()
    }

    private suspend fun run() {
        var backoff = 1
        while (scope.isActive) {
            try {
                val token = listener.token() ?: register()
                http.webSocket(url("/live").replaceFirst("http", "ws"), request = { bearer(token) }) {
                    session = this
                    backoff = 1
                    send(Frame.Text(wire.encodeToString(Message.serializer(), listener.hello())))
                    listener.onStatus(true, "Connected")
                    launch { while (true) { send(Frame.Text("ping")); delay(30.seconds) } }
                    // A pong comes every 30 s, so a silent 75 s means a dead connection (sleep, Wi-Fi change).
                    while (true) {
                        val frame = withTimeoutOrNull(75.seconds) { incoming.receive() } ?: break
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        if (text == "pong") continue
                        runCatching { wire.decodeFromString(Message.serializer(), text) }.getOrNull()?.let(listener::onMessage)
                    }
                }
                listener.onStatus(false, "Reconnecting…")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.onStatus(false, "Offline · retrying")
                // A refused handshake may mean this PC was unpaired and its token revoked: start over with a new one.
                runCatching { listener.token()?.let { t -> if (http.get(url("/me")) { bearer(t); quick() }.status == HttpStatusCode.Unauthorized) register() } }
            } finally {
                session = null
            }
            delay(backoff.seconds)
            backoff = minOf(backoff * 2, 30)
        }
    }

    /** A new token in a room of its own (also how an unpaired PC starts over). */
    private suspend fun register(): String {
        val token = call("/register", JsonObject(mapOf("name" to JsonPrimitive(listener.hello().name))), auth = false)["token"]!!.jsonPrimitive.content
        listener.onToken(token)
        return token
    }

    private suspend fun call(path: String, body: JsonObject, auth: Boolean): JsonObject {
        val res = http.post(url(path)) {
            if (auth) listener.token()?.let { bearer(it) }
            contentType(ContentType.Application.Json)
            setBody(body.toString())
            quick()
        }
        val text = res.bodyAsText()
        if (res.status != HttpStatusCode.OK) throw Refused(text.take(120))
        return wire.parseToJsonElement(text).jsonObject
    }

    private fun url(path: String) = relayUrl.trimEnd('/') + path

    private fun HttpRequestBuilder.bearer(token: String) = header("Authorization", "Bearer $token")

    private fun HttpRequestBuilder.quick() = timeout { requestTimeoutMillis = 10_000; connectTimeoutMillis = 10_000 }

}
