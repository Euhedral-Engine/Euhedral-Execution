package io.euhedral_execution.hardware_utils.internal;

import java.util.Objects;

record NativeProduct(
        String id, String operatingSystem, String architecture, String libc, int loadOrder, String resourcePath) {

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
    }

    String filename() {
        return resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
    }
}
