package dev.gladimdim.superdesktop.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What GET /api/v1/ping says about a computer; needs no token. */
data class PingInfo(
    val hostname: String,
    val version: Int,
    val bridgeId: String,
    val addresses: List<BridgeAddress>,
)

/**
 * The SUPER DESKTOP bridge at [baseUrl], over HTTPS pinned to [pin] (PROTOCOL.md, wire v3).
 * The parts of the SUPER DESKTOP app's HarnessApi a home page needs (see SOURCE.md).
 */
class BridgeApi(
    private val baseUrl: String,
    private val token: String,
    private val pin: String,
) {
    init {
        require(baseUrl.startsWith("https://")) { "Encrypted bridge URL required" }
    }

    private val client get() = BridgeTls.client(pin)
    private val json = "application/json".toMediaType()

    private fun authed(path: String) = Request.Builder().url("$baseUrl$path").header("Authorization", "Bearer $token")

    /** The body as JSON; 401 is [NotPairedException], other failures the bridge's `error` code. */
    private fun Response.json(): JSONObject {
        val body = body?.string().orEmpty()
        if (code == 401) throw NotPairedException()
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (!isSuccessful) throw IOException(o?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP $code")
        return o ?: throw IOException("Not JSON")
    }

    suspend fun ping(timeoutMs: Long = 8000): PingInfo =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$baseUrl/api/v1/ping").build()
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val o = JSONObject(response.body!!.string())
                if (o.optString("service") != "Omarchy Harness Bridge") throw IOException("This address is not a SUPER DESKTOP computer")
                PingInfo(
                    hostname = o.optString("hostname", "computer"),
                    version = o.optInt("protocolVersion", 0),
                    bridgeId = o.optString("bridgeId"),
                    addresses = parseAddresses(o.optJSONArray("addresses")),
                )
            }
        }

    suspend fun harnesses(): HarnessDocument =
        withContext(Dispatchers.IO) {
            client.newCall(authed("/api/v1/harnesses").build()).execute().use { parseHarnessDocument(it.json()) }
        }

    /** The harness list stream: every message is a whole [HarnessDocument]. The caller closes it. */
    fun harnessStream(listener: WebSocketListener): WebSocket =
        client.newWebSocket(
            Request
                .Builder()
                .url("wss://" + baseUrl.substringAfter("://") + "/api/v1/harnesses/stream")
                .header("Authorization", "Bearer $token")
                .build(),
            listener,
        )

    /** What the computer can start, and its current workspace. */
    suspend fun harnessTypes(): Pair<List<HarnessType>, String> =
        withContext(Dispatchers.IO) {
            client.newCall(authed("/api/v1/harness-types").build()).execute().use { response ->
                val root = response.json()
                val types = root.optJSONArray("types")
                (0 until (types?.length() ?: 0)).mapNotNull { i ->
                    val o = types?.optJSONObject(i) ?: return@mapNotNull null
                    HarnessType(o.optString("id"), o.optString("name", o.optString("id")), o.optBoolean("available"))
                } to root.optString("workspace")
            }
        }

    /** Folders worth offering for a new harness: the current one, recent ones, then ones harnesses ran in. */
    suspend fun workspaces(): List<String> =
        withContext(Dispatchers.IO) {
            client.newCall(authed("/api/v1/workspaces").build()).execute().use { response ->
                val root = response.json()
                fun list(key: String) = root.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                (listOf(root.optString("workspace")) + list("recentDirectories") + list("usedDirectories")).filter { it.isNotBlank() }.distinct()
            }
        }

    /**
     * Harness [id]'s live pane: a frame with `tailAnsi` (its last 300 lines, colors included) whenever it changes,
     * then `status: EXITED` and a close once the session ends. With `viewport=1` a computer that can size the pane
     * says so in each frame, and takes `{"type":"viewport",…}` messages on this socket. The caller closes it.
     */
    fun terminalStream(
        id: String,
        listener: WebSocketListener,
    ): WebSocket =
        client.newWebSocket(
            Request
                .Builder()
                .url("wss://" + baseUrl.substringAfter("://") + "/api/v1/harnesses/$id/stream?ansiOnly=1&viewport=1")
                .header("Authorization", "Bearer $token")
                .build(),
            listener,
        )

    /** Types [text] into harness [id]'s terminal and, with [enter], submits it. At most 4 KiB. */
    suspend fun sendKeys(
        id: String,
        text: String,
        enter: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        require(text.toByteArray().size <= 4096) { "Prompt is too long" }
        val body = JSONObject().put("text", text).put("enter", enter).toString().toRequestBody(json)
        client.newCall(authed("/api/v1/harnesses/$id/keys").post(body).build()).execute().use { it.json() }
        Unit
    }

    /** The harness's prompts, newest first (SUPER DESKTOP 1.3.0 and later). */
    suspend fun prompts(id: String): List<PromptEntry> =
        withContext(Dispatchers.IO) {
            client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(authed("/api/v1/harnesses/$id/prompts").build()).execute().use { response ->
                if (response.code == 401) throw NotPairedException()
                val body = response.body?.string().orEmpty()
                if (response.code == 404) {
                    val gone = runCatching { JSONObject(body).optString("error") }.getOrNull() == "no_such_session"
                    throw IOException(if (gone) "This terminal is no longer running on the computer." else "Prompt history needs SUPER DESKTOP 1.3.0 or newer on the computer.")
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                parsePrompts(body)
            }
        }

    /**
     * Sends [text] with [items] (images and files) to harness [id]. [requestId] makes the one submission
     * safe from replays. A computer without the route answers the empty probe with 404, and nothing is sent.
     */
    suspend fun attachmentPrompt(
        id: String,
        text: String,
        items: List<Attachment>,
        requestId: String,
    ) = withContext(Dispatchers.IO) {
        require(items.size in 1..MAX_ATTACHMENTS) { "1 to $MAX_ATTACHMENTS attachments" }
        require(items.sumOf { it.bytes.size.toLong() } <= MAX_ATTACHMENT_BYTES) { "Attachments are limited to 16 MB per prompt" }
        val url = "/api/v1/harnesses/$id/attachment-prompt"
        val probe = client.newCall(authed(url).post("{}".toRequestBody(json)).build()).execute().use { it.code }
        if (probe == 404) throw IOException("Update SUPER DESKTOP on the computer to send files and images. Nothing was sent.")
        if (probe == 401) throw NotPairedException()
        val body = AttachmentBody(text, requestId, items)
        val timeout = minOf(190_000L, 45_000L + body.contentLength() / 125)
        client.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
            .newCall(authed(url).post(body).build()).execute().use { it.json() }
        Unit
    }

    /** Starts [agentType] in [workspace] (the computer's current one when null); returns the new harness id. */
    suspend fun createHarness(
        agentType: String,
        workspace: String?,
    ): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("agentType", agentType).apply { workspace?.let { put("workspace", it) } }.toString().toRequestBody(json)
            client.newBuilder().retryOnConnectionFailure(false).build()
                .newCall(authed("/api/v1/harnesses").post(body).build()).execute().use { it.json().getString("id") }
        }

    suspend fun closeHarness(id: String) =
        withContext(Dispatchers.IO) {
            client.newCall(authed("/api/v1/harnesses/$id").delete().build()).execute().use { it.json() }
            Unit
        }

    /** Asks the computer to let this device in; the owner approves it there. Returns the request id and the code to compare. */
    suspend fun requestPairing(
        secret: String,
        deviceName: String,
    ): Pair<String, String> =
        withContext(Dispatchers.IO) {
            if (ping().version < 3) throw PairException("Update SUPER DESKTOP on the computer for secure pairing.")
            val body = JSONObject().put("secret", secret).put("deviceType", "android").put("deviceName", deviceName).toString().toRequestBody(json)
            client.newCall(Request.Builder().url("$baseUrl/api/v1/pair").post(body).build()).execute().use { response ->
                val o = runCatching { JSONObject(response.body!!.string()) }.getOrNull()
                if (!response.isSuccessful) throw PairException(o?.optString("error")?.takeIf { it.isNotBlank() } ?: "pairing failed")
                if (o?.optString("status") != "pending" || !o.has("requestId")) {
                    throw PairException("Update SUPER DESKTOP on the computer: it must approve this device.")
                }
                o.getString("requestId") to o.optString("code")
            }
        }

    /** `pending`, `denied`, or `paired` with the token. */
    suspend fun pollPairing(requestId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("requestId", requestId).toString().toRequestBody(json)
            client.newCall(Request.Builder().url("$baseUrl/api/v1/pair/poll").post(body).build()).execute().use { response ->
                val o = runCatching { JSONObject(response.body!!.string()) }.getOrNull()
                if (!response.isSuccessful) throw PairException(o?.optString("error")?.takeIf { it.isNotBlank() } ?: "Pairing failed")
                o ?: throw PairException("Pairing failed")
            }
        }
}

fun Bridge.api(host: String = this.host) = BridgeApi(baseUrl(host), token, pin)
