package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

import java.util.List;

@GovernedService(id = "inventory.service")
public interface InventoryService {
    @GovernedMethod(initialMaxConcurrency = 5, unavailableMessage = "inventory update lane closed")
    InventoryItemView upsert(UpsertInventoryRequest request);

    @Excluded
    List<InventoryItemView> list();

    @Excluded
    InventoryItemView get(String sku);

    @GovernedMethod(initialMaxConcurrency = 2, unavailableMessage = "inventory reserve lane closed")
    InventoryItemView reserve(String sku);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 1, initialConsumerThreads = 0)
    void publishSnapshot(String sku);
}
