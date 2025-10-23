package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import spock.lang.Specification
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.MimeMessage
import java.util.Properties

/**
 * Integration test class for refund email fetching functionality in AmazonOrderFetcher
 * Tests the complete flow of detecting, routing, and parsing refund emails
 */
class AmazonOrderFetcher_Refund_IT extends Specification {
    
    Configuration mockConfig
    AmazonOrderFetcher orderFetcher
    
    // Test email file names
    static final String EKOUAER_REFUND_EMAIL = "Your refund for Ekouaer 2 Pack Womens Pajama.....eml"
    static final String RUBIES_REFUND_EMAIL = "Your refund for Rubies Women's Wizard Of Oz.....eml"
    static final String SAMPEEL_REFUND_EMAIL = "Your refund for SAMPEEL Women's V Neck Color.....eml"
    static final String WIHOLL_REFUND_EMAIL = "Your refund for WIHOLL Long Sleeve Shirts for.....eml"
    
    def setup() {
        mockConfig = Mock(Configuration)
        orderFetcher = new AmazonOrderFetcher(mockConfig)
    }
    
    // ========== Subtask 6.1: Set up integration test with test email resources ==========
    
    def "should load all refund email test resources"() {
        given: "refund email file names"
        List<String> refundEmailFiles = [
            EKOUAER_REFUND_EMAIL,
            RUBIES_REFUND_EMAIL,
            SAMPEEL_REFUND_EMAIL,
            WIHOLL_REFUND_EMAIL
        ]
        
        when: "loading all refund email files"
        List<File> emlFiles = refundEmailFiles.collect { fileName ->
            RefundEmailTestHelper.loadRefundEmailFile(fileName)
        }
        
        then: "all files should exist and be readable"
        emlFiles.size() == 4
        emlFiles.every { it.exists() }
        emlFiles.every { it.canRead() }
        emlFiles.every { it.name.endsWith(".eml") }
    }
    
    def "should create Message objects from refund email files"() {
        given: "refund email files"
        List<File> emlFiles = RefundEmailTestHelper.loadAllRefundEmailFiles()
        
        when: "creating Message objects from files"
        List<Message> messages = emlFiles.collect { emlFile ->
            RefundEmailTestHelper.createMessageFromFile(emlFile)
        }
        
        then: "should create valid Message objects"
        messages.size() == 4
        messages.every { it != null }
        messages.every { it.getSubject() != null }
        messages.every { it.getSubject().startsWith("Your refund for") }
    }
    
    def "should extract text content from all refund emails"() {
        given: "refund email files"
        List<File> emlFiles = RefundEmailTestHelper.loadAllRefundEmailFiles()
        
        when: "extracting text content from all files"
        List<String> contents = emlFiles.collect { emlFile ->
            RefundEmailTestHelper.extractTextPlainContent(emlFile)
        }
        
        then: "should extract non-empty content"
        contents.size() == 4
        contents.every { it != null }
        contents.every { it.length() > 0 }
        contents.every { it.contains("refund") || it.contains("Refund") }
    }
    
    // ========== Subtask 6.2: Test refund detection and routing ==========
    
    def "should identify refund emails by sender address"() {
        given: "refund email messages"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "checking sender addresses"
        List<String> fromAddresses = messages.collect { message ->
            message.getFrom()[0].toString().toLowerCase()
        }
        
        then: "all should be from return@amazon.com"
        fromAddresses.size() == 4
        fromAddresses.every { it.contains("return@amazon.com") }
    }
    
    def "should correctly route refund emails to refund parser"() {
        given: "a refund email message"
        Message refundMessage = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(EKOUAER_REFUND_EMAIL)
        )
        
        when: "parsing the email through parseOrderFromEmail (which should detect and route)"
        AmazonOrder order = orderFetcher.parseOrderFromEmail(refundMessage)
        
