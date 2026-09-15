plugins {
    id("buildlogic.java-conventions")
    application
}

description = "Three-dimensional CFD configuration and simulation application"

application {
    applicationName = "euhedral-cfd"
    mainClass.set("io.euhedral_execution.benchmarks.cfd.CfdMain")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("euhedral-cfd")
    archiveFileName.set("euhedral-cfd.jar")
    destinationDirectory.set(layout.buildDirectory)
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Cfd-Source-Revision" to providers.exec {
                commandLine("git", "rev-parse", "HEAD")
            }.standardOutput.asText.get().trim(),
            "Class-Path" to configurations.runtimeClasspath.get().files.joinToString(" ") { "lib/${it.name}" }
        )
    }
}

val copyRuntimeDependencies = tasks.register<Sync>("copyRuntimeDependencies") {
    dependsOn(tasks.named("jar"))
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("lib"))
    description = "Copies CFD runtime dependencies into the build distribution."
}

val copyLauncherScript = tasks.register<Copy>("copyLauncherScript") {
    from("src/main/scripts")
    into(layout.buildDirectory.dir("bin"))
    filePermissions {
        unix("rwxr-xr-x")
    }
    description = "Copies the CFD launcher into the build distribution."
}

val assembleCfdDistribution = tasks.register("assembleCfdDistribution") {
    dependsOn(tasks.named("jar"), copyRuntimeDependencies, copyLauncherScript)
    group = "distribution"
    description = "Assembles the CFD JAR, runtime dependencies, and launcher."
}

tasks.named("assemble") {
    dependsOn(assembleCfdDistribution)
}

dependencies {
    implementation(project(":euhedral-core"))
    implementation(libs.org.openjdk.jmh.jmh.core)
    annotationProcessor(libs.org.openjdk.jmh.jmh.generator.annprocess)
    implementation(libs.com.fasterxml.jackson.core.jackson.databind)
    runtimeOnly(libs.org.slf4j.slf4j.simple)
    testImplementation(libs.org.junit.jupiter.junit.jupiter)
}

// This standalone benchmark application is distributed locally, never published as a library.
publishing.publications.clear()
tasks.withType<AbstractPublishToMaven>().configureEach { enabled = false }
tasks.withType<GenerateMavenPom>().configureEach { enabled = false }
tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }
tasks.withType<Sign>().configureEach { enabled = false }
tasks.withType<JacocoReport>().configureEach { enabled = false }

/// Bundle the exact adapter used to qualify external reference installations.
tasks.processResources {
    from("validation") { into("validation") }
}
distributions.main {
    contents {
        from("src/main/scripts/euhedral-cfd-sweep") {
            into("bin")
            filePermissions { unix("rwxr-xr-x") }
        }
        from("validation") { into("validation") }
        from("scenes") { into("scenes") }
        from("suites") { into("suites") }
    }
}

/// Reference installation selection affects whether external integration executes or skips.
tasks.named<Test>("integrationTest") {
    inputs.property("openlbHome", providers.environmentVariable("OPENLB_HOME").orElse(""))
    providers.environmentVariable("OPENLB_HOME").orNull?.let {
        inputs.files(file("$it/identity.json"), file("$it/cfd-openlb")).optional()
    }
}
