package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class NativeBuildPolicyTest {

    @Test
    void enforcesTheSelectedPortableBuildPolicy() throws Exception {
        String build = Files.readString(
                TestPaths.projectDirectory().resolve("src/main/native/build.zig"), StandardCharsets.UTF_8);
        assertTrue(build.contains(".optimize = .ReleaseSafe"));
        assertTrue(build.contains(".stack_protector = true"));
        assertTrue(build.contains(".stack_check = true"));
        assertTrue(build.contains(".omit_frame_pointer = false"));
        assertTrue(build.contains(".unwind_tables = .async"));
        assertTrue(build.contains(".sanitize_c = .trap"));
        assertTrue(build.contains("library.link_z_relro = true"));
        assertTrue(build.contains("library.link_z_lazy = false"));
        assertTrue(build.contains("library.link_z_defs = true"));
        assertTrue(build.contains("library.dll_export_fns = false"));
        assertTrue(build.contains("library.bundle_compiler_rt = false"));
        assertTrue(build.contains(".link_libc = true"));
        assertTrue(build.contains(".link_libcpp = false"));
        assertTrue(build.contains("library.entitlements"));
        assertFalse(build.contains("-O3"));
        assertFalse(build.contains("headerpad"));
        assertFalse(build.contains("linkFramework"));
        assertFalse(build.contains("JAVA_HOME"));
        assertFalse(build.contains("b.graph.environ_map"));
    }
}
