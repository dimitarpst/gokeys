@file:OptIn(ExperimentalNativeApi::class)

package app.gokeys.net

import app.gokeys.VERSION
import app.gokeys.Win
import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.experimental.ExperimentalNativeApi

/** A newer gokeys.exe on GitHub Releases, with the size and SHA-256 GitHub reports for it. */
data class Release(val version: String, val url: String, val size: Long, val sha256: String)

/**
 * Updates the way gocast does: ask GitHub for the latest release, download its gokeys.exe, and only use it if the
 * size and SHA-256 match what GitHub published. Running it then replaces this copy ([app.gokeys.Install]).
 */
object Updates {
    const val REPOSITORY = "dimitarpst/gokeys"
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** Debug builds can be pointed at a test server; release builds only ever ask GitHub. */
    private val override = if (Platform.isDebugBinary) Win.env("GOKEYS_UPDATES") else ""
    private val api = override.ifEmpty { "https://api.github.com/repos/$REPOSITORY/releases/latest" }

    /** Whether this build checks at all: release builds always, debug builds only when pointed at a test server. */
    val enabled = !Platform.isDebugBinary || override.isNotEmpty()

    private val http = HttpClient(WinHttp) { install(HttpTimeout) }

    /** The latest release if it's newer than this build, else null. Throws when GitHub can't be asked. */
    suspend fun latest(): Release? {
        val res = http.get(api) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "gokeys/$VERSION")
            timeout { requestTimeoutMillis = 15_000 }
        }
        if (res.status != HttpStatusCode.OK) throw Exception("GitHub said ${res.status.value}")
        val release = wire.parseToJsonElement(res.bodyAsText()).jsonObject
        if (release.bool("draft") != false || release.bool("prerelease") != false) return null
        val tag = release.string("tag_name") ?: return null
        val version = tag.removePrefix("v")
        if (!newer(version, VERSION)) return null
        val asset = release["assets"]?.jsonArray?.map { it.jsonObject }?.singleOrNull { it.string("name") == "gokeys.exe" } ?: return null
        if (asset.string("state") != "uploaded") return null
        val size = asset["size"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..MAX_BYTES } ?: return null
        val sha256 = Regex("sha256:([a-fA-F0-9]{64})").matchEntire(asset.string("digest").orEmpty())?.groupValues?.get(1)?.lowercase() ?: return null
        val url = asset.string("browser_download_url") ?: return null
        if (override.isEmpty() && url != "https://github.com/$REPOSITORY/releases/download/$tag/gokeys.exe") return null
        return Release(version, url, size, sha256)
    }

    /** Downloads [release] next to the installed copy and checks it; returns the file's path. */
    suspend fun download(release: Release): String {
        val res = http.get(release.url) {
            header("User-Agent", "gokeys/$VERSION")
            timeout { requestTimeoutMillis = 120_000 }
        }
        if (res.status != HttpStatusCode.OK) throw Exception("The download didn't start")
        val bytes = res.bodyAsBytes()
        if (bytes.size.toLong() != release.size || Win.sha256(bytes) != release.sha256) throw Exception("The download was damaged. Try again.")
        val dir = Win.env("LOCALAPPDATA") + "\\gokeys\\update"
        Win.mkdir(Win.env("LOCALAPPDATA") + "\\gokeys")
        Win.mkdir(dir)
        val path = "$dir\\gokeys.exe"
        if (!Win.writeBytes(path, bytes)) throw Exception("The update couldn't be saved")
        return path
    }

    /** Is version [a] newer than [b]? Numbers compared part by part: 1.10.0 > 1.9.2. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: return false }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
}
