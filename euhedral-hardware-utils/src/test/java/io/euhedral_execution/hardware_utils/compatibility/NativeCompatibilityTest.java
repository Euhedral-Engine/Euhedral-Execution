package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.ApiSurface.Entry;
import io.euhedral_execution.hardware_utils.compatibility.helpers.ApiSurfaceComparator;
import io.euhedral_execution.hardware_utils.compatibility.helpers.ApiSurfaceReader;
import io.euhedral_execution.hardware_utils.compatibility.helpers.DefectLedger;
import io.euhedral_execution.hardware_utils.compatibility.helpers.TestPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NativeCompatibilityTest {

    private static final Set<String> PRODUCTS = Set.of(
            "/bin/linux/glibc/linux_jni_x64.so",
            "/bin/linux/glibc/linux_jni_arm64.so",
            "/bin/linux/musl/linux_jni_x64.so",
            "/bin/linux/musl/linux_jni_arm64.so",
            "/bin/osx/osx_jni_x64.dylib",
            "/bin/osx/osx_jni_arm64.dylib",
            "/bin/windows/windows_jni_x64.dll",
            "/bin/windows/windows_jni_arm64.dll");

    private static String readNativeSources() throws Exception {
        Path resources = TestPaths.projectDirectory().resolve("src/main/resources");
        StringBuilder contents = new StringBuilder();
        try (Stream<Path> paths = Files.walk(resources)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".cpp")
                            || file.toString().endsWith(".h"))
                    .sorted().toList()) {
                contents.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return contents.toString();
    }

    @Test
    void preservesAggregateProductsAndJavaJniDeclarations() throws Exception {
        ApiSurface contract = ApiSurface.read(TestPaths.resource("native-contract-900d8c50.tsv"));
        Set<String> products = new TreeSet<>(ApiSurface.UTF8_ORDER);
        contract.entries().values().stream()
                .filter(entry -> entry.kind().equals("product"))
                .map(Entry::key)
                .forEach(products::add);
        assertEquals(PRODUCTS, products);

        ApiSurface baselineNatives = new ApiSurface(contract.entries().values().stream()
                .filter(entry -> entry.kind().equals("native"))
                .toList());
        ApiSurface currentNatives = new ApiSurface(
                ApiSurfaceReader.readNativeDeclarations(TestPaths.classesDirectory()));
        CompatibilityReport report = ApiSurfaceComparator.compare(baselineNatives, currentNatives);
        assertTrue(report.passes(), report::render);
        currentNatives.entries().values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        entry -> entry.key().substring(0, entry.key().indexOf('('))))
                .values().stream()
                .filter(overloads -> overloads.size() > 1)
                .flatMap(List::stream)
                .forEach(entry -> assertTrue(entry.value().contains(";long-jni="),
                        () -> "overloaded native lacks a long JNI name: " + entry.key()));

        String nativeSources = readNativeSources();
        DefectLedger ledger = DefectLedger.read(TestPaths.resource("defect-ledger.tsv"));
        for (Entry declaration : baselineNatives.entries().values()) {
            String symbolAndSuffix = declaration.value().substring(
                    declaration.value().indexOf(";jni=") + 5);
            int suffix = symbolAndSuffix.indexOf(';');
            String symbol = suffix < 0 ? symbolAndSuffix : symbolAndSuffix.substring(0, suffix);
            assertTrue(nativeSources.contains(symbol) || ledger.hasSubject("jni:" + symbol),
                    () -> "missing native symbol without exact ledger record: " + symbol);
        }
        assertTrue(ledger.hasSubject(
                "jni:Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution"));
        assertTrue(ledger.hasSubject(
                "jni:Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlString"));
        assertEquals(Set.of("N01", "N02"), contract.entries().values().stream()
                .filter(entry -> entry.kind().equals("native-exception"))
                .map(Entry::key)
                .collect(java.util.stream.Collectors.toSet()));
    }
}
