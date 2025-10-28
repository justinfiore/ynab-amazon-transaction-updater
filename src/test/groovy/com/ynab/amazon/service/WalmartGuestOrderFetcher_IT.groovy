package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import spock.lang.Specification
import spock.lang.Ignore
import javax.mail.Session
import javax.mail.internet.MimeMessage
import java.util.Properties

/**
 * Integration tests for WalmartGuestOrderFetcher
 * Tests email parsing using actual sample emails
 */
class WalmartGuestOrderFetcher_IT extends Specification {
    
    Configuration testConfig
    WalmartGuestOrderFetcher fetcher
    
    def setup() {
        testConfig = new Configuration()
        testConfig.walmartEmail = "walmart@example.com"
        testConfig.walmartEmailPassword = "walmart_email_password"
        testConfig.walmartMode = Configuration.WALMART_MODE_GUEST
        testConfig.walmartHeadless = true
        testConfig.walmartBrowserTimeout = 30000
        testConfig.walmartOrdersUrl = "https://www.walmart.com/orders"
        testConfig.lookBackDays = 30
        
        fetcher = new WalmartGuestOrderFetcher(testConfig)
    }
    
    def "extractOrderId should extract order ID from actual Walmart delivery email"() {
        given: "Actual Walmart delivery email file"
        File emailFile = new File("src/test/resources/walmart/Shipped_ Progresso Rich and Hea... and 5 other items.eml")
        assert emailFile.exists(), "Email file not found: ${emailFile.absolutePath}"
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        MimeMessage message = emailFile.withInputStream { inputStream ->
            new MimeMessage(session, inputStream)
        }
        
        when: "Email content is extracted and order ID is parsed"
        String content = fetcher.getEmailContent(message)
        String orderId = fetcher.extractOrderId(content)
        
        then: "Order ID should be extracted correctly"
        orderId != null
        orderId == "2000139-92737755"
    }
    
    def "extractOrderId should extract order ID from first Walmart delivery email"() {
        given: "First Walmart delivery email file"
        File emailFile = new File("src/test/resources/walmart/Delivered_ Great Value Frozen Swe... and 27 other items.eml")
        assert emailFile.exists(), "Email file not found: ${emailFile.absolutePath}"
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        MimeMessage message = emailFile.withInputStream { inputStream ->
            new MimeMessage(session, inputStream)
        }
        
        when: "Email content is extracted and order ID is parsed"
        String content = fetcher.getEmailContent(message)
        String orderId = fetcher.extractOrderId(content)
        
        then: "Order ID should be extracted correctly"
        orderId != null
        // The order ID should be in the format XXXXXXX-XXXXXXXX
        orderId ==~ /\d+-\d+/
    }
    
    def "parseDateString should parse Walmart email date formats"() {
        given: "WalmartGuestOrderFetcher instance"
        
        expect: "Walmart date formats to be parsed"
        fetcher.parseDateString("Fri, Oct 24, 2025") == "2025-10-24"
        fetcher.parseDateString("Sun, Oct 26, 2025") == "2025-10-26"
        fetcher.parseDateString("Sat, Oct 25, 2025") == "2025-10-25"
    }
    
    def "getEmailContent should handle multipart email from actual file"() {
        given: "Actual multipart email file"
        File emailFile = new File("src/test/resources/walmart/Delivered_ SpaghettiOs Super Mari... and 20 other items.eml")
        assert emailFile.exists(), "Email file not found: ${emailFile.absolutePath}"
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        MimeMessage message = emailFile.withInputStream { inputStream ->
            new MimeMessage(session, inputStream)
        }
        
        when: "Email content is extracted"
        String content = fetcher.getEmailContent(message)
        
        then: "Content should not be empty"
        content != null
        content.length() > 0
    }
    
    def "All Walmart test emails should have extractable order IDs"() {
        given: "Directory with Walmart test emails"
        File walmartDir = new File("src/test/resources/walmart")
        assert walmartDir.exists() && walmartDir.isDirectory()
        
        List<File> emailFiles = walmartDir.listFiles().findAll { it.name.endsWith('.eml') }
        assert !emailFiles.isEmpty(), "No email files found in ${walmartDir.absolutePath}"
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        expect: "Each email file should have an extractable order ID"
        emailFiles.each { emailFile ->
            MimeMessage message = emailFile.withInputStream { inputStream ->
                new MimeMessage(session, inputStream)
            }
            
            String content = fetcher.getEmailContent(message)
            String orderId = fetcher.extractOrderId(content)
            
            assert orderId != null, "Failed to extract order ID from ${emailFile.name}"
            assert orderId ==~ /\d+-\d+/, "Invalid order ID format from ${emailFile.name}: ${orderId}"
        }
    }
    
    @Ignore("This test requires actual IMAP connection - manual test only")
    def "fetchOrderIdsFromEmail should connect to real email account"() {
        given: "Real email configuration"
        // This test is ignored because it requires actual email credentials
        // To run manually, configure real credentials and remove @Ignore annotation
        
        when: "fetchOrderIdsFromEmail is called"
        Map<String, Date> orderIds = fetcher.fetchOrderIdsFromEmail()
        
        then: "Order IDs should be fetched"
        orderIds != null
    }
}
