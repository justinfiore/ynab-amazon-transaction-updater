package com.ynab.amazon.service

import com.fasterxml.jackson.annotation.JsonProperty

/** Schema-v1 payload owned by the repository Python bridge. */
class PythonBridgeEnvelope {
    @JsonProperty('schema_version')
    Integer schemaVersion
    List<PythonBridgeOrder> orders
}

class PythonBridgeOrder {
    @JsonProperty('order_number')
    String orderNumber
    @JsonProperty('order_placed_date')
    String orderPlacedDate
    @JsonProperty('grand_total')
    BigDecimal grandTotal
    @JsonProperty('payment_method')
    String paymentMethod
    @JsonProperty('payment_method_last_4')
    String paymentMethodLast4
    Boolean cancelled
    List<PythonBridgeItem> items
}

class PythonBridgeItem {
    String title
    String asin
    BigDecimal price
    Integer quantity
}
