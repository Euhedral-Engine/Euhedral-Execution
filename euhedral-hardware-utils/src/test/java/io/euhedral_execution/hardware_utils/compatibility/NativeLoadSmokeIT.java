package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class NativeLoadSmokeIT {

    private static String classpath(Path smoke) throws Exception {
        List<Path> entries = new ArrayList<>();
        entries.add(smoke);
        try (Stream<Path> paths = Files.walk(smoke)) {
            paths.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(entries::add);
        }
        return String.join(
                System.getProperty("path.separator"),
                entries.stream().map(Path::toString).toList());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void packagedGlibcProductLoadsAndCallsGetCpu() throws Exception {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        assertTrue(
                architecture.equals("amd64") || architecture.equals("x86_64"),
                "Test build-host smoke requires Linux x64");
        Path smoke = Path.of(System.getProperty("smoke.directory"));
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath(smoke));
        command.add("io.euhedral_execution.hardware_utils.internal.NativeLoadSmokeMain");
        command.add("linux-get-cpu");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "native-loader: smoke process timed out");
        assertEquals(0, process.exitValue(), output);
    }
}
