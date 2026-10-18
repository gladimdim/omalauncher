package dev.gladimdim.superdesktop.bridge

import org.json.JSONObject

/** A computer's pairing invitation: where it is, its certificate, and a single-use secret. Approval still happens on the computer. */
data class Invitation(
    val host: String,
    val port: Int,
    val pin: String,
    val secret: String,
) {
    companion object {
        /** From a `superdesktop://pair?data=…` link or the invitation's JSON; throws IllegalArgumentException with a readable message. */
        fun parse(text: String): Invitation {
            val o = JSONObject(decodePairingInvitation(text))
            require(o.optInt("v") >= 3) { "Update SUPER DESKTOP on the computer for secure pairing." }
            val host = o.optString("host").trim()
            val port = o.optInt("port", 8759)
            val pin = o.optString("fingerprint").lowercase()
            val secret = o.optString("secret")
            require(host.isNotEmpty() && port in 1..65535) { "The invitation has no address." }
            require(BridgeTls.isPin(pin)) { "The invitation has no valid certificate fingerprint." }
            require(secret.isNotEmpty()) { "The invitation has no secret." }
            return Invitation(host, port, pin, secret)
        }
    }
}

// Ported from the SUPER DESKTOP app's PairingLink.kt (see SOURCE.md).

/** The invitation JSON inside a pasted link or JSON text, tolerating Markdown fences and invisible characters. */
internal fun decodePairingInvitation(value: String): String {
    require(value.length <= 8192) { "Invitation too large" }
    val cleaned = value.filterNot { it == '﻿' || it == '​' || it == '⁠' }.trim()
    val trimmed =
        if (cleaned.startsWith("```")) {
            require(cleaned.endsWith("```") && cleaned.contains('\n')) { "Copy the complete invitation" }
            cleaned.substringAfter('\n').removeSuffix("```").trim()
        } else {
            cleaned
        }
    require(trimmed.isNotEmpty()) { "Paste the complete invitation, not the verification digits" }
    if (trimmed.startsWith("{")) {
        require(trimmed.length <= 4096)
        return trimmed
    }
    // A wrapped link has no meaningful whitespace in its encoded payload.
    val uri =
        try {
            java.net.URI(trimmed.filterNot { it.isWhitespace() })
        } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("Paste the JSON invitation or the superdesktop:// pairing link")
        }
    require(uri.scheme == "superdesktop" && uri.rawAuthority == "pair" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null) {
        "Expected a JSON invitation or a superdesktop://pair link"
    }
    val query = requireNotNull(uri.rawQuery) { "The link has no invitation" }
    require(query.startsWith("data=") && !query.contains('&')) { "The link has no invitation" }
    val encoded = query.removePrefix("data=")
    require(encoded.matches(Regex("[A-Za-z0-9_-]+"))) { "The link's invitation is damaged" }
    val bytes = java.util.Base64.getUrlDecoder().decode(encoded)
    require(bytes.size <= 4096)
    return bytes.toString(Charsets.UTF_8)
}
