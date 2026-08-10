package io.euhedral_execution.training.packaging;

import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

final class CanonicalFileSupport {
    private static final int BUFFER_SIZE = 128 * 1024;

    private CanonicalFileSupport() {}

    static void write(Path path, String value) throws IOException {
        if (!value.endsWith("\n") || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Generated text must use canonical LF");
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    static void copy(Path source, Path target) throws IOException {
        rejectSymlink(source);
        Files.createDirectories(target.getParent());
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(source);
                OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read != 0) output.write(buffer, 0, read);
            }
        }
    }

    static void copyDirectory(Path source, Path target) throws IOException {
        rejectSymlink(source);
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted(Comparator.comparing(
                            item -> source.relativize(item).toString().replace('\\', '/')))
                    .toList()) {
                if (path.equals(source)) continue;
                rejectSymlink(path);
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    copy(path, destination);
                } else {
                    throw new IOException("Unsupported source artifact entry");
                }
            }
        }
    }

    static void forceTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
        }
    }

    static String sha256(Path path) throws IOException {
        return ArtifactFingerprint.sha256(path);
    }

    static CsvMetadata csvMetadata(Path path) throws IOException {
        rejectSymlink(path);
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        long records = 0;
        boolean quoted = false;
        boolean quoteClosed = false;
        boolean firstField = true;
        boolean fieldStart = true;
        boolean sawCharacter = false;
        boolean endedWithLf = false;
        StringBuilder first = new StringBuilder();
        String schemaText = null;
        char[] buffer = new char[BUFFER_SIZE];
        try (Reader reader = new java.io.InputStreamReader(Files.newInputStream(path), decoder)) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    char ch = buffer[index];
                    if (!sawCharacter && ch == '\ufeff' || ch == '\r') {
                        throw new IllegalArgumentException("CSV is not canonical UTF-8/LF");
                    }
                    sawCharacter = true;
                    endedWithLf = false;
                    if (quoted) {
                        if (ch == '"') {
                            quoted = false;
                            quoteClosed = true;
                        } else if (firstField) {
                            first.append(ch);
                        }
                    } else if (quoteClosed) {
                        if (ch == '"') {
                            quoted = true;
                            quoteClosed = false;
                            if (firstField) first.append('"');
                        } else if (ch == ',') {
                            firstField = false;
                            fieldStart = true;
                            quoteClosed = false;
                        } else if (ch == '\n') {
                            schemaText = finishCsvRecord(first, records++, schemaText);
                            firstField = true;
                            fieldStart = true;
                            quoteClosed = false;
                            endedWithLf = true;
                        } else {
                            throw new IllegalArgumentException("Characters after quoted field");
                        }
                    } else if (ch == '"' && fieldStart) {
                        quoted = true;
                        fieldStart = false;
                    } else if (ch == '"') {
                        throw new IllegalArgumentException("Quote in unquoted CSV field");
                    } else if (ch == ',') {
                        firstField = false;
                        fieldStart = true;
                    } else if (ch == '\n') {
                        schemaText = finishCsvRecord(first, records++, schemaText);
                        firstField = true;
                        fieldStart = true;
                        endedWithLf = true;
                    } else {
                        fieldStart = false;
                        if (firstField) first.append(ch);
                    }
                }
            }
        }
        if (!sawCharacter || !endedWithLf || quoted || quoteClosed || records == 0) {
            throw new IllegalArgumentException("Incomplete CSV");
        }
        return new CsvMetadata(
                schemaText == null ? null : schemaText.isEmpty() ? 1 : Integer.parseInt(schemaText), records - 1);
    }

    private static String finishCsvRecord(StringBuilder first, long record, String schemaText) {
        String value = first.toString();
        first.setLength(0);
        if (record == 0) {
            return value.equals("schema_version") ? "" : null;
        }
        if (schemaText == null) return null;
        if (value.isEmpty() || !value.matches("[0-9]+")) {
            throw new IllegalArgumentException("Malformed CSV schema version");
        }
        if (schemaText.isEmpty()) return value;
        if (!schemaText.equals(value)) {
            throw new IllegalArgumentException("Mixed CSV schemas");
        }
        return schemaText;
    }

    static void validateRelative(String path) {
        if (!path.matches("[^/\\\\]+(?:/[^/\\\\]+)*")) {
            throw new IllegalArgumentException("Noncanonical package path");
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Noncanonical package path");
            }
        }
    }

    static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symlinks are not supported");
    }

    static void rejectSymlinkComponents(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("Symlink path components are not supported");
            }
        }
    }

    static void deleteOwnedTree(Path directory) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return;
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) Files.deleteIfExists(path);
                else Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Preserve the original publication failure.
        }
    }

    record CsvMetadata(Integer schemaVersion, long rowCount) {}
}
