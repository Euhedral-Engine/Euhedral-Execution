package io.euhedral_execution.training.packaging;

import java.io.IOException;

public final class PackageCollisionException extends IOException {
    public PackageCollisionException(String message) {
        super(message);
    }
}
