package com.ynab.amazon.model

import groovy.transform.CompileStatic
import groovy.transform.MapConstructor

/**
 * Model class representing a match between a YNAB transaction and a retailer order (Amazon or Walmart)
 */
@CompileStatic
@MapConstructor
class TransactionMatch {
    YNABTransaction ynabTransaction
    List<YNABTransaction> transactions  // Support for multi-transaction matches
    AmazonOrder amazonOrder
    WalmartOrder walmartOrder
    String proposedMemo
    double confidenceScore
    String matchReason
    boolean isMultiTransaction
    
    // No-arg constructor to support Groovy named-argument initialization in tests
    TransactionMatch() {}

    TransactionMatch(YNABTransaction ynabTransaction, AmazonOrder amazonOrder, String proposedMemo, double confidenceScore, String matchReason) {
        this.ynabTransaction = ynabTransaction
        this.transactions = [ynabTransaction]
        this.amazonOrder = amazonOrder
        this.proposedMemo = proposedMemo
        this.confidenceScore = confidenceScore
        this.matchReason = matchReason
        this.isMultiTransaction = false
    }
    
    TransactionMatch(YNABTransaction ynabTransaction, WalmartOrder walmartOrder, String proposedMemo, double confidenceScore, String matchReason) {
        this.ynabTransaction = ynabTransaction
        this.transactions = [ynabTransaction]
        this.walmartOrder = walmartOrder
        this.proposedMemo = proposedMemo
        this.confidenceScore = confidenceScore
        this.matchReason = matchReason
        this.isMultiTransaction = false
    }
    
    TransactionMatch(List<YNABTransaction> transactions, WalmartOrder walmartOrder, String proposedMemo, double confidenceScore, String matchReason) {
        this.ynabTransaction = transactions?.first()
        this.transactions = transactions
        this.walmartOrder = walmartOrder
        this.proposedMemo = proposedMemo
        this.confidenceScore = confidenceScore
        this.matchReason = matchReason
        this.isMultiTransaction = transactions?.size() > 1
    }
    
    boolean isHighConfidence() {
        return confidenceScore >= 0.8
    }
    
    boolean isMediumConfidence() {
        return confidenceScore >= 0.6 && confidenceScore < 0.8
    }
    
    boolean isLowConfidence() {
        return confidenceScore < 0.6
    }
    
    @Override
    String toString() {
        String orderId = amazonOrder?.orderId ?: walmartOrder?.orderId
        String retailer = amazonOrder ? "Amazon" : "Walmart"
        return "TransactionMatch{ynabId='${ynabTransaction?.id}', ${retailer}OrderId='${orderId}', confidence=${confidenceScore}, multiTx=${isMultiTransaction}, reason='${matchReason}'}"
    }
} 