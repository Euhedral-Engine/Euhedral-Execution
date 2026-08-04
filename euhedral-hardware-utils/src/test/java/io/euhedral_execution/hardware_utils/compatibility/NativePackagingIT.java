package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NativePackagingIT {

    private static final Set<String> EXACT_NON_CLASS_FILES = Set.of(
            "META-INF/MANIFEST.MF",
            "META-INF/euhedral/native-products.tsv",
            "META-INF/maven/io.euhedral-execution/euhedral-hardware-utils/pom.properties",
            "META-INF/maven/io.euhedral-execution/euhedral-hardware-utils/pom.xml",
            "logback-fragments/euhedral-hardware-utils.xml",
            "bin/linux/glibc/linux_jni_x64.so",
            "bin/linux/glibc/linux_jni_arm64.so",
            "bin/linux/musl/linux_jni_x64.so",
            "bin/linux/musl/linux_jni_arm64.so",
            "bin/osx/osx_jni_x64.dylib",
            "bin/osx/osx_jni_arm64.dylib",
            "bin/windows/windows_jni_x64.dll",
            "bin/windows/windows_jni_arm64.dll");

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String portable(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    @Test
    void jarHasOnlyTheExactGeneratedResourceInventory() throws Exception {
        Path jar = Path.of(System.getProperty("test.jar"));
        assertTrue(Files.isRegularFile(jar), () -> "native-package: missing ordinary jar " + jar);
        Set<String> expectedClasses = new HashSet<>();
        Path classes = TestPaths.classesDirectory();
        try (Stream<Path> paths = Files.walk(classes)) {
            paths.filter(Files::isRegularFile)
                    .map(classes::relativize)
                    .map(NativePackagingIT::portable)
                    .filter(name -> name.endsWith(".class"))
                    .forEach(expectedClasses::add);
        }

        try (JarFile archive = new JarFile(jar.toFile())) {
            Set<String> actualClasses = new HashSet<>();
            Set<String> actualNonClasses = new HashSet<>();
            archive.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> {
                if (entry.getName().endsWith(".class")) {
                    actualClasses.add(entry.getName());
                } else {
                    actualNonClasses.add(entry.getName());
                }
            });
            assertEquals(expectedClasses, actualClasses, "native-package: class inventory differs");
            assertEquals(EXACT_NON_CLASS_FILES, actualNonClasses,
                    "native-package: resource inventory differs");
            assertEquals(NativeManifestTest.CATALOG,
                    new String(archive.getInputStream(archive.getJarEntry(
                            "META-INF/euhedral/native-products.tsv")).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));

            for (String product : NativeManifestTest.PRODUCT_PATHS) {
                try (InputStream input = archive.getInputStream(archive.getJarEntry(product))) {
                    assertEquals(sha256(Files.readAllBytes(TestPaths.buildDirectory()
                                    .resolve("generated-resources/native").resolve(product))),
                            sha256(input.readAllBytes()), product);
                }
            }
        }
    }

    @Test
    void smokeBundleIsSmallAndContainsNoUnrelatedTestOrSourceFiles() throws Exception {
        Path bundle = Path.of(System.getProperty("smoke.directory"));
        assertTrue(Files.isDirectory(bundle), "native-package: missing smoke bundle");
        long files;
        long bytes;
        try (Stream<Path> paths = Files.walk(bundle)) {
            var regular = paths.filter(Files::isRegularFile).toList();
            files = regular.size();
            bytes = regular.stream().mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).sum();
            for (Path path : regular) {
                String name = portable(bundle.relativize(path));
                assertTrue(name.endsWith(".jar")
                                || name.equals(
                                "io/euhedral_execution/hardware_utils/internal/NativeLoadSmokeMain.class"),
                        () -> "native-package: unexpected smoke file " + name);
                assertFalse(name.toLowerCase().matches(".*(credential|secret|token|cache).*"),
                        name);
            }
        }
        assertTrue(files <= 64, "native-package: smoke bundle has " + files + " files");
        assertTrue(bytes <= 134_217_728L, "native-package: smoke bundle has " + bytes + " bytes");
    }
}
