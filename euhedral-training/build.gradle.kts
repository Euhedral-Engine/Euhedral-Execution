plugins {
    id("buildlogic.java-conventions")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("euhedral-training")
    manifest {
        attributes(
            "Main-Class" to "io.euhedral_execution.training.Runner",
            "Class-Path" to configurations.runtimeClasspath.get().files.joinToString(" ") { "lib/${it.name}" }
        )
    }
}

val copyRuntimeDependencies = tasks.register<Copy>("copyRuntimeDependencies") {
    dependsOn(tasks.named("jar"))
    
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("lib"))

    description = "Copies the needed runtime dependencies"
}

val copyGpuLauncher = tasks.register<Copy>("copyGpuLauncher") {
    from("src/main/scripts/euhedral-training-gpu")
    into(layout.buildDirectory.dir("bin"))
    filePermissions {
        unix("rwxr-xr-x")
    }

    description = "Copies the automatic launch script."
}

val assembleTrainerDistribution = tasks.register("assembleTrainerDistribution") {
    dependsOn(tasks.named("jar"), copyRuntimeDependencies, copyGpuLauncher)
    group = "distribution"
    description = "Assembles the complete trainer distribution with dependencies and scripts"
}

tasks.named("assemble") {
    dependsOn(assembleTrainerDistribution)
}

tasks.named<ProcessResources>("processResources") {
    from(project(":euhedral-core").file("src/main/resources")) {
        include("logback-fragments/**")
    }
    from(project(":euhedral-hardware-utils").file("src/main/resources")) {
        include("logback-fragments/**")
    }
}

dependencies {
    api(project(":euhedral-core"))
    api(project(":euhedral-data-structures"))
    api(project(":euhedral-hardware-utils"))
    api(project(":euhedral-hashing"))
    api(libs.org.apache.commons.commons.math4.legacy)
    api(libs.org.jspecify.jspecify)
    api(libs.ai.djl.api)
    api(libs.ai.djl.pytorch.pytorch.engine)
    api(libs.org.slf4j.slf4j.api)
    runtimeOnly(libs.ai.djl.pytorch.pytorch.jni)
    runtimeOnly(libs.ch.qos.logback.logback.classic)
    testImplementation(libs.org.junit.jupiter.junit.jupiter)
    testImplementation(libs.org.assertj.assertj.core)
    testImplementation(libs.org.mockito.mockito.core)
    compileOnly(libs.org.projectlombok.lombok)
    annotationProcessor(libs.org.projectlombok.lombok)
}

description = "Euhedral Training"
