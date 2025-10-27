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
    
    def "findMatches should match Amazon returns with transaction date up to 7 days after order date"() {
        given: "a return order and transaction 5 days later"
        def transactions = [
            createSampleReturnTransaction("tx1", "2023-05-20", 25.99, "AMAZON.COM")  // 5 days after order (return/inflow)
        ]
        
        def orders = [
            createSampleReturnOrder("1234", "2023-05-15", 25.99)  // Return order
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "match should be found with high confidence"
        matches.size() == 1
        matches[0].ynabTransaction.id == "tx1"
        matches[0].amazonOrder.orderId == "1234"
        matches[0].confidenceScore >= 0.9  // Should have high confidence due to return grace period
    }
    
    def "findMatches should match Amazon returns with transaction date exactly 7 days after order date"() {
        given: "a return order and transaction exactly 7 days later"
        def transactions = [
            createSampleReturnTransaction("tx1", "2023-05-22", 25.99, "AMAZON.COM")  // Exactly 7 days after (return/inflow)
        ]
        
        def orders = [
            createSampleReturnOrder("1234", "2023-05-15", 25.99)  // Return order
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "match should be found with high confidence"
        matches.size() == 1
        matches[0].confidenceScore >= 0.9  // Should have high confidence
    }
    
    def "findMatches should match Amazon returns with transaction date 10 days after order date with lower confidence"() {
        given: "a return order and transaction 10 days later"
        def transactions = [
            createSampleReturnTransaction("tx1", "2023-05-25", 25.99, "AMAZON.COM")  // 10 days after (return/inflow)
        ]
        
        def orders = [
            createSampleReturnOrder("1234", "2023-05-15", 25.99)  // Return order
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "match should be found but with lower confidence"
        matches.size() == 1
        matches[0].confidenceScore >= 0.5  // Should still match but with lower confidence
        matches[0].confidenceScore <= 0.92   // But not as high as within grace period (allowing for small calculation differences)
    }
    
    def "findMatches should not match Amazon returns with transaction date more than 21 days after order date"() {
        given: "a return order and transaction 22 days later"
        def transactions = [
            createSampleReturnTransaction("tx1", "2023-06-06", 25.99, "AMAZON.COM")  // 22 days after (return/inflow)
        ]
        
        def orders = [
            createSampleReturnOrder("1234", "2023-05-15", 25.99)  // Return order
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "no match should be found due to exceeding maximum date difference"
        matches.isEmpty()
    }
    
    def "findMatches should handle regular orders normally when transaction is after order date"() {
        given: "a regular order and transaction 5 days later"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-20", 25.99, "AMAZON.COM")  // 5 days after
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 25.99)  // Regular order (not return)
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "match should be found but with normal date scoring (no grace period)"
        matches.size() == 1
        matches[0].confidenceScore < 0.9  // Lower confidence due to 5-day difference
    }
    
    def "findMatches should handle returns normally when transaction is before order date"() {
        given: "a return order and transaction 3 days before order date"
        def transactions = [
            createSampleReturnTransaction("tx1", "2023-05-12", 25.99, "AMAZON.COM")  // 3 days before (return/inflow)
        ]
        
        def orders = [
            createSampleReturnOrder("1234", "2023-05-15", 25.99)  // Return order
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "match should be found with normal date scoring (no grace period applied)"
        matches.size() == 1
        matches[0].confidenceScore <= 0.92  // Normal confidence for 3-day difference (allowing for calculation precision)
    }
    
    def "findMatches should NOT match returns with expense transactions (sign validation)"() {
        given: "a return order and a negative YNAB transaction (expense)"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")  // Negative (expense)
        ]
        
        def orders = [
            createSampleReturnOrder("1234", "2023-05-15", 25.99)  // Positive return
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "no match should be found due to sign mismatch"
        matches.isEmpty()
    }
    
    def "findMatches should NOT match orders with inflow transactions (sign validation)"() {
        given: "a regular order and a positive YNAB transaction (inflow)"
        def transactions = [
            createSampleReturnTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")  // Positive (inflow)
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 25.99)  // Negative order
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "no match should be found due to sign mismatch"
        matches.isEmpty()
    }
    
    def "findMatches should match amounts that differ in 3rd decimal place (rounding to cents)"() {
        given: "a transaction and order that match when rounded to cents"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.993, "AMAZON.COM")  // -25.993, rounds to -25.99
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 25.994)  // -25.994, rounds to -25.99
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "match should be found since amounts round to same cents"
        matches.size() == 1
        matches[0].matchReason.contains("exact amount match")
    }
    
    def "findMatches should report close match when amounts differ slightly but not to the cent"() {
        given: "a transaction and order that differ in 3rd decimal but round differently"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.994, "AMAZON.COM")  // -25.994, rounds to -25.99
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 25.996)  // -25.996, rounds to -26.00
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "match should be found but as close match, not exact"
        matches.size() == 1
        matches[0].matchReason.contains("close amount match")
        !matches[0].matchReason.contains("exact amount match")
    }
    
    def "findMatches should NOT match when amounts differ by more than tolerance"() {
        given: "a transaction and order that differ by more than one dollar"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "AMAZON.COM")  // -25.99
        ]
        
        def orders = [
            createSampleOrder("1234", "2023-05-15", 27.00)  // -27.00, differs by more than $1
        ]
        
        when: "findMatches is called"
        def matches = matcher.findMatches(transactions, orders)
        
        then: "no match should be found since amounts differ by more than tolerance"
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
            amount: -(amount * 1000).longValue(),  // Convert to milliunits and make negative for expenses
            payee_name: payeeName,
            memo: memo,
            cleared: "cleared",
            approved: "true"
        )
    }
    
    private YNABTransaction createSampleReturnTransaction(String id, String date, BigDecimal amount, String payeeName, String memo = null) {
        return new YNABTransaction(
            id: id,
            date: date,
            amount: (amount * 1000).longValue(),  // Convert to milliunits and keep positive for inflows (returns)
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
            totalAmount: -totalAmount,  // Negative for expenses/orders
            isReturn: false
        )
    }
    
    private AmazonOrder createSampleReturnOrder(String orderId, String orderDate, BigDecimal totalAmount) {
        return new AmazonOrder(
            orderId: orderId,
            orderDate: orderDate,
            totalAmount: totalAmount,  // Positive for returns
            isReturn: true
        )
    }

    // ========== Walmart Matching Tests ==========
    
    def "findWalmartMatches should return empty list when no transactions provided"() {
        given: "empty list of transactions and some Walmart orders"
        def transactions = []
        def order = createSampleWalmartOrder("1234", "2023-05-15", 25.99)
        order.orderStatus = "Delivered"
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def result = matcher.findWalmartMatches(transactions, orders)
        
        then: "an empty list should be returned"
        result != null
        result.isEmpty()
    }
    
    def "findWalmartMatches should return empty list when no orders provided"() {
        given: "some Walmart transactions and empty list of orders"
        def transactions = [createSampleTransaction("tx1", "2023-05-15", 25.99, "WALMART")]
        def orders = []
        
        when: "findWalmartMatches is called"
        def result = matcher.findWalmartMatches(transactions, orders)
        
        then: "an empty list should be returned"
        result != null
        result.isEmpty()
    }
    
    def "findWalmartMatches should match single transaction with exact amount and close date"() {
        given: "matching Walmart transaction and order"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "WALMART")
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-05-15", 25.99)
        order.orderStatus = "Delivered"  // Make sure it's delivered
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "matching pair should be found"
        matches.size() == 1
        matches[0].ynabTransaction.id == "tx1"
        matches[0].walmartOrder.orderId == "1234"
        matches[0].isMultiTransaction == false
    }
    
    def "findWalmartMatches should match transaction with charge amount for multi-charge order"() {
        given: "Walmart transaction matching one charge of a multi-charge order"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 50.00, "WALMART.COM")
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-05-15", 150.00)
        order.orderStatus = "Delivered"  // Make sure it's delivered
        order.addFinalCharge(-100.00)
        order.addFinalCharge(-50.00)
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "matching pair should be found"
        matches.size() == 1
        matches[0].ynabTransaction.id == "tx1"
        matches[0].walmartOrder.orderId == "1234"
    }
    
    def "findWalmartMatches should match individual transactions to individual final charges"() {
        given: "two Walmart transactions that match individual final charges"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 100.00, "WALMART"),
            createSampleTransaction("tx2", "2023-05-16", 50.00, "WALMART")
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-05-15", 150.00)
        order.orderStatus = "Delivered"  // Make sure it's delivered
        order.addFinalCharge(-100.00)
        order.addFinalCharge(-50.00)
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "both transactions should be matched individually to their corresponding charges"
        matches.size() == 2
        def matchedTxIds = matches.collect { it.ynabTransaction.id }
        matchedTxIds.contains("tx1")
        matchedTxIds.contains("tx2")
        matches.every { it.walmartOrder.orderId == "1234" }
        matches.every { !it.isMultiTransaction }
    }
    
    def "findWalmartMatches should match three transactions to individual final charges"() {
        given: "three Walmart transactions that match individual final charges"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 50.00, "WALMART"),
            createSampleTransaction("tx2", "2023-05-16", 50.00, "WALMART.COM"),
            createSampleTransaction("tx3", "2023-05-17", 50.00, "WAL-MART")
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-05-15", 150.00)
        order.orderStatus = "Delivered"  // Make sure it's delivered
        order.addFinalCharge(-50.00)
        order.addFinalCharge(-50.00)
        order.addFinalCharge(-50.00)
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "all three transactions should be matched individually"
        matches.size() == 3
        def matchedTxIds = matches.collect { it.ynabTransaction.id }
        matchedTxIds.contains("tx1")
        matchedTxIds.contains("tx2")
        matchedTxIds.contains("tx3")
        matches.every { it.walmartOrder.orderId == "1234" }
        matches.every { !it.isMultiTransaction }
    }
    
    def "findWalmartMatches should NOT match when transaction amounts don't match individual charges"() {
        given: "two Walmart transactions that don't match individual final charges"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 75.00, "WALMART"),
            createSampleTransaction("tx2", "2023-05-16", 75.00, "WALMART")
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-05-15", 150.00)
        order.orderStatus = "Delivered"  // Make sure it's delivered
        // Final charges are different from transaction amounts
        order.addFinalCharge(-100.00)
        order.addFinalCharge(-50.00)
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "no matches should be found since transaction amounts don't match individual charges"
        matches.isEmpty()
    }
    
    def "findWalmartMatches should not match transactions with different amounts"() {
        given: "Walmart transaction and order with different amounts"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "WALMART")
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-05-15", 26.99)
        order.orderStatus = "Delivered"
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "no matches should be found"
        matches.isEmpty()
    }
    
    def "findWalmartMatches should not match transactions with too distant dates"() {
        given: "Walmart transaction and order with distant dates"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "WALMART")
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-06-15", 25.99)  // One month later
        order.orderStatus = "Delivered"
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "no matches should be found due to date difference"
        matches.isEmpty()
    }
    
    def "findWalmartMatches should only match potential Walmart transactions"() {
        given: "Walmart and non-Walmart transactions"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 25.99, "WALMART"),
            createSampleTransaction("tx2", "2023-05-20", 49.99, "AMAZON.COM")
        ]
        
        def order1 = createSampleWalmartOrder("1234", "2023-05-15", 25.99)
        order1.orderStatus = "Delivered"
        def order2 = createSampleWalmartOrder("5678", "2023-05-20", 49.99)
        order2.orderStatus = "Delivered"
        def orders = [order1, order2]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "only Walmart transactions should be matched"
        matches.size() == 1
        matches[0].ynabTransaction.id == "tx1"
        matches[0].walmartOrder.orderId == "1234"
    }
    
    def "findWalmartMatches should match only transactions that match individual charges"() {
        given: "two Walmart transactions where only one matches a final charge"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 100.00, "WALMART"),
            createSampleTransaction("tx2", "2023-05-16", 40.00, "WALMART")  // Doesn't match any charge
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-05-15", 150.00)
        order.orderStatus = "Delivered"
        order.addFinalCharge(-100.00)
        order.addFinalCharge(-50.00)
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "only the matching transaction should be found"
        matches.size() == 1
        matches[0].ynabTransaction.id == "tx1"
        !matches[0].isMultiTransaction
    }
    
    def "findWalmartMatches should not match transactions too far apart in time"() {
        given: "two Walmart transactions more than 14 days apart from order date"
        def transactions = [
            createSampleTransaction("tx1", "2023-05-15", 100.00, "WALMART"),
            createSampleTransaction("tx2", "2023-06-01", 50.00, "WALMART")  // 17 days from order date
        ]
        
        def order = createSampleWalmartOrder("1234", "2023-05-15", 150.00)
        order.orderStatus = "Delivered"
        order.addFinalCharge(-100.00)
        order.addFinalCharge(-50.00)
        def orders = [order]
        
        when: "findWalmartMatches is called"
        def matches = matcher.findWalmartMatches(transactions, orders)
        
        then: "only tx1 should match since tx2 is too far from order date"
        matches.size() == 1
        matches[0].ynabTransaction.id == "tx1"
        !matches[0].isMultiTransaction
    }
    
    @Unroll
    def "isPotentialWalmartTransaction should identify Walmart-related payees: #payeeName"() {
        given: "a transaction with the specified payee name"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, payeeName)
        
        when: "we check if it's a potential Walmart transaction"
        def method = TransactionMatcher.getDeclaredMethod("isPotentialWalmartTransaction", YNABTransaction)
        method.setAccessible(true)
        def result = method.invoke(matcher, transaction)
        
        then: "the result should match expected outcome"
        result == expected
        
        where:
        payeeName              | expected
        "WALMART"              | true
        "WAL-MART"             | true
        "WALMART.COM"          | true
        "WALMART ONLINE"       | true
        "AMAZON.COM"           | false
        "TARGET"               | false
        "TRANSFER: WALMART"    | false  // Blacklisted
    }
    
    def "generateWalmartProposedMemo should handle single item orders"() {
        given: "a Walmart order with a single item"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "WALMART")
        def walmartOrder = createSampleWalmartOrder("1234", "2023-05-15", 25.99)
        walmartOrder.addItem(createSampleWalmartOrderItem("Test Product", 25.99, 1))
        
        when: "generateWalmartProposedMemo is called"
        def method = TransactionMatcher.getDeclaredMethod("generateWalmartProposedMemo", 
            YNABTransaction, com.ynab.amazon.model.WalmartOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, walmartOrder)
        
        then: "the memo should contain the order ID and item title"
        memo == "Walmart Order: 1234 - Test Product"
    }
    
    def "generateWalmartProposedMemo should handle single transaction format"() {
        given: "a Walmart order with multiple items"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 50.00, "WALMART")
        def walmartOrder = createSampleWalmartOrder("1234", "2023-05-15", 150.00)
        walmartOrder.addItem(createSampleWalmartOrderItem("Product A", 50.00, 1))
        walmartOrder.addItem(createSampleWalmartOrderItem("Product B", 100.00, 1))
        
        when: "generateWalmartProposedMemo is called"
        def method = TransactionMatcher.getDeclaredMethod("generateWalmartProposedMemo", 
            YNABTransaction, com.ynab.amazon.model.WalmartOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, walmartOrder)
        
        then: "the memo should use single transaction format"
        memo.contains("Walmart Order: 1234 - ")
        !memo.contains("Charge")
    }
    
    def "generateWalmartProposedMemo should preserve existing memo"() {
        given: "a transaction with existing memo and a Walmart order"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "WALMART", "Original memo")
        def walmartOrder = createSampleWalmartOrder("1234", "2023-05-15", 25.99)
        walmartOrder.addItem(createSampleWalmartOrderItem("Test Product", 25.99, 1))
        
        when: "generateWalmartProposedMemo is called"
        def method = TransactionMatcher.getDeclaredMethod("generateWalmartProposedMemo", 
            YNABTransaction, com.ynab.amazon.model.WalmartOrder)
        method.setAccessible(true)
        def memo = method.invoke(matcher, transaction, walmartOrder)
        
        then: "the memo should preserve the original memo"
        memo == "Original memo | Walmart Order: 1234 - Test Product"
    }
    
    def "calculateIndividualChargeMatchScore should return high score for exact total match"() {
        given: "a transaction and order with exact amount and same date"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 25.99, "WALMART")
        def order = createSampleWalmartOrder("1234", "2023-05-15", 25.99)
        order.orderStatus = "Delivered"
        
        when: "calculateIndividualChargeMatchScore is called with order total"
        def method = TransactionMatcher.getDeclaredMethod("calculateIndividualChargeMatchScore", 
            YNABTransaction, com.ynab.amazon.model.WalmartOrder, BigDecimal)
        method.setAccessible(true)
        def score = method.invoke(matcher, transaction, order, order.totalAmount)
        
        then: "score should be high (0.8 or above)"
        score >= 0.8
    }
    
    def "calculateIndividualChargeMatchScore should return high score for exact charge match"() {
        given: "a transaction that matches a specific final charge amount"
        def transaction = createSampleTransaction("tx1", "2023-05-15", 100.00, "WALMART")
        def order = createSampleWalmartOrder("1234", "2023-05-15", 150.00)
        order.orderStatus = "Delivered"
        order.addFinalCharge(-100.00)
        order.addFinalCharge(-50.00)
        
        when: "calculateIndividualChargeMatchScore is called"
        def method = TransactionMatcher.getDeclaredMethod("calculateIndividualChargeMatchScore", 
            YNABTransaction, com.ynab.amazon.model.WalmartOrder, BigDecimal)
        method.setAccessible(true)
        def score = method.invoke(matcher, transaction, order, new BigDecimal(-100.00))
        
        then: "score should be high (0.8 or above)"
        score >= 0.8
    }
    
    // Helper method to create Walmart order
    private com.ynab.amazon.model.WalmartOrder createSampleWalmartOrder(String orderId, String orderDate, BigDecimal totalAmount) {
        def order = new com.ynab.amazon.model.WalmartOrder()
        order.orderId = orderId
        order.orderDate = orderDate
        order.orderStatus = "Delivered"
        // Walmart amounts should be negative for purchases (same as YNAB transactions)
        order.totalAmount = -totalAmount
        return order
    }
    
    // Helper method to create Walmart order item
    private com.ynab.amazon.model.WalmartOrderItem createSampleWalmartOrderItem(String title, BigDecimal price, int quantity) {
        def item = new com.ynab.amazon.model.WalmartOrderItem()
        item.title = title
        // Walmart item prices should be negative for purchases (same as YNAB transactions)
        item.price = -price
        item.quantity = quantity
        return item
    }
}
