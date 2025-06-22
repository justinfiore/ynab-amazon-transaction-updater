package com.ynab.amazon.model

import groovy.transform.CompileStatic

/**
 * Model class representing an Amazon order
 */
@CompileStatic
class AmazonOrder {
    String orderId
    String orderDate
    BigDecimal totalAmount
    String paymentMethod
    List<AmazonOrderItem> items
    String status
    String shippingAddress
    
    AmazonOrder() {
        this.items = []
    }
    
    void addItem(AmazonOrderItem item) {
        if (items == null) {
            items = []
        }
        items.add(item)
    }
    
    String getProductSummary() {
        if (!items || items.isEmpty()) {
            return "Amazon Order"
        }
        
        if (items.size() == 1) {
            return items[0].title
        }
        
        return "${items.size()} items: ${items.collect { it.title }.take(3).join(', ')}${items.size() > 3 ? '...' : ''}"
    }
    
    BigDecimal getTotalAmount() {
        return totalAmount ?: 0
    }
    
    String getDisplayDate() {
        return orderDate ?: ""
    }
    
    @Override
    String toString() {
        return "AmazonOrder{orderId='${orderId}', date='${orderDate}', amount=${totalAmount}, items=${items?.size() ?: 0}}"
    }
}

/**
 * Model class representing an item within an Amazon order
 */
@CompileStatic
class AmazonOrderItem {
    String title
    String asin
    BigDecimal price
    int quantity
    String category
    
    BigDecimal getTotalPrice() {
        return price ? price * quantity : 0
    }
    
    @Override
    String toString() {
        return "AmazonOrderItem{title='${title}', price=${price}, quantity=${quantity}}"
    }
} 