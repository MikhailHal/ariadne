plugins {
    kotlin("jvm") version "2.3.0"
    application
    id("com.gradleup.shadow") version "9.2.2"
}

group = "io.github.ariadne"
version = "0.3.1"

kotlin {
    jvmToolchain(21)
}

// MCPクライアントへ通知するバージョンをビルド定義と一致させる
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildInfo")
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("io/github/ariadne/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.github.ariadne

            internal object BuildInfo {
                const val VERSION: String = "$versionValue"
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generateBuildInfo)
}

repositories {
    mavenCentral()
    maven("https://redirector.kotlinlang.org/maven/intellij-dependencies")
}

dependencies {
    // sazanami core - コンパイル時は推移的依存を除外
    compileOnly("io.github.mikhailhal:core") { isTransitive = false }
    runtimeOnly("io.github.mikhailhal:core")

    // MCP SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.ariadne.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    // Analysis API (IntelliJ platform) はサービスローダ登録に依存するため、
    // META-INF/services をマージしないと standalone セッションが壊れる
    mergeServiceFiles()
    archiveClassifier.set("all")
}
