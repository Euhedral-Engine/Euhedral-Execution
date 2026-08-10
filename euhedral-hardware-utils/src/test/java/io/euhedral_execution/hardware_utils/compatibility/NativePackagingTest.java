package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NativePackagingTest {

    private static Set<String> relativeFiles(Path root, Path relativeTo) throws Exception {
        assertTrue(Files.isDirectory(root), () -> "missing native output directory " + root);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(relativeTo::relativize)
                    .map(path -> path.toString().replace(path.getFileSystem().getSeparator(), "/"))
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void neverWritesNativeProductsIntoSourceResources() throws Exception {
        Path generated = TestPaths.buildDirectory().resolve("generated-resources/native");
        Path classes = TestPaths.classesDirectory();
        assertEquals(NativeManifestTest.PRODUCT_PATHS, relativeFiles(generated.resolve("bin"), generated));
        assertEquals(NativeManifestTest.PRODUCT_PATHS, relativeFiles(classes.resolve("bin"), classes));

        try (Stream<Path> paths = Files.walk(classes)) {
            assertTrue(
                    paths.filter(Files::isRegularFile).noneMatch(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".h")
                                || name.endsWith(".cpp")
                                || name.endsWith(".zig")
                                || name.endsWith(".json")
                                || name.endsWith(".sh");
                    }),
                    "build inputs leaked into target/classes");
        }

        Path sourceResources = TestPaths.projectDirectory().resolve("src/main/resources");
        assertTrue(Files.isRegularFile(sourceResources.resolve("logback-fragments/euhedral-hardware-utils.xml")));
        assertFalse(Files.exists(sourceResources.resolve("build.zig")));
    }
}
