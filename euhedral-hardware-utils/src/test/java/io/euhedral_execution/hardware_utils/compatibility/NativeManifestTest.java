package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NativeManifestTest {

    static final Set<String> PRODUCT_PATHS = Set.of(
            "bin/linux/glibc/linux_jni_x64.so",
            "bin/linux/glibc/linux_jni_arm64.so",
            "bin/linux/musl/linux_jni_x64.so",
            "bin/linux/musl/linux_jni_arm64.so",
            "bin/osx/osx_jni_x64.dylib",
            "bin/osx/osx_jni_arm64.dylib",
            "bin/windows/windows_jni_x64.dll",
            "bin/windows/windows_jni_arm64.dll");

    static final String CATALOG = """
        schema\t1
        os\texact\tlinux\tlinux
        os\texact\tdarwin\tmacos
        os\texact\tmac os x\tmacos
        os\texact\tmacos\tmacos
        os\tprefix\twindows\twindows
        arch\taarch64\tarm64
        arch\tamd64\tx64
        arch\tarm64\tarm64
        arch\tx86-64\tx64
        arch\tx86_64\tx64
        product\tlinux-glibc-x64\tlinux\tx64\tglibc\t10\t/bin/linux/glibc/linux_jni_x64.so
        product\tlinux-musl-x64\tlinux\tx64\tmusl\t20\t/bin/linux/musl/linux_jni_x64.so
        product\tlinux-glibc-arm64\tlinux\tarm64\tglibc\t10\t/bin/linux/glibc/linux_jni_arm64.so
        product\tlinux-musl-arm64\tlinux\tarm64\tmusl\t20\t/bin/linux/musl/linux_jni_arm64.so
        product\tmacos-x64\tmacos\tx64\tnone\t10\t/bin/osx/osx_jni_x64.dylib
        product\tmacos-arm64\tmacos\tarm64\tnone\t10\t/bin/osx/osx_jni_arm64.dylib
        product\twindows-x64\twindows\tx64\tnone\t10\t/bin/windows/windows_jni_x64.dll
        product\twindows-arm64\twindows\tarm64\tnone\t10\t/bin/windows/windows_jni_arm64.dll
        """;

    @Test
    void discoversEveryDeclaredFolderDeterministically() throws Exception {
        Path nativeRoot = TestPaths.projectDirectory().resolve("src/main/native");
        String manifest = Files.readString(nativeRoot.resolve("native-products.json"), StandardCharsets.UTF_8);
        assertFalse(manifest.startsWith("\ufeff"));
        assertFalse(manifest.contains("\r"));
        assertTrue(manifest.endsWith("\n"));
        assertTrue(manifest.getBytes(StandardCharsets.UTF_8).length <= 1_048_576);

        for (String root : List.of("common", "linux", "macos", "windows")) {
            assertTrue(manifest.contains('"' + root + '"'), () -> "manifest omits source root " + root);
            Path sourceRoot = nativeRoot.resolve(root);
            assertTrue(Files.isDirectory(sourceRoot));
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                List<String> sources = paths.filter(Files::isRegularFile)
                        .map(sourceRoot::relativize)
                        .map(Path::toString)
                        .filter(name -> name.endsWith(".c")
                                || name.endsWith(".cc")
                                || name.endsWith(".cpp")
                                || name.endsWith(".cxx"))
                        .sorted(ApiSurface.UTF8_ORDER)
                        .toList();
                assertFalse(sources.isEmpty(), () -> "source root has no compiled input: " + root);
                assertEquals(sources.stream().sorted(ApiSurface.UTF8_ORDER).toList(), sources);
            }
        }
        try (Stream<Path> paths = Files.walk(nativeRoot)) {
            assertTrue(paths.noneMatch(Files::isSymbolicLink), "native source tree contains a symlink");
        }

        Path catalog =
                TestPaths.buildDirectory().resolve("generated-resources/native/META-INF/euhedral/native-products.tsv");
        assertEquals(CATALOG, Files.readString(catalog, StandardCharsets.UTF_8));
    }
}
