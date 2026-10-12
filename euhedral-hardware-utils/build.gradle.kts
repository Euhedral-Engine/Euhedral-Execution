import java.security.MessageDigest

plugins {
    id("buildlogic.java-conventions")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val nativeGeneratedResources = layout.buildDirectory.dir("generated-resources/native")
val generatedJniDir = layout.buildDirectory.dir("generated-jni")
val hashedCatalogResources = layout.buildDirectory.dir("generated-resources/native-catalog")

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(
        listOf(
            "-h", generatedJniDir.get().file("declarations").asFile.absolutePath,
            "--module-version", version.toString()
        )
    )
    options.compilerArgs.add("--release")
    options.compilerArgs.add("17")

    outputs.dir(generatedJniDir).withPropertyName("jniHeaders")
}

val cleanNativeResources = tasks.register<Delete>("cleanNativeResources") {
    delete(nativeGeneratedResources)
    delete(hashedCatalogResources)
    delete(layout.buildDirectory.dir("classes/java/main/bin"))
    delete(
        layout.buildDirectory.file(
            "classes/java/main/META-INF/euhedral/native-products.tsv"
        )
    )

    isFollowSymlinks = false

    description = "Removes leftover native resources"
}

val zigBuild = tasks.register<Exec>("zigBuild") {
    dependsOn(tasks.named("compileJava"))

    val zigExec = providers.environmentVariable("ZIG").orElse("zig")
    val javaHome = providers.systemProperty("java.home")
    val sdkRoot = providers.environmentVariable("SDKROOT").orElse("")
    val rcodeSign = providers.environmentVariable("RCODESIGN").orElse("")

    workingDir(layout.projectDirectory.dir("src/main/native"))

    commandLine(
        zigExec.get(),
        "build",
        "-Djava-home=${javaHome.get()}",
        "-Dgenerated-jni=${generatedJniDir.get().asFile.absolutePath}",
        "-Doutput-root=${nativeGeneratedResources.get().asFile.absolutePath}",
        "-Dmacos-sdk=${sdkRoot.get()}",
        "-Drcodesign=${rcodeSign.get()}",
        "--cache-dir",
        layout.projectDirectory.dir("zig-cache").asFile.absolutePath,
        "--global-cache-dir",
        layout.projectDirectory.dir("zig-global-cache").asFile.absolutePath
    )

    inputs.dir("src/main/native")
    inputs.dir(generatedJniDir)
        .withPropertyName("jniHeaders")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(nativeGeneratedResources)

    description = "Cross compiles the JNI source code."
}

