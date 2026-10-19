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

    @Test fun newPagesCompile() {
        val dir = kotlin.io.path.createTempDirectory("sd").toFile()
        val id = dev.gladimdim.superdesktop.SdPages.newPage(dir)
        val file = File(dir, "superdesktop-$id.owl")
        try {
            Compiler.compile(file.readText())
        } catch (e: CompileError) {
            fail("a new SUPER DESKTOP page doesn't compile: ${e.message}\n${file.readText()}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun terminalWidgetsCompile() {
        val dir = kotlin.io.path.createTempDirectory("sd").toFile()
        try {
            val id = dev.gladimdim.superdesktop.SdPages.newId()
            dev.gladimdim.superdesktop.SdPages.newTerminalWidget(android.app.Application(), dir, id = id)
            Compiler.compile(File(dir, "superdesktop-terminal-$id.owl").readText())
        } catch (e: CompileError) {
            fail("a SUPER DESKTOP terminal widget doesn't compile: ${e.message}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun facesCompile() {
        val dir = kotlin.io.path.createTempDirectory("sd").toFile()
        try {
            val id = dev.gladimdim.superdesktop.SdPages.newFace(dir)
            Compiler.compile(File(dir, "superdesktop-face-$id.owl").readText())
        } catch (e: CompileError) {
            fail("a SUPER DESKTOP face doesn't compile: ${e.message}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun usageWidgetsCompileAndHaveIndependentSettings() {
        val dir = kotlin.io.path.createTempDirectory("sd-usage").toFile()
        try {
            val first = dev.gladimdim.superdesktop.SdWidgets.addUsage(dir, page = 3)
            val second = dev.gladimdim.superdesktop.SdWidgets.addUsage(dir, page = 1)
            assertTrue(first != second)
            for (id in listOf(first, second)) {
                val source = File(dir, "superdesktop-usage-$id.owl").readText()
                Compiler.compile(source)
                assertTrue(source.contains("kind: \"usage\""))
                assertTrue(source.contains("id: \"$id\""))
                assertTrue(source.contains("minSize: 3x2"))
            }
            assertTrue(File(dir, "superdesktop-usage-$first.owl").readText().contains("page: 3"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun elementTakesOnlyItsId() {
        val err = runCatching { Compiler.compile(page.replace("{ id: \"home\" }", "{ id: \"home\"; color: accent }")) }.exceptionOrNull()
        assertTrue("SuperDesktop should reject properties it doesn't draw", err is CompileError)
    }
}
