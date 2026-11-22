package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncAdmissionRejectedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

final class BoundedStoreWriter implements AutoCloseable {
    @FunctionalInterface
    interface Mutation<T> {
        T apply();
    }

    static final class WriteCommand<T> {
        private final Mutation<T> mutation;
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private T result;
        private RuntimeException localFailure;

        WriteCommand(Mutation<T> mutation) {
            this.mutation = Objects.requireNonNull(mutation, "mutation");
        }

        /**
         * Applies the mutation inside the current batch.
         *
         * <p>Only failures that the store raises <em>before</em> mutating anything are confined to
         * the command: admission rejections and caller-input errors ({@link IllegalArgumentException}).
         * They must never stop the writer, otherwise a single malformed request could disable async
         * admission for the whole process. Storage failures and everything else (consistency
         * violations, exhausted id ranges, unexpected bugs) may leave a partial mutation behind and
         * still fail the batch.</p>
         */
        void apply() {
            try {
                result = mutation.apply();
            } catch (AsyncAdmissionRejectedException exception) {
                if (exception.reason() == AsyncAdmissionRejectedException.Reason.STORAGE_FAILURE) {
                    throw exception;
                }
                localFailure = exception;
            } catch (IllegalArgumentException exception) {
                localFailure = exception;
            }
        }

        boolean committed() {
            if (localFailure == null) {
                completion.complete(result);
                return true;
            } else {
                completion.completeExceptionally(localFailure);
                return false;
            }
        }

        void failed(Throwable failure) {
            completion.completeExceptionally(failure);
        }
    }

    private final MapDbQueueStore store;
    private final ArrayBlockingQueue<WriteCommand<?>> queue;
    private final int batchSize;
    private final long batchWindowNanos;
    private final long operationTimeoutMillis;
    private final long shutdownAwaitMillis;
    private final Object admissionMonitor = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final LongAdder acceptedCommands = new LongAdder();
    private final LongAdder committedCommands = new LongAdder();
    private final LongAdder failedCommands = new LongAdder();
    private final LongAdder batches = new LongAdder();
    private final LongAdder commits = new LongAdder();
    private final LongAdder totalCommitNanos = new LongAdder();
    private final AtomicLong lastCommitNanos = new AtomicLong();
    private final LongAccumulator maxCommitNanos = new LongAccumulator(Long::max, 0L);
    private final Thread writerThread;

