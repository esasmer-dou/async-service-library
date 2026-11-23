package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

import java.util.concurrent.CompletableFuture;

/**
 * A method that returns {@code CompletableFuture<T>} and can be switched to ASYNC from the admin portal: the call is
 * then queued durably and the caller's future completes when the queued run is done.
 */
@GovernedService(id = "quote.service")
public interface QuoteService {
    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 4, initialConsumerThreads = 1,
            unavailableMessage = "quote lane closed")
    CompletableFuture<String> quote(String symbol);
}
