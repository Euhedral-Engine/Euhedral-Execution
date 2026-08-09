package io.euhedral_execution.hardware_utils.macos.sysctl;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/// Provider interface for querying macOS sysctl keys.
@FunctionalInterface
public interface SysctlProvider {

    /// Query raw sysctl key value.
    Optional<Object> queryRaw(String key);

    /// Query sysctl integer value.
    default OptionalInt getInt(String key) {
        Optional<Object> val = queryRaw(key);
        if (val.isPresent() && val.get() instanceof Number n) {
            return OptionalInt.of(n.intValue());
        }
        return OptionalInt.empty();
    }

    /// Query sysctl long value.
    default OptionalLong getLong(String key) {
        Optional<Object> val = queryRaw(key);
        if (val.isPresent() && val.get() instanceof Number n) {
            return OptionalLong.of(n.longValue());
        }
        return OptionalLong.empty();
    }

    /// Query sysctl string value.
    default Optional<String> getString(String key) {
        Optional<Object> val = queryRaw(key);
        if (val.isPresent() && val.get() instanceof String s) {
            return Optional.of(s);
        }
        return Optional.empty();
    }
}
