package com.ynab.amazon.service

import com.ynab.amazon.model.YNABTransaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** Conservative matching and guarded writes for validated Sheetz purchase records. */
class SheetzEnrichment {
    static final String HISTORY = 'https://orders.sheetz.com/order/history'
    static final String NOTICE = '🤖❓ No matching Sheetz online or fuel purchase found'

    /** Return usable records and redacted diagnostics, retaining partial-fetch status. */
    static Map normalizeResponse(Object response) {
        if (!(response instanceof Map) || !(response.orders instanceof List)) throw new IllegalArgumentException('Invalid Sheetz response')
        Set ids = [] as Set
        List orders = [], errors = []
        response.orders.eachWithIndex { record, index ->
            def type = record instanceof Map && record.orderType in ['ANYWHERE', 'RETAIL_FUEL'] ? record.orderType : 'unknown'
            def fail = { String field, String reason -> errors << [record:index + 1, type:type, field:field, reason:reason, affects_matching:true] }
            if (!(record instanceof Map)) { fail('record','expected_object'); return }
            if (type == 'unknown') { fail('orderType','unsupported_or_missing'); return }
            if (!(record.orderNumber?.toString() ==~ /[0-9]{1,20}/)) { fail('orderNumber','invalid'); return }
            LocalDate date
            long cents
            try {
                def value = record.orderType == 'ANYWHERE' ? record.pickupTime : record.orderTime
                date = OffsetDateTime.parse(value as String).toLocalDate()
            } catch (Exception ignored) { fail(type == 'ANYWHERE' ? 'pickupTime' : 'orderTime','missing_or_invalid_offset_datetime'); return }
            try {
                cents = new BigDecimal(record.orderTotal.toString()).movePointRight(2).longValueExact()
            } catch (Exception ignored) { fail('orderTotal','missing_invalid_or_fractional_cent'); return }
            if (cents <= 0 || cents > Long.MAX_VALUE / 10) { fail('orderTotal','out_of_range'); return }
            if (!ids.add("${type}/${record.orderNumber}")) { fail('orderNumber','duplicate'); return }
            List titles = []
            if (!(record.items instanceof List)) errors << [record:index + 1, type:type, field:'items', reason:'expected_list', affects_matching:false]
            else record.items.eachWithIndex { item, itemIndex ->
                if (!(item instanceof Map)) errors << [record:index + 1, type:type, field:'items', item:itemIndex + 1, reason:'expected_object', affects_matching:false]
                else {
                    def title = item.description ?: item.receiptText
                    if (title instanceof String && title.trim()) titles << title.trim()
                    else errors << [record:index + 1, type:type, field:'description/receiptText', item:itemIndex + 1, reason:'missing_or_invalid', affects_matching:false]
                }
            }
            orders << [id: record.orderNumber.toString(), type: type, date: date, cents: cents, titles: titles]
        }
        [orders:orders, errors:errors, incomplete:errors.any { it.affects_matching }]
    }

