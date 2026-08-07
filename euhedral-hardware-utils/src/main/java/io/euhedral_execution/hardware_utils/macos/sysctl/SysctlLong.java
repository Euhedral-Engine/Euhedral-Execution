package io.euhedral_execution.hardware_utils.macos.sysctl;

import java.util.OptionalLong;

/// Type-safe long sysctl key query helper.
public final class SysctlLong {

    private SysctlLong() {
    }

    /// Query sysctl long value using the default native sysctl provider.
    public static OptionalLong query(String key) {
        return query(SysctlNative.INSTANCE, key);
    }

    /// Query sysctl long value using the specified sysctl provider.
    public static OptionalLong query(SysctlProvider provider, String key) {
        if (provider == null || key == null) {
            return OptionalLong.empty();
        }
        return provider.getLong(key);
    }
}
