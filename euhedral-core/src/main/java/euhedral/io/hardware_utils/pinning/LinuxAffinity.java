package euhedral.io.hardware_utils.pinning;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class LinuxAffinity extends ThreadPinner {

    public static final LinuxAffinity INSTANCE;

    static {
        LinuxAffinity instance = null;
        try (var in = LinuxAffinity.class.getResourceAsStream(
                "/monitoring/bin/linux_affinity.so")) {

            var tempFile = Files.createTempFile("monitoring_", "linux_affinity.so");
            tempFile.toFile().deleteOnExit();

            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);

            System.load(tempFile.toAbsolutePath().toString());
            instance = new LinuxAffinity();
        } catch (Throwable ignored) {
        }
        INSTANCE = instance;
    }

    private LinuxAffinity() {
    }

    @Override
    public boolean setAffinity(long[] masks) {
        int status = setThreadAffinity(masks);
        if (status != 0) {
            System.err.println("Failed to set thread affinity: ERR_CODE: " + status);
        }

        return status == 0;
    }

    private static native int setThreadAffinity(long[] masks);
}
