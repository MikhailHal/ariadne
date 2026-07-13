package io.github.ariadne

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * Gradle プロジェクトを規約ベースで走査し、sazanami に渡すモジュール情報を組み立てる。
 *
 * 探索方法:
 * 1. settings.gradle(.kts) の include(...) からモジュール一覧を取得
 * 2. モジュール名をディレクトリに変換し (`:a:b` → `a/b`)、標準レイアウトのソースルートを検出
 * 3. 各モジュールの build.gradle(.kts) から project(":x") / projects.x 依存を抽出し、推移閉包を取る
 *
 * 非対応ケース (動的include、convention plugin注入の依存など) は
 * https://github.com/MikhailHal/ariadne/issues/1 を参照。
 */
object GradleProjectScanner {

    data class Result(
        val moduleSourceRoots: Map<String, List<Path>>,
        val modulePathMapping: Map<String, String>,
        val moduleDependencies: Map<String, Set<String>>
    )

    private val SOURCE_ROOT_CANDIDATES = listOf(
        "src/main/kotlin",
        "src/test/kotlin",
        "src/main/java",
        "src/test/java"
    )

    // includeBuild にはマッチしないよう \b で区切る
    private val INCLUDE_STATEMENT = Regex("""\binclude\b[^\n]*""")
    private val QUOTED_STRING = Regex("""["']([^"']+)["']""")
    private val PROJECT_DEPENDENCY = Regex("""\bproject\s*\(\s*(?:path\s*=\s*)?["'](:[^"']+)["']""")
    private val TYPE_SAFE_ACCESSOR = Regex("""\bprojects\.([A-Za-z0-9_.]+)""")

    fun scan(projectRoot: Path): Result {
        val moduleNames = parseIncludedModules(projectRoot)

        val sourceRoots = mutableMapOf<String, List<Path>>()
        val pathMapping = mutableMapOf<String, String>()

        // ルートプロジェクト自体がソースを持つ場合はモジュールとして扱う
        val rootSourceRoots = findSourceRoots(projectRoot)
        if (rootSourceRoots.isNotEmpty()) {
            sourceRoots[":"] = rootSourceRoots
            pathMapping[":"] = "."
        }

        for (name in moduleNames) {
            val relativeDir = name.trimStart(':').replace(':', '/')
            val moduleDir = projectRoot.resolve(relativeDir)
            val roots = findSourceRoots(moduleDir)
            if (roots.isNotEmpty()) {
                sourceRoots[name] = roots
                pathMapping[name] = relativeDir
            }
        }

        // モジュールが1つも見つからない場合は従来のシングルモジュール仮定にフォールバック
        if (sourceRoots.isEmpty()) {
            return Result(
                moduleSourceRoots = mapOf(
                    ":" to listOf(
                        projectRoot.resolve("src/main/kotlin"),
                        projectRoot.resolve("src/test/kotlin")
                    )
                ),
                modulePathMapping = mapOf(":" to "."),
                moduleDependencies = emptyMap()
            )
        }

        val directDependencies = parseDependencies(projectRoot, sourceRoots.keys)

        return Result(
            moduleSourceRoots = sourceRoots,
            modulePathMapping = pathMapping,
            // api 経由の推移的公開に備えて閉包を渡す (余分なエッジは解析上無害)
            moduleDependencies = transitiveClosure(directDependencies)
        )
    }

    private fun parseIncludedModules(projectRoot: Path): List<String> {
        val settingsFile = listOf("settings.gradle.kts", "settings.gradle")
            .map { projectRoot.resolve(it) }
            .firstOrNull { it.exists() }
            ?: return emptyList()

        return INCLUDE_STATEMENT.findAll(settingsFile.readText())
            .flatMap { statement -> QUOTED_STRING.findAll(statement.value) }
            .map { it.groupValues[1] }
            .map { if (it.startsWith(":")) it else ":$it" }
            .distinct()
            .toList()
    }

    private fun findSourceRoots(moduleDir: Path): List<Path> =
        SOURCE_ROOT_CANDIDATES
            .map { moduleDir.resolve(it) }
            .filter { it.isDirectory() }

    private fun parseDependencies(projectRoot: Path, moduleNames: Set<String>): Map<String, Set<String>> {
        // 型安全アクセサの逆引き表: ":core-api" → "core.api" ではなく "coreApi"
        val accessorToModule = moduleNames.associateBy { toTypeSafeAccessor(it) }

        return moduleNames.associateWith { name ->
            val moduleDir = if (name == ":") projectRoot else projectRoot.resolve(name.trimStart(':').replace(':', '/'))
            val buildFile = listOf("build.gradle.kts", "build.gradle")
                .map { moduleDir.resolve(it) }
                .firstOrNull { it.exists() }
                ?: return@associateWith emptySet()

            val text = buildFile.readText()

            val explicit = PROJECT_DEPENDENCY.findAll(text).map { it.groupValues[1] }
            val typeSafe = TYPE_SAFE_ACCESSOR.findAll(text).mapNotNull { accessorToModule[it.groupValues[1]] }

            (explicit + typeSafe)
                .filter { it in moduleNames && it != name }
                .toSet()
        }
    }

    /**
     * `:core-api` → `coreApi`、`:a:b-c` → `a.bC` (Gradle の型安全アクセサ表記)
     */
    private fun toTypeSafeAccessor(moduleName: String): String =
        moduleName.trimStart(':').split(':').joinToString(".") { segment ->
            segment.split('-', '_')
                .filter { it.isNotEmpty() }
                .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercaseChar() } }
                .joinToString("")
        }

    private fun transitiveClosure(direct: Map<String, Set<String>>): Map<String, Set<String>> =
        direct.mapValues { (name, _) ->
            val reachable = mutableSetOf<String>()
            val queue = ArrayDeque(direct[name] ?: emptySet())
            while (queue.isNotEmpty()) {
                val dep = queue.removeFirst()
                if (reachable.add(dep)) {
                    queue.addAll(direct[dep] ?: emptySet())
                }
            }
            reachable
        }
}
