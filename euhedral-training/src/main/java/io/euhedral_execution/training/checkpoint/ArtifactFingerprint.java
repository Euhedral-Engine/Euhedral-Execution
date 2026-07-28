package io.euhedral_execution.training.checkpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class ArtifactFingerprint {
    public static String sha256(Path artifact) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (Files.isDirectory(artifact)) {
            try (var stream = Files.walk(artifact)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .sorted(java.util.Comparator.comparing(Path::toString)).toList()) {
                    Path relative = artifact.relativize(file);
                    digest.update(relative.toString().replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\t');
                    digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\t');
                    digest.update(Files.readAllBytes(file));
                    digest.update((byte) '\n');
                }
            }
        } else {
            digest.update(Files.readAllBytes(artifact));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private ArtifactFingerprint() {
    }
}
