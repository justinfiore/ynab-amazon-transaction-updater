package com.ynab.amazon.model

import spock.lang.Specification

/**
 * Test class for the AmazonOrder model
 */
class AmazonOrder_UT extends Specification {

    def "should initialize with empty items list"() {
        when: "a new AmazonOrder is created"
        def order = new AmazonOrder()
        
        then: "items list should be initialized as empty"
        order.items != null
        order.items.isEmpty()
    }
    
    def "should add items correctly"() {
        given: "an AmazonOrder and some items"
        def order = new AmazonOrder()
        def item1 = new AmazonOrderItem(title: "Test Item 1", price: 10.99, quantity: 1)
        def item2 = new AmazonOrderItem(title: "Test Item 2", price: 20.50, quantity: 2)
        
        when: "items are added to the order"
        order.addItem(item1)
        order.addItem(item2)
        
        then: "the order should contain all added items"
        order.items.size() == 2
        order.items.contains(item1)
        order.items.contains(item2)
    }
    
    def "getProductSummary should handle empty items"() {
        given: "an AmazonOrder with no items"
        def order = new AmazonOrder()
        
        when: "getProductSummary is called"
        def summary = order.getProductSummary()
        
        then: "it should return a generic message"
        summary == "Amazon Order"
    }
    
    def "getProductSummary should handle single item"() {
        given: "an AmazonOrder with one item"
        def order = new AmazonOrder()
        order.addItem(new AmazonOrderItem(title: "Test Product", price: 19.99, quantity: 1))
        
        when: "getProductSummary is called"
        def summary = order.getProductSummary()
        
        then: "it should return the item title"
        summary == "Test Product"
    }
    
    def "getProductSummary should handle multiple items"() {
        given: "an AmazonOrder with multiple items"
        def order = new AmazonOrder()
        order.addItem(new AmazonOrderItem(title: "Product 1", price: 9.99, quantity: 1))
        order.addItem(new AmazonOrderItem(title: "Product 2", price: 19.99, quantity: 2))
        order.addItem(new AmazonOrderItem(title: "Product 3", price: 29.99, quantity: 1))
        
        when: "getProductSummary is called"
        def summary = order.getProductSummary()
        
        then: "it should return a summary with all item titles"
        summary == "3 items: Product 1, Product 2, Product 3"
    }
    
    def "getProductSummary should truncate when there are many items"() {
        given: "an AmazonOrder with more than 3 items"
        def order = new AmazonOrder()
        order.addItem(new AmazonOrderItem(title: "Product 1", price: 9.99, quantity: 1))
        order.addItem(new AmazonOrderItem(title: "Product 2", price: 19.99, quantity: 2))
        order.addItem(new AmazonOrderItem(title: "Product 3", price: 29.99, quantity: 1))
        order.addItem(new AmazonOrderItem(title: "Product 4", price: 39.99, quantity: 1))
        
        when: "getProductSummary is called"
        def summary = order.getProductSummary()
        
        then: "it should return a summary with truncated items"
        summary == "4 items: Product 1, Product 2, Product 3..."
    }
    
    def "getTotalAmount should handle null value"() {
        given: "an AmazonOrder with null totalAmount"
        def order = new AmazonOrder(totalAmount: null)
        
        when: "getTotalAmount is called"
        def amount = order.getTotalAmount()
        
        then: "it should return 0"
        amount == 0
    }
    
    def "getDisplayDate should handle null value"() {
        given: "an AmazonOrder with null orderDate"
        def order = new AmazonOrder(orderDate: null)
        
        when: "getDisplayDate is called"
        def date = order.getDisplayDate()
        
        then: "it should return empty string"
        date == ""
    }
    
    def "toString should include key order information"() {
        given: "a populated AmazonOrder"
        def order = new AmazonOrder(
            orderId: "ABC123",
            orderDate: "2023-01-15",
            totalAmount: 29.99,
            isReturn: false
        )
        order.addItem(new AmazonOrderItem(title: "Test Product"))
        
        when: "toString is called"
        def string = order.toString()
        
        then: "it should contain key order information"
        string.contains("ABC123")
        string.contains("2023-01-15")
        string.contains("29.99")
        string.contains("items=1")
        string.contains("isReturn=false")
    }
}

/**
 * Test class for the AmazonOrderItem model
 */
class AmazonOrderItem_UT extends Specification {
    
    def "getTotalPrice should calculate correctly"() {
        given: "an AmazonOrderItem with price and quantity"
        def item = new AmazonOrderItem(price: 15.99, quantity: 3)
        
        when: "getTotalPrice is called"
        def totalPrice = item.getTotalPrice()
        
        then: "it should return price * quantity"
        totalPrice == 47.97
    }
    
    def "getTotalPrice should handle null price"() {
        given: "an AmazonOrderItem with null price"
        def item = new AmazonOrderItem(price: null, quantity: 2)
        
        when: "getTotalPrice is called"
        def totalPrice = item.getTotalPrice()
        
        then: "it should return 0"
        totalPrice == 0
    }
    
    def "toString should include key item information"() {
        given: "a populated AmazonOrderItem"
        def item = new AmazonOrderItem(
            title: "Test Item",
            price: 25.99,
            quantity: 2
        )
        
        when: "toString is called"
        def string = item.toString()
        
        then: "it should contain key item information"
        string.contains("Test Item")
        string.contains("25.99")
        string.contains("quantity=2")
    }
}
