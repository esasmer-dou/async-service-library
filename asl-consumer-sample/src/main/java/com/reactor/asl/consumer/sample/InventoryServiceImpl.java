package com.reactor.asl.consumer.sample;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class InventoryServiceImpl implements InventoryService {
    private final ConcurrentMap<String, InventoryRecord> inventory = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> snapshotEvents = new CopyOnWriteArrayList<>();
    private final AsyncScenarioControl snapshotScenario = new AsyncScenarioControl("inventory.publishSnapshot");

    @Override
    public InventoryItemView upsert(UpsertInventoryRequest request) {
        validate(request);
        InventoryRecord record = inventory.compute(request.sku(), (sku, current) -> {
            Instant now = Instant.now();
            if (current == null) {
                return new InventoryRecord(sku, request.title(), request.available(), now);
            }
            current.replaceAvailable(request.available(), now);
            return current;
        });
        return toView(record);
    }

    @Override
    public List<InventoryItemView> list() {
        return inventory.values().stream()
                .sorted(Comparator.comparing(InventoryRecord::sku))
                .map(this::toView)
                .toList();
    }

    @Override
    public InventoryItemView get(String sku) {
        return toView(requireInventory(sku));
    }

    @Override
    public InventoryItemView reserve(String sku) {
        InventoryRecord record = requireInventory(sku);
        record.reserveOne(Instant.now());
        return toView(record);
    }

    @Override
    public void publishSnapshot(String sku) {
        InventoryRecord record = requireInventory(sku);
        snapshotScenario.beforeInvocation("Configured async failure for inventory snapshot: " + sku);
        snapshotEvents.add("SNAPSHOT:" + record.sku() + ":available=" + record.available() + ":reserved=" + record.reserved());
    }

    public List<String> snapshotEvents() {
        return List.copyOf(snapshotEvents);
    }

    public void reset() {
        inventory.clear();
        snapshotEvents.clear();
        snapshotScenario.reset();
    }

    public AsyncScenarioSnapshot configureSnapshotScenario(AsyncScenarioRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        snapshotScenario.configure(request.failuresRemaining(), request.processingDelayMillis());
        return snapshotScenario.snapshot();
    }

    public AsyncScenarioSnapshot snapshotScenario() {
        return snapshotScenario.snapshot();
    }

    private InventoryRecord requireInventory(String sku) {
        InventoryRecord record = inventory.get(sku);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventory item not found: " + sku);
        }
        return record;
    }

    private InventoryItemView toView(InventoryRecord record) {
        return new InventoryItemView(
                record.sku(),
                record.title(),
                record.available(),
                record.reserved(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private static void validate(UpsertInventoryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.sku() == null || request.sku().isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (request.available() < 0) {
            throw new IllegalArgumentException("available must be zero or positive");
        }
    }
}
