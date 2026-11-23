package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class QuoteServiceImpl implements QuoteService {
    @Override
    public CompletableFuture<String> quote(String symbol) {
        if (symbol.startsWith("FAIL")) {
            return CompletableFuture.failedFuture(new IllegalStateException("no price for " + symbol));
        }
        // Completes on another thread a little later, like a call to a remote price source.
        long price = Math.abs(symbol.hashCode() % 10_000);
        return CompletableFuture.supplyAsync(() -> symbol + "=" + price / 100 + "." + String.format("%02d", price % 100),
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
    }
}
