package com.ynab.amazon.service

import com.ynab.amazon.model.YNABTransaction
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import com.ynab.amazon.model.TransactionMatch
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Test class for the TransactionMatcher service
 */
class TransactionMatcher_UT extends Specification {

    TransactionMatcher matcher

    def setup() {
        matcher = new TransactionMatcher()
    }
    
    def "findMatches should return empty list when no transactions provided"() {
        given: "empty list of transactions and some orders"
        def transactions = []
        def orders = [createSampleOrder("1234", "2023-05-15", 25.99)]
        
        when: "findMatches is called"
        def result = matcher.findMatches(transactions, orders)
        
        then: "an empty list should be returned"
        result != null
        result.isEmpty()
    }
    
    def "findMatches should return empty list when no orders provided"() {
        given: "some transactions and empty list of orders"
        def transactions = [createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")]
        def orders = []
        
        when: "findMatches is called"
        def result = matcher.findMatches(transactions, orders)
        
        then: "an empty list should be returned"
        result != null
        result.isEmpty()
    }
    
    def "findMatches should match transactions with exact amount and close date"() {
        given: "matching transactions and orders"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM"),
            createSampleTransaction("tx2", "2023-05-20", 49.99, "AMAZON MARKETPLACE")
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 25.99),
            createSampleOrder("5678", "2023-05-21", 49.99)
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "matching pairs should be found"
        matches.size() == 2
        matches[0].ynabTransaction.id == "tx1"
        matches[0].amazonOrder.orderId == "1234"
        matches[1].ynabTransaction.id == "tx2"
        matches[1].amazonOrder.orderId == "5678"
    }
    
