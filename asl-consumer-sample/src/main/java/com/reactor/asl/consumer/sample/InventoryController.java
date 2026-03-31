package com.reactor.asl.consumer.sample;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService inventoryService;
    private final InventoryServiceImpl delegate;

    public InventoryController(InventoryService inventoryService, InventoryServiceImpl delegate) {
        this.inventoryService = inventoryService;
        this.delegate = delegate;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryItemView upsert(@RequestBody UpsertInventoryRequest request) {
        return inventoryService.upsert(request);
    }

    @GetMapping
    public List<InventoryItemView> list() {
        return inventoryService.list();
    }

    @GetMapping("/{sku}")
    public InventoryItemView get(@PathVariable("sku") String sku) {
        return inventoryService.get(sku);
    }

    @PostMapping("/{sku}/reserve")
    public InventoryItemView reserve(@PathVariable("sku") String sku) {
        return inventoryService.reserve(sku);
    }

    @PostMapping("/{sku}/publish-snapshot")
    public Map<String, String> publishSnapshot(@PathVariable("sku") String sku) {
        inventoryService.publishSnapshot(sku);
        return Map.of("status", "accepted", "sku", sku);
    }

    @GetMapping("/snapshot-events")
    public List<String> snapshotEvents() {
        return delegate.snapshotEvents();
    }
}
