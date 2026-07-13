package io.github.ariadne

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleProjectScannerTest {

    private val root: Path = createTempDirectory("scanner-test")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun file(relative: String, content: String = "") {
        val path = root.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content)
    }

    private fun dir(relative: String) {
        root.resolve(relative).createDirectories()
    }

    @Test
    fun `multi-module kts settings is discovered with source roots and path mapping`() {
        file("settings.gradle.kts", """
            rootProject.name = "sample"
            include(":core", ":app")
        """.trimIndent())
        dir("core/src/main/kotlin")
        dir("core/src/test/kotlin")
        dir("app/src/main/kotlin")

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":core", ":app"), result.moduleSourceRoots.keys)
        assertEquals(
            listOf(root.resolve("core/src/main/kotlin"), root.resolve("core/src/test/kotlin")),
            result.moduleSourceRoots[":core"]
        )
        assertEquals("core", result.modulePathMapping[":core"])
        assertEquals("app", result.modulePathMapping[":app"])
    }

    @Test
    fun `nested module names map to nested directories`() {
        file("settings.gradle.kts", """include(":libs:common")""")
        dir("libs/common/src/main/kotlin")

        val result = GradleProjectScanner.scan(root)

        assertEquals("libs/common", result.modulePathMapping[":libs:common"])
    }

    @Test
    fun `groovy settings without parentheses is parsed`() {
        file("settings.gradle", "include ':core', ':app'")
        dir("core/src/main/kotlin")
        dir("app/src/main/kotlin")

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":core", ":app"), result.moduleSourceRoots.keys)
    }

    @Test
    fun `includeBuild is not treated as a module include`() {
        file("settings.gradle.kts", """
            includeBuild("sazanami")
            include(":core")
        """.trimIndent())
        dir("core/src/main/kotlin")
        dir("sazanami/src/main/kotlin")

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":core"), result.moduleSourceRoots.keys)
    }

    @Test
    fun `modules without source roots are dropped`() {
        file("settings.gradle.kts", """include(":core", ":docs")""")
        dir("core/src/main/kotlin")
        dir("docs")

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":core"), result.moduleSourceRoots.keys)
    }

    @Test
    fun `root project with sources becomes the root module`() {
        file("settings.gradle.kts", """include(":core")""")
        dir("src/main/kotlin")
        dir("core/src/main/kotlin")

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":", ":core"), result.moduleSourceRoots.keys)
        assertEquals(".", result.modulePathMapping[":"])
    }

    @Test
    fun `project dependencies are extracted from build files`() {
        file("settings.gradle.kts", """include(":core", ":app")""")
        dir("core/src/main/kotlin")
        dir("app/src/main/kotlin")
        file("app/build.gradle.kts", """
            dependencies {
                implementation(project(":core"))
            }
        """.trimIndent())

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":core"), result.moduleDependencies[":app"])
        assertEquals(emptySet(), result.moduleDependencies[":core"])
    }

    @Test
    fun `type-safe project accessors resolve against known modules`() {
        file("settings.gradle.kts", """include(":core-api", ":app")""")
        dir("core-api/src/main/kotlin")
        dir("app/src/main/kotlin")
        file("app/build.gradle.kts", """
            dependencies {
                implementation(projects.coreApi)
            }
        """.trimIndent())

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":core-api"), result.moduleDependencies[":app"])
    }

    @Test
    fun `dependencies include the transitive closure`() {
        file("settings.gradle.kts", """include(":common", ":core", ":app")""")
        dir("common/src/main/kotlin")
        dir("core/src/main/kotlin")
        dir("app/src/main/kotlin")
        file("core/build.gradle.kts", """implementation(project(":common"))""")
        file("app/build.gradle.kts", """implementation(project(":core"))""")

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":core", ":common"), result.moduleDependencies[":app"])
    }

    @Test
    fun `single-module project without settings file uses root sources`() {
        dir("src/main/kotlin")
        dir("src/test/kotlin")

        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":"), result.moduleSourceRoots.keys)
        assertEquals(
            listOf(root.resolve("src/main/kotlin"), root.resolve("src/test/kotlin")),
            result.moduleSourceRoots[":"]
        )
    }

    @Test
    fun `empty directory falls back to conventional single-module layout`() {
        val result = GradleProjectScanner.scan(root)

        assertEquals(setOf(":"), result.moduleSourceRoots.keys)
        assertEquals(mapOf(":" to "."), result.modulePathMapping)
        assertTrue(result.moduleDependencies.isEmpty())
    }
}
