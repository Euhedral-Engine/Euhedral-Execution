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

    @Test
    void windowsStackGuardIsSeededAtLoadNotOnlyAConstant() throws Exception {
        java.nio.file.Path native_ = TestPaths.projectDirectory().resolve("src/main/native");
        String src = Files.readString(native_.resolve("windows/windows_hardening.cpp"), StandardCharsets.UTF_8);
        int def = src.indexOf("void euhedral_seed_stack_guard(");
        assertTrue(def >= 0, "guard must be seeded at load");
        int open = src.indexOf('{', def);
        int depth = 0;
        int close = open;
        for (; close < src.length(); close++) {
            char c = src.charAt(close);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                break;
            }
        }
        String body = src.substring(open, close);
        assertTrue(body.contains("__stack_chk_guard ="), "seeder body must assign the guard");
        assertTrue(src.substring(0, def).contains("no_stack_protector"), "seeder must be no_stack_protector");

        String onload = Files.readString(native_.resolve("common/jni_onload.cpp"), StandardCharsets.UTF_8);
        int guardIf = onload.indexOf("#ifdef _WIN32");
        int call = onload.indexOf("euhedral_seed_stack_guard();");
        assertTrue(guardIf >= 0 && call > guardIf, "JNI_OnLoad must call the seeder under _WIN32");
        assertTrue(call < onload.indexOf("return JNI_VERSION"), "seeder must run before JNI_OnLoad returns");
    }
}
