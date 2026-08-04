plugins {
    id("buildlogic.java-conventions")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api(libs.org.jspecify.jspecify)
    testImplementation(libs.org.junit.jupiter.junit.jupiter)
    compileOnly(libs.org.projectlombok.lombok)
    annotationProcessor(libs.org.projectlombok.lombok)
}

tasks.named<Test>("test") {
    maxParallelForks = 1
    forkEvery = 0
    
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
}

description = "Euhedral Data Structures"
