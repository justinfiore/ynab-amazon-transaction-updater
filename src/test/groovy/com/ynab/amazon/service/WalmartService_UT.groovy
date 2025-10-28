package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import com.ynab.amazon.model.WalmartOrderItem
import spock.lang.Ignore
import spock.lang.Specification

/**
 * Unit tests for WalmartService
 */
class WalmartService_UT extends Specification {
    
    Configuration mockConfig
    WalmartService walmartService
    
    def setup() {
        mockConfig = Mock(Configuration)
    }
    
    def "getOrders should return empty list when Walmart integration is disabled"() {
        given: "Walmart integration is disabled"
        mockConfig.walmartEnabled >> false
        walmartService = new WalmartService(mockConfig)
        
        when: "getOrders is called"
        def result = walmartService.getOrders()
        
        then: "an empty list should be returned"
        result == []
    }
    
    def "getOrders should throw exception when email is missing and Walmart is enabled"() {
        given: "Walmart is enabled but email is missing"
        mockConfig.walmartEnabled >> true
        mockConfig.walmartEmail >> null
        mockConfig.walmartPassword >> "password123"
        walmartService = new WalmartService(mockConfig)
        
        when: "getOrders is called"
        walmartService.getOrders()
        
        then: "an IllegalStateException should be thrown"
        def exception = thrown(IllegalStateException)
        exception.message.contains("walmart.email")
    }

    @Ignore("Not able to login to Walmart with fake credentials")
    def "getOrders should throw exception when password is missing and Walmart is enabled"() {
        given: "Walmart is enabled but password is missing"
        mockConfig.walmartEnabled >> true
        mockConfig.walmartEmail >> "test@example.com"
        mockConfig.walmartPassword >> null
        walmartService = new WalmartService(mockConfig)
        
        when: "getOrders is called"
        walmartService.getOrders()
        
        then: "an IllegalStateException should be thrown"
        def exception = thrown(IllegalStateException)
        exception.message.contains("walmart.password")
    }

    @Ignore("Not able to login to Walmart with fake credentials")
    def "getOrders should throw exception when both email and password are missing and Walmart is enabled"() {
        given: "Walmart is enabled but credentials are missing"
        mockConfig.walmartEnabled >> true
        mockConfig.walmartEmail >> null
        mockConfig.walmartPassword >> null
        walmartService = new WalmartService(mockConfig)
        
        when: "getOrders is called"
        walmartService.getOrders()
        
        then: "an IllegalStateException should be thrown"
        def exception = thrown(IllegalStateException)
        exception.message.contains("walmart.email")
        exception.message.contains("walmart.password")
    }

    def "validateConfiguration should not throw exception when Walmart is disabled"() {
        given: "Walmart integration is disabled with missing credentials"
        mockConfig.walmartEnabled >> false
        mockConfig.walmartEmail >> null
        mockConfig.walmartPassword >> null
        walmartService = new WalmartService(mockConfig)
        
        when: "validateConfiguration is called via getOrders"
        def result = walmartService.getOrders()
        
        then: "no exception should be thrown"
        notThrown(IllegalStateException)
        result == []
    }
    
    def "WalmartService should be instantiated with valid configuration"() {
        given: "valid Walmart configuration"
        mockConfig.walmartEnabled >> true
        mockConfig.walmartEmail >> "test@example.com"
        mockConfig.walmartPassword >> "password123"
        
        when: "WalmartService is instantiated"
        walmartService = new WalmartService(mockConfig)
        
        then: "service should be created successfully"
        walmartService != null
        walmartService.config == mockConfig
    }

    @Ignore("Not able to login to Walmart with fake credentials")
    def "WalmartService should handle null configuration gracefully"() {
        when: "WalmartService is instantiated with null config"
        walmartService = new WalmartService(null)
        
        then: "service should be created but fail on getOrders"
        walmartService != null
        
        when: "getOrders is called"
        walmartService.getOrders()
        
        then: "a NullPointerException should be thrown"
        thrown(NullPointerException)
    }
}
