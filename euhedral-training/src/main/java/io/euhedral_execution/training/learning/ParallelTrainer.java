package io.euhedral_execution.training.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class ParallelTrainer {

    static <T> List<T> run(int taskCount, TrainingTask<T> task, Consumer<T> failedRunCleanup) throws Exception {
        if (taskCount <= 0) {
            throw new IllegalArgumentException("Parallel training requires at least one task");
        }
        ArrayList<Future<T>> futures = new ArrayList<>(taskCount);
        try (ExecutorService executor = Executors.newFixedThreadPool(
                taskCount,
                Thread.ofPlatform().name("scenario-model-training-", 0).factory())) {
            for (int index = 0; index < taskCount; index++) {
                int taskIndex = index;
                futures.add(executor.submit(() -> task.run(taskIndex)));
            }

            ArrayList<T> results = new ArrayList<>(taskCount);
            Throwable failure = null;
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failure = error;
                    break;
                } catch (ExecutionException error) {
                    if (failure == null) {
                        failure = error.getCause();
                    }
                }
            }
            if (failure != null) {
                futures.forEach(future -> future.cancel(true));
                for (T result : results) {
                    try {
                        failedRunCleanup.accept(result);
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                rethrow(failure);
            }
            return List.copyOf(results);
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Parallel training failed", failure);
    }

    @FunctionalInterface
    interface TrainingTask<T> {
        T run(int index) throws Exception;
    }

    private ParallelTrainer() {}
}
