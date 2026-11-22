package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.*
import java.security.MessageDigest
import org.slf4j.LoggerFactory

/** Walmart-only orchestration: frozen intent, fresh reads, no legacy processed-ID heuristic. */
class WalmartEnrichment {
    private static final JSON = new ObjectMapper()
    private static final LOG = LoggerFactory.getLogger(WalmartEnrichment)
    private final Configuration config

    WalmartEnrichment(Configuration config) { this.config = config }

    Map process(List<TransactionMatch> matches, YNABService ynab, boolean dryRun, WalmartRemoteClassifier classifier = null) {
        def stats = [updated: 0, high_confidence: matches.size(), medium_confidence: 0, low_confidence: 0, preview: []]
        if (!matches) return stats
        // Recheck at the write boundary as well as before workflow matching.
        if (ynab.getWalmartPlanCurrency() != 'USD') {
            stats.preview << [reason:'unsupported_or_unknown_plan_currency']
            return stats
        }
        if (!classifier && config.walmartEnrichment == 'exception_first' && config.walmartCategorization.provider) {
            try { classifier = new WalmartRemoteClassifier(config.walmartCategorization) }
            catch (Exception ignored) { LOG.warn('Walmart provider connection unavailable; rerun setup or use mappings only') }
        }
        new WalmartJournal(config.walmartJournalFile).locked(dryRun) { Map state, Closure save ->
            Map categories = [:]
            Set accounts = [] as Set
            if (config.walmartEnrichment == 'exception_first') {
                categories = WalmartAllocation.categories(ynab.getWalmartCategories(), config.walmartCategorization.categories as List, config.walmartDefaultCategoryId)
                accounts = ynab.getWalmartBudgetAccounts()
                learn(state, ynab)
                if (!dryRun) save(state)
            }
            matches.groupBy { it.walmartOrder.orderId }.each { String orderId, List<TransactionMatch> group ->
                String key = config.ynabBudgetId + '/' + orderId
                Map old = state.plans[key]
                Map decisions = [:]
                boolean inferenceFailed = false
                String inferenceReason = 'inference_unavailable'
                def order = group[0].walmartOrder
                if (config.walmartEnrichment == 'exception_first') {
                    order.items.each { item ->
                        def evidence = state.mappings[config.ynabBudgetId + '/' + item.productId]
                        def choices = evidence instanceof List ? evidence.toSet() : [] as Set
                        String category = item.productId && choices.size() == 1 && categories.containsKey(choices.first()) ? choices.first() : null
                        decisions[item.itemId] = [category_id: category, source: choices ? 'approved_mapping' : 'unresolved', conflict: choices.size() > 1]
                    }
                    def pending = order.items.findAll { decisions[it.itemId].source == 'unresolved' }
                    if (pending && config.walmartCategorization.provider && !classifier) inferenceFailed = true
                    if (pending && classifier) {
                        try {
                            String scope = classifier.scope(categories)
                            state.inferred = state.inferred ?: [:]
                            Map cacheKeys = pending.collectEntries { item -> [(item.itemId):fingerprint([config.ynabBudgetId,item.productId,item.title,scope])] }
                            pending.each { item ->
                                Map cached = state.inferred[cacheKeys[item.itemId]]
                                if (cached?.scope == scope) decisions[item.itemId] = cached
                            }
                            def uncached = pending.findAll { decisions[it.itemId].source == 'unresolved' }
                            if (uncached) {
                                if (dryRun) {
                                    inferenceFailed = true
                                    inferenceReason = classifier.qualified(categories) ? 'inference_required' : 'inference_unavailable'
                                } else {
                                    Map occurrences = uncached.groupBy { cacheKeys[it.itemId] }
                                    def inferred = classifier.classify(occurrences.values().collect { it.first() }, categories)
                                    occurrences.each { cacheKey, items ->
                                        Map decision = inferred[items.first().itemId]
                                        items.each { decisions[it.itemId] = decision }
                                        state.inferred[cacheKey] = decision
                                    }
                                    save(state)
                                }
                            }
                        } catch (Exception ignored) {
                            inferenceFailed = true
                            LOG.warn('Walmart remote classification unavailable/unqualified; allocation skipped. Check key setup, qualification and limits')
                        }
                    }
                }
                Map plan = inferenceFailed ? [eligible:false, reason:inferenceReason] : config.walmartEnrichment == 'exception_first' ?
                    WalmartAllocation.plan(order, group, config.walmartDefaultCategoryId, decisions, accounts) : [eligible: false, reason: 'link_only']
                // Source/settings/mappings freeze even for operations already completed.
                String fingerprint = fingerprint([order: [id: order.orderId, date: order.orderDate, total: order.totalAmount,
                    currency: order.currency, complete: order.complete, items: order.items.collect { [it.itemId, it.productId, it.title, it.grossCents] }.sort { it[0] },
                    charges: order.charges.sort(false) { it.key }, adjustments: order.adjustments],
                    assignments: group.collect { [it.walmartCharge?.key, it.ynabTransaction.id, it.ynabTransaction.account_id] }.sort { it[0] },
                    default: config.walmartDefaultCategoryId, categories: categories,
                    model: config.walmartCategorization.findAll { k,v -> k in ['provider','model','policy_version','acceptance'] },
                    connection: classifier?.connection?.connection_id, decisions: decisions])
                if (old?.frozen && old.fingerprint != fingerprint) {
                    stats.preview << [order_id: orderId, reason: 'applied_plan_drift']; return
                }
                if (old?.frozen) plan = old.plan
                Map entry = old ?: [operations: [:], allocated: false]
                if (!entry.frozen) { entry.fingerprint = fingerprint; entry.plan = plan }
                state.plans[key] = entry
                def operations = plan.eligible ? plan.operations : group.findAll { it.proposedMemo }.collect {
                    [transaction_id: it.ynabTransaction.id, charge_key: it.walmartCharge?.key, memo: it.proposedMemo]
                }
                stats.preview << [order_id: orderId, reason: plan.reason, operations: operations]
                if (dryRun) return
                // Recheck every parent before the first allocation write, including
                // known journaled results, to avoid partial application over human edits.
                Map remote = operations.collectEntries { [(it.transaction_id): ynab.getWalmartTransaction(it.transaction_id)] }
                if (plan.eligible && operations.any { op ->
                    Map saved = entry.operations[op.transaction_id]
                    saved?.pending && saved.allocating ? !(same(remote[op.transaction_id], saved.intent) || same(remote[op.transaction_id], saved.baseline)) :
                    saved?.allocation ? !same(remote[op.transaction_id], saved.result ?: saved.intent) :
                    entry.frozen ? !same(remote[op.transaction_id], entry.baselines?.get(op.transaction_id)) :
                        !initial(group.find { it.ynabTransaction.id == op.transaction_id }.ynabTransaction, remote[op.transaction_id], accounts)
                }) { stats.preview << [order_id: orderId, reason: 'parent_changed']; return }
                if (plan.eligible && !entry.frozen) {
                    // Freeze every original parent, including operations not yet attempted.
                    entry.baselines = remote
                    entry.frozen = true
                    save(state)
                }
                for (Map op : operations) {
                    String id = op.transaction_id
                    Map previous = entry.operations[id]
                    Map current = ynab.getWalmartTransaction(id)
                    if (previous?.allocation || (!plan.eligible && previous?.link)) {
                        if (!same(current, previous.result ?: previous.intent)) break
                        continue
                    }
                    if (previous?.pending) {
                        if (same(current, previous.intent)) {
                            previous.result = current; previous.pending = false
                            previous.allocation = previous.allocating; previous.link = true
                            entry.allocated |= previous.allocation
                            save(state); continue
                        }
                        if (!same(current, previous.baseline)) break
                    }
                    def match = group.find { it.ynabTransaction.id == id }
                    if (plan.eligible ? (!accounts.contains(current.account_id) || !same(current, entry.baselines?.get(id))) :
                        !initial(match.ynabTransaction, current, null)) break
                    Map changes = plan.eligible ? WalmartAllocation.payload(current, op) : [memo: op.memo]
                    if (current.memo == op.memo && !plan.eligible) continue
                    Map intent = current + changes
                    def operation = [baseline: current, intent: intent, pending: true, allocating: plan.eligible,
                        allocation: false, link: false, lines: op.lines ?: []]
                    entry.operations[id] = operation
                    if (plan.eligible) entry.frozen = true
                    save(state) // durable intent before PUT; network errors have unknown outcomes
                    try { ynab.putWalmartTransaction(id, changes) } catch (Exception ignored) { }
                    Map after = ynab.getWalmartTransaction(id)
                    if (!same(after, intent)) break
                    operation.result = after; operation.pending = false
                    operation.link = true; operation.allocation = plan.eligible
                    entry.allocated |= plan.eligible
                    save(state)
                    stats.updated++
                }
            }
        }
        // Purchase/item details are available to the caller, not persistent logs.
        LOG.info('Walmart processing: {} matched, {} updated', matches.size(), stats.updated)
        stats
    }

