package io.euhedral_execution.training;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class BenchmarkRawDataReader implements AutoCloseable {

    private final FileChannel channel;

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(4096);

    public BenchmarkRawDataReader(Path path) throws Exception {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.buffer.limit(0);
    }

    public double[] readDoubleArray() throws Exception {
        int b;
        while (true) {
            b = peek();
            if (b == -1) {
                return null;
            }
            if (b == '\n') {
                next();
                continue;
            }
            break;
        }

        java.util.ArrayList<Double> values = new java.util.ArrayList<>();

        while (true) {
            long bits = readLongToken();
            values.add(Double.longBitsToDouble(bits));
            b = peek();
            if (b == -1 || b == '\n') {
                consumeUntilEol();
                break;
            }
        }

        double[] array = new double[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    private long readLongToken() throws Exception {
        int b;

        while (true) {
            b = peek();
            if (b == -1) {
                throw new IllegalStateException("Unexpected EOF while reading token");
            }
            if (b == ' ' || b == '\t' || b == '\r') {
                next();
            } else if (b == '\n') {
                throw new IllegalStateException("Unexpected newline before token");
            } else {
                break;
            }
        }

        boolean negative = false;
        if (b == '-') {
            negative = true;
            next();
            b = peek();
        }

        long value = 0L;
        boolean hasDigit = false;

        while (b >= '0' && b <= '9') {
            hasDigit = true;
            next();
            value = value * 10 + (b - '0');
            b = peek();
            if (b == -1) {
                break;
            }
        }

        if (!hasDigit) {
            throw new IllegalStateException("Expected digit in token");
        }

        if (negative) {
            value = -value;
        }

        return value;
    }

    private void consumeUntilEol() throws Exception {
        while (true) {
            int b = peek();
            if (b == -1) {
                return;
            }
            if (b == '\n') {
                next();
                return;
            }
            next();
        }
    }

    private boolean read() throws Exception {
        if (buffer.hasRemaining()) {
            return true;
        }
        buffer.clear();
        int n = channel.read(buffer);
        if (n <= 0) {
            buffer.limit(0);
            return false;
        }
        buffer.flip();
        return true;
    }

    private int peek() throws Exception {
        if (!read()) {
            return -1;
        }
        return buffer.get(buffer.position()) & 0xFF;
    }

    private int next() throws Exception {
        if (!read()) {
            return -1;
        }
        return buffer.get() & 0xFF;
    }

    @Override
    public void close() throws Exception {
        this.channel.close();
    }
}
