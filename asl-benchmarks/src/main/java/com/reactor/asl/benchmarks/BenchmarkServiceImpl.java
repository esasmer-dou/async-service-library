package com.reactor.asl.benchmarks;

final class BenchmarkServiceImpl implements BenchmarkService {
    @Override
    public String excluded(String value) {
        return value;
    }

    @Override
    public String unlimited(String value) {
        return value;
    }

    @Override
    public String bounded(String value) {
        return value;
    }

    @Override
    public String failing(String value) {
        throw ExpectedBenchmarkException.INSTANCE;
    }

    static final class ExpectedBenchmarkException extends RuntimeException {
        static final ExpectedBenchmarkException INSTANCE = new ExpectedBenchmarkException();

        private ExpectedBenchmarkException() {
            super(null, null, false, false);
        }
    }
}
