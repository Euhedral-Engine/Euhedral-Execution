package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NativeBinaryGateTest {

    private static final int ELF_X64 = 62;
    private static final int ELF_ARM64 = 183;
    private static final int PE_X64 = 0x8664;
    private static final int PE_ARM64 = 0xaa64;
    private static final int MACHO_X64 = 0x01000007;
    private static final int MACHO_ARM64 = 0x0100000c;
    private static final Set<String> WINDOWS_LIBRARIES = Set.of(
            "KERNEL32.dll",
            "api-ms-win-crt-heap-l1-1-0.dll",
            "api-ms-win-crt-runtime-l1-1-0.dll");

    @Test
    void checksEveryManifestProduct() throws Exception {
        Path generated = TestPaths.buildDirectory().resolve("generated-resources/native");
        Map<String, Integer> expected = Map.of(
                "bin/linux/glibc/linux_jni_x64.so", ELF_X64,
                "bin/linux/glibc/linux_jni_arm64.so", ELF_ARM64,
                "bin/linux/musl/linux_jni_x64.so", ELF_X64,
                "bin/linux/musl/linux_jni_arm64.so", ELF_ARM64,
                "bin/osx/osx_jni_x64.dylib", MACHO_X64,
                "bin/osx/osx_jni_arm64.dylib", MACHO_ARM64,
                "bin/windows/windows_jni_x64.dll", PE_X64,
                "bin/windows/windows_jni_arm64.dll", PE_ARM64);

        assertEquals(NativeManifestTest.PRODUCT_PATHS, expected.keySet());
        for (Map.Entry<String, Integer> product : expected.entrySet()) {
            byte[] bytes = Files.readAllBytes(generated.resolve(product.getKey()));
            assertTrue(bytes.length > 64, product.getKey());
            int machine;
            if (product.getKey().endsWith(".so")) {
                assertEquals(0x7f, Byte.toUnsignedInt(bytes[0]));
                machine = Short.toUnsignedInt(ByteBuffer.wrap(bytes, 18, 2)
                        .order(ByteOrder.LITTLE_ENDIAN).getShort());
            } else if (product.getKey().endsWith(".dll")) {
                int peOffset = ByteBuffer.wrap(bytes, 0x3c, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                assertEquals(0x00004550, ByteBuffer.wrap(bytes, peOffset, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt());
                machine = Short.toUnsignedInt(ByteBuffer.wrap(bytes, peOffset + 4, 2)
                        .order(ByteOrder.LITTLE_ENDIAN).getShort());
            } else {
                assertEquals(0xfeedfacf, ByteBuffer.wrap(bytes, 0, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt());
                machine = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
            assertEquals(product.getValue().intValue(), machine, product.getKey());
        }
    }

    @Test
    void windowsImportsMatchManifestAllowlist() throws Exception {
        String inspectorProperty = System.getProperty("llvm.readobj");
        assertNotNull(inspectorProperty, "llvm.readobj is required");
        Path inspector = Path.of(inspectorProperty);
        assertTrue(inspector.isAbsolute(), "llvm.readobj must be absolute");
        assertTrue(Files.isExecutable(inspector), inspector.toString());

        Path generated = TestPaths.buildDirectory().resolve("generated-resources/native");
        for (String resource : List.of(
                "bin/windows/windows_jni_x64.dll",
                "bin/windows/windows_jni_arm64.dll")) {
            Process process = new ProcessBuilder(
                    inspector.toString(), "--coff-imports", generated.resolve(resource).toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "inspector timed out: " + resource);
            assertEquals(0, process.exitValue(), output);

            Set<String> imports = new HashSet<>();
            output.lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("Name: "))
                    .map(line -> line.substring("Name: ".length()))
                    .forEach(imports::add);
            assertEquals(WINDOWS_LIBRARIES, imports, resource + System.lineSeparator() + output);
            assertFalse(output.toLowerCase().contains("stdio"), resource);
        }
    }
}
