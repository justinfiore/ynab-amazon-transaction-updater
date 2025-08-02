package com.ynab.amazon.model

import groovy.transform.CompileStatic

/**
 * Model class representing a YNAB transaction
 */
@CompileStatic
class YNABTransaction {
    String id
    String date
    Long amount
    String memo
    String payee_name
    String category_name
    String account_id
    String cleared
    String approved
    String import_id
    String transfer_account_id
    String transfer_transaction_id
    String matched_transaction_id
    String deleted
    
    // Helper methods
    boolean isCleared() {
        return "cleared" == cleared
    }
    
    boolean isApproved() {
        return approved == "true"
    }
    
    boolean isDeleted() {
        return deleted == "true"
    }
    
    boolean isTransfer() {
        return transfer_account_id != null
    }
    
    /**
     * Returns the transaction amount in dollars as a formatted string (e.g., "12.34")
     * YNAB stores amounts as milliunits (1/1000 of a unit), so we divide by 1000
     */
    String getDisplayAmount() {
        if (amount == null) return "0.00"
        return String.format("%.2f", amount / 1000.0)
    }
    
    /**
     * Returns the transaction amount as a float value
     * @return the amount in dollars (e.g., 12.34)
     */
    float getAmountInDollars() {
        if (amount == null) return 0.0f
        return amount / 1000.0f
    }
    
    String getDisplayDate() {
        return date ?: ""
    }
    
    @Override
    String toString() {
        return "YNABTransaction{id='${id}', date='${date}', amount=${amount}, memo='${memo}', payee='${payee_name}'}"
    }
} 