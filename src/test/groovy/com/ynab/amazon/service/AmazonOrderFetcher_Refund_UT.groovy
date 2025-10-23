package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import spock.lang.Specification
import spock.lang.Unroll
import javax.mail.Message
import java.text.SimpleDateFormat

/**
 * Unit tests for refund parsing functionality in AmazonOrderFetcher
 */
class AmazonOrderFetcher_Refund_UT extends Specification {
    
    Configuration mockConfig
    AmazonOrderFetcher orderFetcher
    
    def setup() {
        mockConfig = Mock(Configuration)
        orderFetcher = new AmazonOrderFetcher(mockConfig)
    }
    
    // Test fixtures - refund email file names
    static final String EKOUAER_REFUND_EMAIL = "Your refund for Ekouaer 2 Pack Womens Pajama.....eml"
    static final String RUBIES_REFUND_EMAIL = "Your refund for Rubies Women's Wizard Of Oz.....eml"
    static final String SAMPEEL_REFUND_EMAIL = "Your refund for SAMPEEL Women's V Neck Color.....eml"
    static final String WIHOLL_REFUND_EMAIL = "Your refund for WIHOLL Long Sleeve Shirts for.....eml"
    
    // Test fixture - sample refund email content
    static final String SAMPLE_REFUND_CONTENT = """
Return summary

Refund subtotal                 \$25.99
Promo discount                  -\$5.00
Total refund                    \$20.99

Item to be returned

[Ekouaer 2 Pack Womens Pajama Set](https://www.amazon.com/gp/product/B08XYZHQRS?orderId=111-2222222-3333333)

Order #111-2222222-3333333
"""
    
    static final String SAMPLE_REFUND_NO_TOTAL = """
Return summary

Refund subtotal                 \$15.50

Item to be returned

[Test Product](https://www.amazon.com/gp/product/B08XYZHQRS?orderId=111-2222222-3333333)

Order #111-2222222-3333333
"""
    
    static final String SAMPLE_REFUND_NO_AMOUNT = """
Item to be returned

[Test Product](https://www.amazon.com/gp/product/B08XYZHQRS?orderId=111-2222222-3333333)

Order #111-2222222-3333333
"""
    
    static final String SAMPLE_REFUND_NO_ORDER_ID = """
Return summary

Total refund                    \$20.99

Item to be returned

[Test Product](https://www.amazon.com/gp/product/B08XYZHQRS)
"""
    
    // ========== Tests for Refund Amount Extraction (Subtask 5.2) ==========
    // Note: Testing private methods indirectly through parseRefundFromEmail
    
    def "should extract total refund amount from real email"() {
        given: "a real refund email with total refund amount"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(EKOUAER_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should extract the refund amount"
        refundOrder != null
        refundOrder.totalAmount > 0
    }
    
    def "should handle promo discount deductions in refund amount"() {
        given: "all refund emails which may have promo discounts"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseRefundFromEmail(message)
        }.findAll { it != null }
        
        then: "should extract amounts correctly (handling any promo deductions)"
        refundOrders.size() > 0
        refundOrders.every { it.totalAmount > 0 }
    }
    
    def "should extract refund amounts from all example emails"() {
        given: "all four example refund emails"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseRefundFromEmail(message)
        }.findAll { it != null }
        
