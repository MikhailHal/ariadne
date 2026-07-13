plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "io.github.ariadne"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
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
