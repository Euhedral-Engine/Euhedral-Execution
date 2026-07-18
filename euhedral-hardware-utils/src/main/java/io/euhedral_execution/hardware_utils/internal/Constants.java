package io.euhedral_execution.hardware_utils.internal;

public class Constants {
    public static final String LOGGER_PREFIX = "euhedral.hardware_utils.";

    public static String getLoggerName(Class<?> clazz) {
        return LOGGER_PREFIX + clazz.getSimpleName();
    }

    private Constants() {

    }
}
