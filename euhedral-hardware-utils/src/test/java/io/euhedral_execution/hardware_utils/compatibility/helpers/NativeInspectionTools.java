package io.euhedral_execution.hardware_utils.compatibility.helpers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Test prerequisites, resolved independently of the build host architecture. */
public final class NativeInspectionTools {
    private NativeInspectionTools() {}

    public static Path llvm(String property, String command, String environment) throws IOException {
        String configured = System.getProperty(property, "");
        if (configured.isBlank()) {
            configured = System.getenv(environment);
        }
        String searchPath = String.join(
                File.pathSeparator,
                nonblank(
                        System.getenv("PATH"),
                        System.getenv("LLVM_PATH"),
                        System.getenv("LLVM_HOME"),
                        "/usr/lib",
                        "/usr/local/lib"));
        return resolve(configured, command, searchPath, environment);
    }

    private static List<String> nonblank(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                present.add(value);
            }
        }
        return present;
    }

    public static Path resolve(String configured, String command, String searchPath, String environment)
            throws IOException {
        if (configured != null && !configured.isBlank()) {
            Path explicit = Path.of(configured);
            if (!explicit.isAbsolute() || !Files.isRegularFile(explicit) || !Files.isExecutable(explicit)) {
                throw new IllegalStateException(environment + " must name an absolute executable file: " + configured);
            }
            return explicit;
        }
        Set<Path> directories = new LinkedHashSet<>();
        if (searchPath != null) {
            for (String entry : searchPath.split(Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    directories.add(Path.of(entry).toAbsolutePath());
                }
            }
        }
        for (Path directory : directories) {
            Path candidate = directory.resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
            candidate = directory.resolve("bin").resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        /// Debian/Ubuntu packages may expose versioned commands or keep them below an llvm-N root.
        Pattern versioned = Pattern.compile(Pattern.quote(command) + "-[0-9]+");
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var entries = Files.list(directory)) {
                var candidate = entries.filter(Files::isRegularFile)
                        .filter(Files::isExecutable)
                        .filter(p ->
                                versioned.matcher(p.getFileName().toString()).matches())
                        .sorted(Comparator.comparing(Path::toString))
                        .findFirst();
                if (candidate.isPresent()) {
                    return candidate.get();
                }
            }
        }
        Pattern toolchain = Pattern.compile("llvm-[0-9]+(?:\\.[0-9]+)*");
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var entries = Files.list(directory)) {
                var candidate = entries.filter(Files::isDirectory)
                        .filter(p ->
                                toolchain.matcher(p.getFileName().toString()).matches())
                        .map(p -> p.resolve("bin").resolve(command))
                        .filter(Files::isRegularFile)
                        .filter(Files::isExecutable)
                        .sorted(Comparator.comparing(Path::toString))
                        .findFirst();
                if (candidate.isPresent()) {
                    return candidate.get();
                }
            }
        }
        throw new IllegalStateException("Missing native inspection prerequisite " + command
                + ": install LLVM tools on PATH or set " + environment + " to an absolute executable path");
    }
}
