package io.euhedral_execution.training.checkpoint;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WorkspaceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorkspaceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static WorkspaceLock acquire(Path workspace) throws IOException {
        Files.createDirectories(workspace);
        FileChannel channel = FileChannel.open(workspace.resolve("LOCK"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            throw new IOException("Closed-loop workspace is already locked");
        }
        return new WorkspaceLock(channel, lock);
    }

    @Override
    public void close() throws IOException {
        lock.release();
        channel.close();
    }
}