    BoundedStoreWriter(MapDbQueueStore store, MapDbAsyncExecutionOptions options) {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(options, "options");
        this.queue = new ArrayBlockingQueue<>(options.writerQueueCapacity());
        this.batchSize = options.commitBatchSize();
        this.batchWindowNanos = TimeUnit.MILLISECONDS.toNanos(options.commitBatchWindowMillis());
        this.operationTimeoutMillis = options.writerOperationTimeoutMillis();
        this.shutdownAwaitMillis = options.workerShutdownAwaitMillis();
        this.writerThread = new Thread(this::run, "asl-mapdb-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    <T> T execute(Mutation<T> mutation, long admissionTimeoutMillis) {
        WriteCommand<T> command = new WriteCommand<>(mutation);
        if (!accepting.get()) {
            throw rejection(AsyncAdmissionRejectedException.Reason.ENGINE_CLOSED, "MapDB writer is stopping");
        }

        boolean accepted;
        try {
            accepted = admissionTimeoutMillis <= 0
                    ? queue.offer(command)
                    : queue.offer(command, admissionTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw rejection(
                    AsyncAdmissionRejectedException.Reason.WRITER_SATURATED,
                    "Interrupted while waiting for MapDB writer capacity",
                    exception
            );
        }

        if (accepted) {
            synchronized (admissionMonitor) {
                // Close may have started while offer was waiting. Remove the command if the
                // writer has not claimed it; otherwise it is already part of the drain set.
                if (!accepting.get() && queue.remove(command)) {
                    accepted = false;
                } else {
                    acceptedCommands.increment();
                }
            }
        }
        if (!accepted) {
            if (!accepting.get()) {
                throw rejection(AsyncAdmissionRejectedException.Reason.ENGINE_CLOSED, "MapDB writer is stopping");
            }
            throw rejection(
                    AsyncAdmissionRejectedException.Reason.WRITER_SATURATED,
                    "MapDB writer queue reached its bounded capacity"
            );
        }
        try {
            return command.completion.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw rejection(
                    AsyncAdmissionRejectedException.Reason.STORAGE_FAILURE,
                    "Interrupted while awaiting durable MapDB commit; enqueue outcome may be unknown",
                    exception
            );
        } catch (TimeoutException exception) {
            throw rejection(
                    AsyncAdmissionRejectedException.Reason.STORAGE_FAILURE,
                    "Timed out awaiting durable MapDB commit; enqueue outcome may be unknown",
                    exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("MapDB writer command failed", cause);
        }
    }

    MapDbWriterMetrics metrics() {
        long commitCount = commits.sum();
        long totalNanos = totalCommitNanos.sum();
        return new MapDbWriterMetrics(
                acceptedCommands.sum(),
                committedCommands.sum(),
                failedCommands.sum(),
                batches.sum(),
                commitCount,
                queue.size(),
                lastCommitNanos.get(),
                maxCommitNanos.get(),
                commitCount == 0 ? 0.0d : (double) totalNanos / commitCount
        );
    }

    boolean isAccepting() {
        return accepting.get();
    }

    private void run() {
        List<WriteCommand<?>> batch = new ArrayList<>(batchSize);
        try {
            while (accepting.get() || !queue.isEmpty()) {
                WriteCommand<?> first;
                try {
                    first = accepting.get() ? queue.poll(250L, TimeUnit.MILLISECONDS) : queue.poll();
                } catch (InterruptedException interrupted) {
                    if (!accepting.get()) {
                        continue;
                    }
                    Thread.currentThread().interrupt();
                    break;
                }
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                collectBatch(batch);
                executeBatch(batch);
            }
        } finally {
            stopped.set(true);
            AsyncAdmissionRejectedException failure = rejection(
                    AsyncAdmissionRejectedException.Reason.ENGINE_CLOSED,
                    "MapDB writer stopped before command execution"
            );
            WriteCommand<?> pending;
            while ((pending = queue.poll()) != null) {
                pending.failed(failure);
                failedCommands.increment();
            }
        }
    }

    private void collectBatch(List<WriteCommand<?>> batch) {
        queue.drainTo(batch, batchSize - batch.size());
        if (batch.size() >= batchSize || batchWindowNanos == 0L) {
            return;
        }
        long deadline = System.nanoTime() + batchWindowNanos;
        while (batch.size() < batchSize) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            try {
                WriteCommand<?> command = queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (command == null) {
                    return;
                }
                batch.add(command);
                queue.drainTo(batch, batchSize - batch.size());
            } catch (InterruptedException interrupted) {
                if (accepting.get()) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        }
    }

    private void executeBatch(List<WriteCommand<?>> batch) {
        try {
            long commitNanos = store.executeBatch(batch);
            batches.increment();
            if (commitNanos > 0L) {
                commits.increment();
                totalCommitNanos.add(commitNanos);
                lastCommitNanos.set(commitNanos);
                maxCommitNanos.accumulate(commitNanos);
            }
            for (WriteCommand<?> command : batch) {
                if (command.committed()) {
                    committedCommands.increment();
                } else {
                    failedCommands.increment();
                }
            }
        } catch (Throwable failure) {
            failedCommands.add(batch.size());
            for (WriteCommand<?> command : batch) {
                command.failed(failure);
            }
            synchronized (admissionMonitor) {
                accepting.set(false);
            }
        }
    }

    @Override
    public void close() {
        synchronized (admissionMonitor) {
            accepting.set(false);
            writerThread.interrupt();
        }
        try {
            writerThread.join(shutdownAwaitMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping MapDB writer", exception);
        }
        if (!stopped.get()) {
            throw new IllegalStateException("MapDB writer did not stop within " + shutdownAwaitMillis + " ms");
        }
    }

    private static AsyncAdmissionRejectedException rejection(
            AsyncAdmissionRejectedException.Reason reason,
            String message
    ) {
        return new AsyncAdmissionRejectedException(reason, message);
    }

    private static AsyncAdmissionRejectedException rejection(
            AsyncAdmissionRejectedException.Reason reason,
            String message,
            Throwable cause
    ) {
        return new AsyncAdmissionRejectedException(reason, message, cause);
    }
}