    def "findMatches should not match transactions with different amounts"() {
        given: "transactions and orders with different amounts"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 26.99)
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "no matches should be found"
        matches.isEmpty()
    }
    
    def "findMatches should not match transactions with too distant dates"() {
        given: "transactions and orders with distant dates"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-06-15", 25.99)  // One month later
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "no matches should be found due to date difference"
        matches.isEmpty()
    }
    
    def "findMatches should ignore already processed transactions"() {
        given: "transactions with existing memos and matching orders"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM", "Already processed | items: Product A, Product B"),
            createSampleTransaction("tx2", "2023-05-20", 49.99, "AMAZON MARKETPLACE")
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 25.99),
            createSampleOrder("5678", "2023-05-20", 49.99)
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "only unprocessed transaction should be matched"
        matches.size() == 1
        matches[0].ynabTransaction.id == "tx2"
        matches[0].amazonOrder.orderId == "5678"
    }
    
    def "findMatches should only match potential Amazon transactions"() {
        given: "Amazon and non-Amazon transactions"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM"),
            createSampleTransaction("tx2", "2023-05-20", 49.99, "WALMART")
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 25.99),
            createSampleOrder("5678", "2023-05-20", 49.99)
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "only Amazon transactions should be matched"
        matches.size() == 1
        matches[0].ynabTransaction.id == "tx1"
        matches[0].amazonOrder.orderId == "1234"
    }
    
    @Unroll
    def "isPotentialAmazonTransaction should identify Amazon-related payees: #payeeName"() {
        given: "a transaction with the specified payee name"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, payeeName)
        
        when: "we check if it's a potential Amazon transaction"
        // Access the protected method using reflection instead of using a helper class
        def method = TransactionMatcher.getDeclaredMethod("isPotentialAmazonTransaction", YNABTransaction)
        method.setAccessible(true)
        def result = method.invoke(matcher, transaction)
        
        then: "the result should match expected outcome"
        result == expected
        
        where:
        payeeName              | expected
        "AMAZON.COM"           | true
        "AMZN MKTP US"         | true
        "WALMART"              | false
        "AMAZON PRIME"         | true
        "BEST BUY"             | false
        "TRANSFER: AMAZON"     | false  // Blacklisted
    }
    
    def "generateProposedMemo should handle single item orders"() {
        given: "an order with a single item"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")
        def amazonOrder = createSampleOrder("1234", "2023-05-15", 25.99)
        amazonOrder.addItem(new AmazonOrderItem(title: "Test Product", price: 25.99, quantity: 1))
        
        when: "generateProposedMemo is called"
        // Access the protected method using reflection
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should contain the item title"
        memo == "Test Product"
    }
    
    def "generateProposedMemo should handle multiple items"() {
        given: "an order with multiple items"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 59.97, "AMAZON.COM")
        def amazonOrder = createSampleOrder("1234", "2023-05-15", 59.97)
        amazonOrder.addItem(new AmazonOrderItem(title: "Product A", price: 19.99, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Product B", price: 19.99, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Product C", price: 19.99, quantity: 1))
        
        when: "generateProposedMemo is called"
        // Access the protected method using reflection
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should contain a summary of items"
        memo == "3 items: Product A, Product B, Product C"
    }
    
    def "generateProposedMemo should truncate for many items"() {
        given: "an order with more than 3 items"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 79.96, "AMAZON.COM")
        def amazonOrder = createSampleOrder("1234", "2023-05-15", 79.96)
        amazonOrder.addItem(new AmazonOrderItem(title: "Product A", price: 19.99, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Product B", price: 19.99, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Product C", price: 19.99, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Product D", price: 19.99, quantity: 1))
        
        when: "generateProposedMemo is called"
        // Access the protected method using reflection
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should be truncated after 3 items"
        memo == "4 items: Product A, Product B, Product C ..."
    }
    
    def "generateProposedMemo should preserve existing memo"() {
        given: "a transaction with existing memo and an order"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM", "Original memo")
        def amazonOrder = createSampleOrder("1234", "2023-05-15", 25.99)
        amazonOrder.addItem(new AmazonOrderItem(title: "Test Product", price: 25.99, quantity: 1))
        
        when: "generateProposedMemo is called"
        // Access the protected method using reflection
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should preserve the original memo"
        memo == "Original memo | Test Product"
    }
    
    def "generateProposedMemo should sanitize invalid characters"() {
        given: "an order with items containing special characters"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")
        def amazonOrder = createSampleOrder("1234", "2023-05-15", 25.99)
        amazonOrder.addItem(new AmazonOrderItem(title: "Product with & special @ characters!", price: 25.99, quantity: 1))
        
        when: "generateProposedMemo is called"
        // Access the protected method using reflection
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should be sanitized (& is allowed for S&S)"
        memo == "Product with & special  characters "
    }
    
    def "generateProposedMemo should add S&S prefix for Subscribe and Save orders"() {
        given: "a Subscribe and Save order with single item"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")
        def amazonOrder = createSampleOrder("SUB-20230515", "2023-05-15", 25.99)
        amazonOrder.addItem(new AmazonOrderItem(title: "Tide Laundry Detergent (Subscribe & Save)", price: 25.99, quantity: 1))
        
        when: "generateProposedMemo is called"
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should have S&S prefix and no Subscribe & Save suffix"
        memo == "S&S: Tide Laundry Detergent"
    }
    
    def "generateProposedMemo should add S&S prefix for Subscribe and Save orders with multiple items"() {
        given: "a Subscribe and Save order with multiple items"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 75.00, "AMAZON.COM")
        def amazonOrder = createSampleOrder("SUB-20230515", "2023-05-15", 75.00)
        amazonOrder.addItem(new AmazonOrderItem(title: "Tide Laundry Detergent (Subscribe & Save)", price: 25.00, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Charmin Toilet Paper (Subscribe & Save)", price: 25.00, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Bounty Paper Towels (Subscribe & Save)", price: 25.00, quantity: 1))
        
        when: "generateProposedMemo is called"
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should have S&S prefix and list items without Subscribe & Save suffix"
        memo == "S&S: 3 items: Tide Laundry Detergent, Charmin Toilet Paper, Bounty Paper Towels"
    }
    
    def "generateProposedMemo should preserve existing memo for Subscribe and Save orders"() {
        given: "a Subscribe and Save transaction with existing memo"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM", "Monthly delivery")
        def amazonOrder = createSampleOrder("SUB-20230515", "2023-05-15", 25.99)
        amazonOrder.addItem(new AmazonOrderItem(title: "Tide Laundry Detergent (Subscribe & Save)", price: 25.99, quantity: 1))
        
        when: "generateProposedMemo is called"
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should preserve original memo and add S&S prefix"
        memo == "Monthly delivery | S&S: Tide Laundry Detergent"
    }
    
    def "generateProposedMemo should handle Subscribe and Save orders with generic items"() {
        given: "a Subscribe and Save order with generic item names"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 84.32, "AMAZON.COM")
        def amazonOrder = createSampleOrder("SUB-20230515", "2023-05-15", 84.32)
        amazonOrder.addItem(new AmazonOrderItem(title: "Subscribe & Save Item 1", price: 25.70, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Subscribe & Save Item 2", price: 16.13, quantity: 1))
        amazonOrder.addItem(new AmazonOrderItem(title: "Subscribe & Save Item 3", price: 42.49, quantity: 1))
        
        when: "generateProposedMemo is called"
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should have S&S prefix"
        memo.startsWith("S&S: 3 items:")
        memo.contains("Subscribe & Save Item 1")
    }
    
    def "generateProposedMemo should not add S&S prefix for regular Amazon orders"() {
        given: "a regular Amazon order (not Subscribe and Save)"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")
        def amazonOrder = createSampleOrder("123-4567890-1234567", "2023-05-15", 25.99)
        amazonOrder.addItem(new AmazonOrderItem(title: "Regular Product", price: 25.99, quantity: 1))
        
        when: "generateProposedMemo is called"
        def method = TransactionMatcher.getDeclaredMethod("generateProposedMemo", YNABTransaction, AmazonOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, amazonOrder)
        
        then: "the memo should NOT have S&S prefix"
        memo == "Regular Product"
        !memo.startsWith("S&S:")
    }
    
    // Helper methods to create test data
    private YNABTransaction createSampleTransaction(String id, String date, BigDecimal amount, String payeeName, String memo = null) {
        return new YNABTransaction(
            id: id,
            date: date,
            amount: (amount * 1000).longValue(),  // Convert to milliunits
            payee_name: payeeName,
            memo: memo,
            cleared: "cleared",
            approved: "true"
        )
    }
    
    private AmazonOrder createSampleOrder(String orderId, String orderDate, BigDecimal totalAmount) {
        return new AmazonOrder(
            orderId: orderId,
            orderDate: orderDate,
            totalAmount: totalAmount,
            isReturn: false
        )
    }
}
