package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Fork: fails right after an upstream rebase that lost the SuperDesktop element. */
class SdHookTest {
    private val page = File("src/main/assets/widgets/90-superdesktop.owl").readText()

    @Test fun pageCompiles() {
        try {
            Compiler.compile(page)
        } catch (e: CompileError) {
            fail("the SuperDesktop element is gone from Schema.kt KINDS: ${e.message}")
        }
    }

    @Test fun elementTakesOnlyItsId() {
        val err = runCatching { Compiler.compile(page.replace("{ id: \"home\" }", "{ id: \"home\"; color: accent }")) }.exceptionOrNull()
        assertTrue("SuperDesktop should reject properties it doesn't draw", err is CompileError)
    }
}
