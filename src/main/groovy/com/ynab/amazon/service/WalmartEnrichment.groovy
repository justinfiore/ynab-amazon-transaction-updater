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
        def stats = [updated: 0, high_confidence: matches.size(), medium_confidence: 0, low_confidence: 0,
            refund_matches: matches.count { it.ynabTransaction.amount > 0 }, refund_memos_updated: 0, preview: []]
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
            // A later refund must not reopen or be blocked by a frozen purchase allocation.
            def groups = matches.groupBy { it.walmartOrder.orderId + (it.ynabTransaction.amount > 0 ? '/refunds/' + it.walmartCharge.key : '') }
            Closure<List<WalmartOrderItem>> itemsFor = { List<TransactionMatch> group ->
                if (config.walmartEnrichment != 'exception_first') return []
                if (group[0].ynabTransaction.amount > 0) {
                    def refund = group[0].walmartCharge
                    return refund.description && WalmartAllocation.eligible(group[0].ynabTransaction, accounts, true, config.walmartAllowApprovedUncategorized) ?
                        [new WalmartOrderItem(itemId:refund.key, title:refund.description)] : []
                }
                group[0].walmartOrder.complete ? group[0].walmartOrder.items : []
            }
            if (classifier && !dryRun && config.walmartEnrichment == 'exception_first') {
                // Pool inference only. Order matching, allocation and write verification remain isolated.
                try {
                    String scope = classifier.scope(categories)
                    state.inferred = state.inferred ?: [:]
                    Map<String,WalmartOrderItem> pending = [:]
                    groups.values().each { group ->
                        itemsFor(group).each { item ->
                            def evidence = state.mappings[config.ynabBudgetId + '/' + item.productId]
                            if (evidence instanceof List && evidence) return
                            String cacheKey = ItemClassificationService.cacheKey('walmart',config.ynabBudgetId,[productId:item.productId,title:item.title],scope)
                            if (state.inferred[cacheKey]?.scope != scope) {
                                // Correlation IDs are local; the provider receives only titles and policy.
                                pending[cacheKey] = new WalmartOrderItem(itemId:cacheKey, productId:item.productId, title:item.title)
                            }
                        }
                    }
                    ItemClassificationService.resolve(pending.values().collect { [itemId:it.itemId,productId:it.productId,title:it.title] },
                        categories,state,'walmart',config.ynabBudgetId,classifier,false,save)
                } catch (Exception ignored) {
                    LOG.warn('Walmart batched classification failed: provider={} stage={} first_request_failure={} http_status={} requests={}; unresolved purchases skip allocation, refunds use default',
                        config.walmartCategorization.provider, classifier.metrics.stage,
                        classifier.metrics.failure_stage, classifier.metrics.last_http_status, classifier.metrics.requests)
                }
            }
            groups.each { String groupId, List<TransactionMatch> group ->
                String orderId = group[0].walmartOrder.orderId
                boolean refunds = group[0].ynabTransaction.amount > 0
                String key = config.ynabBudgetId + '/' + groupId
                Map old = state.plans[key]
                Map decisions = [:]
                boolean inferenceFailed = false
                String inferenceReason = 'inference_unavailable'
                def order = group[0].walmartOrder
                def refund = refunds ? group[0].walmartCharge : null
                if (!refunds) {
                    def covered = group.collectMany { it.walmartCharge.components ?: [it.walmartCharge] }
                    LOG.info('Walmart order grouping: order_id={} parents={} covered_events={} settled_events={} ledger_covered={} matched_cents={} total_cents={}',
                        orderId, group.size(), covered.size(), order.charges.size(),
                        covered.size() == order.charges.size() && covered*.key.toSet() == order.charges*.key.toSet(),
                        group.sum { it.walmartCharge.cents }, order.totalAmount.movePointRight(2))
                }
                List<WalmartOrderItem> classificationItems = itemsFor(group)
                if (classificationItems) {
                    // Queue above is the only paid attempt. This pass resolves mappings/cache only.
                    Map resolved = ItemClassificationService.resolve(classificationItems.collect { [itemId:it.itemId,productId:it.productId,title:it.title] },
                        categories,state,'walmart',config.ynabBudgetId,classifier,true,{ ignored -> })
                    decisions = resolved.decisions
                    inferenceFailed = resolved.failed || (config.walmartCategorization.provider && !classifier && decisions.values().any { it.source == 'unresolved' })
                    if (dryRun && resolved.failed) inferenceReason = 'inference_required'
                }
                Map plan = config.walmartEnrichment != 'exception_first' ? [eligible:false, reason:'link_only'] :
                    refunds && !(dryRun && inferenceFailed) ? WalmartAllocation.refundPlan(group[0], config.walmartDefaultCategoryId, decisions, accounts, config.walmartAllowApprovedUncategorized) :
                    inferenceFailed ? [eligible:false, reason:inferenceReason] : WalmartAllocation.plan(order, group, config.walmartDefaultCategoryId, decisions, accounts, config.walmartAllowApprovedUncategorized)
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
                    def snapshot = group.find { it.ynabTransaction.id == op.transaction_id }.ynabTransaction
                    boolean eligible = WalmartAllocation.eligible(snapshot, accounts, refunds, config.walmartAllowApprovedUncategorized)
                    saved?.pending && saved.allocating ? !(same(remote[op.transaction_id], saved.intent) ||
                        (eligible && same(remote[op.transaction_id], saved.baseline))) :
                    saved?.allocation ? !same(remote[op.transaction_id], saved.result ?: saved.intent) :
                    entry.frozen ? (!eligible || !same(remote[op.transaction_id], entry.baselines?.get(op.transaction_id))) :
                        !initial(snapshot, remote[op.transaction_id], accounts, refunds, config.walmartAllowApprovedUncategorized)
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
                    if (refunds) stats.refund_memos_updated++
                }
            }
        }
        // Purchase/item details are available to the caller, not persistent logs.
        LOG.info('Walmart processing: {} matched, {} updated', matches.size(), stats.updated)
        LOG.info('Walmart refunds: {} matched, {} memos updated', stats.refund_matches, stats.refund_memos_updated)
        stats
    }

    private static boolean initial(YNABTransaction tx, Map current, Set accounts, boolean refund = false, boolean allowApprovedUncategorized = false) {
        if (!current || current.deleted || current.transfer_account_id || current.cleared == 'reconciled') return false
        if (accounts != null && (!WalmartAllocation.eligible(tx, accounts, refund, allowApprovedUncategorized) || current.subtransactions || !accounts.contains(current.account_id))) return false
        ['id', 'date', 'amount', 'account_id', 'payee_id', 'payee_name', 'flag_color', 'memo', 'category_id', 'cleared', 'approved', 'import_id'].every { field ->
            def before = tx."${field}"
            if (field == 'approved') return tx.getApproved() == current[field]?.toString()
            if (field == 'cleared') return tx.getCleared() == current[field]
            if (before == 'null') before = null
            before == current[field]
        } && (tx.subtransactions ?: []) == (current.subtransactions ?: [])
    }

    static boolean same(Map actual, Map expected) {
        GuardedTransactions.same(actual, expected)
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

    static String fingerprint(Object value) { ItemClassificationService.fingerprint(value) }
}
