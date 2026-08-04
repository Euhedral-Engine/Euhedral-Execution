plugins {
    id("buildlogic.java-conventions")
}

dependencies {
    api(project(":euhedral-core"))
    api(project(":euhedral-data-structures"))
    api(project(":euhedral-hardware-utils"))
    api(project(":euhedral-hashing"))
    api(project(":euhedral-reactor-core"))
    api(libs.io.projectreactor.reactor.core)
    api(libs.org.jctools.jctools.core.jdk11)
    api(libs.org.openjdk.jmh.jmh.core)
    api(libs.org.hdrhistogram.hdrhistogram)
    api(libs.org.jspecify.jspecify)
    api(libs.org.slf4j.slf4j.api)
    runtimeOnly(libs.org.slf4j.slf4j.simple)
    annotationProcessor(libs.org.openjdk.jmh.jmh.generator.annprocess)
}

tasks.named<Test>("test") {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
}

description = "Euhedral Benchmarks"
