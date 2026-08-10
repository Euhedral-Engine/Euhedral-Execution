package io.euhedral_execution.training.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactFingerprintTest {
    @TempDir
    Path temp;

    @Test
    void streamsFilesLargerThanTheReusableBufferWithoutChangingSha256() throws Exception {
        byte[] bytes = new byte[300_001];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        Path file = temp.resolve("model/members/member-000/euhedral-scenario-ordinal.index");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        String expected =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(ArtifactFingerprint.sha256(file)).isEqualTo(expected);
    }
}
