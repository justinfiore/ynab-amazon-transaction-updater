package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/** Category authority only: no money, retailer models, configuration or YNAB writes. */
class ItemClassificationService {
    private static final JSON = new ObjectMapper()

    static String fingerprint(Object value) {
        MessageDigest.getInstance('SHA-256').digest(JSON.writeValueAsBytes(value)).encodeHex().toString()
    }

    static Map categories(List<Map> catalog, List<Map> selected) {
        if (!selected || selected.any { !it.id } || selected*.id.toSet().size() != selected.size()) throw new IllegalStateException('Invalid category shortlist')
        Map eligible = catalog.findAll { !it.deleted && !it.hidden && !it.internal && !it.group_internal && !it.group_hidden && !it.group_deleted }.collectEntries { [(it.id):it] }
        if (selected.any { !eligible[it.id] }) throw new IllegalStateException('Category is absent, hidden, deleted or special')
        selected.collectEntries { [(it.id):[id:it.id, name:eligible[it.id].name, group_name:eligible[it.id].group_name, description:it.description ?: '']] }
    }

    static String mappingKey(String retailer, String plan, String product) {
        // Walmart's separate store predates retailer prefixes. Preserve its keys byte for byte.
        retailer == 'walmart' ? plan + '/' + product : retailer + '/' + plan + '/' + product
    }

    static String cacheKey(String retailer, String plan, Map item, String scope) {
        fingerprint(retailer == 'walmart' ? [plan,item.productId,item.title,scope] : [retailer,plan,item.productId,item.title,scope])
    }

    static Map approved(Map state, String retailer, String plan, Map item, Map categories) {
        def evidence = item.productId ? state.mappings[mappingKey(retailer,plan,item.productId)] : null
        Set choices = evidence instanceof List ? evidence.toSet() : [] as Set
        [category_id:choices.size() == 1 && categories.containsKey(choices.first()) ? choices.first() : null,
            source:choices ? 'approved_mapping' : 'unresolved', conflict:choices.size() > 1]
    }

    static Map resolve(List<Map> items, Map categories, Map state, String retailer, String plan,
                       RemoteItemClassifier classifier, boolean dryRun, Closure save) {
        Map decisions = items.collectEntries { [(it.itemId):approved(state,retailer,plan,it,categories)] }
        List pending = items.findAll { decisions[it.itemId].source == 'unresolved' }
        boolean failed = false
        if (classifier && pending) {
            try {
                String scope = classifier.scope(categories)
                state.inferred = state.inferred ?: [:]
                List missing = []
                pending.each { item ->
                    String key = cacheKey(retailer,plan,item,scope)
                    if (state.inferred[key]?.scope == scope) decisions[item.itemId] = state.inferred[key]
                    else missing << item + [itemId:key]
                }
                if (missing && dryRun) failed = true
                else missing.unique { it.itemId }.collate(classifier.config.batch_size as int).each { batch ->
                    Map results = classifier.classify(batch,categories)
                    state.inferred.putAll(results)
                    save(state)
                }
                pending.each { item ->
                    String key = cacheKey(retailer,plan,item,scope)
                    if (state.inferred[key]?.scope == scope) decisions[item.itemId] = state.inferred[key]
                    else failed = true
                }
            } catch (Exception ignored) { failed = true }
        }
        String fundingProvider = failed ? classifier?.fundingProvider : null
        [decisions:decisions, failed:failed, funding_provider:fundingProvider,
            reason:fundingProvider ? 'provider_funding_unavailable' : dryRun ? 'inference_required' : 'inference_unavailable']
    }
}
