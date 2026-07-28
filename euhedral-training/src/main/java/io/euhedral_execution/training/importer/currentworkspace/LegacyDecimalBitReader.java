package io.euhedral_execution.training.importer.currentworkspace;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class LegacyDecimalBitReader implements AutoCloseable {
    private static final int BUFFER_SIZE = 128 * 1024;
    private final InputStream input;
    private final String relativePath;
    private int line = 1;
    private int token = 1;
    private int pushed = -1;
    private boolean sawRecord;

    LegacyDecimalBitReader(Path path, String relativePath) throws IOException {
        input = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE);
        this.relativePath = relativePath;
    }

    long[] nextRecord() throws IOException {
        int first = read();
        if (first < 0) {
            return null;
        }
        if (first == '\n' || first == '\r') {
            throw malformed("empty record");
        }
        java.util.ArrayList<Long> values = new java.util.ArrayList<>(28);
        while (true) {
            unread(first);
            values.add(readLong());
            int delimiter = read();
            if (delimiter == ' ' || delimiter == '\t') {
                do {
                    delimiter = read();
                } while (delimiter == ' ' || delimiter == '\t');
                if (delimiter < 0 || delimiter == '\n' || delimiter == '\r') {
                    throw malformed("trailing whitespace or missing token");
                }
                token++;
                first = delimiter;
                continue;
            }
            if (delimiter == '\r') {
                if (read() != '\n') {
                    throw malformed("CR must be followed by LF");
                }
            } else if (delimiter != '\n') {
                if (delimiter < 0) {
                    throw malformed("missing final LF");
                }
                throw malformed("invalid token delimiter");
            }
            long[] result = new long[values.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = values.get(i);
            }
            sawRecord = true;
            line++;
            token = 1;
            return result;
        }
    }

    private long readLong() throws IOException {
        int value = read();
        boolean negative = value == '-';
        if (negative) {
            value = read();
        }
        if (value < '0' || value > '9') {
            throw malformed("expected signed decimal integer");
        }
        long result = 0;
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplyLimit = limit / 10;
        do {
            int digit = value - '0';
            if (result < multiplyLimit) {
                throw malformed("signed long overflow");
            }
            result *= 10;
            if (result < limit + digit) {
                throw malformed("signed long overflow");
            }
            result -= digit;
            value = read();
        } while (value >= '0' && value <= '9');
        unread(value);
        return negative ? result : -result;
    }

    private int read() throws IOException {
        if (pushed >= 0) {
            int result = pushed;
            pushed = -1;
            return result;
        }
        return input.read();
    }

    private void unread(int value) {
        pushed = value;
    }

    private IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException("%s line %d token %d: %s"
                .formatted(relativePath, line, token, message));
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
