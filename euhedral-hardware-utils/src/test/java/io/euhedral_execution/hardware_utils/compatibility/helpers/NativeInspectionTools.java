package io.euhedral_execution.hardware_utils.compatibility.helpers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Test prerequisites, resolved independently of the build host architecture. */
public final class NativeInspectionTools {
    private NativeInspectionTools() {}

    public static Path llvm(String property, String command, String environment) throws IOException {
        return resolve(System.getProperty(property, ""), command, System.getenv("PATH"), environment);
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
        List<Path> directories = new ArrayList<>();
        if (searchPath != null) {
            for (String entry : searchPath.split(Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) directories.add(Path.of(entry).toAbsolutePath());
            }
        }
        for (Path directory : directories) {
            Path candidate = directory.resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        // Debian/Ubuntu LLVM packages can install only llvm-readobj-N / llvm-objdump-N.
        Pattern versioned = Pattern.compile(Pattern.quote(command) + "-[0-9]+");
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) continue;
            try (var entries = Files.list(directory)) {
                var candidate = entries.filter(Files::isRegularFile)
                        .filter(Files::isExecutable)
                        .filter(p ->
                                versioned.matcher(p.getFileName().toString()).matches())
                        .sorted(Comparator.comparing(Path::toString))
                        .findFirst();
                if (candidate.isPresent()) return candidate.get();
            }
        }
        throw new IllegalStateException("Missing native inspection prerequisite " + command
                + ": install LLVM tools on PATH or set " + environment + " to an absolute executable path");
    }
}
