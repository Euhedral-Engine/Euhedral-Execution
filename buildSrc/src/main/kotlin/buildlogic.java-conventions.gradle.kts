
plugins {
    `java-library`
    `maven-publish`
}

group = "io.euhedral-execution"
version = "0.0.7-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.5")
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}

tasks.withType<Test>() {
    useJUnitPlatform()
    
    // Suppress Lombok-related JVM warnings about bootstrap classpath
    jvmArgs("-Xshare:off")
}
