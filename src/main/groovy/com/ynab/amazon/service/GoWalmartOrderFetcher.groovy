package com.ynab.amazon.service

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.core.JsonParser
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import java.time.Duration
import java.time.LocalDate

class GoWalmartOrderFetcher {
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
        if (!result.success) throw new IllegalStateException("Walmart fetch failed: ${result.status}")
        try {
            Map root=JSON.readValue(result.stdout,Map)
            if (root.version!=1 || root.status!='ok' || !(root.orders instanceof List) || root.orders.size()>400) throw new IllegalArgumentException()
            def orders=root.orders.collect { WalmartEvidenceNormalizer.normalize(it as Map) }
            if (orders*.orderId.toSet().size()!=orders.size()) throw new IllegalArgumentException()
            orders
        } catch (Exception ignored) { throw new IllegalStateException('Walmart fetch failed: INVALID_SCHEMA') }
    }
}
