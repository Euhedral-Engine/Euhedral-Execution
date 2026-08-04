plugins {
    id("buildlogic.java-conventions")
}

dependencies {
    api(project(":euhedral-core"))
    api(project(":euhedral-data-structures"))
    api(project(":euhedral-hashing"))
    api(libs.io.projectreactor.reactor.core)
    api(libs.io.micrometer.micrometer.core)
    api(libs.org.jspecify.jspecify)
    api(libs.org.reactivestreams.reactive.streams)
    api(libs.org.slf4j.slf4j.api)
    testImplementation(libs.org.junit.jupiter.junit.jupiter)
    testImplementation(libs.io.projectreactor.reactor.test)
    compileOnly(libs.org.projectlombok.lombok)
    annotationProcessor(libs.org.projectlombok.lombok)
}

tasks.named<Test>("test") {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
}

description = "Euhedral Reactor Core"
