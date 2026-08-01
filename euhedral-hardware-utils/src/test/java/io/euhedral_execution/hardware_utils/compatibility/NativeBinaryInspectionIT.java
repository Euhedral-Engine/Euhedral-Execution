package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NativeBinaryInspectionIT {

    private static final Pattern JNI_NAME = Pattern.compile(
            "Name: _?(JNI_OnLoad|Java_[A-Za-z0-9_]+)");
    private static final Pattern HEADER_JNI = Pattern.compile("JNICALL (Java_[A-Za-z0-9_]+)");
    private static final Pattern GLIBC_VERSION = Pattern.compile("GLIBC_([0-9]+)\\.([0-9]+)");
    private static final Pattern PE_IMPORT = Pattern.compile("Import \\{\\s+Name: ([^\\s]+)");

    private static void assertExports(String resource, String output, String osToken)
            throws Exception {
        Set<String> expected = expectedJniExports(osToken);
        expected.add("JNI_OnLoad");
        if (osToken.equals("windows")) {
            expected.remove(
                    "Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution");
            expected.add(
                    "Java_io_euhedral_1execution_hardware_1utils_windows_WindowsTimerResolution_ntSetTimerResolution");
        } else if (osToken.equals("osx")) {
            expected.add(
                    "Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getCoreTypeMask");
        }
        String symbolInventory = output;
        int versionSymbols = output.indexOf("VersionSymbols [");
        if (versionSymbols >= 0) {
            symbolInventory = output.substring(0, versionSymbols);
        }
        Set<String> actual = new HashSet<>();
        Matcher names = JNI_NAME.matcher(symbolInventory);
        int onLoadCount = 0;
        while (names.find()) {
            actual.add(names.group(1));
            if (names.group(1).equals("JNI_OnLoad")) {
                onLoadCount++;
            }
        }
        assertEquals(1, onLoadCount, "native-jni: JNI_OnLoad count for " + resource);
        assertEquals(expected, actual, "native-jni: export inventory for " + resource);
    }

    private static Set<String> expectedJniExports(String osToken) throws Exception {
        Path declarations = TestPaths.buildDirectory().resolve("generated-jni/declarations");
        Set<String> exports = new HashSet<>();
        try (Stream<Path> paths = Files.list(declarations)) {
            for (Path header : paths.filter(
                    path -> path.getFileName().toString().contains('_' + osToken + '_')).toList()) {
                Matcher matcher = HEADER_JNI.matcher(
                        Files.readString(header, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    exports.add(matcher.group(1));
                }
            }
        }
        return exports;
    }

    private static Set<String> neededLibraries(String output) {
        int start = output.indexOf("NeededLibraries [");
        int end = output.indexOf(']', start);
        assertTrue(start >= 0 && end > start, output);
        Set<String> libraries = new HashSet<>();
        output.substring(start + "NeededLibraries [".length(), end).lines()
                .map(String::trim).filter(line -> !line.isEmpty()).forEach(libraries::add);
        return libraries;
    }

    private static Set<String> peImports(String output) {
        Set<String> imports = new HashSet<>();
        Matcher matcher = PE_IMPORT.matcher(output);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    private static String loadCommandName(String output, String command) {
        Set<String> names = loadCommandNames(output, command);
        assertEquals(1, names.size(), output);
        return names.iterator().next();
    }

    private static Set<String> loadCommandNames(String output, String command) {
        Set<String> names = new HashSet<>();
        String[] lines = output.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].trim().equals("cmd " + command)) {
                for (int next = index + 1; next < Math.min(index + 5, lines.length); next++) {
                    String line = lines[next].trim();
                    if (line.startsWith("name ")) {
                        names.add(line.substring("name ".length(), line.indexOf(" (offset")));
                        break;
                    }
                }
            }
        }
        return names;
    }

    private static Path executableProperty(String name) {
        Path executable = Path.of(System.getProperty(name, ""));
        assertTrue(executable.isAbsolute(), name + " must be absolute");
        assertTrue(Files.isExecutable(executable), executable.toString());
        return executable;
    }

    private static String run(Path executable, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] bytes = process.getInputStream().readNBytes(4 * 1_024 * 1_024 + 1);
        assertTrue(bytes.length <= 4 * 1_024 * 1_024,
                "native-package: inspector output is oversized");
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "native-package: inspector timed out");
        String output = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test
    void everyPackagedProductPassesArchitectureImportExportAndRuntimeFloorGates() throws Exception {
        Path readobj = executableProperty("p1.llvm.readobj");
        Path objdump = executableProperty("p1.llvm.objdump");
        Path generated = TestPaths.buildDirectory().resolve("generated-resources/native");
        Map<String, String> machines = Map.of(
                "bin/linux/glibc/linux_jni_x64.so", "Machine: EM_X86_64",
                "bin/linux/glibc/linux_jni_arm64.so", "Machine: EM_AARCH64",
                "bin/linux/musl/linux_jni_x64.so", "Machine: EM_X86_64",
                "bin/linux/musl/linux_jni_arm64.so", "Machine: EM_AARCH64",
                "bin/osx/osx_jni_x64.dylib", "CpuType: X86-64",
                "bin/osx/osx_jni_arm64.dylib", "CpuType: Arm64",
                "bin/windows/windows_jni_x64.dll", "Machine: IMAGE_FILE_MACHINE_AMD64",
                "bin/windows/windows_jni_arm64.dll", "Machine: IMAGE_FILE_MACHINE_ARM64");

        for (String resource : NativeManifestTest.PRODUCT_PATHS.stream().sorted().toList()) {
            Path product = generated.resolve(resource);
            String output;
            if (resource.endsWith(".so")) {
                output = run(readobj, "--file-header", "--needed-libs", "--dyn-symbols",
                        "--version-info",
                        product.toString());
                assertTrue(output.contains("Format: elf64-"), resource);
                assertEquals(resource.contains("/glibc/") ? Set.of("libc.so.6") : Set.of("libc.so"),
                        neededLibraries(output), resource);
                if (resource.contains("/glibc/")) {
                    Matcher versions = GLIBC_VERSION.matcher(output);
                    while (versions.find()) {
                        int major = Integer.parseInt(versions.group(1));
                        int minor = Integer.parseInt(versions.group(2));
                        assertTrue(major < 2 || major == 2 && minor <= 17,
                                () -> "native-package: GLIBC floor exceeded in " + resource);
                    }
                } else {
                    assertFalse(output.contains("GLIBC_"), resource);
                }
                assertExports(resource, output, "linux");
            } else if (resource.endsWith(".dll")) {
                output = run(readobj, "--file-header", "--coff-imports", "--coff-exports",
                        product.toString());
                assertTrue(output.contains("Format: COFF-"), resource);
                assertEquals(Set.of(
                                "KERNEL32.dll",
                                "api-ms-win-crt-heap-l1-1-0.dll",
                                "api-ms-win-crt-runtime-l1-1-0.dll"),
                        peImports(output), resource);
                String lowercase = output.toLowerCase();
                for (String forbidden : List.of("stdio", "libgcc", "libstdc++", "libc++")) {
                    assertFalse(lowercase.contains(forbidden), resource + " contains " + forbidden);
                }
                assertExports(resource, output, "windows");
            } else {
                output = run(readobj, "--file-header", "--needed-libs", "--macho-version-min",
                        "--symbols",
                        product.toString());
                assertTrue(output.contains("Format: Mach-O "), resource);
                assertTrue(output.contains("Version: 11.0"), resource);
                String privateHeaders = run(objdump, "--macho", "--private-headers",
                        product.toString());
                String filename = product.getFileName().toString();
                assertEquals("@rpath/" + filename, loadCommandName(privateHeaders, "LC_ID_DYLIB"),
                        resource);
                assertEquals(Set.of("/usr/lib/libSystem.B.dylib"),
                        loadCommandNames(privateHeaders, "LC_LOAD_DYLIB"), resource);
                assertExports(resource, output, "osx");
            }
            assertTrue(output.contains(machines.get(resource)),
                    resource + System.lineSeparator() + output);
        }
    }

    @Test
    void packagedMacosProductsRetainAdHocHardenedSignatures() throws Exception {
        Path signer = executableProperty("p1.rcodesign");
        Path jar = Path.of(System.getProperty("p1.jar"));
        Map<String, String> identifiers = Map.of(
                "bin/osx/osx_jni_x64.dylib", "io.euhedral.execution.hardware-utils.osx-jni-x64",
                "bin/osx/osx_jni_arm64.dylib",
                "io.euhedral.execution.hardware-utils.osx-jni-arm64");
        Path extraction = Files.createTempDirectory(TestPaths.buildDirectory(), "packaged-macos-");
        try (var archive = new java.util.jar.JarFile(jar.toFile())) {
            for (Map.Entry<String, String> product : identifiers.entrySet()) {
                Path file = extraction.resolve(Path.of(product.getKey()).getFileName());
                try (var input = archive.getInputStream(archive.getJarEntry(product.getKey()))) {
                    Files.copy(input, file);
                }
                String output = run(signer, "print-signature-info", file.toString());
                assertTrue(output.contains("identifier: " + product.getValue()), output);
                assertTrue(output.contains("CodeSignatureFlags(ADHOC | RUNTIME)"), output);
            }
        } finally {
            try (Stream<Path> paths = Files.walk(extraction)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
