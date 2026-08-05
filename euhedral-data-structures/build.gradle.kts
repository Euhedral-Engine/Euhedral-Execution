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

description = "Euhedral Data Structures"
