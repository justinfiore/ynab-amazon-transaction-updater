package com.ynab.amazon.service

import com.ynab.amazon.model.AmazonOrder

interface AmazonOrderFetcher {
    List<AmazonOrder> fetchOrders()
}
