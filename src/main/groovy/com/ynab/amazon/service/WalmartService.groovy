package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service class for handling Walmart order data
 * Uses browser automation via WalmartOrderFetcher to fetch orders from walmart.com
 */
class WalmartService {
    private static final Logger logger = LoggerFactory.getLogger(WalmartService.class)
    
    private final Configuration config
    private final WalmartOrderFetcher orderFetcher
    
    WalmartService(Configuration config) {
        this.config = config
        this.orderFetcher = new WalmartOrderFetcher(config)
    }
    
    /**
     * Get Walmart orders using browser automation
     * Validates configuration before attempting to fetch orders
     * @return List of WalmartOrder objects
     */
    List<WalmartOrder> getOrders() {
        logger.info("Starting Walmart order fetch process")
        
        // Validate Walmart configuration
        validateConfiguration()
        
        // Check if Walmart integration is enabled
        if (!config.walmartEnabled) {
            logger.info("Walmart integration is disabled in configuration")
            return []
        }
        
        List<WalmartOrder> orders = []
        
        try {
            logger.info("Fetching Walmart orders via browser automation...")
            orders = orderFetcher.fetchOrders()
            
            if (orders) {
                logger.info("Successfully fetched ${orders.size()} Walmart orders")
                logOrderStatistics(orders)
            } else {
                logger.warn("No Walmart orders found")
            }
            
        } catch (Exception e) {
            logger.error("Error fetching Walmart orders: ${e.message}", e)
            throw new RuntimeException("Failed to fetch Walmart orders", e)
        }
        
        return orders
    }
    
    /**
     * Validate Walmart configuration settings
     * Throws IllegalStateException if required settings are missing
     */
    private void validateConfiguration() {
        if (!config.walmartEnabled) {
            return // No validation needed if disabled
        }
        
        List<String> missingSettings = []
        
        if (!config.walmartEmail) {
            missingSettings.add("walmart.email")
        }
        
        if (!config.walmartPassword) {
            missingSettings.add("walmart.password")
        }
        
        if (missingSettings) {
            String errorMessage = "Walmart integration is enabled but missing required configuration: ${missingSettings.join(', ')}. " +
                                "Please specify these settings in your config.yml file or set walmart.enabled to false."
            logger.error(errorMessage)
            throw new IllegalStateException(errorMessage)
        }
        
        logger.debug("Walmart configuration validated successfully")
    }
    
    /**
     * Log statistics about fetched orders
     * @param orders List of fetched orders
     */
    private void logOrderStatistics(List<WalmartOrder> orders) {
        if (!orders) {
            return
        }
        
        int totalOrders = orders.size()
        int multiChargeOrders = orders.count { it.hasMultipleCharges() }
        int singleChargeOrders = totalOrders - multiChargeOrders
        
        BigDecimal totalAmount = orders.sum { it.totalAmount ?: 0 } as BigDecimal
        int totalItems = orders.sum { it.items?.size() ?: 0 } as int
        
        logger.info("Walmart order statistics:")
        logger.info("  Total orders: ${totalOrders}")
        logger.info("  Single-charge orders: ${singleChargeOrders}")
        logger.info("  Multi-charge orders: ${multiChargeOrders}")
        logger.info("  Total amount: \$${totalAmount}")
        logger.info("  Total items: ${totalItems}")
    }
}
