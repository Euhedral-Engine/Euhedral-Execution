plugins {
    id("buildlogic.java-conventions")
}

dependencies {
    api(project(":euhedral-core"))
    api(project(":euhedral-data-structures"))
    api(project(":euhedral-hashing"))
    api(libs.org.springframework.cloud.spring.cloud.starter.config)
    api(libs.org.springframework.cloud.spring.cloud.stream.binder.kafka)
    api(libs.org.springframework.grpc.spring.grpc.spring.boot.starter)
    api(libs.com.google.guava.guava)
    api(libs.org.apache.commons.commons.lang3)
    api(libs.com.google.protobuf.protobuf.java)
    api(libs.io.grpc.grpc.api)
    api(libs.io.grpc.grpc.protobuf)
    api(libs.io.grpc.grpc.stub)
    api(project(":euhedral-reactor-core"))
    api(libs.io.micrometer.micrometer.core)
    api(libs.it.unimi.dsi.fastutil)
    api(libs.jakarta.annotation.jakarta.annotation.api)
    api(libs.org.apache.kafka.kafka.clients)
    api(libs.org.jspecify.jspecify)
    api(libs.org.reactivestreams.reactive.streams)
    api(libs.org.slf4j.slf4j.api)
    api(libs.io.projectreactor.reactor.core)
    testImplementation(libs.org.springframework.boot.spring.boot.starter.test) {
        exclude(mapOf("group" to "com.vaadin.external.google", "module" to "android-json"))
    }
    compileOnly(libs.org.projectlombok.lombok)
    annotationProcessor(libs.org.projectlombok.lombok)
}

tasks.named<Test>("test") {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
}

description = "euhedral-spring-core"
