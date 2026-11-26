package com.ynab.amazon.service

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.core.JsonParser
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate

class GoWalmartOrderFetcher {
    private static final LOG = LoggerFactory.getLogger(GoWalmartOrderFetcher)
    static final Set<String> ENVIRONMENT = ['PATH','SYSTEMROOT','WINDIR','TEMP','TMP','LANG','TZ'] as Set
    private final Configuration config
    private final BoundedProcessRunner runner
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    GoWalmartOrderFetcher(Configuration config, BoundedProcessRunner runner = new BoundedProcessRunner(ENVIRONMENT)) {
        this.config=config; this.runner=runner
    }

    List<WalmartOrder> fetchOrders() {
        if (!config.walmartHelperExecutable || !config.walmartSessionFile) throw new IllegalStateException('Walmart helper/session unavailable; install the native Walmart distribution and import a session')
        def request=[version:1,action:'fetch',session_file:config.walmartSessionFile,since:LocalDate.now().minusDays(config.lookBackDays).toString(),max_pages:config.walmartMaxPages]
        def result=runner.runWithInput([config.walmartHelperExecutable],Duration.ofSeconds(config.walmartTimeoutSeconds),config.walmartMaxOutputBytes,JSON.writeValueAsString(request))
        if (!result.success) {
            // Forward only the helper's fixed diagnostic vocabulary, never arbitrary stderr.
            result.stderr.readLines().findAll { it ==~ /walmart_helper operation=(unknown|PurchaseHistoryV2|getOrder|getOrderLedger|session|client|evidence) failure=(destination_rejected|transport_failed|timeout|http_status|body_read_failed|body_limit|invalid_json|invalid_schema|graphql_errors|history_schema|session_unavailable|initialization_failed|client_failed|pagination_limit) http_status=(0|[1-5][0-9]{2})/ }
                .take(20).each { LOG.warn('Walmart helper diagnostic: {}', it) }
            throw new IllegalStateException("Walmart fetch failed: ${result.status}")
        }
        try {
            Map root=JSON.readValue(result.stdout,Map)
            if (root.version!=1 || root.status!='ok' || !(root.orders instanceof List) || root.orders.size()>400) throw new IllegalArgumentException()
            def orders=root.orders.withIndex().collect { evidence, index ->
                try { WalmartEvidenceNormalizer.normalize(evidence as Map, config.walmartCurrency) }
                catch (Exception error) {
                    LOG.warn('Walmart normalization failed: source_order_index={} reason=invalid_source_evidence', index)
                    throw error
                }
            }
            if (orders*.orderId.toSet().size()!=orders.size()) throw new IllegalArgumentException()
            orders
        } catch (Exception ignored) { throw new IllegalStateException('Walmart fetch failed: INVALID_SCHEMA') }
    }
}
