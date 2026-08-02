package io.euhedral_execution.hardware_utils.internal;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Loads the manifest-selected JNI product exactly once through JVM class initialization.
public final class JNIClassLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            Constants.getLoggerName(JNIClassLoader.class));

    public static void load() {
        NativeLoadResult ignored = Holder.RESULT;
    }

    private static NativeLoadResult initialize() {
        try {
            NativeProductCatalog catalog = NativeProductCatalog.load();
            String osName = System.getProperty("os.name");
            String osArch = System.getProperty("os.arch");
            String canonicalOs = catalog.canonicalOs(osName);
            NativeLibraryExtractor extractor = NativeLibraryExtractor.create(canonicalOs, LOGGER);
            NativeLoadResult result = new NativeLibraryLoader(
                    catalog, extractor, NativeLibrarySystem.SYSTEM, LOGGER).load(osName, osArch);
            LOGGER.info("Using JNI product {} for {}/{}", result.product().id(), canonicalOs,
                    result.product().architecture());
            return result;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            ExceptionInInitializerError error = new ExceptionInInitializerError(
                    failure.getMessage() == null ? "native-loader: initialization failed"
                            : failure.getMessage());
            error.addSuppressed(failure);
            throw error;
        }
    }

    private JNIClassLoader() {
    }

    private static final class Holder {

        private static final NativeLoadResult RESULT = initialize();
    }
}
