package io.euhedral_execution.hardware_utils.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

final class NativeLibraryLoader {

    private final NativeProductCatalog catalog;
    private final NativeLibraryExtractor extractor;
    private final NativeLibrarySystem librarySystem;
    private final Logger logger;

    NativeLibraryLoader(
            NativeProductCatalog catalog,
            NativeLibraryExtractor extractor,
            NativeLibrarySystem librarySystem,
            Logger logger) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.librarySystem = Objects.requireNonNull(librarySystem, "librarySystem");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private static String diagnostic(String operatingSystem, String architecture, List<NativeLoadFailure> failures) {
        StringBuilder message = new StringBuilder("native-loader: unable to load a native product for ")
                .append(operatingSystem)
                .append('/')
                .append(architecture)
                .append('.');
        for (NativeLoadFailure failure : failures) {
            message.append(" Attempted ")
                    .append(failure.product().resourcePath())
                    .append(" -> ")
                    .append(
                            failure.extractionPath() == null
                                    ? "<extraction unavailable>"
                                    : failure.extractionPath().toAbsolutePath())
                    .append(": ")
                    .append(failure.cause().getClass().getName());
            String causeMessage = sanitize(failure.cause().getMessage());
            if (!causeMessage.isEmpty()) {
                message.append(": ").append(causeMessage);
            }
            message.append('.');
        }
        return message.append(" A noexec mount is a possible cause; retry with "
                        + "-Dio.euhedral.native.extract.dir=<absolute executable filesystem directory>.")
                .toString();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), 240));
        for (int index = 0; index < value.length() && sanitized.length() < 240; index++) {
            char character = value.charAt(index);
            sanitized.append(Character.isISOControl(character) ? ' ' : character);
        }
        return sanitized.toString().strip();
    }

    NativeLoadResult load(String osName, String osArch) {
        String canonicalOs = catalog.canonicalOs(osName);
        String canonicalArchitecture = catalog.canonicalArchitecture(osArch);
        List<NativeProduct> candidates = catalog.select(osName, osArch);
        List<NativeLoadFailure> failures = new ArrayList<>();

        for (NativeProduct candidate : candidates) {
            Path extractionPath = null;
            try {
                extractionPath = extractor.pathFor(candidate);
                logger.debug("Attempting to load JNI product {} from {}", candidate.id(), candidate.resourcePath());
                Path extracted = extractor.extract(candidate);
                librarySystem.load(extracted);
                extractor.loadSucceeded(extracted);
                return new NativeLoadResult(candidate, extracted, List.copyOf(failures));
            } catch (IOException | SecurityException | LinkageError failure) {
                failures.add(new NativeLoadFailure(candidate, extractionPath, failure));
                extractor.candidateFailed(extractionPath);
                logger.debug("Failed to load JNI product {}", candidate.id(), failure);
            }
        }

        extractor.allCandidatesFailed();
        ExceptionInInitializerError error =
                new ExceptionInInitializerError(diagnostic(canonicalOs, canonicalArchitecture, failures));
        failures.forEach(failure -> error.addSuppressed(failure.cause()));
        throw error;
    }
}
