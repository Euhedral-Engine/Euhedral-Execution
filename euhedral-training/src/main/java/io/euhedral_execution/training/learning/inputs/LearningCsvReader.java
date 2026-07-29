package io.euhedral_execution.training.learning.inputs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LearningCsvReader {

    public static List<String[]> read(Path path, String expectedHeader, int expectedColumns)
            throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.endsWith("\n")) {
            throw new IOException("Phase 1 CSV must be LF-terminated: " + path);
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].equals(expectedHeader)) {
            throw new IOException("Unexpected Phase 1 CSV header: " + path);
        }
        ArrayList<String[]> rows = new ArrayList<>(StrictMath.max(0, lines.length - 2));
        for (int line = 1; line < lines.length - 1; line++) {
            if (lines[line].isEmpty()) {
                throw new IOException("Blank Phase 1 CSV row at " + path + ':' + (line + 1));
            }
            if (lines[line].indexOf('"') >= 0 || lines[line].indexOf('\r') >= 0) {
                throw new IOException(
                        "Unexpected quoting or CR in generated Phase 1 CSV at " + path + ':' + (line
                                + 1));
            }
            String[] fields = lines[line].split(",", -1);
            if (fields.length != expectedColumns) {
                throw new IOException(
                        "Expected " + expectedColumns + " fields at " + path + ':' + (line + 1));
            }
            rows.add(fields);
        }
        return List.copyOf(rows);
    }

    private LearningCsvReader() {
    }
}
