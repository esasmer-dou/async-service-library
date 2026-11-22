package com.reactor.asl.benchmarks;

import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodUnavailableException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class GovernanceDataPlaneBenchmark {
    @State(Scope.Benchmark)
    public static class BenchmarkState {
        private final BenchmarkService direct = new BenchmarkServiceImpl();
        private final BenchmarkService governed = BenchmarkServiceAsl.wrap(
                direct,
                new GovernanceRegistry()
        );
        private final GovernanceRegistry rejectedRegistry = new GovernanceRegistry();
        private final BenchmarkService rejected = BenchmarkServiceAsl.wrap(
                direct,
                rejectedRegistry
        );
        private final String value = "payload";

        @Setup(Level.Trial)
        public void verifySetup() {
            rejectedRegistry.service(BenchmarkServiceAsl.SERVICE_ID)
                    .methodById(BenchmarkServiceAsl.METHOD_0_ID)
                    .disable("benchmark rejection");
            if (!value.equals(governed.unlimited(value))) {
                throw new IllegalStateException("Generated wrapper benchmark setup failed");
            }
        }
    }

    @Benchmark
    public void direct(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(state.direct.unlimited(state.value));
    }

    @Benchmark
    public void excluded(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(state.governed.excluded(state.value));
    }

    @Benchmark
    public void governedUnlimited(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(state.governed.unlimited(state.value));
    }

    @Benchmark
    public void governedBounded(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(state.governed.bounded(state.value));
    }

    @Benchmark
    public void governedException(BenchmarkState state, Blackhole blackhole) {
        try {
            blackhole.consume(state.governed.failing(state.value));
        } catch (BenchmarkServiceImpl.ExpectedBenchmarkException expected) {
            blackhole.consume(expected);
        }
    }

    @Benchmark
    public void governedRejected(BenchmarkState state, Blackhole blackhole) {
        try {
            blackhole.consume(state.rejected.unlimited(state.value));
        } catch (MethodUnavailableException expected) {
            blackhole.consume(expected);
        }
    }
}
