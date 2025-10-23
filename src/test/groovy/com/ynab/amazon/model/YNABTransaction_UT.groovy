package com.ynab.amazon.model

import spock.lang.Specification

/**
 * Test class for the YNABTransaction model
 */
class YNABTransaction_UT extends Specification {

    def "isCleared should return true for cleared transactions"() {
        given: "a transaction with cleared status"
        def transaction = new YNABTransaction(cleared: "cleared")
        
        expect: "isCleared returns true"
        transaction.isCleared()
    }
    
    def "isCleared should return false for non-cleared transactions"() {
        expect: "isCleared returns false for various non-cleared statuses"
        !new YNABTransaction(cleared: "uncleared").isCleared()
        !new YNABTransaction(cleared: "reconciled").isCleared()
        !new YNABTransaction(cleared: null).isCleared()
    }
    
    def "isApproved should return true for approved transactions"() {
        given: "a transaction with approved status"
        def transaction = new YNABTransaction(approved: "true")
        
        expect: "isApproved returns true"
        transaction.isApproved()
    }
    
    def "isApproved should return false for non-approved transactions"() {
        expect: "isApproved returns false for various non-approved statuses"
        !new YNABTransaction(approved: "false").isApproved()
        !new YNABTransaction(approved: null).isApproved()
    }
    
    def "isDeleted should return true for deleted transactions"() {
        given: "a transaction with deleted status"
        def transaction = new YNABTransaction(deleted: "true")
        
        expect: "isDeleted returns true"
        transaction.isDeleted()
    }
    
    def "isDeleted should return false for non-deleted transactions"() {
        expect: "isDeleted returns false for various non-deleted statuses"
        !new YNABTransaction(deleted: "false").isDeleted()
        !new YNABTransaction(deleted: null).isDeleted()
    }
    
    def "isTransfer should return true when transfer_account_id exists"() {
        given: "a transaction with transfer_account_id"
        def transaction = new YNABTransaction(transfer_account_id: "account123")
        
        expect: "isTransfer returns true"
        transaction.isTransfer()
    }
    
    def "isTransfer should return false when transfer_account_id is null"() {
        given: "a transaction without transfer_account_id"
        def transaction = new YNABTransaction(transfer_account_id: null)
        
        expect: "isTransfer returns false"
        !transaction.isTransfer()
    }
    
    def "getDisplayAmount should format amount correctly"() {
        given: "a transaction with an amount in milliunits"
        def transaction = new YNABTransaction(amount: 12340) // 12.34 in milliunits
        
        when: "getDisplayAmount is called"
        def displayAmount = transaction.getDisplayAmount()
        
        then: "the amount is correctly formatted"
        displayAmount == "12.34"
    }
    
    def "getDisplayAmount should handle null amount"() {
        given: "a transaction with null amount"
        def transaction = new YNABTransaction(amount: null)
        
        when: "getDisplayAmount is called"
        def displayAmount = transaction.getDisplayAmount()
        
        then: "default value is returned"
        displayAmount == "0.00"
    }
    
    def "getAmountInDollars should convert milliunits to dollars"() {
        given: "a transaction with an amount in milliunits"
        def transaction = new YNABTransaction(amount: 12340) // 12.34 in milliunits
        
        when: "getAmountInDollars is called"
        def amountInDollars = transaction.getAmountInDollars()
        
        then: "the amount is correctly converted to dollars"
        amountInDollars == 12.34f
    }
    
    def "getAmountInDollars should handle null amount"() {
        given: "a transaction with null amount"
        def transaction = new YNABTransaction(amount: null)
        
        when: "getAmountInDollars is called"
        def amountInDollars = transaction.getAmountInDollars()
        
        then: "default value is returned"
        amountInDollars == 0.0f
    }
    
    def "getDisplayDate should return the date"() {
        given: "a transaction with a date"
        def transaction = new YNABTransaction(date: "2023-01-15")
        
        when: "getDisplayDate is called"
        def displayDate = transaction.getDisplayDate()
        
        then: "the date is returned"
        displayDate == "2023-01-15"
    }
    
    def "getDisplayDate should handle null date"() {
        given: "a transaction with null date"
        def transaction = new YNABTransaction(date: null)
        
        when: "getDisplayDate is called"
        def displayDate = transaction.getDisplayDate()
        
        then: "empty string is returned"
        displayDate == ""
    }
    
    def "toString should include key transaction information"() {
        given: "a populated YNABTransaction"
        def transaction = new YNABTransaction(
            id: "tx-123",
            date: "2023-01-15",
            amount: 12340,
            memo: "Test memo",
            payee_name: "Test Payee"
        )
        
        when: "toString is called"
        def string = transaction.toString()
        
        then: "it should contain key transaction information"
        string.contains("tx-123")
        string.contains("2023-01-15")
        string.contains("12340")
        string.contains("Test memo")
        string.contains("Test Payee")
    }
}
