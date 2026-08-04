package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class NativeWarmRemovalIT {

    private static final String REMOVED_PRODUCT = "bin/windows/windows_jni_arm64.dll";

    private static void runPackage(Path module) throws Exception {
        Path projectRoot = module.getParent();
        Path gradlew = projectRoot.resolve("gradlew");
        List<String> command = new ArrayList<>(List.of(
                gradlew.toString(), "--no-daemon", "--console=plain",
                ":euhedral-hardware-utils:build", "-x", "test"));
        Process process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] bytes = process.getInputStream().readNBytes(4 * 1_024 * 1_024 + 1);
        assertTrue(bytes.length <= 4 * 1_024 * 1_024,
                "native-package: nested build output is oversized");
        assertTrue(process.waitFor(120, TimeUnit.SECONDS),
                "native-package: nested warm build timed out");
        String output = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        copyTree(source, destination, false);
    }

    private static void copyTree(Path source, Path destination, boolean excludeBuild)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException(
                            "native-package: source tree contains symlink " + directory);
                }
                if (excludeBuild && directory.getFileName().toString().equals("build")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (excludeBuild && directory.getFileName().toString().equals(".gradle")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("native-package: source tree contains symlink " + file);
                }
                Files.copy(file, destination.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }
    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledOnOs(OS.LINUX)
    void warmBuildRemovesADeletedManifestProductAndCleanupDoesNotFollowSymlinks() throws Exception {
        Path isolatedRoot = temporaryDirectory.resolve("isolated");
        Path isolatedModule = isolatedRoot.resolve("euhedral-hardware-utils");
        Files.createDirectories(isolatedModule);
        Path projectRoot = TestPaths.projectDirectory().getParent();
        String minimalSettings = """
                rootProject.name = "euhedral-execution"
                include(":euhedral-hardware-utils")
                
                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                    }
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                }
                """;
        Files.writeString(isolatedRoot.resolve("settings.gradle.kts"),
                minimalSettings, StandardCharsets.UTF_8);
        Files.copy(projectRoot.resolve("gradle.properties"),
                isolatedRoot.resolve("gradle.properties"));
        Files.copy(projectRoot.resolve("gradlew"),
                isolatedRoot.resolve("gradlew"));
        isolatedRoot.resolve("gradlew").toFile().setExecutable(true);
        Files.copy(projectRoot.resolve("gradlew.bat"),
                isolatedRoot.resolve("gradlew.bat"));
        copyTree(projectRoot.resolve("gradle"), isolatedRoot.resolve("gradle"));
        copyTree(projectRoot.resolve("buildSrc"), isolatedRoot.resolve("buildSrc"), true);
        copyTree(TestPaths.projectDirectory().resolve("src/main/java"),
                isolatedModule.resolve("src/main/java"));
        copyTree(TestPaths.projectDirectory().resolve("src/main/native"),
                isolatedModule.resolve("src/main/native"));
        Path logback = TestPaths.projectDirectory().resolve(
                "src/main/resources/logback-fragments/euhedral-hardware-utils.xml");
        Path isolatedLogback = isolatedModule.resolve(
                "src/main/resources/logback-fragments/euhedral-hardware-utils.xml");
        Files.createDirectories(isolatedLogback.getParent());
        Files.copy(logback, isolatedLogback);
        Path smokeMain = TestPaths.projectDirectory().resolve(
                "src/test/java/io/euhedral_execution/hardware_utils/internal/NativeLoadSmokeMain.java");
        Path isolatedSmokeMain = isolatedModule.resolve(
                "src/test/java/io/euhedral_execution/hardware_utils/internal/NativeLoadSmokeMain.java");
        Files.createDirectories(isolatedSmokeMain.getParent());
        Files.copy(smokeMain, isolatedSmokeMain);
        Files.copy(TestPaths.projectDirectory().resolve("build.gradle.kts"),
                isolatedModule.resolve("build.gradle.kts"));

        Path external = temporaryDirectory.resolve("external-sentinel");
        Files.createDirectories(external);
        Path sentinel = external.resolve("keep.txt");
        Files.writeString(sentinel, "user-owned\n", StandardCharsets.UTF_8);
        Path generated = isolatedModule.resolve("build/generated-resources/native");
        Files.createDirectories(generated);
        Files.createSymbolicLink(generated.resolve("trap"), external);

        runPackage(isolatedModule);
        assertEquals("user-owned\n", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertTrue(Files.exists(isolatedModule.resolve("build/generated-resources/native")
                .resolve(REMOVED_PRODUCT)));

        Path manifest = isolatedModule.resolve("src/main/native/native-products.json");
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        String marker = ",\n    {\n      \"id\": \"windows-arm64\"";
        int start = json.indexOf(marker);
        assertTrue(start >= 0, "native-package: copied manifest lacks windows-arm64");
        int end = json.indexOf("\n    }\n  ]", start);
        assertTrue(end > start, "native-package: could not isolate windows-arm64 product");
        Files.writeString(manifest,
                json.substring(0, start) + json.substring(end + "\n    }".length()),
                StandardCharsets.UTF_8);

        runPackage(isolatedModule);
        assertFalse(Files.exists(isolatedModule.resolve("build/generated-resources/native")
                .resolve(REMOVED_PRODUCT)));
        assertFalse(
                Files.exists(isolatedModule.resolve("build/classes/java/main").resolve(REMOVED_PRODUCT)));
        String catalog = Files.readString(isolatedModule.resolve(
                        "build/generated-resources/native/META-INF/euhedral/native-products.tsv"),
                StandardCharsets.UTF_8);
        assertFalse(catalog.contains(REMOVED_PRODUCT));
        Path jar = isolatedModule.resolve("build/libs/euhedral-hardware-utils-0.0.7-SNAPSHOT.jar");
        try (JarFile archive = new JarFile(jar.toFile())) {
            assertFalse(
                    archive.stream().anyMatch(entry -> entry.getName().equals(REMOVED_PRODUCT)));
        }
        assertEquals("user-owned\n", Files.readString(sentinel, StandardCharsets.UTF_8));
    }
}
