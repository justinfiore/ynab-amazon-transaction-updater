package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import com.ynab.amazon.model.WalmartOrderItem
import java.time.Duration
import java.time.LocalDate

class GoWalmartOrderFetcher {
    static final Set<String> ENVIRONMENT = ['PATH', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'LANG', 'TZ'] as Set
    private final Configuration config
    private final BoundedProcessRunner runner
    private static final ObjectMapper JSON = new ObjectMapper()

    GoWalmartOrderFetcher(Configuration config, BoundedProcessRunner runner = new BoundedProcessRunner(ENVIRONMENT)) {
        this.config = config
        this.runner = runner
    }

    List<WalmartOrder> fetchOrders() {
        if (!config.walmartHelperExecutable || !config.walmartSessionFile) throw new IllegalStateException('Walmart helper/session unavailable; install the native Walmart distribution and import a session')
        def request = [version: 1, action: 'fetch', session_file: config.walmartSessionFile,
                       since: LocalDate.now().minusDays(config.lookBackDays).toString(), max_pages: config.walmartMaxPages]
        def result = runner.runWithInput([config.walmartHelperExecutable], Duration.ofSeconds(config.walmartTimeoutSeconds),
                                        config.walmartMaxOutputBytes, JSON.writeValueAsString(request))
        if (!result.success) throw new IllegalStateException("Walmart fetch failed: ${result.status}")
        try {
            def root = JSON.readValue(result.stdout, Map)
            if (root.version != 1 || root.status != 'ok' || !(root.orders instanceof List) || root.orders.size() > 400) throw new IllegalArgumentException()
            Set ids = [] as Set
            return root.orders.collect { Map raw ->
                if (!(raw.id ==~ /[A-Za-z0-9_-]{1,100}/) || !ids.add(raw.id) || !(raw.complete instanceof Boolean) ||
                    !(raw.items instanceof List) || !(raw.charges instanceof List) || !(raw.adjustments instanceof List)) throw new IllegalArgumentException()
                LocalDate.parse(raw.date)
                long total = integer(raw.total_cents)
                if (total <= 0) throw new IllegalArgumentException()
                def order = new WalmartOrder(orderId: raw.id, orderDate: raw.date, orderStatus: 'Delivered', currency: raw.currency,
                    orderUrl: raw.url, totalAmount: BigDecimal.valueOf(total, 2), complete: raw.complete, ineligibleReason: raw.reason)
                if (raw.url != order.orderLink) throw new IllegalArgumentException()
                Set itemIds = [] as Set
                order.items = raw.items.collect { item ->
                    long gross = integer(item.gross_cents)
                    if (!item.id || !itemIds.add(item.id) || !(item.title instanceof String) || item.title.size() > 1000 || gross < 0) throw new IllegalArgumentException()
                    BigDecimal quantity = new BigDecimal(item.quantity.toString())
                    if (quantity <= 0) throw new IllegalArgumentException()
                    new WalmartOrderItem(itemId: item.id, productId: item.product_id, title: item.title, quantity: quantity, grossCents: gross)
                }
                Set chargeKeys = [] as Set
                order.charges = raw.charges.collect { charge ->
                    long cost = integer(charge.cents)
                    if (!charge.key || !chargeKeys.add(charge.key) || !charge.payment || charge.currency != raw.currency || cost <= 0) throw new IllegalArgumentException()
                    LocalDate.parse(charge.date)
                    [key: charge.key, date: charge.date, payment: charge.payment, currency: charge.currency, cents: cost]
                }
                order.adjustments = raw.adjustments.collect { adjustment ->
                    if (!(adjustment.kind in ['tax', 'fee', 'tip', 'discount'])) throw new IllegalArgumentException()
                    long cost = integer(adjustment.cents)
                    if (cost < 0) throw new IllegalArgumentException()
                    [kind: adjustment.kind, cents: cost]
                }
                order
            }
        } catch (Exception ignored) {
            throw new IllegalStateException('Walmart fetch failed: INVALID_SCHEMA')
        }
    }

    private static long integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException()
        new BigDecimal(value.toString()).longValueExact()
    }
}