        then: "should extract amounts from all emails"
        refundOrders.size() == 4
        refundOrders.every { it.totalAmount > 0 }
        refundOrders.every { it.totalAmount instanceof BigDecimal }
    }
    
    def "should fallback to refund subtotal when total refund not found"() {
        given: "a mock message with only refund subtotal (no total refund)"
        Message mockMessage = Mock(Message)
        mockMessage.getSentDate() >> new Date()
        mockMessage.getSubject() >> "Your refund for Test Product...."
        mockMessage.getContent() >> SAMPLE_REFUND_NO_TOTAL
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(mockMessage)
        
        then: "should extract refund subtotal as the amount"
        refundOrder != null
        refundOrder.totalAmount == new BigDecimal("15.50")
    }
    
    def "should return null when refund amount is missing"() {
        given: "a mock message with no refund amount"
        Message mockMessage = Mock(Message)
        mockMessage.getSentDate() >> new Date()
        mockMessage.getSubject() >> "Your refund for Test Product...."
        mockMessage.getContent() >> SAMPLE_REFUND_NO_AMOUNT
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(mockMessage)
        
        then: "should return null due to missing amount"
        refundOrder == null
    }

    
    // ========== Tests for Product Title Extraction (Subtask 5.3) ==========
    // Note: Testing private methods indirectly through parseRefundFromEmail
    
    def "should extract product title from subject line"() {
        given: "a mock message with product title in subject"
        Message mockMessage = Mock(Message)
        mockMessage.getSentDate() >> new Date()
        mockMessage.getSubject() >> "Your refund for Test Product Name...."
        mockMessage.getContent() >> """
Return summary

Total refund                    \$20.99

Item to be returned

[Test Product Name](https://www.amazon.com/gp/product/B08XYZHQRS?orderId=111-2222222-3333333)

Order #111-2222222-3333333
"""
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(mockMessage)
        
        then: "should extract product title from subject line"
        refundOrder != null
        refundOrder.items[0].title == "Test Product Name"
        !refundOrder.items[0].title.contains("....") // Should remove ellipsis
    }
    
    def "should extract product title from email body"() {
        given: "a mock message with product title in body"
        Message mockMessage = Mock(Message)
        mockMessage.getSentDate() >> new Date()
        mockMessage.getSubject() >> "Your refund for Product...."
        mockMessage.getContent() >> """
Return summary

Total refund                    \$20.99

Item to be returned

[Full Product Title From Body](https://www.amazon.com/gp/product/B08XYZHQRS?orderId=111-2222222-3333333)

Order #111-2222222-3333333
"""
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(mockMessage)
        
        then: "should extract product title from email body"
        refundOrder != null
        refundOrder.items[0].title == "Full Product Title From Body"
    }
    
    def "should prefer body title over subject title"() {
        given: "a mock message with different titles in subject and body"
        Message mockMessage = Mock(Message)
        mockMessage.getSentDate() >> new Date()
        mockMessage.getSubject() >> "Your refund for Truncated Subject Title...."
        mockMessage.getContent() >> """
Return summary

Total refund                    \$20.99

Item to be returned

[Complete Full Product Title From Email Body](https://www.amazon.com/gp/product/B08XYZHQRS?orderId=111-2222222-3333333)

Order #111-2222222-3333333
"""
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(mockMessage)
        
        then: "should prefer the full title from body over truncated subject"
        refundOrder != null
        refundOrder.items[0].title == "Complete Full Product Title From Email Body"
        refundOrder.items[0].title != "Truncated Subject Title"
    }
    
    def "should handle truncated subject titles with ellipsis"() {
        given: "a mock message with truncated subject (ellipsis)"
        Message mockMessage = Mock(Message)
        mockMessage.getSentDate() >> new Date()
        mockMessage.getSubject() >> "Your refund for Product Name With Long Title That Gets Truncated...."
        mockMessage.getContent() >> """
Return summary

Total refund                    \$20.99

Item to be returned

[Product Name With Long Title That Gets Truncated](https://www.amazon.com/gp/product/B08XYZHQRS?orderId=111-2222222-3333333)

Order #111-2222222-3333333
"""
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(mockMessage)
        
        then: "should remove ellipsis from title"
        refundOrder != null
        refundOrder.items[0].title != null
        !refundOrder.items[0].title.contains("....") // Should not include ellipsis
        !refundOrder.items[0].title.contains("...") // Should not include any ellipsis
    }
    
    def "should extract product title from real refund emails"() {
        given: "real refund email messages"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseRefundFromEmail(message)
        }.findAll { it != null }
        
        then: "should extract product titles"
        refundOrders.size() == 4
        refundOrders.every { it.items[0].title != null }
        refundOrders.every { it.items[0].title.length() > 0 }
        refundOrders.every { it.items[0].title != "Amazon Refund" } // Should get actual product names
    }
    
    def "should extract product title from email with truncated subject"() {
        given: "refund email with truncated subject (WIHOLL)"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(WIHOLL_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should extract product title"
        refundOrder != null
        refundOrder.items[0].title != null
        refundOrder.items[0].title.length() > 0
        !refundOrder.items[0].title.contains("....") // Should not include ellipsis
    }
    
    @Unroll
    def "should extract product title from each refund email: #fileName"() {
        given: "a refund email file"
        File emlFile = RefundEmailTestHelper.loadRefundEmailFile(fileName)
        Message message = RefundEmailTestHelper.createMessageFromFile(emlFile)
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should extract product title"
        refundOrder != null
        refundOrder.items[0].title != null
        refundOrder.items[0].title.length() > 5 // Should have meaningful title
        
        where:
        fileName << [
            EKOUAER_REFUND_EMAIL,
            RUBIES_REFUND_EMAIL,
            SAMPEEL_REFUND_EMAIL,
            WIHOLL_REFUND_EMAIL
        ]
    }

    
    // ========== Tests for Order ID Extraction (Subtask 5.4) ==========
    // Note: Testing private methods indirectly through parseRefundFromEmail
    
    def "should extract order ID from real refund emails"() {
        given: "real refund email messages"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseRefundFromEmail(message)
        }.findAll { it != null }
        
        then: "should extract order IDs"
        refundOrders.size() == 4
        refundOrders.every { it.orderId != null }
        refundOrders.every { it.orderId.startsWith("RETURN-") }
    }
    
    def "should add RETURN prefix to extracted order ID"() {
        given: "a refund email message"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(EKOUAER_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should have RETURN- prefix"
        refundOrder != null
        refundOrder.orderId.startsWith("RETURN-")
        refundOrder.orderId.length() > 7 // "RETURN-" plus order ID
    }
    
    def "should extract order ID in Amazon format from all emails"() {
        given: "all refund email messages"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseRefundFromEmail(message)
        }.findAll { it != null }
        
        then: "should extract order IDs in Amazon format (XXX-XXXXXXX-XXXXXXX)"
        refundOrders.size() == 4
        refundOrders.every { order ->
            // Remove RETURN- prefix and check format
            String originalId = order.orderId.substring(7)
            originalId.matches(/\d{3}-\d{7}-\d{7}/)
        }
    }
    
    def "should return null when order ID is missing"() {
        given: "a mock message with no order ID"
        Message mockMessage = Mock(Message)
        mockMessage.getSentDate() >> new Date()
        mockMessage.getSubject() >> "Your refund for Test Product...."
        mockMessage.getContent() >> SAMPLE_REFUND_NO_ORDER_ID
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(mockMessage)
        
        then: "should return null due to missing order ID"
        refundOrder == null
    }

    
    // ========== Tests for Complete Refund Parsing (Subtask 5.5) ==========
    
    def "should parse complete refund from email message"() {
        given: "a refund email message"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(EKOUAER_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should create refund order with correct properties"
        refundOrder != null
        refundOrder.isReturn == true
        refundOrder.totalAmount > 0
        refundOrder.orderId.startsWith("RETURN-")
        refundOrder.orderDate != null
        refundOrder.items.size() == 1
    }
    
    def "should create refund order with RETURN prefix"() {
        given: "a refund email message"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(RUBIES_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should add RETURN- prefix to order ID"
        refundOrder != null
        refundOrder.orderId.startsWith("RETURN-")
        refundOrder.orderId.contains("-") // Should have original order ID format
    }
    
    def "should create refund with positive amount value"() {
        given: "a refund email message"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(SAMPEEL_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should have positive amount for refund"
        refundOrder != null
        refundOrder.totalAmount > 0
        refundOrder.items[0].price > 0
    }
    
    def "should set isReturn flag to true for refunds"() {
        given: "a refund email message"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(WIHOLL_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should set isReturn flag"
        refundOrder != null
        refundOrder.isReturn == true
    }
    
    @Unroll
    def "should parse all four example refund emails: #fileName"() {
        given: "a refund email file"
        File emlFile = RefundEmailTestHelper.loadRefundEmailFile(fileName)
        Message message = RefundEmailTestHelper.createMessageFromFile(emlFile)
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should successfully parse the refund"
        refundOrder != null
        refundOrder.isReturn == true
        refundOrder.totalAmount > 0
        refundOrder.orderId.startsWith("RETURN-")
        refundOrder.orderDate != null
        refundOrder.items.size() == 1
        refundOrder.items[0].title != null
        refundOrder.items[0].title != "Amazon Refund" // Should extract actual product name
        
        where:
        fileName << [
            EKOUAER_REFUND_EMAIL,
            RUBIES_REFUND_EMAIL,
            SAMPEEL_REFUND_EMAIL,
            WIHOLL_REFUND_EMAIL
        ]
    }
    
    def "should handle delayed refund status in real emails"() {
        given: "all refund emails (some may have delayed status)"
        List<Message> messages = RefundEmailTestHelper.createMessagesFromAllRefundFiles()
        
        when: "parsing all refund emails"
        List<AmazonOrder> refundOrders = messages.collect { message ->
            orderFetcher.parseRefundFromEmail(message)
        }.findAll { it != null }
        
        then: "should still parse refunds regardless of status"
        refundOrders.size() == 4
        refundOrders.every { it.totalAmount > 0 }
    }
    
    def "should use email sent date as order date"() {
        given: "a refund email message"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(EKOUAER_REFUND_EMAIL)
        )
        Date sentDate = message.getSentDate()
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
        String expectedDate = sdf.format(sentDate)
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should use email sent date as order date"
        refundOrder != null
        refundOrder.orderDate == expectedDate
    }
    
    def "should include product title in order items"() {
        given: "a refund email message"
        Message message = RefundEmailTestHelper.createMessageFromFile(
            RefundEmailTestHelper.loadRefundEmailFile(EKOUAER_REFUND_EMAIL)
        )
        
        when: "parsing the refund email"
        AmazonOrder refundOrder = orderFetcher.parseRefundFromEmail(message)
        
        then: "should include product title in items"
        refundOrder != null
        refundOrder.items.size() == 1
        refundOrder.items[0].title != null
        refundOrder.items[0].title.length() > 0
        refundOrder.items[0].quantity == 1
    }
}
