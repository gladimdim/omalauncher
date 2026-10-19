package dev.gladimdim.superdesktop.bridge

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID

// Ported from the SUPER DESKTOP app's PromptAttachment.kt (see SOURCE.md).

const val MAX_ATTACHMENTS = 4

/** Bytes of all attachments of one prompt together, the bridge's own limit. */
const val MAX_ATTACHMENT_BYTES = 16 * 1024 * 1024

/** An image (a bounded JPEG the harness attaches natively where it can) or a file (sent byte for byte; the harness gets its path). */
class Attachment(
    val image: Boolean,
    val name: String,
    val bytes: ByteArray,
)

fun newRequestId() = UUID.randomUUID().toString().replace("-", "")

/** The attachment-prompt JSON, streamed: the files are base64-encoded as they're written, never held twice. */
internal class AttachmentBody(
    text: String,
    requestId: String,
    private val items: List<Attachment>,
) : RequestBody() {
    private val head = "{\"requestId\":${JSONObject.quote(requestId)},\"text\":${JSONObject.quote(text)},\"attachments\":[".toByteArray()
    private val starts =
        items.mapIndexed { i, a ->
            "${if (i == 0) "" else ","}{\"kind\":\"${if (a.image) "image" else "file"}\",\"name\":${JSONObject.quote(a.name)},\"dataBase64\":\"".toByteArray()
        }
    private val end = "\"}".toByteArray()
    private val tail = "]}".toByteArray()

    override fun contentType(): MediaType = "application/json".toMediaType()

    override fun contentLength(): Long = head.size + tail.size + items.indices.sumOf { starts[it].size + (items[it].bytes.size + 2L) / 3 * 4 + end.size }

    override fun writeTo(sink: BufferedSink) {
        sink.write(head)
        items.forEachIndexed { i, a ->
            sink.write(starts[i])
            // Closing the encoder writes its last quantum; the sink stays open.
            java.util.Base64.getEncoder().wrap(
                object : FilterOutputStream(sink.outputStream()) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

                    override fun close() = flush()
                } as OutputStream,
            ).use { it.write(a.bytes) }
            sink.write(end)
        }
        sink.write(tail)
    }
}
