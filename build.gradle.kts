plugins {
    kotlin("jvm") version "2.1.20"
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
    // sazanami core
    implementation("io.github.mikhailhal:core")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.ariadne.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
