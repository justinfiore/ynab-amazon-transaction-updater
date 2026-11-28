package com.ynab.amazon.service

/** Approximate item allocation using extended item prices as weights. */
class SheetzAllocation {
    static Map costs(List<Map> items, long total) {
        if (!items || total <= 0 ||
            items.any { !it.itemId || !it.title || !AmazonAllocation.integer(it.cents) } ||
            items*.itemId.toSet().size() != items.size()) return [eligible:false, reason:'invalid_financial_evidence']
        Map<String,Long> amounts = items.collectEntries { [(it.itemId):(it.cents as long)] }
        try {
            [eligible:true, costs:AmazonAllocation.distribute(total, amounts)]
        } catch (IllegalArgumentException ignored) {
            [eligible:false, reason:'invalid_allocation']
        }
    }
}
