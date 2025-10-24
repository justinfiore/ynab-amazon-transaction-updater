package com.ynab.amazon.model

import groovy.transform.CompileStatic

/**
 * Model class representing a Walmart order
 */
@CompileStatic
class WalmartOrder {
    String orderId
    String orderDate
    String orderStatus
    BigDecimal totalAmount
    List<BigDecimal> finalChargeAmounts
    List<WalmartOrderItem> items
    String orderUrl
    
    WalmartOrder() {
        this.items = []
        this.finalChargeAmounts = []
    }
    
    void addItem(WalmartOrderItem item) {
        if (items == null) {
            items = []
        }
        items.add(item)
    }
    
    void addFinalCharge(BigDecimal amount) {
        if (finalChargeAmounts == null) {
            finalChargeAmounts = []
        }
        finalChargeAmounts.add(amount)
    }
    
    String getProductSummary() {
        if (!items || items.isEmpty()) {
            return "Walmart Order"
        }
        
        if (items.size() == 1) {
            return items[0].title
        }
        
        return "${items.size()} items: ${items.collect { it.title }.take(3).join(', ')}${items.size() > 3 ? '...' : ''}"
    }
    
    String getOrderLink() {
        return "https://www.walmart.com/orders/details?orderId=${orderId}"
    }
    
    boolean isDelivered() {
        return orderStatus?.equalsIgnoreCase("Delivered")
    }
    
    boolean hasMultipleCharges() {
        return finalChargeAmounts && finalChargeAmounts.size() > 1
    }
    
    BigDecimal getTotalAmount() {
        return totalAmount ?: 0
    }
    
    String getDisplayDate() {
        return orderDate ?: ""
    }
    
    @Override
    String toString() {
        return "WalmartOrder{orderId='${orderId}', date='${orderDate}', status='${orderStatus}', amount=${totalAmount}, charges=${finalChargeAmounts?.size() ?: 0}, items=${items?.size() ?: 0}}"
    }
}

/**
 * Model class representing an item within a Walmart order
 */
@CompileStatic
class WalmartOrderItem {
    String title
    BigDecimal price
    int quantity
    String imageUrl
    
    BigDecimal getTotalPrice() {
        return price ? price * quantity : 0
    }
    
    @Override
    String toString() {
        return "WalmartOrderItem{title='${title}', price=${price}, quantity=${quantity}}"
    }
}
