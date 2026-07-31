package io.euhedral_execution.hardware_utils.compatibility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class ApiSurface {

    static final String BASELINE = "900d8c50";
    static final Comparator<String> UTF8_ORDER = (left, right) -> {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(a.length, b.length);
    };

    static ApiSurface read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0) {
            throw new IOException(path + ": CRLF is not canonical");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || !"format\t1".equals(lines[0])
                || !("baseline\t" + BASELINE).equals(lines[1])) {
            throw new IOException(path + ": unknown compatibility manifest header");
        }
        List<Entry> entries = new ArrayList<>();
        String previous = null;
        for (int i = 2; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                if (i == lines.length - 1) {
                    continue;
                }
                throw new IOException(path + ":" + (i + 1) + ": blank line");
            }
            String[] columns = splitEscaped(lines[i], path, i + 1);
            if (columns.length != 3) {
                throw new IOException(path + ":" + (i + 1) + ": expected three columns");
            }
            String canonical = escape(columns[0]) + '\t' + escape(columns[1]) + '\t'
                    + escape(columns[2]);
            if (!canonical.equals(lines[i])) {
                throw new IOException(path + ":" + (i + 1) + ": noncanonical escaping");
            }
            if (previous != null && UTF8_ORDER.compare(previous, lines[i]) >= 0) {
                throw new IOException(path + ":" + (i + 1) + ": noncanonical ordering");
            }
            previous = lines[i];
            entries.add(new Entry(columns[0], columns[1], columns[2]));
        }
        return new ApiSurface(entries);
    }

    private static String[] splitEscaped(String line, Path path, int lineNumber)
            throws IOException {
        List<String> columns = new ArrayList<>(3);
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (escaped) {
                value.append(switch (character) {
                    case '\\' -> '\\';
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> throw new IOException(
                            path + ":" + lineNumber + ": invalid escape \\" + character);
                });
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '\t') {
                columns.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        if (escaped) {
            throw new IOException(path + ":" + lineNumber + ": trailing escape");
        }
        columns.add(value.toString());
        return columns.toArray(String[]::new);
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("usage: ApiSurface <classes-directory> <output>");
        }
        Path classes = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        ApiSurfaceReader.read(classes).writeCreateNew(output);
    }
    private final NavigableMap<String, Entry> entries;

    ApiSurface(Collection<Entry> entries) {
        NavigableMap<String, Entry> collected = new TreeMap<>(UTF8_ORDER);
        for (Entry entry : entries) {
            Entry old = collected.putIfAbsent(entry.identity(), entry);
            if (old != null) {
                throw new IllegalArgumentException("duplicate manifest key: " + entry.identity());
            }
        }
        this.entries = java.util.Collections.unmodifiableNavigableMap(collected);
    }

    NavigableMap<String, Entry> entries() {
        return this.entries;
    }

    List<Entry> moduleEntries() {
        return this.entries.values().stream()
                .filter(entry -> entry.kind().startsWith("module"))
                .toList();
    }

    String render() {
        StringBuilder output = new StringBuilder("format\t1\nbaseline\t" + BASELINE + "\n");
        this.entries.values().stream()
                .map(Entry::line)
                .sorted(UTF8_ORDER)
                .forEach(line -> output.append(line).append('\n'));
        return output.toString();
    }

    void writeCreateNew(Path path) throws IOException {
        Files.writeString(path, render(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        ApiSurface reread = read(path);
        if (!this.entries.equals(reread.entries)) {
            throw new IOException("generated manifest did not round-trip: " + path);
        }
    }

    record Entry(String kind, String key, String value) {

        String identity() {
            return kind + '\t' + key;
        }

        String line() {
            return escape(kind) + '\t' + escape(key) + '\t' + escape(value);
        }
    }
}
