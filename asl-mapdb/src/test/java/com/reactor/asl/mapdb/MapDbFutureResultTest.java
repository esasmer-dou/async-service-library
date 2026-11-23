package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncAdmissionRejectedException;
import com.reactor.asl.core.AsyncCompletionTimeoutException;
import com.reactor.asl.core.AsyncMethodBinding;
import com.reactor.asl.core.AsyncResult;
import com.reactor.asl.core.AsyncResultUnavailableException;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDbFutureResultTest {
    private static final String SERVICE = "quote.service";
    private static final String METHOD = "quote(java.lang.String)";

    /** The governed contract, as a user would declare it. */
    interface Quotes {
        CompletableFuture<String> quote(String symbol);
    }

    @TempDir
    Path tempDir;

    private static MethodRuntime runtime(GovernanceRegistry registry) {
        MethodRuntime runtime = registry.register(new ServiceDescriptor(
                SERVICE, new MethodDescriptor[]{new MethodDescriptor(METHOD, "quote", true, 4, "disabled", true, 0)}
        )).methodById(METHOD);
        runtime.switchMode(ExecutionMode.ASYNC);
        return runtime;
    }

    private static AsyncMethodBinding binding(Function<String, CompletableFuture<String>> implementation) {
        return AsyncMethodBinding.typedFuture(Quotes.class, "quote", new Class<?>[]{String.class},
                arguments -> implementation.apply((String) arguments[0]));
    }

    private MapDbAsyncExecutionEngine engine(String file, MapDbAsyncExecutionOptions.Builder options) {
        return new MapDbAsyncExecutionEngine(tempDir.resolve(file), new JavaObjectStreamAsyncPayloadCodec(), options.build());
    }

    private static void consumers(MapDbAsyncExecutionEngine engine, MethodRuntime runtime, int threads) {
        runtime.setConsumerThreads(threads);
        engine.applyRuntime(runtime);
    }

    @Test
    void callerGetsTheValueOrTheFailureAndCallbacksDoNotRunOnWorkers() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = runtime(registry);
        try (MapDbAsyncExecutionEngine engine = engine("ok.db", MapDbAsyncExecutionOptions.builder())) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, binding(symbol -> symbol.equals("BAD")
                    ? CompletableFuture.failedFuture(new IllegalStateException("no price for " + symbol))
                    : CompletableFuture.supplyAsync(() -> "price:" + symbol)));

            CompletableFuture<String> ok = registry.enqueueAsyncForResult(runtime, new Object[]{"ACME"});
            CompletableFuture<String> bad = registry.enqueueAsyncForResult(runtime, new Object[]{"BAD"});
            List<String> callbackThreads = new CopyOnWriteArrayList<>();
            CompletableFuture<String> seen = ok.thenApply(value -> {
                callbackThreads.add(Thread.currentThread().getName());
                return value;
            });
            assertFalse(ok.isDone(), "nothing runs while the lane has no consumer");
            consumers(engine, runtime, 1);

            assertEquals("price:ACME", seen.get(5, TimeUnit.SECONDS));
            ExecutionException failed = assertThrows(ExecutionException.class, () -> bad.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failed.getCause());
            assertEquals(1, runtime.stats().successCount());
            assertEquals(1, runtime.stats().errorCount());
            assertEquals(1, engine.snapshot(SERVICE, METHOD, 0).failedCount());
            assertFalse(callbackThreads.get(0).toLowerCase().contains("asl"), "callback ran on " + callbackThreads);

            // The caller's future is a copy: completing it does not touch the engine's bookkeeping.
            CompletableFuture<String> third = registry.enqueueAsyncForResult(runtime, new Object[]{"XYZ"});
            third.complete("mine");
            waitFor(() -> runtime.stats().successCount() == 2);
            assertEquals(0, engine.pendingResults());
        }
    }

    @Test
    void aFutureThatNeverCompletesTimesOutAndFailsAsTimeout() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = runtime(registry);
        CompletableFuture<String> never = new CompletableFuture<>();
        try (MapDbAsyncExecutionEngine engine = engine("timeout.db",
                MapDbAsyncExecutionOptions.builder().completionTimeoutMillis(200))) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, binding(symbol -> never));
            consumers(engine, runtime, 1);

            CompletableFuture<String> result = registry.enqueueAsyncForResult(runtime, new Object[]{"SLOW"});
            ExecutionException failed = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(AsyncCompletionTimeoutException.class, failed.getCause());
            assertEquals(1, engine.completionTimeouts());
            assertTrue(never.isCancelled(), "the method's future is told that nobody waits any more");
            assertEquals("TIMEOUT", engine.snapshot(SERVICE, METHOD, 10).entries().get(0).errorCategory());
            assertEquals(0, runtime.stats().inFlight(), "the worker's slot is free again");
        }
    }

    @Test
    void outcomesNobodyWaitsForGoToTheResultHandler() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = runtime(registry);
        List<AsyncResult> handled = new CopyOnWriteArrayList<>();
        try (MapDbAsyncExecutionEngine engine = engine("handler.db",
                MapDbAsyncExecutionOptions.builder().resultWaitTimeoutMillis(300))) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, binding(symbol -> CompletableFuture.completedFuture("late:" + symbol)));
            engine.resultHandler(handled::add);

            CompletableFuture<String> result = registry.enqueueAsyncForResult(runtime, new Object[]{"ACME"});
            ExecutionException failed = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            AsyncResultUnavailableException unavailable = assertInstanceOf(AsyncResultUnavailableException.class, failed.getCause());
            assertEquals(AsyncResultUnavailableException.Reason.WAIT_TIMEOUT, unavailable.reason());
            assertEquals(1, engine.resultWaitTimeouts());

            consumers(engine, runtime, 1);
            waitFor(() -> handled.size() == 1);
            AsyncResult outcome = handled.get(0);
            assertEquals("late:ACME", outcome.value());
            assertTrue(outcome.succeeded());
            assertFalse(outcome.callerWaiting());
            assertEquals(unavailable.invocationId(), outcome.invocationId());
            assertEquals(1, engine.resultsWithoutCaller());
        }
    }

    @Test
    void shutdownTellsTheCallerAndTheRestartedEngineHandsTheOutcomeToTheHandler() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = runtime(registry);
        CompletableFuture<String> result;
        try (MapDbAsyncExecutionEngine engine = engine("restart.db", MapDbAsyncExecutionOptions.builder())) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, binding(symbol -> CompletableFuture.completedFuture("after:" + symbol)));
            result = registry.enqueueAsyncForResult(runtime, new Object[]{"ACME"});
        }
        ExecutionException failed = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        AsyncResultUnavailableException unavailable = assertInstanceOf(AsyncResultUnavailableException.class, failed.getCause());
        assertEquals(AsyncResultUnavailableException.Reason.SHUTDOWN, unavailable.reason());

        GovernanceRegistry restartedRegistry = new GovernanceRegistry();
        MethodRuntime restarted = runtime(restartedRegistry);
        List<AsyncResult> handled = new CopyOnWriteArrayList<>();
        try (MapDbAsyncExecutionEngine engine = engine("restart.db", MapDbAsyncExecutionOptions.builder())) {
            engine.resultHandler(handled::add);
            restartedRegistry.attachAsyncExecutionEngine(engine);
            restartedRegistry.registerAsyncMethod(restarted, binding(symbol -> CompletableFuture.completedFuture("after:" + symbol)));
            consumers(engine, restarted, 1);
            waitFor(() -> handled.size() == 1);
            assertEquals("after:ACME", handled.get(0).value());
            assertEquals(unavailable.invocationId(), handled.get(0).invocationId());
        }
    }

    @Test
    void tooManyWaitingCallersAreRejectedBeforeAnythingIsQueued() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = runtime(registry);
        try (MapDbAsyncExecutionEngine engine = engine("cap.db", MapDbAsyncExecutionOptions.builder().maxPendingResults(1))) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, binding(symbol -> CompletableFuture.completedFuture(symbol)));
            CompletableFuture<String> first = registry.enqueueAsyncForResult(runtime, new Object[]{"A"});
            AsyncAdmissionRejectedException rejected = assertThrows(AsyncAdmissionRejectedException.class,
                    () -> registry.enqueueAsyncForResult(runtime, new Object[]{"B"}));
            assertEquals(AsyncAdmissionRejectedException.Reason.RESULT_CAPACITY, rejected.reason());
            assertEquals(1, engine.snapshot(SERVICE, METHOD, 0).pendingCount(), "the rejected call was not queued");
            assertEquals(1, runtime.rejectedCount());

            consumers(engine, runtime, 1);
            assertEquals("A", first.get(5, TimeUnit.SECONDS));
            assertEquals("B", registry.<String>enqueueAsyncForResult(runtime, new Object[]{"B"}).get(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> engine.enqueueForResult(
                    registry.register(new ServiceDescriptor("plain.service", new MethodDescriptor[]{
                            new MethodDescriptor("run()", "run", true, 1, "", true, 0)})).methodById("run()"),
                    new Object[0], com.reactor.asl.core.AsyncInvocationContext.none()));
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition was not met within timeout");
            }
            Thread.sleep(20);
        }
    }
}
