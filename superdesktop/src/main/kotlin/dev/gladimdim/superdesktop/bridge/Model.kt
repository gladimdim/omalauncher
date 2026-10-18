package dev.gladimdim.superdesktop.bridge

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** One address a computer reported; `tailscale`, `lan` or `loopback`. */
@Immutable
data class BridgeAddress(
    val address: String,
    val network: String,
    val connectable: Boolean,
)

/** A paired computer: where it is, the certificate it must show, and the token it gave us. */
@Immutable
data class Bridge(
    val host: String,
    val port: Int,
    val token: String,
    val pin: String,
    val bridgeId: String,
    val name: String,
    val addresses: List<BridgeAddress> = emptyList(),
) {
    /** Stable across address changes: the certificate is the computer's identity. */
    val key: String get() = pin

    fun baseUrl(host: String = this.host) = "https://${if (':' in host && !host.startsWith("[")) "[$host]" else host}:$port"

    /** Addresses to try, the saved one first, then Tailscale, then the rest it serves. */
    fun hosts(): List<String> =
        (listOf(host) + addresses.filter { it.connectable && it.network == "tailscale" }.map { it.address } +
            addresses.filter { it.connectable && it.network != "loopback" }.map { it.address }).distinct()
}

/** One harness row of GET /api/v1/harnesses (PROTOCOL.md). Missing fields are null: newer bridges may add more. */
@Immutable
data class Harness(
    val id: String,
    val agentType: String,
    val agentName: String,
    /** IDLE, WORKING, WAITING, ERROR, UNKNOWN, FINISHED or EXITED. */
    val status: String,
    val model: String?,
    val effort: String?,
    val sessionTitle: String?,
    val lastPrompt: String?,
    /** The last lines of the terminal, plain text. */
    val preview: String?,
    val directory: String?,
    val directoryDisplay: String?,
    val tagColor: String?,
)

/** A kind of harness the computer can start; [available] when its CLI is installed there. */
@Immutable
data class HarnessType(
    val id: String,
    val name: String,
    val available: Boolean,
)

/** One limit of a provider's plan; [used] is 0..1, null when unknown. */
@Immutable
data class UsageLimit(
    val label: String,
    val used: Double?,
)

@Immutable
data class Usage(
    val id: String,
    val name: String,
    val ready: Boolean,
    val limits: List<UsageLimit>,
)

/** A whole GET /api/v1/harnesses document, also every message of its stream. */
@Immutable
data class HarnessDocument(
    val harnesses: List<Harness>,
    val usage: List<Usage>,
)

class NotPairedException : IOException("not paired")

class PairException(
    message: String,
) : IOException(message)

/** JSON null is absence, not the text "null". */
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun parseAddresses(values: JSONArray?): List<BridgeAddress> =
    (0 until (values?.length() ?: 0)).mapNotNull { i ->
        val o = values?.optJSONObject(i) ?: return@mapNotNull null
        val address = o.text("address") ?: return@mapNotNull null
        BridgeAddress(address, o.optString("kind", "lan"), o.optBoolean("connectable", false))
    }

internal fun parseHarnessDocument(root: JSONObject): HarnessDocument {
    val rows = root.optJSONArray("harnesses")
    val harnesses =
        (0 until (rows?.length() ?: 0)).mapNotNull { i ->
            val o = rows?.optJSONObject(i) ?: return@mapNotNull null
            Harness(
                id = o.optString("id"),
                agentType = o.optString("agentType"),
                agentName = o.text("agentName") ?: o.optString("agentType"),
                status = o.optString("status", "UNKNOWN"),
                model = o.text("model"),
                effort = o.text("effort"),
                sessionTitle = o.text("sessionTitle"),
                lastPrompt = o.text("lastPrompt"),
                preview = o.text("preview"),
                directory = o.text("directory"),
                directoryDisplay = o.text("directoryDisplay"),
                tagColor = o.text("tagColor"),
            )
        }
    val providers = root.optJSONArray("usage")
    val usage =
        (0 until (providers?.length() ?: 0)).mapNotNull { i ->
            val p = providers?.optJSONObject(i) ?: return@mapNotNull null
            val id = p.text("id") ?: return@mapNotNull null
            val limits = p.optJSONArray("limits")
            Usage(
                id = id,
                name = p.optString("name", id),
                ready = p.optBoolean("ready", false),
                limits =
                    (0 until (limits?.length() ?: 0)).mapNotNull { j ->
                        val l = limits?.optJSONObject(j) ?: return@mapNotNull null
                        val used = l.optDouble("percentUsed", Double.NaN)
                        UsageLimit(l.optString("label", "Limit"), used.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0))
                    },
            )
        }
    return HarnessDocument(harnesses, usage)
}
