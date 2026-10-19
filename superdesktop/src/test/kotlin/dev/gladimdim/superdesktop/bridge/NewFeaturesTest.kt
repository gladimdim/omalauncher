package dev.gladimdim.superdesktop.bridge

import dev.gladimdim.superdesktop.terminal.Glyphs
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewFeaturesTest {
    @Test fun wakeCardsComeWithTheDocument() {
        val doc =
            parseHarnessDocument(
                JSONObject(
                    """{"harnesses":[],"wake":{"interfaces":[
                    {"interface":"enp5s0","kind":"ethernet","mac":"AA:BB:CC:DD:EE:01","address":"192.168.50.27","broadcast":"192.168.50.255","wakeOnLan":"enabled"},
                    {"interface":"bad","mac":"00:00:00:00:00:00"}]}}""",
                ),
            )
        val card = doc.wake!!.single()
        assertEquals("aa:bb:cc:dd:ee:01", card.mac)
        assertEquals("192.168.50.255", card.broadcast)
        assertNull("an older computer sends no wake", parseHarnessDocument(JSONObject("""{"harnesses":[]}""")).wake)
    }

    @Test fun promptsSkipBlankAndNull() {
        val p = parsePrompts("""{"prompts":[{"text":"fix the build","at":"2026-10-19T08:00:00Z"},{"text":"  "},{"text":null},{"text":"again"}]}""")
        assertEquals(listOf("fix the build", "again"), p.map { it.text })
        assertEquals("2026-10-19T08:00:00Z", p[0].at)
    }

    @Test fun missingGlyphsGetSubstitutes() {
        // JVM tests have no fonts, so every symbol counts as missing.
        assertEquals("▸▸ bypass permissions", Glyphs.drawable("⏵⏵ bypass permissions"))
        assertEquals("plain text", Glyphs.drawable("plain text"))
    }

    @Test fun attachmentBodyIsTheBridgeJson() {
        val body = AttachmentBody("look", "req1", listOf(Attachment(image = false, name = "a.txt", bytes = "hi".toByteArray())))
        val out = Buffer().also { body.writeTo(it) }.readUtf8()
        assertEquals(body.contentLength(), out.length.toLong())
        val o = JSONObject(out)
        assertEquals("look", o.getString("text"))
        assertEquals("file", o.getJSONArray("attachments").getJSONObject(0).getString("kind"))
        assertEquals("aGk=", o.getJSONArray("attachments").getJSONObject(0).getString("dataBase64"))
    }
}
