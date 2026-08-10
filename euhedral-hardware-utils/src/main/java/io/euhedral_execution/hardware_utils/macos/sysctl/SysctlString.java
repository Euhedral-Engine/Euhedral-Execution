package io.euhedral_execution.hardware_utils.macos.sysctl;

import java.util.Optional;

/// Type-safe string sysctl key query helper.
public final class SysctlString {

    private SysctlString() {}

    /// Query sysctl string value using the default native sysctl provider.
    public static Optional<String> query(String key) {
        return query(SysctlNative.INSTANCE, key);
    }

    /// Query sysctl string value using the specified sysctl provider.
    public static Optional<String> query(SysctlProvider provider, String key) {
        if (provider == null || key == null) {
            return Optional.empty();
        }
        return provider.getString(key);
    }
}
