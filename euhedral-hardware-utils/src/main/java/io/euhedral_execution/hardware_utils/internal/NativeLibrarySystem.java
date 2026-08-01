package io.euhedral_execution.hardware_utils.internal;

import java.nio.file.Path;

@FunctionalInterface
interface NativeLibrarySystem {

    NativeLibrarySystem SYSTEM = library -> System.load(library.toAbsolutePath().toString());

    void load(Path library);
}
