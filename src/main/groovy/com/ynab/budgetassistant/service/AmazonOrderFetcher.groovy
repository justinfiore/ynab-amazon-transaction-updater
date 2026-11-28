package com.ynab.budgetassistant.service

import com.ynab.budgetassistant.model.AmazonOrder

interface AmazonOrderFetcher {
    List<AmazonOrder> fetchOrders()
}
