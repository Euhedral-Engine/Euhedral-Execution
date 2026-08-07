package io.euhedral_execution.hardware_utils.macos.sysctl;

import java.util.OptionalInt;

/// Type-safe integer sysctl key query helper.
public final class SysctlInt {

    private SysctlInt() {
    }

    /// Query sysctl integer value using the default native sysctl provider.
    public static OptionalInt query(String key) {
        return query(SysctlNative.INSTANCE, key);
    }

    /// Query sysctl integer value using the specified sysctl provider.
    public static OptionalInt query(SysctlProvider provider, String key) {
        if (provider == null || key == null) {
            return OptionalInt.empty();
        }
        return provider.getInt(key);
    }
}