        then: "should parse as a refund order"
        order != null
        order.isReturn == true
        order.orderId.startsWith("RETURN-")
        order.totalAmount > 0
    }
    
    def "should route all refund emails to refund parser"() {
        given: "all refund email messages"
        List<Message> refundMessages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all emails through parseOrderFromEmail"
        List<AmazonOrder> orders = refundMessages.collect { message ->
            orderFetcher.parseOrderFromEmail(message)
        }.findAll { it != null }
        
        then: "all should be parsed as refund orders"
        orders.size() == 4
        orders.every { it.isReturn == true }
        orders.every { it.orderId.startsWith("RETURN-") }
        orders.every { it.totalAmount > 0 }
    }
    
    def "should handle mixed order and refund emails correctly"() {
        given: "a mix of refund emails and mock regular order email"
        List<Message> refundMessages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        // Create a mock regular order email
        Message mockOrderEmail = Mock(Message)
        mockOrderEmail.getSubject() >> "Your Amazon.com order of 'Test Product' has shipped"
        mockOrderEmail.getFrom() >> [new javax.mail.internet.InternetAddress("order-confirmation@amazon.com")]
        mockOrderEmail.getSentDate() >> new Date()
        mockOrderEmail.getContent() >> """
Order #123-4567890-1234567
Ordered on January 15, 2024

* Test Product Quantity: 1 29.99 USD

Total Amount: \$29.99
"""
        
        List<Message> mixedMessages = refundMessages + [mockOrderEmail]
        
        when: "parsing all emails"
        List<AmazonOrder> orders = mixedMessages.collect { message ->
            orderFetcher.parseOrderFromEmail(message)
        }.findAll { it != null }
        
        then: "should correctly identify refunds and regular orders"
        orders.size() == 5
        
        // 4 refund orders
        def refundOrders = orders.findAll { it.isReturn == true }
        refundOrders.size() == 4
        refundOrders.every { it.orderId.startsWith("RETURN-") }
        refundOrders.every { it.totalAmount > 0 }
        
        // 1 regular order
        def regularOrders = orders.findAll { it.isReturn == false }
        regularOrders.size() == 1
        regularOrders.every { !it.orderId.startsWith("RETURN-") }
        regularOrders.every { it.totalAmount < 0 } // Regular orders are negative
    }
    
    def "should not misidentify regular order emails as refunds"() {
        given: "a mock regular order email from order-confirmation@amazon.com"
        Message mockOrderEmail = Mock(Message)
        mockOrderEmail.getSubject() >> "Your Amazon.com order has shipped"
        mockOrderEmail.getFrom() >> [new javax.mail.internet.InternetAddress("order-confirmation@amazon.com")]
        mockOrderEmail.getSentDate() >> new Date()
        mockOrderEmail.getContent() >> """
Order #123-4567890-1234567
Ordered on January 15, 2024

* Test Product Quantity: 1 29.99 USD

Total Amount: \$29.99
"""
        
        when: "parsing the regular order email"
        AmazonOrder order = orderFetcher.parseOrderFromEmail(mockOrderEmail)
        
        then: "should parse as a regular order, not a refund"
        order != null
        order.isReturn == false
        !order.orderId.startsWith("RETURN-")
        order.totalAmount < 0 // Regular orders are negative
    }
    
    // ========== Subtask 6.3: Test end-to-end refund processing ==========
    
    def "should fetch and parse refunds from email end-to-end"() {
        given: "refund email messages"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "processing all refund emails end-to-end"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseOrderFromEmail(message)
        }.findAll { it != null }
        
        then: "should successfully parse all refunds with complete data"
        refundOrders.size() == 4
        
        // Verify all refunds have required fields
        refundOrders.every { order ->
            order.orderId != null &&
            order.orderId.startsWith("RETURN-") &&
            order.orderDate != null &&
            order.totalAmount != null &&
            order.totalAmount > 0 &&
            order.isReturn == true &&
            order.items != null &&
            order.items.size() == 1 &&
            order.items[0].title != null &&
            order.items[0].title != "Amazon Refund" // Should have actual product names
        }
    }
    
    def "should parse refund with all expected fields populated"() {
        given: "a refund email message"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(EKOUAER_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseOrderFromEmail(message)
        
        then: "should have all expected fields populated"
        refundOrder != null
        
        // Order-level fields
        refundOrder.orderId != null
        refundOrder.orderId.startsWith("RETURN-")
        refundOrder.orderId.length() > 7 // "RETURN-" + order ID
        
        refundOrder.orderDate != null
        refundOrder.orderDate.matches(/\d{4}-\d{2}-\d{2}/) // YYYY-MM-DD format
        
        refundOrder.totalAmount != null
        refundOrder.totalAmount > 0
        refundOrder.totalAmount instanceof BigDecimal
        
        refundOrder.isReturn == true
        
        // Item-level fields
        refundOrder.items != null
        refundOrder.items.size() == 1
        refundOrder.items[0].title != null
        refundOrder.items[0].title.length() > 5
        refundOrder.items[0].price != null
        refundOrder.items[0].price > 0
        refundOrder.items[0].quantity == 1
    }
    
    def "should integrate refund parsing with existing order fetching"() {
        given: "a mix of refund and regular order emails"
        List<Message> refundMessages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        // Create mock regular order emails
        Message mockOrder1 = Mock(Message)
        mockOrder1.getSubject() >> "Your Amazon.com order has shipped"
        mockOrder1.getFrom() >> [new javax.mail.internet.InternetAddress("order-confirmation@amazon.com")]
        mockOrder1.getSentDate() >> new Date()
        mockOrder1.getContent() >> """
Order #111-1111111-1111111
Ordered on January 15, 2024

* Product A Quantity: 1 19.99 USD

Total Amount: \$19.99
"""
        
        Message mockOrder2 = Mock(Message)
        mockOrder2.getSubject() >> "Your Amazon.com order has shipped"
        mockOrder2.getFrom() >> [new javax.mail.internet.InternetAddress("order-confirmation@amazon.com")]
        mockOrder2.getSentDate() >> new Date()
        mockOrder2.getContent() >> """
Order #222-2222222-2222222
Ordered on January 20, 2024

* Product B Quantity: 1 39.99 USD

Total Amount: \$39.99
"""
        
        List<Message> allMessages = refundMessages + [mockOrder1, mockOrder2]
        
        when: "parsing all emails"
        List<AmazonOrder> allOrders = allMessages.collect { message ->
            orderFetcher.parseOrderFromEmail(message)
        }.findAll { it != null }
        
        then: "should correctly parse both refunds and regular orders"
        allOrders.size() == 6
        
        // Verify refunds
        def refunds = allOrders.findAll { it.isReturn == true }
        refunds.size() == 4
        refunds.every { it.orderId.startsWith("RETURN-") }
        refunds.every { it.totalAmount > 0 }
        
        // Verify regular orders
        def regularOrders = allOrders.findAll { it.isReturn == false }
        regularOrders.size() == 2
        regularOrders.every { !it.orderId.startsWith("RETURN-") }
        regularOrders.every { it.totalAmount < 0 }
        
        // Verify no cross-contamination
        refunds.every { it.orderId != regularOrders[0].orderId && it.orderId != regularOrders[1].orderId }
    }
    
    def "should parse refunds with correct amounts matching email content"() {
        given: "all refund email messages"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseOrderFromEmail(message)
        }.findAll { it != null }
        
        then: "should extract reasonable refund amounts"
        refundOrders.size() == 4
        refundOrders.every { order ->
            order.totalAmount > 0 &&
            order.totalAmount < 1000 && // Reasonable upper bound
            order.totalAmount == order.items[0].price // Amount should match item price
        }
    }
    
    def "should parse refunds with RETURN prefix on order IDs"() {
        given: "all refund email messages"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseOrderFromEmail(message)
        }.findAll { it != null }
        
        then: "should have RETURN- prefix on all order IDs"
        refundOrders.size() == 4
        def orderIds = refundOrders.collect { it.orderId }
        orderIds.every { it.startsWith("RETURN-") }
        // Note: Some refunds may share the same original order ID if they're from the same order
    }
    
    def "should handle refund email processing without errors"() {
        given: "all refund email messages"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = []
        List<Exception> errors = []
        
        messages.each { message ->
            try {
                AmazonOrder order = orderFetcher.parseOrderFromEmail(message)
                if (order != null) {
                    refundOrders.add(order)
                }
            } catch (Exception e) {
                errors.add(e)
            }
        }
        
        then: "should process all without throwing exceptions"
        errors.isEmpty()
        refundOrders.size() == 4
    }
}
