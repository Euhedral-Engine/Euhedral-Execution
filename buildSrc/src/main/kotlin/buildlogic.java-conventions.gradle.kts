
plugins {
    `java-library`
    `maven-publish`
    signing
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

// Check if release mode is enabled (similar to Maven's -P release)
val isReleaseMode = project.hasProperty("release") || System.getenv("RELEASE_MODE") == "true"

// Only generate sources and javadoc JARs in release mode
if (isReleaseMode) {
    java {
        withSourcesJar()
        withJavadocJar()
    }
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
        
        pom {
            name.set(project.name)
            description.set(project.description ?: project.name)
            url.set("https://github.com/euhedral/Euhedral-Execution")
            
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            
            developers {
                developer {
                    id.set("euhedral")
                    name.set("Euhedral Team")
                    email.set("dev@euhedral.io")
                }
            }
            
            scm {
                connection.set("scm:git:git://github.com/euhedral/Euhedral-Execution.git")
                developerConnection.set("scm:git:ssh://github.com/euhedral/Euhedral-Execution.git")
                url.set("https://github.com/euhedral/Euhedral-Execution")
            }
        }
    }
    
    repositories {
        maven {
            name = "MavenCentral"
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            
            credentials {
                username = project.findProperty("mavenCentralUsername") as String? ?: System.getenv("MAVEN_CENTRAL_USERNAME")
                password = project.findProperty("mavenCentralPassword") as String? ?: System.getenv("MAVEN_CENTRAL_PASSWORD")
            }
        }
    }
}

signing {
    // Only configure signing in release mode
    isRequired = isReleaseMode
    
    // Use in-memory key from environment variable if available
    val signingKey = project.findProperty("signingKey") as String? ?: System.getenv("SIGNING_KEY")
    val signingPassword = project.findProperty("signingPassword") as String? ?: System.getenv("SIGNING_PASSWORD")
    
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    
    sign(publishing.publications["maven"])
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
    // Disable strict doclint to match Maven configuration
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.withType<Test>() {
    useJUnitPlatform()
    
    // Suppress Lombok-related JVM warnings about bootstrap classpath
    jvmArgs("-Xshare:off")
}

