package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class JniHeaderTest {

    private static final Set<String> DECLARATIONS = Set.of(
            "io_euhedral_execution_hardware_utils_linux_LinuxAffinity.h",
            "io_euhedral_execution_hardware_utils_macos_MacosAffinity.h",
            "io_euhedral_execution_hardware_utils_macos_MacosResources.h",
            "io_euhedral_execution_hardware_utils_macos_MacosSystemLayout.h",
            "io_euhedral_execution_hardware_utils_windows_WindowsAffinity.h",
            "io_euhedral_execution_hardware_utils_windows_WindowsResources.h",
            "io_euhedral_execution_hardware_utils_windows_WindowsSystemLayout.h");

    @Test
    void usesTargetCorrectPlatformHeaders() throws Exception {
        Path generatedJni = TestPaths.buildDirectory().resolve("generated-jni");
        try (Stream<Path> paths = Files.list(generatedJni.resolve("declarations"))) {
            assertEquals(DECLARATIONS, paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet()));
        }
        try (Stream<Path> paths = Files.list(generatedJni.resolve("include"))) {
            assertEquals(Set.of("jni.h", "jni_md.h"), paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet()));
        }

        String abi = Files.readString(
                TestPaths.projectDirectory().resolve("src/main/native/include/jni_md.h"),
                StandardCharsets.UTF_8);
        assertTrue(abi.contains("defined(_WIN32)"));
        assertTrue(abi.contains("defined(__linux__) || defined(__APPLE__)"));
        assertTrue(abi.contains("static_assert(sizeof(jint) == 4"));
        assertTrue(abi.contains("static_assert(sizeof(jlong) == 8"));
        assertTrue(abi.contains("static_assert(sizeof(void *) == 8"));

        String contents = Files.readString(
                TestPaths.projectDirectory().resolve("../.github/workflows").normalize().resolve("build.yaml"),
                StandardCharsets.UTF_8);
        assertFalse(contents.contains("cp \"$INCLUDE_DIR/linux/jni_md.h\""), "build.yaml");
    }
}
