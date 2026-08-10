plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("com.palantir.java-format:com.palantir.java-format.gradle.plugin:2.73.0")
    implementation("org.junit.jupiter:junit-jupiter-api:5.10.5")
}
