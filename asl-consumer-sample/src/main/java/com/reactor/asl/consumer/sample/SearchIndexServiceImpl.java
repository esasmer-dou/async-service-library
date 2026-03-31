package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class SearchIndexServiceImpl extends AbstractSyntheticServiceSupport implements SearchIndexService {
    public SearchIndexServiceImpl() {
        super("search");
    }

    @Override
    public String indexDocument(String documentId) {
        return register(documentId, "INDEXED");
    }

    @Override
    public String removeDocument(String documentId) {
        return transition(documentId, "REMOVED");
    }

    @Override
    public void publishIndexState(String documentId) {
        publishPrimary(documentId);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