    private static boolean initial(YNABTransaction tx, Map current, Set accounts) {
        if (!current || current.deleted || current.transfer_account_id || current.cleared == 'reconciled') return false
        if (accounts != null && (!WalmartAllocation.eligible(tx, accounts) || current.approved || current.subtransactions || !accounts.contains(current.account_id))) return false
        ['id', 'date', 'amount', 'account_id', 'payee_id', 'payee_name', 'flag_color', 'memo', 'category_id', 'cleared', 'approved', 'import_id'].every { field ->
            def before = tx."${field}"
            if (field == 'approved') return tx.getApproved() == current[field]?.toString()
            if (field == 'cleared') return tx.getCleared() == current[field]
            if (before == 'null') before = null
            before == current[field]
        } && (tx.subtransactions ?: []) == (current.subtransactions ?: [])
    }

    static boolean same(Map actual, Map expected) {
        if (!actual || !expected) return false
        def fields = ['id','date','amount','account_id','payee_id','payee_name','memo','category_id','cleared','approved','import_id','flag_color','deleted','transfer_account_id']
        fields.every { actual[it] == expected[it] } &&
            (actual.subtransactions ?: []).collect { [it.amount,it.category_id,it.memo,it.payee_id] }.sort { it.toString() } ==
            (expected.subtransactions ?: []).collect { [it.amount,it.category_id,it.memo,it.payee_id] }.sort { it.toString() }
    }

