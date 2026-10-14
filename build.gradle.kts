plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "by.ciszkin.draftsllman"
version = "1.0-SNAPSHOT"

application {
    // Runs on the JVM 21 toolchain (kasuari-kotlin ships Java-21 bytecode),
    // so `./gradlew run` is immune to whatever java is on PATH.
    mainClass.set("by.ciszkin.draftsllman.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Constraint solver (linear Cassowary). Ships Java-21 bytecode -> needs JVM 21.
    implementation("io.github.kotlinmania:kasuari-kotlin:0.1.6")
    // Serialization + JSON-schema generation/validation (tool contract).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-schema-generator-json:0.6.0")
    implementation("io.github.optimumcode:json-schema-validator:0.5.5")
    // Rendering: Skia via Skiko (direct-canvas), headless Linux runtime.
    implementation("org.jetbrains.skiko:skiko-awt:0.9.18")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.9.18")
    // Agent orchestration (Phase 2 Slice B): Koog + coroutines.
    implementation("ai.koog:koog-agents:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    // Test-only oracle: Apache Batik proves export == raster equivalence.
    testImplementation("org.apache.xmlgraphics:batik-transcoder:1.19")
    testImplementation("org.apache.xmlgraphics:batik-codec:1.19")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djava.awt.headless=true")
}
