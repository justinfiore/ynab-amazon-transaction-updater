package com.ynab.amazon.config

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

/**
 * Test class for the Configuration class
 */
class Configuration_UT extends Specification {
    
    @Rule
    TemporaryFolder tempFolder = new TemporaryFolder()
    
    def "should load valid configuration"() {
        given: "a valid configuration file"
        def configuration = new Configuration()
        
        and: "configuration has valid values"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.ynabBaseUrl = "https://api.test.com/v1"
        configuration.amazonEmail = "test@example.com"
        configuration.amazonEmailPassword = "test-password"
        configuration.processedTransactionsFile = "processed.json"
        configuration.logLevel = "DEBUG"
        configuration.dryRun = false
        configuration.lookBackDays = 14
        
        when: "configuration validation is checked"
        def result = configuration.isValid()
        
        then: "all values should be properly set"
        configuration.ynabApiKey == "test-api-key"
        configuration.ynabBudgetId == "test-budget-id"
        configuration.ynabBaseUrl == "https://api.test.com/v1"
        configuration.amazonEmail == "test@example.com"
        configuration.amazonEmailPassword == "test-password"
        configuration.processedTransactionsFile == "processed.json"
        configuration.logLevel == "DEBUG"
        configuration.dryRun == false
        configuration.lookBackDays == 14
        result
    }
    
    def "should use default values when optional fields are missing"() {
        given: "a configuration with only required fields"
        def configuration = new Configuration()
        
        and: "configuration has minimal values with defaults"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.amazonCsvFilePath = "/path/to/file.csv"
        
        expect: "default values should be used for optional fields"
        configuration.ynabBaseUrl == "https://api.ynab.com/v1"
        configuration.logLevel == "INFO"
        configuration.dryRun == false
        configuration.lookBackDays == 30
        configuration.isValid()
    }
    
    def "should fail validation if required YNAB fields are missing"() {
        given: "a configuration without required YNAB fields"
        def configuration = new Configuration()
        
        and: "configuration has missing YNAB fields"
        configuration.amazonEmail = "test@example.com"
        configuration.amazonEmailPassword = "test-password"
        configuration.processedTransactionsFile = "processed.json"
        // Missing ynabApiKey and ynabBudgetId
        
        when: "isValid is called"
        def result = configuration.isValid()
        
        then: "validation should fail"
        !result
    }
    
    def "should fail validation if both Amazon authentication methods are missing"() {
        given: "a configuration without Amazon credentials or CSV path"
        def configuration = new Configuration()
        
        and: "configuration has missing Amazon fields"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.processedTransactionsFile = "processed.json"
        // Missing Amazon fields: amazonEmail, amazonEmailPassword, amazonCsvFilePath
        
        when: "isValid is called"
        def result = configuration.isValid()
        
        then: "validation should fail"
        !result
    }
    
    def "should pass validation with just CSV path"() {
        given: "a configuration with YNAB credentials and Amazon CSV path"
        def configuration = new Configuration()
        
        and: "configuration has CSV path but no email credentials"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.amazonCsvFilePath = "/path/to/orders.csv"
        configuration.processedTransactionsFile = "processed.json"
        
        when: "isValid is called"
        def result = configuration.isValid()
        
        then: "validation should pass"
        result
    }
    
    def "should pass validation with just email credentials"() {
        given: "a configuration with YNAB credentials and Amazon email credentials"
        def configuration = new Configuration()
        
        and: "configuration has email credentials but no CSV path"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.amazonEmail = "test@example.com"
        configuration.amazonEmailPassword = "test-password"
        configuration.processedTransactionsFile = "processed.json"
        
        when: "isValid is called"
        def result = configuration.isValid()
        
        then: "validation should pass"
        result
    }
    
    def "should use default Walmart values when not configured"() {
        given: "a configuration without Walmart settings"
        def configuration = new Configuration()
        
        expect: "default Walmart values should be used"
        configuration.walmartEnabled == false
        configuration.walmartHeadless == true
        configuration.walmartBrowserTimeout == 30000
        configuration.walmartOrdersUrl == "https://www.walmart.com/orders"
    }
    
    def "should pass validation when Walmart is disabled"() {
        given: "a configuration with Walmart disabled"
        def configuration = new Configuration()
        
        and: "configuration has required YNAB and Amazon fields"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.amazonEmail = "test@example.com"
        configuration.amazonEmailPassword = "test-password"
        configuration.walmartEnabled = false
        
        when: "isValid is called"
        def result = configuration.isValid()
        
        then: "validation should pass even without Walmart credentials"
        result
    }
    
    def "should fail validation when Walmart is enabled but email is missing"() {
        given: "a configuration with Walmart enabled but missing email"
        def configuration = new Configuration()
        
        and: "configuration has required YNAB and Amazon fields"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.amazonEmail = "test@example.com"
        configuration.amazonEmailPassword = "test-password"
        configuration.walmartEnabled = true
        configuration.walmartPassword = "walmart-password"
        // Missing walmartEmail
        
        when: "isValid is called"
        def result = configuration.isValid()
        
        then: "validation should fail"
        !result
    }
    
    def "should fail validation when Walmart is enabled but password is missing"() {
        given: "a configuration with Walmart enabled but missing password"
        def configuration = new Configuration()
        
        and: "configuration has required YNAB and Amazon fields"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.amazonEmail = "test@example.com"
        configuration.amazonEmailPassword = "test-password"
        configuration.walmartEnabled = true
        configuration.walmartEmail = "walmart@example.com"
        // Missing walmartPassword
        
        when: "isValid is called"
        def result = configuration.isValid()
        
        then: "validation should fail"
        !result
    }
    
    def "should pass validation when Walmart is enabled with all required fields"() {
        given: "a configuration with Walmart enabled and all required fields"
        def configuration = new Configuration()
        
        and: "configuration has all required fields including Walmart"
        configuration.ynabApiKey = "test-api-key"
        configuration.ynabBudgetId = "test-budget-id"
        configuration.amazonEmail = "test@example.com"
        configuration.amazonEmailPassword = "test-password"
        configuration.walmartEnabled = true
        configuration.walmartEmail = "walmart@example.com"
        configuration.walmartPassword = "walmart-password"
        
        when: "isValid is called"
        def result = configuration.isValid()
        
        then: "validation should pass"
        result
    }
    
    def "should allow custom Walmart timeout and URL"() {
        given: "a configuration with custom Walmart settings"
        def configuration = new Configuration()
        
        and: "configuration has custom Walmart values"
        configuration.walmartBrowserTimeout = 60000
        configuration.walmartOrdersUrl = "https://custom.walmart.com/orders"
        
        expect: "custom values should be set"
        configuration.walmartBrowserTimeout == 60000
        configuration.walmartOrdersUrl == "https://custom.walmart.com/orders"
    }
    
    def "should allow setting Walmart headless mode to false"() {
        given: "a configuration with headless mode disabled"
        def configuration = new Configuration()
        
        and: "configuration has headless set to false"
        configuration.walmartHeadless = false
        
        expect: "headless should be false"
        configuration.walmartHeadless == false
    }
    
    def "should default Walmart headless mode to true"() {
        given: "a configuration without explicit headless setting"
        def configuration = new Configuration()
        
        expect: "headless should default to true"
        configuration.walmartHeadless == true
    }
}
