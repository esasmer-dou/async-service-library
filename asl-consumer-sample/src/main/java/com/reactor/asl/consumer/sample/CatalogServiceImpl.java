package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class CatalogServiceImpl extends AbstractSyntheticServiceSupport implements CatalogService {
    public CatalogServiceImpl() {
        super("catalog");
    }

    @Override
    public String upsertCatalog(String sku) {
        return register(sku, "UPSERTED");
    }

    @Override
    public String deactivateCatalog(String sku) {
        return transition(sku, "DEACTIVATED");
    }

    @Override
    public void publishCatalogSnapshot(String sku) {
        publishPrimary(sku);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
