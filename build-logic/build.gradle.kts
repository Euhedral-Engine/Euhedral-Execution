plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.8.0")
    implementation("org.junit.jupiter:junit-jupiter-api:5.10.5")
}
