package io.euhedral_execution.training.checkpoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class ArtifactFingerprint {
    private static final int BUFFER_SIZE = 128 * 1024;

    private ArtifactFingerprint() {}

    public static String sha256(Path artifact) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (Files.isDirectory(artifact)) {
            digest.update("directory-artifact-v1\n".getBytes(StandardCharsets.UTF_8));
            try (var stream = Files.walk(artifact)) {
                List<Path> paths = stream.sorted(java.util.Comparator.comparing(
                                path -> artifact.relativize(path).toString().replace('\\', '/')))
                        .toList();
                for (Path file : paths) {
                    if (Files.isSymbolicLink(file)) {
                        throw new IllegalArgumentException("Artifact must not contain symlinks");
                    }
                    if (file.equals(artifact) || Files.isDirectory(file)) {
                        continue;
                    }
                    if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalArgumentException("Unsupported artifact entry " + file);
                    }
                    Path relative = artifact.relativize(file);
                    digest.update(relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\t');
                    digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\t');
                    digest.update(fileSha256(file).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
            }
        } else {
            if (Files.isSymbolicLink(artifact)
                    || !Files.isRegularFile(artifact, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Artifact must be a regular file");
            }
            updateFromFile(digest, artifact);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fileSha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        updateFromFile(digest, file);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateFromFile(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read != 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
    }
}
