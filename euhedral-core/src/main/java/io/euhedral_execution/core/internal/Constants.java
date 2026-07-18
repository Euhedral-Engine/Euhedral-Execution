package io.euhedral_execution.core.internal;

public class Constants {
    public static final String LOGGER_PREFIX = "euhedral.core.";

    public static String getLoggerName(Class<?> clazz) {
        return LOGGER_PREFIX + clazz.getSimpleName();
    }

    public static String getLoggerName(String name) {
        return LOGGER_PREFIX + name;
    }

    private Constants() {

    }
}
