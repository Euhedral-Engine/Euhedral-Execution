package io.euhedral_execution.hardware_utils.nativelib;

import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// One JNI product row. `sha256` is the lowercase hex SHA-256 of the installed binary, or `null` for catalogs
/// generated before the hash column existed (the loader then falls back to a size check).
record NativeProduct(
        String id,
        String operatingSystem,
        String architecture,
        String libc,
        int loadOrder,
        String resourcePath,
        @Nullable String sha256) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    NativeProduct(
            String id, String operatingSystem, String architecture, String libc, int loadOrder, String resourcePath) {
        this(id, operatingSystem, architecture, libc, loadOrder, resourcePath, null);
    }

    NativeProduct {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(architecture, "architecture");
        Objects.requireNonNull(libc, "libc");
        Objects.requireNonNull(resourcePath, "resourcePath");
        if (id.isEmpty() || operatingSystem.isEmpty() || architecture.isEmpty() || libc.isEmpty()) {
            throw new IllegalArgumentException("native-loader: product fields must not be empty");
        }
        if (loadOrder <= 0) {
            throw new IllegalArgumentException("native-loader: load order must be positive for " + id);
        }
        if (!resourcePath.startsWith("/bin/") || resourcePath.contains("..") || resourcePath.endsWith("/")) {
            throw new IllegalArgumentException("native-loader: invalid product resource path for " + id);
        }
        if (sha256 != null && !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("native-loader: sha256 must be 64 lowercase hex digits for " + id);
        }
    }

    String filename() {
        return resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
    }
}
