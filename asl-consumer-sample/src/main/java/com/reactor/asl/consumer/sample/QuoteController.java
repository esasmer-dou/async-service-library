package com.reactor.asl.consumer.sample;

import com.reactor.asl.core.AsyncResult;
import com.reactor.asl.core.AsyncResultHandler;
import com.reactor.asl.core.AsyncResultUnavailableException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Calls {@link QuoteService}; works the same whether the method is SYNC or ASYNC. */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {
    private final QuoteService quoteService;
    private final RecentResults recentResults;

    public QuoteController(QuoteService quoteService, RecentResults recentResults) {
        this.quoteService = quoteService;
        this.recentResults = recentResults;
    }

    @GetMapping("/{symbol}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> quote(@PathVariable("symbol") String symbol) {
        return quoteService.quote(symbol).handle((value, failure) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("symbol", symbol);
            if (failure == null) {
                body.put("quote", value);
                return ResponseEntity.ok(body);
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            body.put("error", cause.getClass().getSimpleName());
            body.put("message", cause.getMessage());
            if (cause instanceof AsyncResultUnavailableException unavailable) {
                body.put("invocationId", unavailable.invocationId());
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        });
    }

    /** Outcomes of queued quotes that no caller was waiting for any more (for example after a restart). */
    @GetMapping("/unclaimed")
    public List<Map<String, Object>> unclaimed() {
        return recentResults.list();
    }

    /** Keeps the last 20 outcomes nobody waited for; a real service would store or forward them. */
    public static final class RecentResults implements AsyncResultHandler {
        private final Deque<Map<String, Object>> results = new ArrayDeque<>();

        @Override
        public synchronized void onResult(AsyncResult result) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("invocationId", result.invocationId());
            entry.put("method", result.serviceId() + "::" + result.methodId());
            entry.put("succeeded", result.succeeded());
            entry.put("value", result.succeeded() ? String.valueOf(result.value()) : String.valueOf(result.failure()));
            results.addFirst(entry);
            while (results.size() > 20) {
                results.removeLast();
            }
        }

        synchronized List<Map<String, Object>> list() {
            return List.copyOf(results);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Results {
        @Bean
        RecentResults quoteRecentResults() {
            return new RecentResults();
        }
    }
}
