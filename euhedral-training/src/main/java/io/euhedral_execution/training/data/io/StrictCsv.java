package io.euhedral_execution.training.data.io;

import java.util.ArrayList;
import java.util.List;

final class StrictCsv {

    static String row(List<String> fields) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            String field = fields.get(i);
            if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                    || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0) {
                result.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                result.append(field);
            }
        }
        return result.append('\n').toString();
    }

    static List<List<String>> parse(String input) {
        if (input.startsWith("\ufeff") || input.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("CSV must be UTF-8 with LF line endings");
        }
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
        return rows;
    }

    static String hex(long value) {
        return String.format("%016x", value);
    }

    private StrictCsv() {
    }
}