// zig writes the catalog before the products exist, so the sha256 column is appended here, after the build, from the
// installed binaries. The zig-written TSV stays an intermediate; only this task's output is packaged.
val hashNativeCatalog = tasks.register("hashNativeCatalog") {
    dependsOn(zigBuild)

    val nativeRoot = nativeGeneratedResources
    val outputRoot = hashedCatalogResources
    inputs.dir(nativeRoot).withPropertyName("nativeResources")
    outputs.dir(outputRoot).withPropertyName("hashedCatalog")

    doLast {
        val root = nativeRoot.get().asFile
        val source = root.resolve("META-INF/euhedral/native-products.tsv")
        val target = outputRoot.get().asFile.resolve("META-INF/euhedral/native-products.tsv")
        val hashed = StringBuilder()
        val text = source.readText(Charsets.UTF_8)
        if (!text.endsWith("\n")) {
            throw GradleException("native catalog must end with LF: $source")
        }
        text.split("\n").dropLast(1).forEach { line ->
            hashed.append(line)
            if (line.startsWith("product\t")) {
                val fields = line.split("\t")
                if (fields.size != 7) {
                    throw GradleException("native catalog product row must have 7 fields before hashing: $line")
                }
                val product = root.resolve(fields[6].removePrefix("/"))
                if (!product.isFile) {
                    throw GradleException("native catalog references a missing product: $product")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                product.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                hashed.append('\t').append(digest.digest().joinToString("") { "%02x".format(it) })
            }
            hashed.append('\n')
        }
        target.parentFile.mkdirs()
        target.writeText(hashed.toString(), Charsets.UTF_8)
    }

    description = "Appends the sha256 column to the generated native product catalog."
}

val copyNativeResources = tasks.register<Copy>("copyNativeResources") {
    dependsOn(zigBuild)
    dependsOn(hashNativeCatalog)

    from(nativeGeneratedResources) {
        include("bin/**")
    }
    from(hashedCatalogResources) {
        include("META-INF/euhedral/native-products.tsv")
    }
    into(sourceSets.main.get().output.classesDirs.singleFile)

    description = "Copies the compiled JNI binaries to the build directory"
}

tasks.named("classes") {
    dependsOn(copyNativeResources)
}

// Generate Maven pom.properties file as a task to avoid race conditions
val generatePomProperties = tasks.register("generatePomProperties") {
    val outputFile = layout.buildDirectory.file("maven-metadata/pom.properties")
    val projectVersion = project.version.toString()
    val projectGroup = project.group.toString()
    val projectName = project.name

    outputs.file(outputFile)

    doLast {
        outputFile.get().asFile.parentFile.mkdirs()
        outputFile.get().asFile.writeText(
            """
            #Generated by Gradle
            version=$projectVersion
            groupId=$projectGroup
            artifactId=$projectName
        """.trimIndent()
        )
    }
}

// Configure JAR to include Maven metadata
tasks.named<Jar>("jar") {
    dependsOn(generatePomProperties)

    into("META-INF/maven/${project.group}/${project.name}") {
        from("${project.projectDir}/pom.xml")
        from(generatePomProperties.map { layout.buildDirectory.file("maven-metadata/pom.properties") })
    }
}

tasks.register<Copy>("prepareNativeSmokeBundle") {
    dependsOn(tasks.named("jar"))
    dependsOn(tasks.named("testClasses"))

    val smokeDir = file("${layout.buildDirectory.get()}/native-smoke")

    from(tasks.jar.get().archiveFile) {
        into(".")
    }

    from("${layout.buildDirectory.get()}/classes/java/test/io/euhedral_execution/hardware_utils/nativelib/NativeLoadSmokeMain.class") {
        into("io/euhedral_execution/hardware_utils/nativelib")
    }

    from(configurations.runtimeClasspath) {
        into("lib")
    }

    into(smokeDir)

    description = "Task to prepare native smoke test bundle"
}

tasks.named("test") {
    dependsOn("prepareNativeSmokeBundle")
}

tasks.named<Test>("test") {
    systemProperty("project.basedir", project.projectDir.absolutePath)
    systemProperty("classes.directory", sourceSets.main.get().output.classesDirs.asPath)
    systemProperty("build.directory", layout.buildDirectory.get().asFile.absolutePath)
    systemProperty("rcodesign", System.getenv("RCODESIGN") ?: "")
    systemProperty("llvm.readobj", System.getenv("LLVM_READOBJ") ?: "")
    systemProperty("llvm.objdump", System.getenv("LLVM_OBJDUMP") ?: "")
    systemProperty("test.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("smoke.directory", "${layout.buildDirectory.get()}/native-smoke")
    systemProperty("test.classes", sourceSets.test.get().output.classesDirs.asPath)
}

dependencies {
    api(libs.it.unimi.dsi.fastutil)
    api(libs.org.slf4j.slf4j.api)
    testImplementation(libs.org.junit.jupiter.junit.jupiter)
    testImplementation(libs.org.mockito.mockito.core)
    testImplementation(libs.org.ow2.asm.asm)
    compileOnly(libs.org.projectlombok.lombok)
    annotationProcessor(libs.org.projectlombok.lombok)
    compileOnly(libs.org.jspecify.jspecify)
}

description = "Euhedral Hardware Utils"
