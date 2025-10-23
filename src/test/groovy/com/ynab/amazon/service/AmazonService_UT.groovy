package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import spock.lang.Specification
import spock.lang.Unroll
import spock.lang.TempDir

/**
 * Test class for the AmazonService service
 */
class AmazonService_UT extends Specification {

    @TempDir
    File tempFolder
    
    Configuration mockConfig
    File csvFile
    
    def setup() {
        mockConfig = Mock(Configuration)
        
        // Create a temporary CSV file for testing
        csvFile = new File(tempFolder, "amazon_orders.csv")
        csvFile.createNewFile()
    }
    
    def "getOrders should throw exception when neither email nor CSV is configured"() {
        given: "no email or CSV configuration"
        def amazonService = new AmazonService(mockConfig)
        mockConfig.getAmazonEmail() >> null
        mockConfig.getAmazonEmailPassword() >> null
        mockConfig.getAmazonCsvFilePath() >> null
        
        when: "getOrders is called"
        amazonService.getOrders()
        
        then: "an IllegalStateException should be thrown"
        thrown(IllegalStateException)
    }
    
    def "parseCsvLine should handle quoted fields correctly"() {
        given: "a CSV line with quoted fields"
        def amazonService = new AmazonService(mockConfig)
        def line = '123-456,"Item with, comma",29.99,1'
        
        when: "parseCsvLine is called directly with reflection"
        def method = AmazonService.getDeclaredMethod("parseCsvLine", String.class)
        method.setAccessible(true)
        def result = method.invoke(amazonService, line)
        
        then: "correct parsing should occur"
        result == ['123-456', 'Item with, comma', '29.99', '1']
    }
    
    @Unroll
    def "parsePrice should handle various price formats"() {
        given: "a test AmazonService"
        def amazonService = new AmazonService(mockConfig)
        
        when: "parsePrice is called with reflection"
        def method = AmazonService.getDeclaredMethod("parsePrice", String.class)
        method.setAccessible(true)
        def result = method.invoke(amazonService, [input] as Object[])
        
        then: "parsePrice returns correct value"
        result == expected
        
        where:
        input       | expected
        '29.99'     | 29.99
        '$29.99'    | 29.99
        '29.99 USD' | 29.99
        '1,299.99'  | 1299.99
        ''          | 0
        null        | 0
    }
    
    @Unroll
    def "parseQuantity should handle various quantity formats"() {
        given: "a test AmazonService"
        def amazonService = new AmazonService(mockConfig)
        
        when: "parseQuantity is called with reflection"
        def method = AmazonService.getDeclaredMethod("parseQuantity", String.class)
        method.setAccessible(true)
        def result = method.invoke(amazonService, [input] as Object[])
        
        then: "parseQuantity returns correct value"
        result == expected
        
        where:
        input  | expected
        '1'    | 1
        '10'   | 10
        ' 5 '  | 5
        ''     | 1
        null   | 1
        'abc'  | 1  // Should default to 1 for invalid input
    }
    
    def "createSampleCsvFile should create a valid CSV file"() {
        given: "a test AmazonService"
        def amazonService = new AmazonService(mockConfig)
        mockConfig.getAmazonCsvFilePath() >> csvFile.absolutePath
        
        when: "createSampleCsvFile is called"
        amazonService.createSampleCsvFile()
        
        then: "a valid CSV file should be created"
        csvFile.exists()
        csvFile.text.contains("Order ID,Order Date,Title,Price,Quantity")
        csvFile.text.contains("123-4567890-1234567")
    }
    
    // Helper method to create a test CSV file
    private void createTestCsvFile(File file) {
        file.text = """Order ID,Order Date,Title,Price,Quantity
123-4567890-1234567,2024-01-15,Wireless Headphones,29.99,1
123-4567890-1234567,2024-01-15,USB Cable,12.99,2
123-4567890-2345678,2024-01-20,Kindle Paperwhite,139.99,1"""
    }
}
