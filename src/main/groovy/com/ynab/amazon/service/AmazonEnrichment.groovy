package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory

/** Event association, retailer accounting, durable intent, review and recovery. */
class AmazonEnrichment {
    private static final LOG = LoggerFactory.getLogger(AmazonEnrichment)
    final Configuration config
    AmazonEnrichment(Configuration config) { this.config = config }

    Map process(List<YNABTransaction> transactions, List<AmazonOrder> orders, YNABService ynab, boolean dryRun,
                Set<String> legacyIds = [] as Set, RemoteItemClassifier classifier = null) {
        Map stats = [updated:0,high_confidence:0,medium_confidence:0,low_confidence:0,preview:[],completed:[]]
        if (!transactions) return stats
        boolean financial = config.amazonEnrichment == 'item_categories'
        if (financial && ynab.getPlanCurrency() != 'USD') {
            LOG.warn('Amazon categorization blocked: unsupported_or_unknown_plan_currency')
            stats.preview << [reason:'unsupported_or_unknown_plan_currency']; return stats
        }
        Set accounts = ynab.getBudgetAccounts()
        if (financial && !accounts.containsAll(config.amazonPaymentAccounts.values())) {
            LOG.warn('Amazon categorization blocked: invalid_payment_account')
            stats.preview << [reason:'invalid_payment_account']; return stats
        }
        Map categories = financial ? ItemClassificationService.categories(ynab.getCategoryCatalog(),config.amazonCategorization.categories as List) : [:]
        if (!classifier && financial && config.amazonCategorization.provider && config.amazonCategorization.consent == true) {
            try { classifier = new RemoteItemClassifier(config.amazonCategorization) }
            catch (Exception ignored) { LOG.warn('Amazon provider unavailable; unresolved allocations will use guarded fallback') }
        }
        if (classifier && (config.amazonCategorization.consent != true || classifier.policy != 'amazon-choice-v1')) classifier = null
        try {
        new ProtectedJournal(config.amazonJournalFile).locked(dryRun) { Map state, Closure save ->
            transactions.findAll { legacyIds.contains(it.id) && new TransactionMatcher().isPotentialAmazonTransaction(it) }.each { tx ->
                LOG.info('Amazon decision: ref={} reason=legacy_processed_exclusion dry_run={}',ItemClassificationService.fingerprint(tx.id).take(16),dryRun)
            }
            if (financial) learn(state,ynab)
            Map byOrder = [:]
            Set conflicts = [] as Set
            orders.findAll { !it.isReturn }.groupBy { it.orderId }.each { id, copies ->
                AmazonOrder best = copies.find { it.financialEvidence } ?: copies[0]
                if (copies.any { it.financialEvidence.conflicting_source || (it != best && conflicting(best,it)) }) conflicts << id
                byOrder[id] = best
            }
            List<Map> events = orders.collectMany { it.paymentEvents ?: [] }.collect { new LinkedHashMap(it) }
            events.each { event ->
                String orderDate = byOrder[event.order_id]?.orderDate
                if (orderDate && event.signed_cents < 0) event.matching_dates = ((event.matching_dates ?: []) + [orderDate]).unique()
            }
            // Email notices corroborate payment occurrences; they never create extra bank credits.
            List refunds = orders.findAll { it.isReturn && it.refundEvidence }
            Map refundCandidates = [:]
            refunds.each { notice ->
                refundCandidates[notice] = events.findAll { e ->
                    try {
                        e.signed_cents > 0 && e.order_id == notice.orderId.replaceFirst(/^RETURN-/, '') &&
                            e.signed_cents == notice.refundEvidence.full_total_cents &&
                            Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(e.date as String),LocalDate.parse(notice.refundEvidence.date as String))) <= 14
                    } catch (Exception ignored) { false }
                }
            }
            refundCandidates.each { notice, options ->
                if (options.size() == 1 && refundCandidates.values().count { it.any { it.key == options[0].key } } == 1) options[0].refund_evidence = notice.refundEvidence
            }
            Set duplicated = events.countBy { it.key }.findAll { k,v -> v > 1 }.keySet()
            List<Map> pairs = []
            Map candidates = [:]
            Map orderCandidates = [:]
            List orderEvents = []
            // Excluded parents still compete for events; legacy IDs confer no event ownership.
            transactions.each { tx ->
                def options = events.findAll { e -> matches(tx,e,accounts,config.amazonPaymentAccounts) }
                def closest = options.collect { dateDistance(tx,it) }.min()
                candidates[tx.id] = options.findAll { dateDistance(tx,it) == closest }
            }
            if (financial) {
                orderEvents = byOrder.values().collect { orderEvent(it) }.findAll { it }
                orderCandidates = transactions.collectEntries { tx ->
                    def options = orderEvents.findAll { matches(tx,it,accounts,config.amazonPaymentAccounts) }
                    def closest = options.collect { dateDistance(tx,it) }.min()
                    [(tx.id):options.findAll { dateDistance(tx,it) == closest }]
                }
                transactions.each { tx ->
                    def options = orderCandidates[tx.id]
                    Map previous = state.plans[config.ynabBudgetId + '/' + tx.id]
                    if (legacyIds.contains(tx.id) || options.size() != 1 ||
                        (previous?.trusted && previous.event?.source != 'order') ||
                        !new TransactionMatcher().isPotentialAmazonTransaction(tx)) return
                    Map event = new LinkedHashMap(options[0])
                    if (event.competition_only || conflicts.contains(event.order_id) ||
                        orderCandidates.values().count { it.any { it.order_id == event.order_id } } != 1 ||
                        candidates[tx.id].any { it.order_id != event.order_id } ||
                        claimed(state,tx.id,event)) return
                    AmazonOrder order = byOrder[event.order_id]
                    if (!AmazonAllocation.plan(order,event,events,[:]).eligible) return
                    event.order_only = !events.any { it.order_id == event.order_id && it.complete == true &&
                        !duplicated.contains(it.key) && it.competition_only != true && matches(tx,it,accounts,config.amazonPaymentAccounts) }
                    pairs << [tx:tx,order:order,event:event,trusted:true]
                }
            }
            candidates.each { id, options ->
                if (legacyIds.contains(id) || pairs.any { it.tx.id == id }) return
                if (options.size() != 1) {
                    if (options) stats.preview << [transaction_id:id,reason:'ambiguous_payment_match']
                    return
                }
                Map event = options[0]
                if (event.signed_cents < 0 && pairs.any { it.order.orderId == event.order_id && it.event.source == 'order' }) return
                if (event.competition_only == true) {
                    stats.preview << [transaction_id:id,reason:'historical_competitor_only']; return
                }
                if (event.complete != true || duplicated.contains(event.key) || !event.key ||
                    candidates.values().count { it.any { it.key == event.key } } != 1 || conflicts.contains(event.order_id) ||
                    claimed(state,id,event)) {
                    stats.preview << [transaction_id:id,reason:'ambiguous_or_incomplete_payment']; return
                }
                AmazonOrder order = byOrder[event.order_id] ?: new AmazonOrder(orderId:event.order_id)
                pairs << [tx:transactions.find { it.id == id },order:order,event:event,trusted:true]
            }
            // Trusted, confirmed links survive temporarily unavailable sources, but never contradictions.
            state.plans.findAll { k,v -> k.startsWith(config.ynabBudgetId + '/') && v.trusted && (v.pending || v.status in ['fallback','memo_only']) }.each { key,entry ->
                def tx = transactions.find { config.ynabBudgetId + '/' + it.id == key && !legacyIds.contains(it.id) }
                if (!tx || pairs.any { it.tx.id == tx.id }) return
                if (!matches(tx,entry.event,accounts,config.amazonPaymentAccounts) || claimed(state,tx.id,entry.event)) return
                boolean contradicts = events.any { it.key == entry.event.key &&
                    (association(it) != association(entry.event) || it.complete != true || it.competition_only == true || duplicated.contains(it.key) ||
                        !matches(tx,it,accounts,config.amazonPaymentAccounts)) } ||
                    (candidates[tx.id] ?: []).any { it.key != entry.event.key } ||
                    candidates.any { id,options -> id != tx.id && options.any { it.key == entry.event.key } }
                if (contradicts || conflicts.contains(entry.event.order_id)) return
                pairs << [tx:tx,order:byOrder[entry.event.order_id] ?: new AmazonOrder(orderId:entry.event.order_id),event:entry.event,trusted:true]
            }
            if (!financial) {
                def remaining = transactions.findAll { !legacyIds.contains(it.id) && !pairs.any { pair -> pair.tx.id == it.id } &&
                    eligible(snapshot(it),accounts,false) && (state.plans[config.ynabBudgetId + '/' + it.id] || !legacyMarker(it.memo)) }
                def legacy = new TransactionMatcher().findMatches(remaining,orders,false).findAll { it.highConfidence }
                legacy.findAll { match -> legacy.count { it.amazonOrder.orderId == match.amazonOrder.orderId } == 1 }.each { match ->
                    pairs << [tx:match.ynabTransaction,order:match.amazonOrder,event:match.amazonOrder.isReturn ?
                        [signed_cents:match.amazonOrder.totalAmount.movePointRight(2).longValueExact(),date:match.amazonOrder.orderDate,order_only:true] :
                        [order_only:true],trusted:false]
                }
            }
            if (financial) transactions.findAll { !legacyIds.contains(it.id) && !pairs.any { pair -> pair.tx.id == it.id } && new TransactionMatcher().isPotentialAmazonTransaction(it) }.each { tx ->
                List reasons = []
                List options = orderCandidates[tx.id] ?: []
                if (!options) {
                    List amountOptions = orderEvents.findAll { tx.amount != null && tx.amount == it.signed_cents * 10L }
                    List accountOptions = amountOptions.findAll { accounts.contains(tx.account_id) && config.amazonPaymentAccounts[it.payment_identity] == tx.account_id }
                    List currencyOptions = accountOptions.findAll { it.currency == 'USD' }
                    reasons << (!orderEvents ? 'no_usable_order_identity' : !amountOptions ? 'no_exact_order_amount' : !accountOptions ? 'no_mapped_order_account' :
                        !currencyOptions ? 'unsupported_order_currency' : 'no_valid_order_date_within_14_days')
                }
                else if (options.size() > 1) reasons << 'ambiguous_order_match'
                else {
                    Map event = options[0]
                    if (event.competition_only) reasons << 'unqualified_order_evidence'
                    if (conflicts.contains(event.order_id)) reasons << 'conflicting_order_sources'
                    if (orderCandidates.values().count { it.any { it.order_id == event.order_id } } != 1) reasons << 'order_competing_bank_transactions'
                    if (candidates[tx.id].any { it.order_id != event.order_id }) reasons << 'competing_payment_order'
                    if (claimed(state,tx.id,event)) reasons << 'order_already_claimed'
                    Map allocation = AmazonAllocation.plan(byOrder[event.order_id],event,events,[:])
                    if (!allocation.eligible) reasons << allocation.reason
                    Map previous = state.plans[config.ynabBudgetId + '/' + tx.id]
                    if (previous?.trusted && previous.event?.source != 'order') reasons << 'existing_payment_association_unavailable'
                }
                if (!candidates[tx.id]) reasons << 'no_payment_amount_account_date_match'
                stats.preview << [transaction_id:tx.id,reason:'no_eligible_match',matching_reasons:reasons.unique()]
            }
            pairs.each { pair ->
                YNABTransaction tx = pair.tx
                AmazonOrder order = pair.order
                Map event = pair.event
                LOG.info('Amazon match accepted: ref={} basis={} dry_run={}',ItemClassificationService.fingerprint(tx.id).take(16),
                    event.order_only ? 'order_only' : 'payment',dryRun)
                String key = config.ynabBudgetId + '/' + tx.id
                Map old = state.plans[key]
                Map evidence = event?.signed_cents > 0 ? event.refund_evidence ?: [:] : order.financialEvidence
                String frozen = ItemClassificationService.fingerprint([evidence,config.amazonCategorization,
                    config.amazonPaymentAccounts,classifier?.scope(categories),
                    state.mappings.findAll { k,v -> k.startsWith('amazon/' + config.ynabBudgetId + '/') }])
                if (!old && legacyMarker(tx.memo)) {
                    stats.preview << [transaction_id:tx.id,reason:'legacy_unowned_metadata']; return
                }
                Map current = ynab.getTransaction(tx.id)
                if (!GuardedTransactions.same(current,snapshot(tx))) {
                    stats.preview << [transaction_id:tx.id,reason:'parent_changed']; return
                }
                if (old?.pending) {
                    if (GuardedTransactions.same(current,old.intent)) {
                        old.result = current; old.pending = false; old.status = old.target_status
                        if (!dryRun) save(state)
                    } else if (!GuardedTransactions.same(current,old.baseline)) {
                        stats.preview << [transaction_id:tx.id,reason:'unknown_write_outcome']; return
                    } else {
                        if (!financial && old.changes.keySet().any { it != 'memo' }) {
                            stats.preview << [transaction_id:tx.id,reason:'financial_opt_in_required']; return
                        }
                        // Replay only the already durable intent; never replan an unresolved write.
                        if (old.fingerprint != frozen || (old.trusted && association(old.event) != association(event))) {
                            stats.preview << [transaction_id:tx.id,reason:'pending_intent_drift']; return
                        }
                        stats.preview << [transaction_id:tx.id,reason:'resume_intent',changes:old.changes]
                        if (!dryRun) apply(old,current,ynab,state,save,stats)
                        return
                    }
                }
                if (old?.result && !GuardedTransactions.same(current,old.result)) {
                    stats.preview << [transaction_id:tx.id,reason:'parent_changed']; return
                }
                if (old?.trusted && (!pair.trusted || association(old.event) != association(event))) {
                    stats.preview << [transaction_id:tx.id,reason:'association_drift']; return
                }
                if (old?.facts && contradicts(old.facts,evidence)) {
                    stats.preview << [transaction_id:tx.id,reason:'source_drift']; return
                }
                boolean categorized = current.subtransactions ? current.subtransactions.every { it.category_id } : current.category_id != null
                boolean ownedIncompleteSplit = old?.status in ['split','fallback'] && old.result?.subtransactions && !categorized
                if (old?.status in ['category','split'] && categorized) {
                    stats.preview << [transaction_id:tx.id,reason:old.fingerprint == frozen ? 'allocation_complete' : 'applied_plan_drift']; return
                }
                if (ownedIncompleteSplit && !financial) {
                    stats.preview << [transaction_id:tx.id,reason:'financial_opt_in_required']; return
                }
                if (!eligible(ownedIncompleteSplit ? current + [subtransactions:[]] : current,accounts,financial)) {
                    stats.preview << [transaction_id:tx.id,reason:'ineligible_parent']; return
                }
                // Parent non-null category is not evidence of app ownership, even if auto-categorized.
                if (financial && !ownedIncompleteSplit && current.category_id != null && (current.approved == true || !(old?.result && old.status == 'memo_only' && old.owned_category))) {
                    stats.preview << [transaction_id:tx.id,reason:'non_owned_category']; return
                }
                String userText = old?.user_text != null ? old.user_text : AmazonAuthNotice.withoutNotice(current.memo)?.with { it == 'null' ? '' : it } ?: ''
                if (old && !order.orderDate) order.orderDate = old.order_date
                if (old?.facts) order.financialEvidence = old.facts + order.financialEvidence
                String memo = AmazonMemos.parent(order,event,userText)
                if (!memo) { stats.preview << [transaction_id:tx.id,reason:'memo_too_long']; return }
                Map plan = [eligible:false,reason:'memo_only']
                if (financial) {
                    plan = AmazonAllocation.plan(order,event,events,[:])
                    if (plan.eligible) {
                        Map originals = [:]
                        if (event.signed_cents > 0) evidence.items.each { item ->
                            def choices = state.originalItems?.get(config.ynabBudgetId + '/' + order.orderId + '/' + item.productId)
                            if (item.productId && choices) originals[item.itemId] = [category_id:choices.size() == 1 && categories.containsKey(choices[0]) ? choices[0] : null,
                                source:'approved_original',conflict:choices.size() > 1]
                        }
                        Map resolved = ItemClassificationService.resolve(evidence.items.findAll { !originals.containsKey(it.itemId) } as List,categories,state,'amazon',config.ynabBudgetId,classifier,dryRun,save)
                        resolved.decisions.putAll(originals)
                        resolved.decisions.values().eachWithIndex { decision,index ->
                            if (decision.category_id) return
                            List scores = (decision.scores?.values() ?: []).sort { a,b -> b <=> a }
                            Map gates = config.amazonCategorization.acceptance ?: [:]
                            List reasons = []
                            if (decision.conflict) reasons << 'conflicting_approved_categories'
                            if (scores && !decision.raw_category_id) reasons << 'provider_abstained'
                            if (scores && scores[0] < gates.probability) reasons << 'probability_below_threshold'
                            if (decision.confidence != null && decision.confidence < gates.confidence) reasons << 'confidence_below_threshold'
                            if (scores.size() > 1 && scores[0] - scores[1] < gates.margin) reasons << 'margin_below_threshold'
                            if (!reasons) reasons << (resolved.failed ? (dryRun ? 'uncached_inference_requires_live_run' : 'provider_inference_failed') :
                                !classifier ? 'no_approved_mapping_or_enabled_provider' : 'category_not_in_active_shortlist')
                            LOG.info('Amazon classification unresolved: ref={} item_index={} reasons={} probability={} confidence={} margin={} thresholds={} provider_stage={} provider_http_status={}',
                                ItemClassificationService.fingerprint(tx.id).take(16),index,reasons,
                                scores ? scores[0] : null,decision.confidence,scores.size() > 1 ? scores[0] - scores[1] : null,
                                gates.subMap(['probability','confidence','margin']),classifier?.metrics?.failure_stage ?: classifier?.metrics?.stage,classifier?.metrics?.last_http_status)
                        }
                        boolean unavailable = config.amazonCategorization.provider && !classifier && resolved.decisions.values().any { it.source == 'unresolved' }
                        if (resolved.failed || unavailable) plan = [eligible:false,reason:dryRun ? 'inference_required' : 'inference_unavailable']
                        else plan = AmazonAllocation.plan(order,event,events,resolved.decisions)
                        if (plan.eligible && plan.lines.any { !it.category_id }) plan = [eligible:false,reason:'unresolved_categories']
                    }
                }
                if (financial && ownedIncompleteSplit && !plan.eligible) {
                    stats.preview << [transaction_id:tx.id,reason:plan.reason]
                    if (!dryRun) {
                        old.status = 'fallback'; old.allocation_reason = plan.reason; save(state)
                    }
                    return
                }
                Map changes = financial ? AmazonAllocation.payload(event,memo,plan) : [memo:memo]
                if (financial && current.approved == true) changes.approved = true
                String status = !financial ? 'memo_only' : !plan.eligible ? 'fallback' : plan.lines.size() == 1 ? 'category' : 'split'
                stats.high_confidence++
                if (old?.status == status && current.memo == memo && status in ['fallback','memo_only']) {
                    stats.preview << [transaction_id:tx.id,status:status,reason:'unchanged',allocation_reason:plan.reason]
                    if (!dryRun && old.allocation_reason != plan.reason) {
                        old.allocation_reason = plan.reason; save(state)
                    }
                    stats.completed << tx.id
                    return
                }
                stats.preview << [transaction_id:tx.id,order_id:order.orderId,event_key:event?.key,reason:plan.reason,
                    status:status,changes:changes,lines:plan.lines ?: []]
                if (dryRun) {
                    stats.completed << tx.id
                    return
                }
                Map entry = [trusted:pair.trusted,event:event,user_text:userText,facts:knownFacts(evidence),
                    fingerprint:frozen,allocation_reason:plan.reason,
                    baseline:current,intent:current + changes,changes:changes,pending:true,target_status:status,
                    lines:plan.lines ?: [],order_id:order.orderId,order_date:order.orderDate,refund:event?.signed_cents > 0]
                state.plans[key] = entry
                save(state)
                apply(entry,current,ynab,state,save,stats)
            }
            if (!dryRun && financial) save(state)
        }
        } catch (Exception failure) {
            LOG.warn('Amazon enrichment interrupted: exception_type={} verified_updates={}; durable intents retained',failure.class.simpleName,stats.updated)
            throw failure
        } finally {
            // Log only bounded diagnostic fields, never previews containing memos, items or categories.
            stats.preview.each { row ->
                LOG.info('Amazon decision: ref={} reason={} status={} allocation_reason={} matching_reasons={} dry_run={}',
                    row.transaction_id ? ItemClassificationService.fingerprint(row.transaction_id).take(16) : 'run',
                    row.reason,row.status,row.allocation_reason,row.matching_reasons,dryRun)
            }
            LOG.info('Amazon enrichment progress: verified_updates={} decision_records={} dry_run={}',stats.updated,stats.preview.size(),dryRun)
        }
        stats
    }

    private static void apply(Map entry, Map current, YNABService ynab, Map state, Closure save, Map stats) {
        // Save has already completed. A lost response is not permission to send again.
        String ref = ItemClassificationService.fingerprint(current.id).take(16)
        if (!GuardedTransactions.same(ynab.getTransaction(current.id),entry.baseline)) {
            stats.preview << [transaction_id:current.id,reason:'parent_changed_before_write']; return
        }
        try { ynab.putTransaction(current.id,entry.changes) }
        catch (Exception failure) { LOG.warn('Amazon write response uncertain: ref={} exception_type={}; checking durable intent, not replaying',ref,failure.class.simpleName) }
        Map after
        try { after = ynab.getTransaction(current.id) }
        catch (Exception failure) {
            LOG.warn('Amazon write readback failed: ref={} exception_type={}; durable intent remains pending',ref,failure.class.simpleName)
            throw failure
        }
        if (!GuardedTransactions.same(after,entry.intent)) {
            stats.preview << [transaction_id:current.id,reason:'write_not_confirmed']; return
        }
        entry.result = after; entry.pending = false; entry.status = entry.target_status
        save(state); stats.updated++; stats.completed << current.id
        LOG.info('Amazon write verified: ref={} status={}',ref,entry.status)
    }

    private boolean claimed(Map state, String transactionId, Map event) {
        state.plans.any { key,entry -> key.startsWith(config.ynabBudgetId + '/') && key != config.ynabBudgetId + '/' + transactionId &&
            (entry.event?.key == event.key || (event.signed_cents < 0 && entry.event?.signed_cents != null && entry.event.signed_cents < 0 &&
                entry.order_id == event.order_id && (event.source == 'order' || entry.event.source == 'order'))) }
    }

    private static Map orderEvent(AmazonOrder order) {
        Map evidence = order.financialEvidence
        if (evidence.source != 'python' || evidence.purchase_eligible != true ||
            !evidence.payment_identity || !AmazonMemos.realId(order.orderId)) return null
        try {
            Long total = AmazonAllocation.integer(evidence.full_total_cents) ? evidence.full_total_cents as Long :
                order.@totalAmount?.abs()?.movePointRight(2)?.longValueExact()
            if (total == null) return null
            boolean qualified = evidence.complete == true && AmazonAllocation.integer(evidence.gift_cents) && evidence.reward_cents == 0
            long bank = total - (AmazonAllocation.integer(evidence.gift_cents) ? evidence.gift_cents as long : 0L)
            if (bank <= 0) return null
            [key:'order/' + order.orderId,order_id:order.orderId,date:order.orderDate,signed_cents:-bank,
                payment_identity:evidence.payment_identity,currency:evidence.currency,source:'order',order_only:true,
                competition_only:!qualified]
        } catch (Exception ignored) { null }
    }

    static boolean matches(YNABTransaction tx, Map event, Set accounts, Map paymentAccounts) {
        try {
            AmazonMemos.realId(event.order_id as String) && event.currency == 'USD' && event.payment_identity &&
                tx.amount != null && tx.amount % 10 == 0 && event.signed_cents instanceof Number &&
                event.signed_cents == (event.signed_cents as long) && tx.amount == (event.signed_cents as long) * 10L &&
                accounts.contains(tx.account_id) && paymentAccounts[event.payment_identity] == tx.account_id &&
                dateDistance(tx,event) <= 14
        } catch (Exception ignored) { false }
    }

    private static long dateDistance(YNABTransaction tx, Map event) {
        ([event.date] + (event.matching_dates ?: [])).collect { date ->
            Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(tx.date),LocalDate.parse(date as String)))
        }.min() as long
    }

    private static Map association(Map event) { event?.subMap(['key','order_id','date','signed_cents','payment_identity','currency']) }
    private static boolean legacyMarker(String memo) { memo?.startsWith('🤖') || memo?.contains('items:') || memo?.contains('Amazon Order') }
    private static Map knownFacts(Map evidence) { evidence.findAll { k,v -> k in ['full_total_cents','gift_cents','reward_cents','currency','items','adjustments'] && v != null && v != [] } }
    private static boolean contradicts(Map before, Map after) {
        before.any { k,v ->
            def next = after[k]
            if (v == null || next == null || v == [] || next == []) return false
            if (v instanceof Map && next instanceof Map) return contradicts(v,next)
            if (v instanceof List && next instanceof List && v.every { it instanceof Map && it.itemId }) {
                return v.any { item ->
                    def found = next.find { it.itemId == item.itemId }
                    !found || contradicts(item,found)
                }
            }
            v != next
        }
    }
    private static boolean conflicting(AmazonOrder a, AmazonOrder b) {
        a.orderDate && b.orderDate && a.orderDate != b.orderDate ||
            a.@totalAmount != null && b.@totalAmount != null && a.@totalAmount.abs() != b.@totalAmount.abs() ||
            a.items.any { item -> item.asin && b.items.any { other -> other.asin == item.asin &&
                ((item.price != null && other.price != null && item.price != other.price) ||
                 (item.quantity > 0 && other.quantity > 0 && item.quantity != other.quantity)) } } ||
            a.financialEvidence && b.financialEvidence && a.financialEvidence != b.financialEvidence
    }
    static boolean eligible(Map tx, Set accounts, boolean financial) {
        tx && !tx.deleted && !tx.transfer_account_id && tx.amount &&
            (!financial || (tx.approved in [true, false] && !tx.subtransactions && tx.cleared in ['cleared','uncleared'] &&
                tx.amount % 10 == 0 && accounts.contains(tx.account_id)))
    }
    static Map snapshot(YNABTransaction tx) {
        Map result = ['id','date','amount','account_id','payee_id','payee_name','memo','category_id','cleared','import_id','flag_color','transfer_account_id'].collectEntries {
            def value = it == 'cleared' ? tx.getCleared() : tx."${it}"; [(it):value == 'null' ? null : value]
        }
        result + [approved:tx.getApproved() == 'true',deleted:tx.getDeleted() == 'true',subtransactions:tx.subtransactions ?: []]
    }

    private void learn(Map state, YNABService ynab) {
        Map observations = [:].withDefault { [] }
        Map originals = [:].withDefault { [] }
        state.plans.findAll { k,v -> k.startsWith(config.ynabBudgetId + '/') && v.result &&
            (v.status in ['category','split'] || (v.status == 'fallback' && v.result.subtransactions)) }.each { key,entry ->
            Map current = ynab.getTransaction(entry.result.id)
            Map before = entry.result
            boolean intact = current?.approved == true && !current.deleted &&
                ['id','amount','date','account_id','payee_id','payee_name','memo','import_id','cleared','flag_color'].every { current[it] == before[it] }
            List original = before.subtransactions ?: [before]
            List rows = current?.subtransactions ?: (current ? [current] : [])
            if (!intact || original.size() != rows.size()) return
            entry.lines.each { line ->
                if (!line.product_id) return
                List found = original.findAll { row -> row.amount == line.cents * (entry.refund ? 10L : -10L) && (!before.subtransactions || row.memo == line.memo) }
                Map row = found.size() == 1 && found[0].id ? rows.find { it.id == found[0].id } : null
                if (!row || row.deleted || row.amount != found[0].amount || row.memo != found[0].memo || row.payee_id != found[0].payee_id || !row.category_id) return
                observations[ItemClassificationService.mappingKey('amazon',config.ynabBudgetId,line.product_id)] << row.category_id
                if (!entry.refund) originals[config.ynabBudgetId + '/' + entry.order_id + '/' + line.product_id] << row.category_id
            }
        }
        String prefix = 'amazon/' + config.ynabBudgetId + '/'
        List learned = state.learnedProducts ?: []
        learned.findAll { it.startsWith(prefix) }.each { state.mappings.remove(it) }
        observations.each { k,v -> state.mappings[k] = v.unique().sort() }
        state.learnedProducts = (learned.findAll { !it.startsWith(prefix) } + observations.keySet()).unique().sort()
        state.originalItems = (state.originalItems ?: [:]).findAll { k,v -> !k.startsWith(config.ynabBudgetId + '/') } +
            originals.collectEntries { k,v -> [(k):v.unique().sort()] }
    }
}
