package io.euhedral_execution.training.data.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CanonicalCsv {
    private CanonicalCsv() {}

    public static List<List<String>> read(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("CSV must be a regular non-symlink file: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))
                || text.startsWith("\ufeff")
                || text.indexOf('\r') >= 0
                || !text.endsWith("\n")) {
            throw new IllegalArgumentException("CSV must be canonical UTF-8 with LF endings");
        }
        return parse(text);
    }

    public static String row(List<String> fields) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            String field = fields.get(i);
            if (field.indexOf(',') >= 0
                    || field.indexOf('"') >= 0
                    || field.indexOf('\r') >= 0
                    || field.indexOf('\n') >= 0) {
                result.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                result.append(field);
            }
        }
        return result.append('\n').toString();
    }

    private static List<List<String>> parse(String input) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean quoteClosed = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < input.length() && input.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                    quoteClosed = true;
                } else {
                    field.append(ch);
                }
            } else if (quoteClosed) {
                if (ch == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    quoteClosed = false;
                } else if (ch == '\n') {
                    row.add(field.toString());
                    rows.add(List.copyOf(row));
                    row.clear();
                    field.setLength(0);
                    quoteClosed = false;
                } else {
                    throw new IllegalArgumentException("Characters after quoted CSV field");
                }
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == '"') {
                throw new IllegalArgumentException("Quote in unquoted CSV field");
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                row.add(field.toString());
                rows.add(List.copyOf(row));
                row.clear();
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        if (quoted || quoteClosed || !row.isEmpty() || !field.isEmpty()) {
            throw new IllegalArgumentException("Incomplete CSV");
        }
        return List.copyOf(rows);
    }
}
