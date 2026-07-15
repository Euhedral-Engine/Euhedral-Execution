package io.euhedral_execution.hardware_utils.internal;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Loads the JNI binary for all OS classes.
///
/// ##### Requirements:
/// The directory must be located in `~/src/main/java/resources`. The file naming must be structured
/// as (os)_jni_(architecture).(file_type)
///
/// ##### Subdirectories:
/// - `./bin/linux/glibc/`
/// - `./bin/linux/musl/`
/// - `./bin/osx/`
/// - `./bin/windows/`
///
/// ##### Example:
/// - **OS**: OSX
/// - **Suffix**: x64 or arm64
/// - **Relative path**: ./bin/osx/osx_jni_x64.dylib
public final class JNIClassLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(JNIClassLoader.class);

    private static final String X86_SUFFIX = "x64";
    private static final String ARM64_SUFFIX = "arm64";
    private static final String[] LINUX_PATH = {"/bin/linux/glibc/", "/bin/linux/musl/"};
    private static final String LINUX_SUFFIX = "so";
    private static final String[] OSX_PATH = {"/bin/osx/"};
    private static final String OSX_SUFFIX = "dylib";
    private static final String[] WIN_PATH = {"/bin/windows/"};
    private static final String WIN_SUFFIX = "dll";

    private static final String FILE_TEMPLATE = "%s_jni_%s.%s";
    private static final Cleaner CLEANER = Cleaner.create();

    static {
        String fileName = format(OSName.CURRENT_OS.name().toLowerCase());
        String[] bases = switch (OSName.CURRENT_OS) {
            case LINUX -> LINUX_PATH;
            case OSX -> OSX_PATH;
            case WINDOWS -> WIN_PATH;
            default -> throw new RuntimeException("Unsupported OS");
        };

        Path tempFile = null;

        String selectedBase = null;
        List<Throwable> errors = new ArrayList<>();
        for (String base : bases) {
            LOGGER.debug("Attempting to load JNI library from {}", base);
            selectedBase = base;
            try (InputStream in =
                    JNIClassLoader.class.getResourceAsStream(base + fileName)) {

                if (in == null) {
                    throw new RuntimeException("Missing resource: " + base + fileName);
                }

                tempFile = Files.createTempFile("resources_", fileName);
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);

                long size = Files.size(tempFile);
                if (size < 1024) {
                    throw new RuntimeException("Native library too small: " + size);
                }

                Files.setPosixFilePermissions(
                        tempFile,
                        PosixFilePermissions.fromString("rwx------")
                );

                System.load(tempFile.toAbsolutePath().toString());
                break;

            } catch (Exception e) {
                errors.add(e);
                LOGGER.debug("Failed to load JNI library from {}", base, e);

                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // We can ignore temp file deletion errors.
                }
                tempFile = null;
            }
        }

        if (tempFile == null) {
            LOGGER.error("[CRITICAL] Unable to load JNI binary {}. Dumping errors.", fileName);
            for (Throwable cause : errors) {
                LOGGER.error(cause.getMessage(), cause);
            }
            throw new ExceptionInInitializerError("Failed to load the JNI library for OS: " + OSName.CURRENT_OS);
        }
        LOGGER.info("Using JNI library: " + selectedBase + fileName + " for OS: " + OSName.CURRENT_OS);
        Path finalLoaded = tempFile;

        CLEANER.register(JNIClassLoader.class, () -> {
            try {
                Files.deleteIfExists(finalLoaded);
            } catch (Exception ignored) {
                // We can ignore temp file deletion errors.
            }
        });
    }

    public static void load() {
        // Intentionally empty to trigger static initializer block
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

    private JNIClassLoader() {

    }
}
