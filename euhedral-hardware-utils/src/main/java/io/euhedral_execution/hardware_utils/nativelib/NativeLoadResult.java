package io.euhedral_execution.hardware_utils.nativelib;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

record NativeLoadResult(NativeProduct product, Path extractionPath, List<NativeLoadFailure> failures) {

    NativeLoadResult {
        failures = List.copyOf(failures);
    }
}

record NativeLoadFailure(NativeProduct product, @Nullable Path extractionPath, Throwable cause) {}
