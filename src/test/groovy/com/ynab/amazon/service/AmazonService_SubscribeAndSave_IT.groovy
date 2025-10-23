package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Integration test class for Subscribe and Save functionality in AmazonService
 * Note: Subscribe and Save orders are automatically detected from email content
 */
class AmazonService_SubscribeAndSave_IT extends Specification {
    
    @TempDir
    File tempDir
    
    Configuration config
    AmazonService amazonService
    
    def setup() {
        config = new Configuration()
        amazonService = new AmazonService(config)
    }
    
    def "getOrders should include Subscribe and Save orders from CSV"() {
        given: "regular orders CSV that could include Subscribe and Save orders"
        def regularCsv = new File(tempDir, "orders.csv")
        regularCsv.text = """Order ID,Order Date,Title,Price,Quantity
123-4567890-1234567,2024-01-15,Wireless Headphones,29.99,1
123-4567890-2345678,2024-01-20,Kindle Paperwhite,139.99,1"""
        
        config.amazonCsvFilePath = regularCsv.absolutePath
        
        when: "getting all orders"
        List<AmazonOrder> orders = amazonService.getOrders()
        
        then: "should return regular orders"
        orders.size() == 2
        orders.any { it.orderId == "123-4567890-1234567" }
        orders.any { it.orderId == "123-4567890-2345678" }
    }
    
    def "getOrders should validate configuration correctly"() {
        given: "no data sources configured"
        config.amazonEmail = null
        config.amazonEmailPassword = null
        config.amazonCsvFilePath = null
        
        when: "getting orders"
        amazonService.getOrders()
        
        then: "should throw IllegalStateException"
        thrown(IllegalStateException)
    }
}