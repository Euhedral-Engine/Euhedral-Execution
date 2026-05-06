package euhedral.hardware_utils.common;

import euhedral.hardware_utils.SystemInfo;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Loads the JNI binary for a class.
///
/// ##### Requirements:
/// The directory must be located in `~/src/main/java/resources`. The file naming must be in
/// snake-case and have a suffix for the architecture.
///
/// ##### Subdirectories:
/// - `./bin/linux/`
/// - `./bin/osx/`
/// - `./bin/windows/`
///
/// ##### Example:
/// - **Class**: OSXSystemResources
/// - **Snake-Case**: osx_system_resources
/// - **Suffix**: x64 or arm64
/// - **Relative path**: ./bin/osx/osx_system_resources_x64.dylib
public final class JNIClassLoader {

    private static final String LINUX_PATH = "/bin/linux/";
    private static final String OSX_PATH = "/bin/osx/";
    private static final String WIN_PATH = "/bin/windows/";

    private static final String X86_SUFFIX = "x64";
    private static final String ARM64_SUFFIX = "arm64";

    private static final String LINUX_SUFFIX = "so";
    private static final String OSX_SUFFIX = "dylib";
    private static final String WIN_SUFFIX = "dll";

    private static final String FILE_TEMPLATE = "%s_%s.%s";

    private static final Pattern SNAKE_CASE_PATTERN =
            Pattern.compile("[A-Z]+(?=[A-Z][a-z]|$)|[A-Z]?[a-z]+|[0-9]+");

    private static final Cleaner CLEANER = Cleaner.create();

    public static void load(Class<?> clazz) throws Throwable {
        String dirPath = switch (OSName.CURRENT_OS) {
            case LINUX -> LINUX_PATH;
            case WINDOWS -> WIN_PATH;
            case OSX -> OSX_PATH;
            default -> throw new RuntimeException("Unsupported OS");
        };

        String file = format(toSnakeCase(clazz));
        String fullPath = dirPath + file;

        try (InputStream in = clazz.getResourceAsStream(fullPath)) {
            if (in == null) {
                throw new RuntimeException(fullPath + " not found in resources");
            }

            Path tempFile;

            // Owner-only read, write, and execute permissions.
            if (Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")))
                    .supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
                var attr = PosixFilePermissions.asFileAttribute(perms);
                tempFile = Files.createTempFile("resources_", file, attr);
            } else {
                tempFile = Files.createTempFile("resources_", file);
                var f = tempFile.toFile();
                f.setReadable(true, true);
                f.setWritable(true, true);
                f.setExecutable(true, true);
            }

            Path fileToDelete = tempFile;
            CLEANER.register(clazz, () -> {
                try {
                    Files.deleteIfExists(fileToDelete);
                } catch (Throwable ignored) {
                }
            });

            tempFile.toFile().deleteOnExit();
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);

            System.load(tempFile.toAbsolutePath().toString());
        }
    }

    private static String format(String prefix) {
        String arch = SystemInfo.isX86() ? X86_SUFFIX : ARM64_SUFFIX;
        String suffix = switch (OSName.CURRENT_OS) {
            case LINUX -> LINUX_SUFFIX;
            case WINDOWS -> WIN_SUFFIX;
            case OSX -> OSX_SUFFIX;
            default -> throw new RuntimeException("Unsupported OS");
        };

        return String.format(FILE_TEMPLATE, prefix, arch, suffix);
    }

    private static String toSnakeCase(Class<?> clazz) {
        Matcher matcher = SNAKE_CASE_PATTERN.matcher(clazz.getSimpleName());
        StringJoiner result = new StringJoiner("_");

        while (matcher.find()) {
            result.add(matcher.group().toLowerCase());
        }

        return result.toString();
    }
}
