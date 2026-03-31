package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "search.service")
public interface SearchIndexService {
    @GovernedMethod(initialMaxConcurrency = 6, unavailableMessage = "search index lane closed")
    String indexDocument(String documentId);

    @GovernedMethod(initialMaxConcurrency = 3, unavailableMessage = "search delete lane closed")
    String removeDocument(String documentId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 2)
    void publishIndexState(String documentId);

    @Excluded
    String health();
}
