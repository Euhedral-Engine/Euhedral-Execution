package io.euhedral_execution.hardware_utils.internal;

import java.nio.file.Path;
import java.util.List;

record NativeLoadResult(NativeProduct product, Path extractionPath, List<NativeLoadFailure> failures) {

    NativeLoadResult {
        failures = List.copyOf(failures);
    }
}

record NativeLoadFailure(NativeProduct product, Path extractionPath, Throwable cause) {}
