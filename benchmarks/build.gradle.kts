plugins {
    id("buildlogic.java-conventions")
}

// Configure JAR with manifest and custom name
tasks.named<Jar>("jar") {
    archiveBaseName.set("euhedral-benchmark")
    archiveFileName.set("euhedral-benchmark.jar")
    destinationDirectory.set(layout.buildDirectory)
    manifest {
        attributes(
            "Main-Class" to "io.euhedral_execution.benchmarks.BenchRunner",
            "Class-Path" to configurations.runtimeClasspath.get().files.joinToString(" ") { "lib/${it.name}" }
        )
    }
}

val copyRuntimeDependencies = tasks.register<Sync>("copyRuntimeDependencies") {
    dependsOn(tasks.named("jar"))

    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("lib"))

    description = "Copies the needed runtime dependencies"
}

val copyLauncherScript = tasks.register<Copy>("copyLauncherScript") {
    from("src/main/scripts/euhedral-benchmarks")
    into(layout.buildDirectory.dir("bin"))
    filePermissions {
        unix("rwxr-xr-x")
    }
    description = "Copies the automatic launch script."
}

val assembleBenchmarkDistribution = tasks.register("assembleBenchmarkDistribution") {
    dependsOn(tasks.named("jar"), copyRuntimeDependencies, copyLauncherScript)
    group = "distribution"
    description = "Assembles the complete benchmark distribution with dependencies and scripts"
}

tasks.named("assemble") {
    dependsOn(assembleBenchmarkDistribution)
}

tasks.named<ProcessResources>("processResources") {
    from(project(":euhedral-core").file("src/main/resources")) {
        include("logback-fragments/**")
    }
    from(project(":euhedral-hardware-utils").file("src/main/resources")) {
        include("logback-fragments/**")
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("AllEuhedralLogs", "ERROR")
    systemProperty("logback.configurationFile", "benchmark-logback.xml")
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
    implementation(libs.com.fasterxml.jackson.core.jackson.annotations)
    implementation(libs.com.fasterxml.jackson.core.jackson.databind)
    runtimeOnly(libs.ch.qos.logback.logback.classic)
    testImplementation(libs.org.junit.jupiter.junit.jupiter)
    annotationProcessor(libs.org.openjdk.jmh.jmh.generator.annprocess)
}

tasks.withType<JacocoReport>().configureEach {
    enabled = false
}

description = "Euhedral Benchmarks"
