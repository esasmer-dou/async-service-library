package com.reactor.asl.mapdb;

/**
 * Point-in-time health of the MapDB async engine.
 *
 * @param running            engine not closed and coordinator thread alive
 * @param writerAccepting    the single store writer still accepts commands
 * @param storeFailureType   exception type of a fatal storage failure, or {@code null}
 */
public record MapDbWorkerHealth(
        boolean running,
        int registeredLanes,
        int activeTasks,
        int poolSize,
        int queuedTasks,
        long completedTasks,
        long workerFailures,
        long rejectedSchedules,
        long lastCoordinatorHeartbeatEpochMillis,
        String lastWorkerFailure,
        boolean writerAccepting,
        String storeFailureType
) {
    /** Source-compatible constructor for callers built against the original component list. */
    public MapDbWorkerHealth(
            boolean running,
            int registeredLanes,
            int activeTasks,
            int poolSize,
            int queuedTasks,
            long completedTasks,
            long workerFailures,
            long rejectedSchedules,
            long lastCoordinatorHeartbeatEpochMillis,
            String lastWorkerFailure
    ) {
        this(
                running,
                registeredLanes,
                activeTasks,
                poolSize,
                queuedTasks,
                completedTasks,
                workerFailures,
                rejectedSchedules,
                lastCoordinatorHeartbeatEpochMillis,
                lastWorkerFailure,
                true,
                null
        );
    }

    /** {@code true} only when the engine can both admit and execute async work. */
    public boolean healthy() {
        return running && writerAccepting && storeFailureType == null;
    }
}
