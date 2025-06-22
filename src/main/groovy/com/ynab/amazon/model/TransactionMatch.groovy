package com.ynab.amazon.model

import groovy.transform.CompileStatic

/**
 * Model class representing a match between a YNAB transaction and an Amazon order
 */
@CompileStatic
class TransactionMatch {
    YNABTransaction ynabTransaction
    AmazonOrder amazonOrder
    String proposedMemo
    double confidenceScore
    String matchReason
    
    TransactionMatch(YNABTransaction ynabTransaction, AmazonOrder amazonOrder, String proposedMemo, double confidenceScore, String matchReason) {
        this.ynabTransaction = ynabTransaction
        this.amazonOrder = amazonOrder
        this.proposedMemo = proposedMemo
        this.confidenceScore = confidenceScore
        this.matchReason = matchReason
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
        return "TransactionMatch{ynabId='${ynabTransaction?.id}', amazonOrderId='${amazonOrder?.orderId}', confidence=${confidenceScore}, reason='${matchReason}'}"
    }
} 