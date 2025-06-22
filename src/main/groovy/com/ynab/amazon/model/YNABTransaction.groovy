package com.ynab.amazon.model

import groovy.transform.CompileStatic

/**
 * Model class representing a YNAB transaction
 */
@CompileStatic
class YNABTransaction {
    String id
    String date
    BigDecimal amount
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
    
    String getDisplayAmount() {
        return amount ? amount.toString() : "0"
    }
    
    String getDisplayDate() {
        return date ?: ""
    }
    
    @Override
    String toString() {
        return "YNABTransaction{id='${id}', date='${date}', amount=${amount}, memo='${memo}', payee='${payee_name}'}"
    }
} 