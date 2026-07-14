package io.euhedral_execution.hardware_utils.common;

public enum OSName {
    LINUX("Linux"), OSX("MacOS"), WINDOWS("Windows"), UNSUPPORTED("UNSUPPORTED");

    public static final OSName CURRENT_OS;
    public final String os;

    static {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            CURRENT_OS = LINUX;
        } else if (os.contains("mac")) {
            CURRENT_OS = OSX;
        } else if(os.contains("win")) {
            CURRENT_OS = WINDOWS;
        } else {
            CURRENT_OS = UNSUPPORTED;
        }
    }

    public static boolean isLinux() {
        return CURRENT_OS == LINUX;
    }

    public static boolean isMacOS() {
        return CURRENT_OS == OSX;
    }

    public static boolean isWindows() {
        return CURRENT_OS == WINDOWS;
    }

    OSName(String os) {
        this.os = os;
    }

    @Override
    public String toString() {
        return this.os;
    }
}
