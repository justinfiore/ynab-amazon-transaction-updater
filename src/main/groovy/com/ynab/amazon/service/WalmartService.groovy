package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder

/** Order-fetching boundary for the pending Go-backed Walmart integration. */
class WalmartService {
    private final Configuration config

    WalmartService(Configuration config) {
        this.config = config
    }

    List<WalmartOrder> getOrders() {
        if (!config.walmartEnabled) return []
        return new GoWalmartOrderFetcher(config).fetchOrders()
    }
}
