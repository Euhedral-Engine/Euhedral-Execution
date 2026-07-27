package io.euhedral_execution.training.utils;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

@SuppressWarnings("unused")
public class BenchmarkOutputWriter implements AutoCloseable {

    private static final byte[] DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
    };

    private final FileChannel channel;

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(4096);

    public BenchmarkOutputWriter(Path path) throws Exception {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public void writeLine() throws Exception {
        this.buffer.clear();
        this.buffer.put((byte) '\n');
        flush();
    }

    public void writeLine(long num) throws Exception {
        this.buffer.clear();
        writeLongToBuffer(num);
        this.buffer.put((byte) '\n');
        flush();
    }

    public void write(long num) throws Exception {
        this.buffer.clear();
        writeLongToBuffer(num);
        flush();
    }

    public void writeLine(String string) throws Exception {
        write(string);
        writeLine();
    }

    public void write(String string) throws Exception {
        Objects.requireNonNull(string);

        this.buffer.clear();
        for (int i = 0; i < string.length(); i++) {
            this.buffer.put((byte) string.charAt(i));
            if (i == buffer.limit() - 1) {
                this.channel.write(buffer);
                buffer.clear();
            }
        }
        flush();
    }

    public void spaceSeparatedWriteLine(double[] array) throws Exception {
        spaceSeparatedWrite(array);
        writeLine();
    }

    public void spaceSeparatedWrite(double[] array) throws Exception {
        Objects.requireNonNull(array);
        if (array.length == 0) {
            return;
        }

        this.buffer.clear();
        for (int i = 0; i < array.length; i++) {
            writeLongToBuffer(Double.doubleToLongBits(array[i]));
            if (i < array.length - 1) {
                this.buffer.put((byte) ' ');
            }
        }
        flush();
    }

    public void commaSeparatedWriteLine(double[] array) throws Exception {
        commaSeparatedWrite(array);
        writeLine();
    }

    public void commaSeparatedWrite(double[] array) throws Exception {
        Objects.requireNonNull(array);
        if (array.length == 0) {
            throw new RuntimeException("Empty Array");
        }

        this.buffer.clear();
        for (int i = 0; i < array.length; i++) {
            writeLongToBuffer(Double.doubleToLongBits(array[i]));
            if (i < array.length - 1) {
                this.buffer.put((byte) ',');
                this.buffer.put((byte) ' ');
            }
        }
        flush();
    }

    private void flush() throws Exception {
        this.buffer.flip();
        this.channel.write(this.buffer);
    }

    private void writeLongToBuffer(long value) {
        if (value == 0) {
            buffer.put((byte) '0');
            return;
        }

        if (value == Long.MIN_VALUE) {
            buffer.put((byte) '-').put((byte) '9').put((byte) '2').put((byte) '2')
                    .put((byte) '3').put((byte) '3').put((byte) '7').put((byte) '2')
                    .put((byte) '0').put((byte) '3').put((byte) '6').put((byte) '8')
                    .put((byte) '5').put((byte) '4').put((byte) '7').put((byte) '7')
                    .put((byte) '5').put((byte) '8').put((byte) '0').put((byte) '8');
            return;
        }

        boolean isNegative = value < 0;
        if (isNegative) {
            value = -value;
        }

        int length = 0;
        long temp = value;
        while (temp > 0) {
            length++;
            temp /= 10;
        }
        if (isNegative) {
            length++;
        }

        int currentPosition = buffer.position();
        int writeIndex = currentPosition + length - 1;
        temp = value;

        while (temp > 0) {
            buffer.put(writeIndex--, DIGITS[(int) (temp % 10)]);
            temp /= 10;
        }

        if (isNegative) {
            buffer.put(currentPosition, (byte) '-');
        }

        buffer.position(currentPosition + length);
    }

    public void force() throws Exception {
        this.channel.force(true);
    }

    @Override
    public void close() throws Exception {
        force();
        this.channel.close();
        this.buffer.clear();
    }
}
