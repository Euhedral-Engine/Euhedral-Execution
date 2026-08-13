plugins {
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
}

val reportSubprojects = subprojects.filter { it.name != "benchmarks" }

tasks.register<JacocoReport>("jacocoReport") {
    group = "verification"
    description = "Generates aggregated JaCoCo coverage report for all subprojects except benchmarks."

    dependsOn(reportSubprojects.map { sub -> sub.tasks.matching { t -> t.name == "test" || t.name == "integrationTest" } })

    val sourceDirs = reportSubprojects.map { sub ->
        sub.layout.projectDirectory.dir("src/main/java")
    }
    sourceDirectories.setFrom(files(sourceDirs))

    val classDirs = reportSubprojects.map { sub ->
        sub.layout.buildDirectory.dir("classes/java/main")
    }
    classDirectories.setFrom(files(classDirs))

    val execFiles = reportSubprojects.map { sub ->
        sub.fileTree(sub.layout.buildDirectory) {
            include("jacoco/*.exec")
        }
    }
    executionData.setFrom(files(execFiles))

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoReport/jacocoReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoReport/html"))
    }
}
