package io.euhedral_execution.training;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

@SuppressWarnings("unused")
public class FileWriter implements  AutoCloseable {
    private static final byte[] DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
    };

    private final FileChannel channel;

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(4096);

    public FileWriter(Path path) throws Exception {
        if(path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public void println(long num) throws Exception {
        this.buffer.clear();
        writeLongToBuffer(num);
        this.buffer.putChar('\n');
        this.buffer.flip();
        this.channel.write(this.buffer);
    }

    public void println(String string) throws Exception {
        Objects.requireNonNull(string);
        if(string.isBlank()) {
            return;
        }

        this.buffer.clear();
        for(int i = 0; i < string.length(); i++) {
            this.buffer.putChar(string.charAt(i));
            if(i == buffer.limit() - 1) {
                this.channel.write(buffer);
                buffer.clear();
            }
        }
        this.buffer.putChar('\n');
        this.buffer.flip();
        this.channel.write(this.buffer);
    }

    public void printlnArraySpaceSeparated(double[] array) throws Exception {
        Objects.requireNonNull(array);
        if(array.length == 0) {
            return;
        }

        this.buffer.clear();
        for(int i = 0; i < array.length; i++) {
            writeLongToBuffer(Double.doubleToLongBits(array[i]));
            if(i < array.length - 1) {
                this.buffer.putChar(' ');
            }
        }
        this.buffer.putChar('\n');
        this.buffer.flip();
        this.channel.write(this.buffer);
    }

    public void printlnArrayCommaSeparated(double[] array) throws Exception {
        Objects.requireNonNull(array);
        if(array.length == 0) {
            return;
        }

        this.buffer.clear();
        for(int i = 0; i < array.length; i++) {
            writeLongToBuffer(Double.doubleToLongBits(array[i]));
            if(i < array.length - 1) {
                this.buffer.putChar(',');
                this.buffer.putChar(' ');
            }
        }
        this.buffer.putChar('\n');
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

    @Override
    public void close() throws Exception {
        this.channel.close();
        this.buffer.clear();
    }
}
