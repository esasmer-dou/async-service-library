package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "catalog.service")
public interface CatalogService {
    @GovernedMethod(initialMaxConcurrency = 5, unavailableMessage = "catalog upsert lane closed")
    String upsertCatalog(String sku);

    @GovernedMethod(initialMaxConcurrency = 2, unavailableMessage = "catalog deactivate lane closed")
    String deactivateCatalog(String sku);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 1, initialConsumerThreads = 0)
    void publishCatalogSnapshot(String sku);

    @Excluded
    String health();
}
