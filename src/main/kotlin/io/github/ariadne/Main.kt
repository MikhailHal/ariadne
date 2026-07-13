package io.github.ariadne

import io.github.mikhailhal.sazanami.Sazanami
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path

private const val DEFAULT_BASE_BRANCH = "origin/main"

fun main(): Unit = runBlocking {
    val server = Server(
        serverInfo = Implementation(name = "ariadne", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    ) {
        addTool(
            name = "get_affected_tests",
            description = """
                Get tests affected by code changes.
                Automatically runs git diff and analyzes which tests need to be run.
                Returns a list of test FQNs (fully qualified names).
            """.trimIndent()
        ) { request ->
            val projectPath = request.params.arguments?.get("project_path")?.jsonPrimitive?.content
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent(text = "Error: project_path is required"))
                )

            val baseBranch = request.params.arguments?.get("base_branch")?.jsonPrimitive?.content
                ?: resolveBaseBranch()

            try {
                val projectDir = File(projectPath)
                if (!projectDir.exists()) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent(text = "Error: project_path does not exist: $projectPath"))
                    )
                }

                val diff = getGitDiff(projectDir, baseBranch)
                if (diff.isEmpty()) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent(text = "No changes detected against $baseBranch"))
                    )
                }

                val affectedTests = findAffectedTests(projectPath, diff)
                if (affectedTests.isEmpty()) {
                    CallToolResult(
                        content = listOf(TextContent(text = "No affected tests detected"))
                    )
                } else {
                    CallToolResult(
                        content = listOf(TextContent(text = affectedTests.joinToString("\n")))
                    )
                }
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: ${e.message}"))
                )
            }
        }
    }

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered()
    )
    server.createSession(transport)
}

private fun resolveBaseBranch(): String = DEFAULT_BASE_BRANCH

/**
 * git diff を実行して差分を取得
 */
private fun getGitDiff(projectDir: File, baseBranch: String): String {
    return try {
        val process = ProcessBuilder("git", "diff", "--unified=0", baseBranch)
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode == 0) output else ""
    } catch (e: Exception) {
        ""
    }
}

private fun findAffectedTests(projectPath: String, diff: String): Set<String> {
    val path = Path.of(projectPath)

    // シンプルなシングルモジュール構成を仮定
    val moduleSourceRoots = mapOf(
        ":" to listOf(
            path.resolve("src/main/kotlin"),
            path.resolve("src/test/kotlin")
        )
    )
    val modulePathMapping = mapOf(":" to ".")

    return Sazanami.findAffectedTests(
        diff = diff,
        moduleSourceRoots = moduleSourceRoots,
        modulePathMapping = modulePathMapping,
        projectRoot = path
    )
}