    private void learn(Map state, YNABService ynab) {
        Map observations = [:].withDefault { [] }
        state.plans.findAll { key, value -> key.startsWith(config.ynabBudgetId + '/') }.values().each { entry ->
            Map fragments = [:].withDefault { [] }
            (entry.plan.operations ?: []).each { op ->
                String id=op.transaction_id
                Map saved=entry.operations[id] ?: [lines:op.lines ?: []]
                Map current = saved.allocation && saved.result ? ynab.getWalmartTransaction(id) : null
                def originalRows = saved.result ? (saved.result.subtransactions ?: [saved.result]) : []
                def currentRows = current ? (current.subtransactions ?: [current]) : []
                boolean intact = current?.approved == true && !current.deleted &&
                    ['id','amount','date','account_id','payee_id','payee_name','memo','import_id','cleared','flag_color'].every { current[it]==saved.result[it] } &&
                    originalRows.size()==currentRows.size() && originalRows.every { before ->
                        def after=currentRows.find { it.id==before.id }
                        after && !after.deleted && after.amount==before.amount && after.memo==before.memo && after.payee_id==before.payee_id
                    }
                saved.lines.findAll { it.item_id && it.product_id }.each { line ->
                    def rows = originalRows.findAll { it.amount == -((long)line.cents)*10 && (!saved.result.subtransactions || it.memo == line.memo) }
                    def row = rows.size()==1 && rows[0].id ? currentRows.find { it.id==rows[0].id } : null
                    // Evidence is independent of write eligibility: an out-of-list
                    // human correction must still veto conflicting automatic reuse.
                    fragments[line.item_id] << [product:line.product_id,category:intact && row ? row.category_id : null]
                }
                saved.reviewed = intact
            }
            fragments.values().each { pieces ->
                if (pieces.every { it.category } && pieces*.product.toSet().size()==1) {
                    observations[config.ynabBudgetId+'/'+pieces[0].product].addAll(pieces*.category)
                }
            }
        }
        // Recompute prior learned evidence so later corrections or lost provenance
        // do not leave a stale permanent vote. Other plans remain isolated.
        List learned = new ArrayList(state.learnedProducts ?: [])
        String prefix = String.valueOf(config.ynabBudgetId) + '/'
        learned.findAll { String.valueOf(it).startsWith(prefix) }.each { state.mappings.remove(it) }
        observations.each { key, values -> state.mappings[key] = values.unique().sort() }
        state.learnedProducts = (learned.findAll { !String.valueOf(it).startsWith(prefix) } + new ArrayList(observations.keySet())).unique().sort()
    }

    static String fingerprint(Object value) { MessageDigest.getInstance('SHA-256').digest(JSON.writeValueAsBytes(value)).encodeHex().toString() }
}
