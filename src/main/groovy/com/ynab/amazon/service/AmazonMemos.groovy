package com.ynab.amazon.service

import com.ynab.amazon.model.AmazonOrder

class AmazonMemos {
    static boolean realId(String id) { id ==~ /[0-9]{3}-[0-9]{7}-[0-9]{7}/ }
    static String money(long cents) { BigDecimal.valueOf(cents,2).setScale(2).toPlainString() }
    static String item(Map item, boolean refund = false) {
        String text = (refund ? 'Refund: ' : '') + (item.quantity > 1 ? "${item.quantity} × " : '') + item.title
        bounded('🤖 ' + text,500)
    }
    static String parent(AmazonOrder order, Map event, String userText = '', int maximum = 500) {
        boolean refund = event?.signed_cents > 0
        String id = order.orderId?.replaceFirst(/^RETURN-/, '')
        List required = [(event?.order_only == true ? '🤖❓' : '🤖') + (userText ? ' ' + userText : '')]
        String date = refund ? event.date : order.orderDate
        if (date) {
            if (userText) required << date
            else required[0] += ' ' + date
        }
        Map evidence = order.financialEvidence
        if (refund) required << 'Refund $' + money(event.signed_cents as long) + ' USD'
        else {
            Long total = evidence ? evidence.full_total_cents as Long :
                order.@totalAmount != null ? order.@totalAmount.abs().movePointRight(2).longValueExact() : null
            if (total != null) required << 'Total $' + money(total) + (evidence.currency ? ' ' + evidence.currency : '')
            if (evidence.gift_cents > 0 && event?.signed_cents != null) {
                required << 'Gift card $' + money(evidence.gift_cents as long)
                required << 'Charged $' + money(Math.abs(event.signed_cents as long))
            }
        }
        if (id?.startsWith('SUB-')) required << 'Subscribe & Save'
        String link = realId(id) ? 'https://www.amazon.com/your-orders/order-details?orderID=' + id : null
        String suffix = link ? ' | ' + link : ''
        String base = required.join(' | ')
        if ((base + suffix).length() > maximum) return null
        List items = refund ? (event.refund_evidence?.items ?: []) :
            (evidence.items ?: order.items.collect { [title:it.title] })
        String summary = items.size() > 1 ? "${items.size()} items: " : ''
        summary += items.take(3).collect { it.title }.findAll { it }.join(', ')
        if (!items) summary = "❌ Amazon Order (Couldn't identify items)"
        if (items.size() > 3) summary += '…'
        int room = maximum - (base + suffix).length() - 3
        if (summary && room > 12) base += ' | ' + bounded(summary,room)
        base + suffix
    }
    private static String bounded(String text, int maximum) {
        if (text.length() <= maximum) return text
        int end = maximum - 1
        if (Character.isHighSurrogate(text.charAt(end-1))) end--
        text.substring(0,end) + '…'
    }
}
