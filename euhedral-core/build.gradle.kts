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

description = "Euhedral Core"
