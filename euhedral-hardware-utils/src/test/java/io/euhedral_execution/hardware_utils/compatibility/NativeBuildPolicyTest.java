package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path nativeRoot = TestPaths.projectDirectory().resolve("src/main/native");
        String src = Files.readString(nativeRoot.resolve("windows/windows_hardening.cpp"), StandardCharsets.UTF_8);
        int def = src.indexOf("void euhedral_seed_stack_guard(");
        assertTrue(def >= 0, "guard must be seeded at load");
        // The seeder's own declaration: text after the previous '}' or ';'.
        int declStart = Math.max(src.lastIndexOf('}', def), src.lastIndexOf(';', def)) + 1;
        assertTrue(
                src.substring(declStart, def).contains("no_stack_protector"),
                "seeder itself must be no_stack_protector");
        String body = braced(src, src.indexOf('{', def));
        assertTrue(body.contains("__stack_chk_guard ="), "seeder body must assign the guard");

        String onload = Files.readString(nativeRoot.resolve("common/jni_onload.cpp"), StandardCharsets.UTF_8);
        int fn = onload.indexOf("JNI_OnLoad(");
        assertTrue(fn >= 0);
        String onloadBody = braced(onload, onload.indexOf('{', fn));
        String afterIf = onloadBody.substring(onloadBody.indexOf("#ifdef _WIN32") + "#ifdef _WIN32".length());
        assertTrue(onloadBody.contains("#ifdef _WIN32"), "JNI_OnLoad must guard the seeder with _WIN32");
        assertTrue(
                afterIf.strip().startsWith("//") || afterIf.strip().startsWith("euhedral_seed_stack_guard();"),
                "seeder call must be the first statement after #ifdef _WIN32");
        int call = afterIf.indexOf("euhedral_seed_stack_guard();");
        assertTrue(call >= 0, "JNI_OnLoad must call the seeder");
        assertFalse(afterIf.substring(0, call).contains("#endif"), "no #endif before the seeder call");
        assertFalse(
                afterIf.substring(0, call)
                                .replaceAll("(?m)^\\s*//.*$", "")
                                .strip()
                                .length()
                        > 0,
                "only comments may precede the call");
        assertTrue(call < afterIf.indexOf("return JNI_VERSION"), "seeder must run before JNI_OnLoad returns");
    }

    private static String sysctlBody(String src, String function) {
        int fn = src.indexOf("MacosSystemLayout_" + function + "(");
        assertTrue(fn >= 0, function + " must exist");
        return braced(src, src.indexOf('{', fn));
    }

    @Test
    void macosSysctlWrappersAreMemorySafe() throws Exception {
        String src = Files.readString(
                TestPaths.projectDirectory().resolve("src/main/native/macos/macos_system_layout.cpp"),
                StandardCharsets.UTF_8);
        for (String function : new String[] {"getSysctlString", "getSysctlInt", "getSysctlLong"}) {
            String body = sysctlBody(src, function);
            int get = body.indexOf("GetStringUTFChars(");
            assertTrue(get >= 0, function + " must read its key");
            String afterGet = body.substring(body.indexOf(';', get) + 1).strip();
            assertTrue(
                    afterGet.startsWith("if (key == NULL)"),
                    function + " must NULL-check the key immediately after GetStringUTFChars");
        }

        String string = sysctlBody(src, "getSysctlString");
        assertTrue(string.contains("malloc(size + 1)"), "string buffer needs room for the terminator");
        assertTrue(string.contains("buffer[size] = '\\0'"), "string buffer must be NUL-terminated");
        assertTrue(
                string.indexOf("buffer[size] = '\\0'") < string.indexOf("NewStringUTF(buffer)"),
                "terminate before NewStringUTF");

        for (String function : new String[] {"getSysctlInt", "getSysctlLong"}) {
            String body = sysctlBody(src, function);
            assertTrue(
                    body.contains("sysctlbyname(key, &value, &size, NULL, 0) != 0"),
                    function + " must check the sysctlbyname return code");
            assertTrue(body.contains("size != sizeof(value)"), function + " must verify the returned size");
        }
    }

    private static String braced(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(open, i);
            }
        }
        throw new AssertionError("unbalanced braces");
    }
}
