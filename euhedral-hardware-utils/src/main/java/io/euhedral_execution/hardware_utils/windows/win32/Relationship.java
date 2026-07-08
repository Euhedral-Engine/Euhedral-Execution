package io.euhedral_execution.hardware_utils.windows.win32;

/// [LOGICAL_PROCESSOR_RELATIONSHIP](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ne-winnt-logical_processor_relationship)
public enum Relationship {
    PROCESSOR_CORE(0),
    CACHE(2),
    PROCESSOR_PACKAGE(3),
    UNKNOWN(Integer.MAX_VALUE);

    public final int value;

    Relationship(int value) {
        this.value = value;
    }

    public static Relationship from(int id) {
        switch (id) {
            case 0 -> {
                return PROCESSOR_CORE;
            }
            case 2 -> {
                return CACHE;
            }
            case 3 -> {
                return PROCESSOR_PACKAGE;
            }
            default -> {
                return UNKNOWN;
            }
        }
    }
}
