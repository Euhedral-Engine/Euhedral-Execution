pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "euhedral-execution"
include(":euhedral-core")
include(":benchmarks")
include(":euhedral-reactor-core")
include(":euhedral-spring-core")
include(":euhedral-data-structures")
include(":euhedral-hardware-utils")
include(":euhedral-hashing")

// Configure dependency resolution to use only Maven Central
// This is the Gradle equivalent of Maven's settings.xml with mirror configuration
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
}