    static Map plan(List<YNABTransaction> transactions, List<Map> orders, Set accounts) {
        def eligible = transactions.findAll { tx ->
            tx.id && tx.payee_name?.toLowerCase(Locale.ROOT)?.contains('sheetz') && accounts.contains(tx.account_id) &&
                !tx.isDeleted() && !tx.isTransfer() && tx.amount != null && tx.amount < 0 && tx.amount % 10 == 0 && validDate(tx.date)
        }
        Map candidates = eligible.collectEntries { tx ->
            [(tx.id): orders.findAll { order -> order.cents * 10L == -tx.amount &&
                Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(tx.date), order.date as LocalDate)) <= 3 }]
        }
        Map reverse = [:].withDefault { 0 }
        candidates.values().flatten().each { reverse[it.type + '/' + it.id]++ }
        eligible.collectEntries { tx ->
            def found = candidates[tx.id]
            String reason = !found ? 'unmatched' : found.size() == 1 && reverse[found[0].type + '/' + found[0].id] == 1 ? 'matched' : 'ambiguous'
            [(tx.id): [transaction: tx, reason: reason, order: reason == 'matched' ? found[0] : null]]
        }
    }

    static String memo(String existing, Map order) {
        String user = existing ?: ''
        def generated = user =~ /(?:^| \| )(🤖❓ No matching Sheetz online or fuel purchase found|🤖 \d{4}-\d{2}-\d{2} \| Total \$.*(?:https:\/\/orders\.sheetz\.com\/order\/history| \| Fuel purchase [^|]+))$/
        if (generated.find()) user = user.substring(0, generated.start())
        String prefix = user ? user + ' | ' : ''
        if (!order) {
            String result = prefix + NOTICE
            return result.length() <= 500 ? result : null
        }
        String base = prefix + '🤖 ' + order.date + ' | Total $' + AmazonMemos.money(order.cents as long)
        String suffix = order.type == 'ANYWHERE' ? ' | Order ' + order.id + ' | ' + HISTORY : ' | Fuel purchase ' + order.id
        if ((base + suffix).length() > 500) return null
        String summary = order.titles.take(3).join(', ')
        if (summary) {
            int room = 500 - (base + suffix).length() - 3
            if (room > 1) {
                if (summary.length() > room) {
                    int end = room - 1
                    if (end > 0 && Character.isHighSurrogate(summary.charAt(end - 1))) end--
                    summary = summary.substring(0, end) + '…'
                }
                base += ' | ' + summary
            }
        }
        base + suffix
    }

    static Map process(List<YNABTransaction> transactions, List<Map> orders, YNABService ynab, Set accounts,
                       boolean dryRun, boolean memoWrites, boolean categoryWrites, String fuelCategory, String unmatchedCategory,
                       boolean allowUnmatched = true, Set targetIds = null) {
        Map plans = plan(transactions, orders, accounts)
        def catalog = categoryWrites ? ynab.getCategoryCatalog() : []
        Set valid = catalog.findAll { !it.hidden && !it.deleted && !it.internal && !it.group_hidden && !it.group_deleted && !it.group_internal }.collect { it.id } as Set
        Map result = [preview: [], updated: 0, failures: []]
        plans.each { id, entry ->
            if (targetIds != null && !targetIds.contains(id)) return
            YNABTransaction tx = entry.transaction
            if (entry.reason == 'ambiguous') { result.preview << [transaction_id:id, reason:'ambiguous']; return }
            if (entry.reason == 'unmatched' && !allowUnmatched) { result.preview << [transaction_id:id, reason:'incomplete_history']; return }
            if (tx.getApproved() != 'false' || tx.getDeleted() != 'false' || tx.subtransactions || !(tx.getCleared() in ['cleared', 'uncleared'])) return
            Map changes = [:]
            if (memoWrites) {
                String proposed = memo(tx.memo, entry.order as Map)
                if (proposed != null && proposed != tx.memo) changes.memo = proposed
            }
            if (categoryWrites && entry.reason == 'unmatched') {
                if (unmatchedCategory && !valid.contains(unmatchedCategory)) result.preview << [transaction_id:id, reason:'invalid_unmatched_category']
                else if (tx.category_id != unmatchedCategory) changes.category_id = unmatchedCategory
            } else if (categoryWrites && entry.order?.type == 'RETAIL_FUEL' && !tx.category_id) {
                if (!fuelCategory || !valid.contains(fuelCategory)) result.preview << [transaction_id:id, reason:'invalid_gasoline_category']
                else changes.category_id = fuelCategory
            }
            result.preview << [transaction_id:id, reason:entry.reason, changes:changes]
            if (!changes || dryRun) return
            Map current = ynab.getTransaction(id as String)
            if (!sameSnapshot(tx, current)) return
            try { ynab.putTransaction(id as String, changes) } catch (Exception ignored) { }
            Map after
            try { after = ynab.getTransaction(id as String) } catch (Exception ignored) { }
            if (GuardedTransactions.same(after, current + changes)) result.updated++
            else result.failures << [transaction_id:id, reason:'write_not_confirmed']
        }
        result
    }

    private static boolean validDate(String date) {
        try { LocalDate.parse(date); true } catch (Exception ignored) { false }
    }

    private static boolean sameSnapshot(YNABTransaction tx, Map current) {
        if (!current || current.approved != false || current.deleted != false || current.subtransactions || current.transfer_account_id ||
            !(current.cleared in ['cleared', 'uncleared'])) return false
        ['id','date','amount','account_id','payee_id','payee_name','memo','category_id','cleared','import_id','flag_color'].every { field ->
            def before = field == 'cleared' ? tx.getCleared() : tx."${field}"
            if (before == 'null') before = null
            before == current[field]
        }
    }
}
