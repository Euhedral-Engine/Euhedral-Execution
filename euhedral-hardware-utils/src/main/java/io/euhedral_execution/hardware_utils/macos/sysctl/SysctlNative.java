package io.euhedral_execution.hardware_utils.macos.sysctl;

import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.macos.MacosSystemLayout;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/// Native sysctl provider delegating to JNI native sysctlbyname wrappers.
public final class SysctlNative implements SysctlProvider {

    public static final SysctlNative INSTANCE = new SysctlNative();

    private final boolean loaded;

    private SysctlNative() {
        boolean isLoaded = false;
        if (OSName.isMacOS()) {
            try {
                JNIClassLoader.load();
                isLoaded = true;
            } catch (Throwable ignored) {
                isLoaded = false;
            }
        }
        this.loaded = isLoaded;
    }

    @Override
    public Optional<Object> queryRaw(String key) {
        if (!loaded || key == null) {
            return Optional.empty();
        }
        OptionalInt intVal = getInt(key);
        if (intVal.isPresent()) {
            return Optional.of(intVal.getAsInt());
        }
        OptionalLong longVal = getLong(key);
        if (longVal.isPresent()) {
            return Optional.of(longVal.getAsLong());
        }
        return Optional.empty();
    }

    @Override
    public OptionalInt getInt(String key) {
        if (!loaded || key == null) {
            return OptionalInt.empty();
        }
        try {
            int val = MacosSystemLayout.getSysctlInt(key);
            if (val > 0) {
                return OptionalInt.of(val);
            }
            long lval = MacosSystemLayout.getSysctlLong(key);
            if (lval > 0 && lval <= Integer.MAX_VALUE) {
                return OptionalInt.of((int) lval);
            }
        } catch (Throwable ignored) {
        }
        return OptionalInt.empty();
    }

    @Override
    public OptionalLong getLong(String key) {
        if (!loaded || key == null) {
            return OptionalLong.empty();
        }
        try {
            long val = MacosSystemLayout.getSysctlLong(key);
            if (val > 0) {
                return OptionalLong.of(val);
            }
            int ival = MacosSystemLayout.getSysctlInt(key);
            if (ival > 0) {
                return OptionalLong.of(ival);
            }
        } catch (Throwable ignored) {
        }
        return OptionalLong.empty();
    }

    @Override
    public Optional<String> getString(String key) {
        if (!loaded || key == null) {
            return Optional.empty();
        }
        try {
            String str = MacosSystemLayout.getSysctlString(key);
            if (str != null && !str.isEmpty()) {
                return Optional.of(str);
            }
        } catch (Throwable ignored) {
        }
        return Optional.empty();
    }
}
