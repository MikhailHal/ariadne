package io.github.ariadne

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class UnanalyzedChangesTest {

    private val root: Path = Path.of("/proj")
    private val scan = GradleProjectScanner.Result(
        moduleSourceRoots = mapOf(
            ":" to listOf(
                root.resolve("src/main/kotlin"),
                root.resolve("src/test/kotlin")
            )
        ),
        modulePathMapping = mapOf(":" to "."),
        moduleDependencies = emptyMap()
    )

    private fun diffFor(vararg paths: String): String =
        paths.joinToString("\n") { "diff --git a/$it b/$it\n--- a/$it\n+++ b/$it" }

    @Test
    fun `kt files under scanned source roots are analyzed`() {
        val diff = diffFor("src/main/kotlin/demo/Calculator.kt")

        assertEquals(emptyList(), collectUnanalyzedChanges(diff, root, scan))
    }

    @Test
    fun `non-kt files are reported as unanalyzed`() {
        val diff = diffFor("build.gradle.kts", "src/main/res/values/strings.xml")

        assertEquals(
            listOf("build.gradle.kts", "src/main/res/values/strings.xml"),
            collectUnanalyzedChanges(diff, root, scan)
        )
    }

    @Test
    fun `kt files outside scanned roots are reported as unanalyzed`() {
        val diff = diffFor("build-logic/src/main/kotlin/Convention.kt")

        assertEquals(
            listOf("build-logic/src/main/kotlin/Convention.kt"),
            collectUnanalyzedChanges(diff, root, scan)
        )
    }

    @Test
    fun `mixed diff reports only out-of-scope files`() {
        val diff = diffFor(
            "src/main/kotlin/demo/Calculator.kt",
            "build.gradle.kts",
            "src/test/kotlin/demo/CalculatorTest.kt"
        )

        assertEquals(listOf("build.gradle.kts"), collectUnanalyzedChanges(diff, root, scan))
    }

    @Test
    fun `duplicate entries are deduplicated`() {
        val diff = diffFor("build.gradle.kts", "build.gradle.kts")

        assertEquals(listOf("build.gradle.kts"), collectUnanalyzedChanges(diff, root, scan))
    }

    @Test
    fun `empty diff yields no entries`() {
        assertEquals(emptyList(), collectUnanalyzedChanges("", root, scan))
    }
}
