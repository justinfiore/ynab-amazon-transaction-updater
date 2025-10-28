package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import spock.lang.Specification
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.MimeMessage
import java.text.SimpleDateFormat

/**
 * Unit tests for WalmartGuestOrderFetcher
 */
class WalmartGuestOrderFetcher_UT extends Specification {
    
    Configuration testConfig
    WalmartGuestOrderFetcher fetcher
    
    def setup() {
        testConfig = new Configuration()
        testConfig.amazonEmail = "test@example.com"
        testConfig.amazonEmailPassword = "password"
        testConfig.walmartEmail = "walmart@example.com"
        testConfig.walmartMode = "guest"
        testConfig.walmartHeadless = true
        testConfig.walmartBrowserTimeout = 30000
        testConfig.walmartOrdersUrl = "https://www.walmart.com/orders"
        testConfig.lookBackDays = 30
    }
    
    def "extractOrderId should extract order ID from HTML content with anchor tag"() {
        given: "HTML content with order number in anchor tag"
        def fetcher = new WalmartGuestOrderFetcher(testConfig)
        String htmlContent = 'Order number: <a href="http://example.com">2000139-92737755</a>'
        
        when: "extractOrderId is called"
        String orderId = fetcher.extractOrderId(htmlContent)
        
        then: "order ID should be extracted"
        orderId == "2000139-92737755"
    }
    
    def "extractOrderId should extract order ID with Order # format"() {
        given: "HTML content with 'Order #' format"
        def fetcher = new WalmartGuestOrderFetcher(testConfig)
        String htmlContent = 'Order #: <a href="http://example.com">1234567-12345678</a>'
        
        when: "extractOrderId is called"
        String orderId = fetcher.extractOrderId(htmlContent)
        
        then: "order ID should be extracted"
        orderId == "1234567-12345678"
    }
    
    def "extractOrderId should extract order ID from plain text"() {
        given: "Plain text content with order number"
        def fetcher = new WalmartGuestOrderFetcher(testConfig)
        String textContent = 'Order number: 2000139-92737755 was shipped'
        
        when: "extractOrderId is called"
        String orderId = fetcher.extractOrderId(textContent)
        
        then: "order ID should be extracted"
        orderId == "2000139-92737755"
    }
    
    def "extractOrderId should return null when no order ID found"() {
        given: "Content without order ID"
        def fetcher = new WalmartGuestOrderFetcher(testConfig)
        String content = 'This email has no order information'
        
        when: "extractOrderId is called"
        String orderId = fetcher.extractOrderId(content)
        
        then: "null should be returned"
        orderId == null
    }
    
    def "parseDateString should parse multiple date formats"() {
        given: "WalmartGuestOrderFetcher instance"
        def fetcher = new WalmartGuestOrderFetcher(testConfig)
        
        expect: "various date formats to be parsed correctly"
        fetcher.parseDateString("January 15, 2025") == "2025-01-15"
        fetcher.parseDateString("Jan 15, 2025") == "2025-01-15"
        fetcher.parseDateString("01/15/2025") == "2025-01-15"
        fetcher.parseDateString("2025-01-15") == "2025-01-15"
        fetcher.parseDateString("Fri, Oct 24, 2025") == "2025-10-24"
    }
    
    def "parseDateString should return null for invalid date"() {
        given: "WalmartGuestOrderFetcher instance"
        def fetcher = new WalmartGuestOrderFetcher(testConfig)
        
        when: "invalid date is parsed"
        String result = fetcher.parseDateString("not a date")
        
        then: "null should be returned"
        result == null
    }
    
    def "getEmailContent should extract plain text content"() {
        given: "Email with plain text content"
        def fetcher = new WalmartGuestOrderFetcher(testConfig)
        Properties props = new Properties()
        Session session = Session.getInstance(props, null)
        MimeMessage message = new MimeMessage(session)
        message.setText("Order number: 123-456")
        
        when: "getEmailContent is called"
        String content = fetcher.getEmailContent(message)
        
        then: "text content should be extracted"
        content.contains("Order number: 123-456")
    }
    
    def "WalmartGuestOrderFetcher should be instantiated with valid configuration"() {
        given: "valid configuration"
        Configuration config = new Configuration()
        config.amazonEmail = "test@example.com"
        config.amazonEmailPassword = "password"
        config.walmartEmail = "walmart@example.com"
        config.walmartMode = "guest"
        config.lookBackDays = 30
        
        when: "WalmartGuestOrderFetcher is instantiated"
        def fetcher = new WalmartGuestOrderFetcher(config)
        
        then: "fetcher should be created successfully"
        fetcher != null
        fetcher.config == config
    }
    
    def "Configuration validation should pass for guest mode with only email"() {
        given: "Configuration with guest mode and email only"
        Configuration config = new Configuration()
        config.walmartEnabled = true
        config.walmartMode = "guest"
        config.walmartEmail = "test@example.com"
        config.walmartPassword = null  // Password not required for guest mode
        config.ynabApiKey = "test-key"
        config.ynabBudgetId = "test-budget"
        config.amazonEmail = "amazon@example.com"
        config.amazonEmailPassword = "password"
        
        when: "isValid is called"
        boolean valid = config.isValid()
        
        then: "configuration should be valid"
        valid == true
    }
    
    def "Configuration validation should fail for login mode without password"() {
        given: "Configuration with login mode but no password"
        Configuration config = new Configuration()
        config.walmartEnabled = true
        config.walmartMode = "login"
        config.walmartEmail = "test@example.com"
        config.walmartPassword = null
        config.ynabApiKey = "test-key"
        config.ynabBudgetId = "test-budget"
        config.amazonEmail = "amazon@example.com"
        config.amazonEmailPassword = "password"
        
        when: "isValid is called"
        boolean valid = config.isValid()
        
        then: "configuration should be invalid"
        valid == false
    }
    
    def "Configuration validation should pass for login mode with password"() {
        given: "Configuration with login mode and password"
        Configuration config = new Configuration()
        config.walmartEnabled = true
        config.walmartMode = "login"
        config.walmartEmail = "test@example.com"
        config.walmartPassword = "test-password"
        config.ynabApiKey = "test-key"
        config.ynabBudgetId = "test-budget"
        config.amazonEmail = "amazon@example.com"
        config.amazonEmailPassword = "password"
        
        when: "isValid is called"
        boolean valid = config.isValid()
        
        then: "configuration should be valid"
        valid == true
    }
    
    def "Configuration validation should reject invalid mode"() {
        given: "Configuration with invalid mode"
        Configuration config = new Configuration()
        config.walmartEnabled = true
        config.walmartMode = "invalid_mode"
        config.walmartEmail = "test@example.com"
        config.ynabApiKey = "test-key"
        config.ynabBudgetId = "test-budget"
        config.amazonEmail = "amazon@example.com"
        config.amazonEmailPassword = "password"
        
        when: "isValid is called"
        boolean valid = config.isValid()
        
        then: "configuration should be invalid"
        valid == false
    }
}
