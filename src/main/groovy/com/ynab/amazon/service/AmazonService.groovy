package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service class for handling Amazon order data
 * Note: Amazon doesn't provide a public API for order history, so this service
 * reads from a CSV file that you can export from your Amazon account
 */
class AmazonService {
    private static final Logger logger = LoggerFactory.getLogger(AmazonService.class)
    
    private final Configuration config
    
    AmazonService(Configuration config) {
        this.config = config
    }
    
    /**
     * Get Amazon orders from CSV file
     * You can export your Amazon order history as CSV from:
     * Amazon.com -> Your Account -> Your Orders -> Download order reports
     */
    List<AmazonOrder> getOrders() {
        try {
            File csvFile = new File(config.amazonCsvFilePath)
            if (!csvFile.exists()) {
                logger.warn("Amazon CSV file not found: ${config.amazonCsvFilePath}")
                logger.info("Please export your Amazon order history as CSV and update the config file")
                return []
            }
            
            List<AmazonOrder> orders = []
            Map<String, AmazonOrder> orderMap = [:]
            
            // Read CSV file
            csvFile.eachLine { line, lineNumber ->
                if (lineNumber == 1) {
                    // Skip header row
                    return
                }
                
                def fields = parseCsvLine(line)
                if (fields.size() < 5) {
                    logger.warn("Skipping invalid line ${lineNumber}: ${line}")
                    return
                }
                
                try {
                    String orderId = fields[0]?.trim()
                    String orderDate = fields[1]?.trim()
                    String title = fields[2]?.trim()
                    String priceStr = fields[3]?.trim()
                    String quantityStr = fields[4]?.trim()
                    
                    if (!orderId || !orderDate) {
                        return
                    }
                    
                    // Parse price and quantity
                    BigDecimal price = parsePrice(priceStr)
                    int quantity = parseQuantity(quantityStr)
                    
                    // Create or get existing order
                    AmazonOrder order = orderMap.get(orderId)
                    if (!order) {
                        order = new AmazonOrder()
                        order.orderId = orderId
                        order.orderDate = orderDate
                        order.totalAmount = 0
                        orderMap[orderId] = order
                        orders.add(order)
                    }
                    
                    // Create order item
                    AmazonOrderItem item = new AmazonOrderItem()
                    item.title = title
                    item.price = price
                    item.quantity = quantity
                    
                    order.addItem(item)
                    order.totalAmount += item.getTotalPrice()
                    
                } catch (Exception e) {
                    logger.warn("Error parsing line ${lineNumber}: ${line}", e)
                }
            }
            
            logger.info("Successfully loaded ${orders.size()} Amazon orders from CSV")
            return orders
            
        } catch (Exception e) {
            logger.error("Error loading Amazon orders", e)
            return []
        }
    }
    
    /**
     * Parse a CSV line, handling quoted fields
     */
    private List<String> parseCsvLine(String line) {
        List<String> fields = []
        StringBuilder currentField = new StringBuilder()
        boolean inQuotes = false
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i)
            
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString())
                currentField = new StringBuilder()
            } else {
                currentField.append(c)
            }
        }
        
        // Add the last field
        fields.add(currentField.toString())
        
        return fields
    }
    
    /**
     * Parse price string to BigDecimal
     */
    private BigDecimal parsePrice(String priceStr) {
        if (!priceStr) return 0
        
        // Remove currency symbols and commas
        String cleanPrice = priceStr.replaceAll(/[^\d.-]/, '')
        
        try {
            return new BigDecimal(cleanPrice)
        } catch (NumberFormatException e) {
            logger.warn("Could not parse price: ${priceStr}")
            return 0
        }
    }
    
    /**
     * Parse quantity string to int
     */
    private int parseQuantity(String quantityStr) {
        if (!quantityStr) return 1
        
        try {
            return Integer.parseInt(quantityStr.trim())
        } catch (NumberFormatException e) {
            logger.warn("Could not parse quantity: ${quantityStr}")
            return 1
        }
    }
    
    /**
     * Create a sample CSV file for testing
     */
    void createSampleCsvFile() {
        try {
            File csvFile = new File(config.amazonCsvFilePath)
            csvFile.parentFile?.mkdirs()
            
            csvFile.text = """Order ID,Order Date,Title,Price,Quantity
123-4567890-1234567,2024-01-15,Wireless Bluetooth Headphones,29.99,1
123-4567890-1234567,2024-01-15,USB-C Charging Cable,12.99,2
123-4567890-2345678,2024-01-20,Kindle Paperwhite,139.99,1
123-4567890-3456789,2024-01-25,Amazon Echo Dot,49.99,1
123-4567890-3456789,2024-01-25,Smart Light Bulb Pack,24.99,1"""
            
            logger.info("Created sample CSV file: ${config.amazonCsvFilePath}")
            
        } catch (Exception e) {
            logger.error("Error creating sample CSV file", e)
        }
    }
} 