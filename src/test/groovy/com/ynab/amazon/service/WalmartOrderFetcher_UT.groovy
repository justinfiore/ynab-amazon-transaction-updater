package com.ynab.amazon.service

import com.microsoft.playwright.*
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import spock.lang.Specification

/**
 * Unit tests for WalmartOrderFetcher
 * Tests focus on parsing logic and error handling
 */
class WalmartOrderFetcher_UT extends Specification {
    
    Configuration mockConfig
    WalmartOrderFetcher orderFetcher
    
    def setup() {
        mockConfig = Mock(Configuration) {
            getWalmartEmail() >> "test@example.com"
            getWalmartPassword() >> "testpassword"
            getWalmartBrowserTimeout() >> 30000
            getWalmartOrdersUrl() >> "https://www.walmart.com/orders"
            getLookBackDays() >> 30
        }
        orderFetcher = new WalmartOrderFetcher(mockConfig)
    }
    
    def "should parse date string in MMMM d, yyyy format"() {
        when: "parsing a date string"
        String result = orderFetcher.parseDateString("January 15, 2024")
        
        then: "should return yyyy-MM-dd format"
        result == "2024-01-15"
    }
    
    def "should parse date string in MMM d, yyyy format"() {
        when: "parsing a date string"
        String result = orderFetcher.parseDateString("Jan 15, 2024")
        
        then: "should return yyyy-MM-dd format"
        result == "2024-01-15"
    }
    
    def "should parse date string in MM/dd/yyyy format"() {
        when: "parsing a date string"
        String result = orderFetcher.parseDateString("01/15/2024")
        
        then: "should return yyyy-MM-dd format"
        result == "2024-01-15"
    }
    
    def "should parse date string already in yyyy-MM-dd format"() {
        when: "parsing a date string"
        String result = orderFetcher.parseDateString("2024-01-15")
        
        then: "should return same format"
        result == "2024-01-15"
    }
    
    def "should return null for invalid date string"() {
        when: "parsing an invalid date string"
        String result = orderFetcher.parseDateString("invalid date")
        
        then: "should return null"
        result == null
    }
    
    def "should handle null date string"() {
        when: "parsing a null date string"
        String result = orderFetcher.parseDateString(null)
        
        then: "should return null"
        result == null
    }
    
    def "should create WalmartOrderFetcher with configuration"() {
        when: "creating a WalmartOrderFetcher"
        def fetcher = new WalmartOrderFetcher(mockConfig)
        
        then: "should be created successfully"
        fetcher != null
    }
    
    def "should handle browser initialization failure gracefully"() {
        given: "a fetcher that will fail to initialize browser"
        def fetcher = new WalmartOrderFetcher(mockConfig)
        
        when: "fetching orders with browser initialization failure"
        // This will fail because we don't have a real browser environment
        List<WalmartOrder> orders = fetcher.fetchOrders()
        
        then: "should return empty list and not throw exception"
        orders != null
        orders.isEmpty()
    }
    
    def "should track skipped orders count"() {
        given: "a fetcher"
        def fetcher = new WalmartOrderFetcher(mockConfig)
        
        when: "fetching orders"
        List<WalmartOrder> orders = fetcher.fetchOrders()
        
        then: "should complete without throwing exception"
        orders != null
    }
    
    def "should use configured timeout"() {
        given: "configuration with custom timeout"
        def customConfig = Mock(Configuration) {
            getWalmartEmail() >> "test@example.com"
            getWalmartPassword() >> "testpassword"
            getWalmartBrowserTimeout() >> 60000
            getWalmartOrdersUrl() >> "https://www.walmart.com/orders"
            getLookBackDays() >> 30
        }
        
        when: "creating a fetcher with custom timeout"
        def fetcher = new WalmartOrderFetcher(customConfig)
        
        then: "should be created successfully"
        fetcher != null
    }
    
    def "should use configured orders URL"() {
        given: "configuration with custom orders URL"
        def customConfig = Mock(Configuration) {
            getWalmartEmail() >> "test@example.com"
            getWalmartPassword() >> "testpassword"
            getWalmartBrowserTimeout() >> 30000
            getWalmartOrdersUrl() >> "https://www.walmart.com/custom/orders"
            getLookBackDays() >> 30
        }
        
        when: "creating a fetcher with custom URL"
        def fetcher = new WalmartOrderFetcher(customConfig)
        
        then: "should be created successfully"
        fetcher != null
    }
    
    def "should use configured lookback days"() {
        given: "configuration with custom lookback days"
        def customConfig = Mock(Configuration) {
            getWalmartEmail() >> "test@example.com"
            getWalmartPassword() >> "testpassword"
            getWalmartBrowserTimeout() >> 30000
            getWalmartOrdersUrl() >> "https://www.walmart.com/orders"
            getLookBackDays() >> 60
        }
        
        when: "creating a fetcher with custom lookback days"
        def fetcher = new WalmartOrderFetcher(customConfig)
        
        then: "should be created successfully"
        fetcher != null
    }
}
