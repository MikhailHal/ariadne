package io.github.ariadne

import io.github.mikhailhal.sazanami.Sazanami
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

private const val DEFAULT_BASE_BRANCH = "origin/main"

/** 解析のタイムアウト。超過時はエラーを返し、MCPクライアントを待たせ続けない */
private const val ANALYSIS_TIMEOUT_MS = 120_000L

fun main(): Unit = runBlocking {
    // stdout は JSON-RPC 専用チャンネルなので退避し、
    // ライブラリの標準出力への書き込み (kotlin-logging の診断メッセージ等) は stderr に流す
    val protocolOutput = System.out
    System.setOut(System.err)

    val server = Server(
        serverInfo = Implementation(name = "ariadne", version = BuildInfo.VERSION),
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
                Automatically runs git diff (including uncommitted changes) and analyzes
                which tests need to be run. Returns a sorted list of test FQNs
                (fully qualified names). If the diff contains changes outside the analyzed
                Kotlin sources (build scripts, resources), a note is appended — consider
                running the full test suite for those changes.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("project_path") {
                        put("type", "string")
                        put("description", "Absolute path to the Kotlin project root directory")
                    }
                    putJsonObject("base_branch") {
                        put("type", "string")
                        put("description", "Git branch to compare against (default: origin/main)")
                    }
                },
                required = listOf("project_path")
            )
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
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent(text = "Error: git diff against '$baseBranch' failed. Check that the ref exists and the directory is a git repository."))
                    )
                if (diff.isEmpty()) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent(text = "No changes detected against $baseBranch"))
                    )
                }

                val path = Path.of(projectPath)
                val scan = GradleProjectScanner.scan(path)

                // Analysis API はブロッキング処理のため、タイムアウト時に割り込みで打ち切る
                val affectedTests = withTimeout(ANALYSIS_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.IO) { findAffectedTests(path, diff, scan) }
                }

                val body = if (affectedTests.isEmpty()) {
                    "No affected tests detected"
                } else {
                    affectedTests.sorted().joinToString("\n")
                }

                // 解析対象外の変更 (ビルドスクリプト・リソース・走査外ソース) を検出した場合、
                // 「影響なし」が偽の安心にならないよう明示的に伝える
                val unanalyzed = collectUnanalyzedChanges(diff, path, scan)
                val text = if (unanalyzed.isEmpty()) {
                    body
                } else {
                    val listed = unanalyzed.take(5).joinToString("\n") { "  - $it" }
                    val more = if (unanalyzed.size > 5) "\n  ... and ${unanalyzed.size - 5} more" else ""
                    "$body\n\nNote: ${unanalyzed.size} changed file(s) are outside the analyzed Kotlin sources and were NOT considered:\n$listed$more\nRun the full test suite if these changes can affect behavior."
                }

                CallToolResult(content = listOf(TextContent(text = text)))
            } catch (e: TimeoutCancellationException) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: analysis timed out after ${ANALYSIS_TIMEOUT_MS / 1000}s. The project may be too large; consider running the full test suite."))
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: ${e.message}"))
                )
            }
        }
    }

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = protocolOutput.asSink().buffered()
    )
    val session = server.createSession(transport)

    // createSession はブロックしないため、セッションが閉じるまで main を待機させる
    val done = CompletableDeferred<Unit>()
    session.onClose { done.complete(Unit) }
    done.await()

    // Analysis API (IntelliJ platform) が非デーモンスレッドを残すため、
    // main が戻るだけでは JVM が終了しない。明示的にプロセスを落とす
    exitProcess(0)
}

private fun resolveBaseBranch(): String = DEFAULT_BASE_BRANCH

/**
 * git diff を実行して差分を取得
 *
 * @return diff文字列。git が失敗した場合 (refが存在しない等) は null
 */
private fun getGitDiff(projectDir: File, baseBranch: String): String? {
    return try {
        val process = ProcessBuilder("git", "diff", "--unified=0", baseBranch)
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode == 0) output else null
    } catch (e: Exception) {
        null
    }
}

private fun findAffectedTests(path: Path, diff: String, scan: GradleProjectScanner.Result): Set<String> {
    return Sazanami.findAffectedTests(
        diff = diff,
        moduleSourceRoots = scan.moduleSourceRoots,
        modulePathMapping = scan.modulePathMapping,
        projectRoot = path,
        moduleDependencies = scan.moduleDependencies
    )
}

internal val DIFF_FILE_PATTERN = Regex("""^diff --git a/\S+ b/(\S+)$""", RegexOption.MULTILINE)

/**
 * diff に含まれる変更のうち、解析対象 (走査済みソースルート配下の .kt) に
 * 該当しないファイルを列挙する。
 *
 * ビルドスクリプトやリソース、走査外ソースセットの変更はテストに影響しうるが
 * 解析では追跡できないため、結果に注記して偽陰性の静かな見逃しを防ぐ。
 */
internal fun collectUnanalyzedChanges(
    diff: String,
    projectRoot: Path,
    scan: GradleProjectScanner.Result
): List<String> {
    val roots = scan.moduleSourceRoots.values.flatten().map { it.normalize() }
    return DIFF_FILE_PATTERN.findAll(diff)
        .map { it.groupValues[1] }
        .distinct()
        .filter { relativePath ->
            val absolute = projectRoot.resolve(relativePath).normalize()
            !relativePath.endsWith(".kt") || roots.none { root -> absolute.startsWith(root) }
        }
        .toList()
}
