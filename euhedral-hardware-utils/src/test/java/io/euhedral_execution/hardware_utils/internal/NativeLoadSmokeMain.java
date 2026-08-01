package io.euhedral_execution.hardware_utils.internal;

import io.euhedral_execution.hardware_utils.linux.LinuxAffinity;

public final class NativeLoadSmokeMain {

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "usage: NativeLoadSmokeMain <load-only|linux-get-cpu>");
        }
        JNIClassLoader.load();
        switch (arguments[0]) {
            case "load-only" -> {
                // JNI_OnLoad has completed before load() returns.
            }
            case "linux-get-cpu" -> {
                int cpu = LinuxAffinity.INSTANCE.getCpu();
                if (cpu < 0) {
                    throw new IllegalStateException("Linux getCpu returned " + cpu);
                }
            }
            default -> throw new IllegalArgumentException("unknown smoke mode: " + arguments[0]);
        }
    }

    private NativeLoadSmokeMain() {
    }
}
