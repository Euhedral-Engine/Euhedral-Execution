plugins {
    id("buildlogic.java-conventions")
}

dependencies {
    api(project(":euhedral-data-structures"))
    api(project(":euhedral-hardware-utils"))
    api(project(":euhedral-hashing"))
    api(libs.org.slf4j.slf4j.api)
    api(libs.io.micrometer.micrometer.core)
    api(libs.org.jspecify.jspecify)
    implementation(libs.com.fasterxml.jackson.core.jackson.annotations)
    implementation(libs.com.fasterxml.jackson.core.jackson.databind)
    testImplementation(libs.org.assertj.assertj.core)
    testImplementation(libs.org.junit.jupiter.junit.jupiter)
    testImplementation(libs.org.mockito.mockito.core)
    testImplementation(libs.org.awaitility.awaitility)
    compileOnly(libs.org.projectlombok.lombok)
    annotationProcessor(libs.org.projectlombok.lombok)
    testCompileOnly(libs.org.projectlombok.lombok)
    testAnnotationProcessor(libs.org.projectlombok.lombok)
}

tasks.test {
    filter {
        excludeTestsMatching("io.euhedral_execution.core.control_plane.FragmentDecisionTreeRuntimeParityTest")
    }
}

val sourceSets = the<SourceSetContainer>()

tasks.register<Test>("runtimeParityTest") {
    description =
        "Runs dedicated runtime parity tests with -Deuhedral.fragment.cacheExecutePath=true"
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform()

    filter {
        includeTestsMatching("io.euhedral_execution.core.control_plane.FragmentDecisionTreeRuntimeParityTest")
    }

    systemProperty("euhedral.fragment.cacheExecutePath", "true")
    jvmArgs("-Xshare:off")
}

description = "Euhedral Core"
